import hashlib
import json
import os
import re
import secrets
import uuid
from pathlib import Path
from typing import Optional

import yaml
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from kfp import Client

from src.custom_models.algorithm_detector import detect_algorithms
from src.custom_models.import_detector import analyze_script_imports


app = FastAPI(title="Custom Models Upload API")

APP_ROOT = Path(__file__).resolve().parents[2]
SHARED_ROOT = Path(os.getenv("SHARED_ROOT", "/tmp/mlops-gateway")).resolve()
GENERATED_CONFIG_DIR = Path(os.getenv("GENERATED_CONFIG_DIR", str(SHARED_ROOT / "configs"))).resolve()
DATASET_STORAGE_DIR = Path(os.getenv("DATASET_STORAGE_DIR", str(SHARED_ROOT / "datasets"))).resolve()

for directory in (SHARED_ROOT, GENERATED_CONFIG_DIR, DATASET_STORAGE_DIR):
    directory.mkdir(parents=True, exist_ok=True)

CUSTOM_PIPELINE_PATH = Path(
    os.getenv(
        "CUSTOM_PIPELINE_PATH",
        str(APP_ROOT / "kubeflow" / "compiled" / "custom_model_pipeline.yaml"),
    )
).resolve()

KUBEFLOW_HOST = os.getenv(
    "KUBEFLOW_HOST",
    "http://ml-pipeline.kubeflow.svc.cluster.local:8888",
)

GATEWAY_INTERNAL_BASE_URL = os.getenv(
    "GATEWAY_INTERNAL_BASE_URL",
    "http://pipeline-gateway-service.mlops.svc.cluster.local:8001",
).rstrip("/")

MAX_MODEL_FILE_BYTES = int(os.getenv("MAX_MODEL_FILE_BYTES", str(5 * 1024 * 1024)))
MAX_DATASET_FILE_BYTES = int(os.getenv("MAX_DATASET_FILE_BYTES", str(200 * 1024 * 1024)))


def decode_python_source(content: bytes, label: str) -> tuple[str, str]:
    """
    Decode a Python source file safely.

    Accepted encodings:
    - UTF-8 with or without BOM;
    - Windows-1252;
    - Latin-1 as a final compatibility fallback.

    The returned source is always a Python ``str`` and will later be written
    back to YAML as UTF-8.
    """
    if not content:
        raise ValueError(f"{label} is empty")

    attempts = (
        ("utf-8-sig", "utf-8"),
        ("cp1252", "cp1252"),
        ("latin-1", "latin-1"),
    )

    last_error: Exception | None = None

    for codec, reported_name in attempts:
        try:
            text = content.decode(codec)

            # Reject embedded NUL bytes, which usually indicate a binary file.
            if "\x00" in text:
                raise ValueError(f"{label} is not a valid text Python file")

            # Normalize line endings before passing the source to AST/YAML.
            text = text.replace("\r\n", "\n").replace("\r", "\n")

            # Validate syntax only when the file is expected to be Python.
            compile(text, f"<{label}>", "exec")

            return text, reported_name

        except (UnicodeDecodeError, SyntaxError, ValueError) as exc:
            last_error = exc

    raise ValueError(
        f"{label} could not be decoded as a valid Python source file. "
        "Save it as UTF-8 and verify its Python syntax. "
        f"Last error: {last_error}"
    )

allowed_origins = [
    origin.strip()
    for origin in os.getenv(
        "CORS_ALLOWED_ORIGINS",
        "http://localhost:4200,http://127.0.0.1:4200,http://128.251.112.197",
    ).split(",")
    if origin.strip()
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def safe_name(value: str) -> str:
    value = str(value or "").strip().lower()
    value = re.sub(r"[^a-zA-Z0-9_.-]+", "-", value)
    value = re.sub(r"-+", "-", value)
    return value.strip("-")


def safe_filename(filename: str, default: str) -> str:
    name = Path(filename or default).name
    name = re.sub(r"[^a-zA-Z0-9_.-]+", "_", name).strip("._")
    return name or default


def validate_direction(direction: str) -> str:
    normalized = str(direction or "").strip().lower()
    if normalized not in {"min", "max"}:
        raise ValueError("direction must be 'min' or 'max'")
    return normalized


def validate_project_type(project_type: Optional[str], primary_metric: str) -> str:
    normalized = str(project_type or "").strip().lower()
    if normalized in {"classification", "regression"}:
        return normalized
    metric = str(primary_metric or "").strip().lower()
    return "regression" if metric in {"rmse", "mse", "mae", "r2"} else "classification"


def build_registered_model_name(project_name: str, registered_model_name: Optional[str]) -> str:
    if registered_model_name and registered_model_name.strip():
        return safe_name(registered_model_name)
    return safe_name(project_name)


def parse_algorithm_replacements(algorithm_replacements: Optional[str]) -> list[dict]:
    if not algorithm_replacements or not algorithm_replacements.strip():
        return []
    try:
        parsed = json.loads(algorithm_replacements)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid algorithm_replacements JSON: {exc}") from exc
    if not isinstance(parsed, list):
        raise ValueError("algorithm_replacements must be a JSON list")
    for index, replacement in enumerate(parsed):
        if not isinstance(replacement, dict):
            raise ValueError(f"Each algorithm replacement must be an object. Invalid index: {index}")
    return parsed


async def read_upload_limited(upload: UploadFile, max_bytes: int, label: str) -> bytes:
    content = await upload.read()
    if not content:
        raise ValueError(f"{label} is empty")
    if len(content) > max_bytes:
        raise ValueError(
            f"{label} is too large: {len(content)} bytes. Maximum allowed: {max_bytes} bytes."
        )
    return content


def persist_dataset(config_id: str, dataset_file: UploadFile, dataset_bytes: bytes) -> dict:
    original_filename = safe_filename(dataset_file.filename or "dataset.csv", "dataset.csv")
    dataset_dir = DATASET_STORAGE_DIR / config_id
    dataset_dir.mkdir(parents=True, exist_ok=True)

    suffix = Path(original_filename).suffix.lower() or ".csv"
    stored_filename = f"dataset{suffix}"
    dataset_path = dataset_dir / stored_filename
    dataset_path.write_bytes(dataset_bytes)

    token = secrets.token_urlsafe(32)
    (dataset_dir / ".download_token").write_text(token, encoding="utf-8")

    sha256 = hashlib.sha256(dataset_bytes).hexdigest()
    download_url = f"{GATEWAY_INTERNAL_BASE_URL}/internal/datasets/{config_id}?token={token}"

    return {
        "source_type": "url",
        "dataset_name": original_filename,
        "filename": stored_filename,
        "url": download_url,
        "sha256": sha256,
        "size_bytes": len(dataset_bytes),
    }


@app.get("/")
def health():
    return {"status": "ok", "message": "Custom upload API is running"}


@app.get("/Health")
def health_uppercase():
    return health()


@app.get("/health/details")
def health_details():
    return {
        "status": "ok",
        "storageMode": "ephemeral-pod-filesystem",
        "appRoot": str(APP_ROOT),
        "sharedRoot": str(SHARED_ROOT),
        "generatedConfigDir": str(GENERATED_CONFIG_DIR),
        "datasetStorageDir": str(DATASET_STORAGE_DIR),
        "customPipelinePath": str(CUSTOM_PIPELINE_PATH),
        "customPipelineExists": CUSTOM_PIPELINE_PATH.is_file(),
        "kubeflowHost": KUBEFLOW_HOST,
        "gatewayInternalBaseUrl": GATEWAY_INTERNAL_BASE_URL,
    }


@app.get("/internal/datasets/{config_id}")
def download_dataset(config_id: str, token: str = Query(...)):
    normalized_id = safe_name(config_id)
    if normalized_id != config_id:
        raise HTTPException(status_code=400, detail="Invalid config id")

    dataset_dir = DATASET_STORAGE_DIR / config_id
    token_path = dataset_dir / ".download_token"

    if not token_path.is_file():
        raise HTTPException(status_code=404, detail="Dataset not found")

    expected_token = token_path.read_text(encoding="utf-8").strip()
    if not secrets.compare_digest(token, expected_token):
        raise HTTPException(status_code=403, detail="Invalid download token")

    files = [p for p in dataset_dir.iterdir() if p.is_file() and p.name != ".download_token"]
    if len(files) != 1:
        raise HTTPException(status_code=404, detail="Dataset file not found")

    dataset_path = files[0]
    return FileResponse(
        path=dataset_path,
        filename=dataset_path.name,
        media_type="application/octet-stream",
    )


@app.post("/analyze-model-file")
async def analyze_model_file(model_file: UploadFile = File(...)):
    try:
        filename = safe_filename(model_file.filename or "model.py", "model.py")
        if not filename.lower().endswith(".py"):
            return JSONResponse(status_code=400, content={"status": "FAILED", "error": "model_file must end with .py"})

        model_bytes = await read_upload_limited(model_file, MAX_MODEL_FILE_BYTES, "model_file")
        model_code, detected_encoding = decode_python_source(model_bytes, "model_file")
        return {
            "status": "SUCCESS",
            "filename": filename,
            "detectedEncoding": detected_encoding,
            "analysis": detect_algorithms(model_code),
        }
    except ValueError as exc:
        return JSONResponse(status_code=400, content={"status": "FAILED", "error": str(exc)})
    except Exception as exc:
        return JSONResponse(status_code=500, content={"status": "FAILED", "error": "Failed to analyze model file", "details": str(exc)})


@app.post("/api/runtime/analyze-script")
async def analyze_runtime_script(script_file: UploadFile = File(...)):
    try:
        filename = safe_filename(script_file.filename or "train.py", "train.py")
        if not filename.lower().endswith(".py"):
            return JSONResponse(status_code=400, content={"supported": False, "error": "script_file must end with .py"})

        script_bytes = await read_upload_limited(script_file, MAX_MODEL_FILE_BYTES, "script_file")
        script_code, detected_encoding = decode_python_source(script_bytes, "script_file")

        import_analysis = analyze_script_imports(script_code)
        algorithm_analysis = detect_algorithms(script_code)

        runtime_compatible = bool(import_analysis.get("supported", False))
        detected_imports = import_analysis.get("detectedImports", [])
        supported_imports = import_analysis.get("supportedImports", [])
        unsupported_imports = import_analysis.get("unsupportedImports", [])
        detected_metrics = import_analysis.get("detectedMetrics", [])

        supported_libraries = algorithm_analysis.get("supportedModules", []) or algorithm_analysis.get("supported_modules", [])
        unsupported_libraries = algorithm_analysis.get("unsupportedModules", []) or algorithm_analysis.get("unsupported_modules", [])

        return {
            "status": "SUCCESS",
            "detectedEncoding": detected_encoding,
            "runtimeCompatible": runtime_compatible,
            "supported": runtime_compatible,
            "message": algorithm_analysis.get("message")
            or algorithm_analysis.get("summary")
            or ("Runtime standard compatible" if runtime_compatible else "Use the custom Dockerfile runtime."),
            "summary": algorithm_analysis.get("summary"),
            "recommendation": algorithm_analysis.get("recommendation", import_analysis.get("recommendation")),
            "detectedImports": detected_imports,
            "supportedImports": supported_imports,
            "unsupportedImports": unsupported_imports,
            "detectedMetrics": detected_metrics,
            "supportedLibraries": supported_libraries,
            "unsupportedLibraries": unsupported_libraries,
            "missingDependencies": unsupported_imports,
            "detectedAlgorithms": algorithm_analysis.get("detectedAlgorithms", []),
            "replaceableAlgorithms": algorithm_analysis.get("replaceableAlgorithms", []),
            "warnings": algorithm_analysis.get("warnings", []),
            "dataType": algorithm_analysis.get("dataType"),
            "taskType": algorithm_analysis.get("taskType"),
        }
    except ValueError as exc:
        return JSONResponse(status_code=400, content={"supported": False, "error": str(exc)})
    except Exception as exc:
        return JSONResponse(status_code=500, content={"supported": False, "error": "Failed to analyze runtime script", "details": str(exc)})


@app.post("/upload-and-run-custom-model")
async def upload_and_run_custom_model(
    project_name: str = Form(...),
    model_name: str = Form(...),
    run_name: str = Form(...),
    primary_metric: str = Form("f1_score"),
    direction: str = Form("max"),
    project_type: Optional[str] = Form(None),
    registered_model_name: Optional[str] = Form(None),
    algorithm_replacements: Optional[str] = Form(None),
    model_file: UploadFile = File(...),
    dataset_file: Optional[UploadFile] = File(None),
):
    config_id = str(uuid.uuid4())

    try:
        raw_project_name = project_name
        raw_model_name = model_name
        raw_run_name = run_name

        project_name = safe_name(project_name)
        model_name = safe_name(model_name)
        run_name = safe_name(run_name)
        primary_metric = safe_name(primary_metric)
        direction = validate_direction(direction)
        project_type = validate_project_type(project_type, primary_metric)

        if not project_name or not model_name or not run_name:
            raise ValueError("project_name, model_name and run_name are required")

        model_filename = safe_filename(model_file.filename or "train.py", "train.py")
        if not model_filename.lower().endswith(".py"):
            raise ValueError("model_file must end with .py")

        model_bytes = await read_upload_limited(model_file, MAX_MODEL_FILE_BYTES, "model_file")
        model_code, detected_encoding = decode_python_source(model_bytes, "model_file")

        if dataset_file and dataset_file.filename:
            dataset_bytes = await read_upload_limited(dataset_file, MAX_DATASET_FILE_BYTES, "dataset_file")
            data_config = persist_dataset(config_id, dataset_file, dataset_bytes)
        else:
            data_config = {
                "source_type": "none",
                "dataset_name": "internal-data",
                "filename": None,
            }

        parsed_algorithm_replacements = parse_algorithm_replacements(algorithm_replacements)

        final_registered_model_name = build_registered_model_name(
            project_name,
            registered_model_name,
        )

        config = {
            "config_id": config_id,
            "project_name": project_name,
            "project_type": project_type,
            "task": {"type": project_type},
            "run": {"name": run_name},
            "tracking": {
                "uri": "http://mlflow-service.mlops.svc.cluster.local:5000",
                "experiment_name": project_name,
            },
            "model": {
                "name": model_name,
                "primary_metric": primary_metric,
                "direction": direction,
                "project_type": project_type,
                "task_type": project_type,
                "params": {},
            },
            "custom_code": {
                "source_type": "inline",
                "source_encoding": detected_encoding,
                "entrypoint": model_filename,
                "files": {model_filename: model_code},
            },
            "data": data_config,
            "registry": {
                "model_name": final_registered_model_name,
                "alias": "production",
            },
            "algorithm_replacements": parsed_algorithm_replacements,
            "validation": {
                "enabled": False,
                "strict": False,
                "input_example": None,
            },
        }

        config_path = GENERATED_CONFIG_DIR / f"{config_id}.yaml"
        config_path.write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True),
            encoding="utf-8",
        )

        if not CUSTOM_PIPELINE_PATH.is_file():
            return JSONResponse(
                status_code=500,
                content={
                    "status": "FAILED",
                    "error": "Compiled Kubeflow pipeline not found",
                    "expectedPath": str(CUSTOM_PIPELINE_PATH),
                    "configId": config_id,
                },
            )

        config_yaml = config_path.read_text(encoding="utf-8")

        if len(config_yaml.encode("utf-8")) > 512 * 1024:
            raise ValueError(
                "Generated config is too large. Never embed datasets in config_yaml."
            )

        client = Client(host=KUBEFLOW_HOST)
        kubeflow_run = client.create_run_from_pipeline_package(
            pipeline_file=str(CUSTOM_PIPELINE_PATH),
            arguments={"config_yaml": config_yaml},
            experiment_name=project_name,
            run_name=run_name,
        )

        return {
            "status": "RUNNING",
            "message": "Model uploaded and Kubeflow pipeline started",
            "configId": config_id,
            "configPath": str(config_path),
            "projectName": project_name,
            "rawProjectName": raw_project_name,
            "modelName": model_name,
            "rawModelName": raw_model_name,
            "runName": run_name,
            "rawRunName": raw_run_name,
            "projectType": project_type,
            "primaryMetric": primary_metric,
            "direction": direction,
            "algorithmReplacements": parsed_algorithm_replacements,
            "kubeflowRunId": kubeflow_run.run_id,
            "kubeflowRunName": run_name,
            "mlflowExperimentName": project_name,
            "mlflowRunId": None,
            "registeredModelName": final_registered_model_name,
            "registered_model_name": final_registered_model_name,
            "modelVersion": None,
            "primaryMetricValue": None,
            "mlflow_registered_model": final_registered_model_name,
            "mlflow_model_uri_after_training": f"models:/{final_registered_model_name}@production",
            "datasetSourceType": data_config.get("source_type"),
            "datasetSizeBytes": data_config.get("size_bytes"),
            "datasetSha256": data_config.get("sha256"),
        }

    except ValueError as exc:
        return JSONResponse(status_code=400, content={"status": "FAILED", "error": str(exc), "configId": config_id})
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={
                "status": "FAILED",
                "error": "Failed to upload model or launch Kubeflow pipeline",
                "details": str(exc),
                "configId": config_id,
                "registeredModelName": registered_model_name,
                "registered_model_name": registered_model_name,
            },
        )