import json
import re
import uuid
from pathlib import Path
from typing import Optional

import yaml
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from kfp import Client

from src.custom_models.algorithm_detector import detect_algorithms
from src.custom_models.import_detector import analyze_script_imports


app = FastAPI(title="Custom Models Upload API")

# IMPORTANT :
# Autoriser Angular Ã  appeler FastAPI.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:4200",
        "http://127.0.0.1:4200",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

GENERATED_CONFIG_DIR = Path("uploaded_configs")
GENERATED_CONFIG_DIR.mkdir(exist_ok=True)


def safe_name(value: str) -> str:
    value = str(value or "").strip().lower()
    value = re.sub(r"[^a-zA-Z0-9_.-]+", "-", value)
    value = re.sub(r"-+", "-", value)
    return value.strip("-")


def validate_direction(direction: str) -> str:
    direction = str(direction or "").strip().lower()

    if direction not in ["min", "max"]:
        raise ValueError("direction must be 'min' or 'max'")

    return direction


def validate_project_type(project_type: Optional[str], primary_metric: str) -> str:
    """
    On accepte project_type si Angular l'envoie.
    Sinon on l'infÃ¨re depuis la mÃ©trique principale.
    """
    normalized = str(project_type or "").strip().lower()

    if normalized in {"classification", "regression"}:
        return normalized

    metric = str(primary_metric or "").strip().lower()

    regression_metrics = {"rmse", "mse", "mae", "r2"}
    classification_metrics = {
        "accuracy",
        "f1",
        "f1_score",
        "f1_weighted",
        "precision",
        "precision_weighted",
        "recall",
        "recall_weighted",
    }

    if metric in regression_metrics:
        return "regression"

    if metric in classification_metrics:
        return "classification"

    return "classification"


def build_registered_model_name(
    project_name: str,
    registered_model_name: Optional[str]
) -> str:
    """
    RÃ¨gle MLOps :
    - 1 projet = 1 Registered Model unique
    - chaque nouveau training = 1 nouvelle version du mÃªme Registered Model
    """
    if registered_model_name and registered_model_name.strip():
        return safe_name(registered_model_name)

    return safe_name(project_name)


def build_data_config(
    dataset_file: Optional[UploadFile],
    dataset_content: Optional[str]
):
    if dataset_file and dataset_file.filename and dataset_content is not None:
        return {
            "source_type": "inline",
            "dataset_name": dataset_file.filename,
            "filename": dataset_file.filename,
            "content": dataset_content,
        }

    return {
        "source_type": "none",
        "dataset_name": "internal-data",
        "filename": None,
        "content": None,
    }


def parse_algorithm_replacements(
    algorithm_replacements: Optional[str]
) -> list[dict]:
    if not algorithm_replacements or not algorithm_replacements.strip():
        return []

    try:
        parsed = json.loads(algorithm_replacements)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid algorithm_replacements JSON: {exc}")

    if not isinstance(parsed, list):
        raise ValueError("algorithm_replacements must be a JSON list")

    for index, replacement in enumerate(parsed):
        if not isinstance(replacement, dict):
            raise ValueError(
                f"Each algorithm replacement must be a JSON object. Invalid index: {index}"
            )

    return parsed


@app.get("/")
def health():
    return {
        "status": "ok",
        "message": "Custom upload API is running",
    }


@app.get("/Health")
def health_uppercase():
    return {
        "status": "ok",
        "message": "Custom upload API is running",
    }


@app.post("/analyze-model-file")
async def analyze_model_file(
    model_file: UploadFile = File(...)
):
    """
    Analyse le fichier Python uploadÃ© avant le lancement Kubeflow.

    RÃ´le :
    - dÃ©tecter les algorithmes ML prÃ©sents dans le fichier ;
    - dÃ©tecter les bibliothÃ¨ques supportÃ©es ou non supportÃ©es ;
    - retourner les options de remplacement au frontend.
    """
    try:
        filename = model_file.filename or "model.py"

        if not filename.endswith(".py"):
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "model_file must be a Python file ending with .py",
                },
            )

        model_code = (await model_file.read()).decode("utf-8")

        if not model_code.strip():
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "model_file is empty",
                },
            )

        analysis_result = detect_algorithms(model_code)

        return {
            "status": "SUCCESS",
            "filename": filename,
            "analysis": analysis_result,
        }

    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={
                "status": "FAILED",
                "error": "Failed to analyze model file",
                "details": str(exc),
            },
        )


@app.post("/api/runtime/analyze-script")
async def analyze_runtime_script(
    script_file: UploadFile = File(...),
):
    try:
        filename = script_file.filename or "train.py"

        if not filename.endswith(".py"):
            return JSONResponse(
                status_code=400,
                content={
                    "supported": False,
                    "error": "script_file must be a Python file ending with .py",
                },
            )

        script_code = (await script_file.read()).decode("utf-8")

        if not script_code.strip():
            return JSONResponse(
                status_code=400,
                content={
                    "supported": False,
                    "error": "script_file is empty",
                },
            )

        analysis_result = analyze_script_imports(script_code)

        return {
            "supported": analysis_result["supported"],
            "detectedImports": analysis_result["detectedImports"],
            "supportedImports": analysis_result["supportedImports"],
            "unsupportedImports": analysis_result["unsupportedImports"],
            "recommendation": analysis_result["recommendation"],
        }

    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={
                "supported": False,
                "error": "Failed to analyze runtime script",
                "details": str(exc),
            },
        )


@app.post("/upload-and-run-custom-model")
async def upload_and_run_custom_model(
    project_name: str = Form(...),
    model_name: str = Form(...),
    run_name: str = Form(...),

    primary_metric: str = Form("f1_score"),
    direction: str = Form("max"),

    # Nouveau : optionnel, mais recommandÃ© depuis Angular.
    project_type: Optional[str] = Form(None),

    # Nom stable dans MLflow Registry.
    registered_model_name: Optional[str] = Form(None),

    # JSON contenant les remplacements choisis depuis Angular.
    algorithm_replacements: Optional[str] = Form(None),

    model_file: UploadFile = File(...),
    dataset_file: Optional[UploadFile] = File(None),
):
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

        if not project_name:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "project_name is required",
                },
            )

        if not model_name:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "model_name is required",
                },
            )

        if not run_name:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "run_name is required",
                },
            )

        model_filename = model_file.filename or "train.py"

        if not model_filename.endswith(".py"):
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "model_file must be a Python file ending with .py",
                },
            )

        model_code = (await model_file.read()).decode("utf-8")

        if not model_code.strip():
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": "model_file is empty",
                },
            )

        dataset_content = None

        if dataset_file and dataset_file.filename:
            dataset_content = (await dataset_file.read()).decode("utf-8")

            if not dataset_content.strip():
                return JSONResponse(
                    status_code=400,
                    content={
                        "status": "FAILED",
                        "error": "dataset_file is empty",
                    },
                )

        data_config = build_data_config(dataset_file, dataset_content)

        try:
            parsed_algorithm_replacements = parse_algorithm_replacements(
                algorithm_replacements
            )
        except ValueError as exc:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "FAILED",
                    "error": str(exc),
                },
            )

        final_registered_model_name = build_registered_model_name(
            project_name=project_name,
            registered_model_name=registered_model_name,
        )

        config = {
            "project_name": project_name,
            "project_type": project_type,
            "task": {
                "type": project_type,
            },
            "run": {
                "name": run_name,
            },
            "tracking": {
                "uri": "http://mlflow-service.mlops.svc.cluster.local:5000",
                # IMPORTANT :
                # custom_runner.py lit config["tracking"]["experiment_name"].
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
                "entrypoint": model_filename,
                "files": {
                    model_filename: model_code,
                },
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

        config_id = str(uuid.uuid4())
        config_path = GENERATED_CONFIG_DIR / f"{config_id}.yaml"

        config_path.write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True),
            encoding="utf-8",
        )

        pipeline_path = Path("kubeflow/compiled/custom_model_pipeline.yaml")

        if not pipeline_path.exists():
            return JSONResponse(
                status_code=500,
                content={
                    "status": "FAILED",
                    "error": "Pipeline Kubeflow custom not found",
                    "solution": "Compile it first with: python kubeflow/pipelines/custom_model_pipeline.py",
                    "expected_path": str(pipeline_path),
                    "configId": config_id,
                    "configPath": str(config_path),
                    "registeredModelName": final_registered_model_name,
                    "registered_model_name": final_registered_model_name,
                },
            )

        config_yaml = config_path.read_text(encoding="utf-8")

        client = Client(host="http://ml-pipeline.kubeflow.svc.cluster.local:8888")

        kubeflow_run = client.create_run_from_pipeline_package(
            pipeline_file=str(pipeline_path),
            arguments={
                "config_yaml": config_yaml,
            },
            experiment_name=project_name,
            run_name=run_name,
        )

        return {
            "status": "RUNNING",
            "message": "ModÃ¨le uploadÃ© et pipeline Kubeflow lancÃ© avec succÃ¨s",
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
            "modelVersion": None,
            "primaryMetricValue": None,

            "registered_model_name": final_registered_model_name,
            "mlflow_registered_model": final_registered_model_name,
            "mlflow_model_uri_after_training": f"models:/{final_registered_model_name}@production",
        }

    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={
                "status": "FAILED",
                "error": "Failed to upload model or launch Kubeflow pipeline",
                "details": str(exc),
                "registeredModelName": registered_model_name,
                "registered_model_name": registered_model_name,
            },
        )
