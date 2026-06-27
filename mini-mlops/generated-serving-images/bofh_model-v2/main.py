import io
import os
import time
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import mlflow
import mlflow.pyfunc
import mlflow.artifacts
import numpy as np
import pandas as pd
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from PIL import Image
from pydantic import BaseModel, Field

MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI", "http://mlflow-service.mlops.svc.cluster.local:5000").strip()
DEFAULT_MODEL_NAME = os.getenv("DEFAULT_MODEL_NAME", "").strip()
DEFAULT_MODEL_ALIAS = os.getenv("DEFAULT_MODEL_ALIAS", "production").strip()

mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

app = FastAPI(
    title="Mini MLOps Generic Prediction API",
    description="Generic prediction API for MLflow production models.",
    version="6.0.0-safe-loader",
)

loaded_models: Dict[str, Dict[str, Any]] = {}

class PredictionRequest(BaseModel):
    rows: Optional[List[Dict[str, Any]]] = Field(default=None)
    pixels: Optional[List[float]] = Field(default=None)
    model_name: Optional[str] = Field(default=None)
    model_alias: Optional[str] = Field(default=None)

class TextPredictionRequest(BaseModel):
    text: str
    model_name: Optional[str] = Field(default=None)
    model_alias: Optional[str] = Field(default=None)

def normalize_text(value: Optional[str]) -> str:
    if value is None:
        return ""
    return str(value).strip()

def build_model_uri(model_name: str, model_alias: str) -> str:
    model_name = normalize_text(model_name)
    model_alias = normalize_text(model_alias) or "production"
    if not model_name:
        raise ValueError("model_name is required.")
    return f"models:/{model_name}@{model_alias}"

def normalize_prediction_output(predictions: Any) -> Any:
    if hasattr(predictions, "tolist"):
        return predictions.tolist()
    if isinstance(predictions, np.ndarray):
        return predictions.tolist()
    return predictions

def get_model_version_info(model_name: str, model_alias: str) -> Dict[str, Any]:
    try:
        client = mlflow.tracking.MlflowClient(tracking_uri=MLFLOW_TRACKING_URI)
        model_version = client.get_model_version_by_alias(model_name, model_alias)
        return {
            "registered_model_name": model_name,
            "alias": model_alias,
            "version": str(model_version.version),
            "run_id": model_version.run_id,
            "status": model_version.status,
            "current_stage": getattr(model_version, "current_stage", None),
            "source": model_version.source,
        }
    except Exception as exc:
        return {
            "registered_model_name": model_name,
            "alias": model_alias,
            "warning": f"Could not resolve model version by alias: {str(exc)}",
        }

def list_available_files(local_dir: Optional[str]) -> List[str]:
    if not local_dir:
        return []
    local_path = Path(local_dir)
    if not local_path.exists():
        return []
    return [str(p.relative_to(local_path)) for p in local_path.rglob("*") if p.is_file()]

def resolve_mlflow_model_path_from_downloaded_dir(local_dir: str) -> str:
    if not local_dir:
        raise FileNotFoundError("local_dir is empty.")
    local_path = Path(local_dir)
    if not local_path.exists():
        raise FileNotFoundError(f"Downloaded artifact directory does not exist: {local_dir}")

    root_mlmodel = local_path / "MLmodel"
    nested_mlmodel = local_path / "model" / "MLmodel"

    print(f"[MODEL PATH RESOLVER] local_dir={local_dir}")
    print(f"[MODEL PATH RESOLVER] root_mlmodel={root_mlmodel}")
    print(f"[MODEL PATH RESOLVER] nested_mlmodel={nested_mlmodel}")

    if root_mlmodel.exists():
        print("[MODEL PATH RESOLVER] MLmodel found at root.")
        return str(local_path)

    if nested_mlmodel.exists():
        print("[MODEL PATH RESOLVER] MLmodel found inside model/ folder.")
        return str(local_path / "model")

    raise FileNotFoundError(
        "MLmodel file not found after downloading artifact. "
        f"local_dir={local_dir}, available_files={list_available_files(local_dir)}"
    )

def find_torch_model_file(local_dir: str) -> str:
    local_path = Path(local_dir)
    if not local_path.exists():
        raise FileNotFoundError(f"Downloaded artifact directory does not exist: {local_dir}")
    candidates = list(local_path.rglob("*.pt")) + list(local_path.rglob("*.pth"))
    if not candidates:
        raise FileNotFoundError(
            "No .pt/.pth TorchScript model found inside downloaded MLflow artifact. "
            f"local_dir={local_dir}, available_files={list_available_files(local_dir)}"
        )
    candidates = sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True)
    selected = candidates[0]
    print(f"[TORCH LOADER] Selected torch model file: {selected}")
    return str(selected)

def load_model_from_registry(model_name: str, model_alias: str = "production") -> Tuple[Dict[str, Any], str, Dict[str, Any]]:
    model_name = normalize_text(model_name)
    model_alias = normalize_text(model_alias) or "production"
    model_uri = build_model_uri(model_name, model_alias)
    cache_key = f"{model_name}@{model_alias}"

    if cache_key in loaded_models:
        cached = loaded_models[cache_key]
        return cached, model_uri, cached["version_info"]

    print("========== LOADING MODEL ==========")
    print(f"MLFLOW_TRACKING_URI = {MLFLOW_TRACKING_URI}")
    print(f"MODEL_NAME = {model_name}")
    print(f"MODEL_ALIAS = {model_alias}")
    print(f"MODEL_URI = {model_uri}")
    print("===================================")

    version_info = get_model_version_info(model_name, model_alias)
    pyfunc_errors: List[str] = []
    local_dir: Optional[str] = None

    try:
        pyfunc_model = mlflow.pyfunc.load_model(model_uri)
        loaded = {
            "type": "pyfunc",
            "model": pyfunc_model,
            "model_uri": model_uri,
            "version_info": version_info,
            "loaded_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        loaded_models[cache_key] = loaded
        print(f"[MODEL LOADER] MLflow pyfunc model loaded successfully from registry: {model_uri}")
        return loaded, model_uri, version_info
    except Exception as exc:
        pyfunc_errors.append(f"direct_registry_load_error={repr(exc)}")
        print("[MODEL LOADER] Direct pyfunc load failed.")
        print(f"[MODEL LOADER] Direct pyfunc error: {repr(exc)}")

    try:
        local_dir = mlflow.artifacts.download_artifacts(model_uri)
        model_path = resolve_mlflow_model_path_from_downloaded_dir(local_dir)
        pyfunc_model = mlflow.pyfunc.load_model(model_path)
        loaded = {
            "type": "pyfunc",
            "model": pyfunc_model,
            "model_uri": model_uri,
            "version_info": version_info,
            "local_dir": local_dir,
            "model_path": model_path,
            "loaded_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        loaded_models[cache_key] = loaded
        print(f"[MODEL LOADER] MLflow pyfunc model loaded successfully from local path: {model_path}")
        return loaded, model_uri, version_info
    except Exception as exc:
        pyfunc_errors.append(f"local_artifact_load_error={repr(exc)}")
        print("[MODEL LOADER] Local artifact pyfunc load failed.")
        print(f"[MODEL LOADER] Local artifact pyfunc error: {repr(exc)}")
        print(f"[MODEL LOADER] available_files={list_available_files(local_dir)}")

    try:
        import torch
        if local_dir is None:
            local_dir = mlflow.artifacts.download_artifacts(model_uri)
        model_path = find_torch_model_file(local_dir)
        try:
            torch_model = torch.jit.load(model_path, map_location="cpu")
            torch_model_kind = "torchscript"
        except Exception as jit_exc:
            print(f"[TORCH LOADER] torch.jit.load failed, trying torch.load. Error: {jit_exc}")
            torch_model = torch.load(model_path, map_location="cpu")
            torch_model_kind = "torch_load"
        if hasattr(torch_model, "eval"):
            torch_model.eval()
        loaded = {
            "type": "torch",
            "model": torch_model,
            "model_uri": model_uri,
            "version_info": version_info,
            "local_dir": local_dir,
            "model_path": model_path,
            "torch_model_kind": torch_model_kind,
            "loaded_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        loaded_models[cache_key] = loaded
        print(f"[MODEL LOADER] Torch model loaded successfully: {model_path}")
        return loaded, model_uri, version_info
    except Exception as torch_exc:
        available_files = list_available_files(local_dir)
        print("[MODEL LOADER ERROR] Failed to load model as pyfunc or torch artifact.")
        print(f"[MODEL LOADER ERROR] pyfunc_errors={pyfunc_errors}")
        print(f"[MODEL LOADER ERROR] torch_error={repr(torch_exc)}")
        print(f"[MODEL LOADER ERROR] available_files={available_files}")
        raise HTTPException(
            status_code=404,
            detail={
                "message": "Failed to load model from MLflow Model Registry.",
                "model_uri": model_uri,
                "tracking_uri": MLFLOW_TRACKING_URI,
                "version_info": version_info,
                "pyfunc_errors": pyfunc_errors,
                "torch_error": repr(torch_exc),
                "available_files": available_files,
                "hint": "If MLmodel exists but pyfunc loading fails, the remaining common cause is a missing Python dependency in the serving image.",
            },
        )

def validate_pixels(pixels: Any) -> List[float]:
    if not isinstance(pixels, list):
        raise HTTPException(status_code=400, detail="pixels must be a list of exactly 784 numeric values.")
    if len(pixels) != 784:
        raise HTTPException(status_code=400, detail=f"pixels must contain exactly 784 numeric values. Received {len(pixels)}.")
    validated_pixels: List[float] = []
    for index, value in enumerate(pixels):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise HTTPException(status_code=400, detail=f"Each pixel must be numeric. Invalid value at index {index}: {value}")
        validated_pixels.append(float(value))
    return validated_pixels

def dataframe_from_rows(rows: List[Dict[str, Any]]) -> pd.DataFrame:
    if rows is None or len(rows) == 0:
        raise HTTPException(status_code=400, detail="rows must contain at least one input row.")
    df = pd.DataFrame(rows)
    if df.empty:
        raise HTTPException(status_code=400, detail="Input dataframe is empty after converting rows.")
    return df

def prepare_prediction_input(request: PredictionRequest) -> Tuple[str, pd.DataFrame]:
    if request.pixels is not None:
        if request.rows:
            raise HTTPException(status_code=400, detail="Do not provide both pixels and rows.")
        pixels = validate_pixels(request.pixels)
        return "vision_pixels", pd.DataFrame([{"pixels": pixels}])
    if request.rows is None:
        raise HTTPException(status_code=400, detail="You must provide rows for tabular models or pixels for image/MNIST models.")
    df = dataframe_from_rows(request.rows)
    if "pixels" in df.columns:
        for pixels in df["pixels"]:
            validate_pixels(pixels)
        return "vision_pixels", df
    if "image" in df.columns:
        return "vision_image", df
    return "tabular", df

def parse_possible_json(value: Any) -> Any:
    if isinstance(value, str):
        cleaned = value.strip()
        if cleaned.startswith("[") or cleaned.startswith("{"):
            return json.loads(cleaned)
    return value

def normalize_image_array_for_mnist(arr: np.ndarray) -> np.ndarray:
    arr = arr.astype(np.float32)
    if arr.max() > 1.0:
        arr = arr / 255.0
    if arr.mean() > 0.5:
        arr = 1.0 - arr
    arr = (arr - 0.1307) / 0.3081
    return arr

def prepare_torch_input_from_dataframe(df: pd.DataFrame):
    import torch
    if df is None or df.empty:
        raise HTTPException(status_code=400, detail="Input dataframe is empty.")
    first_row = df.iloc[0].to_dict()
    if "pixels" in first_row:
        pixels = parse_possible_json(first_row["pixels"])
        pixels = validate_pixels(pixels)
        arr = np.array(pixels, dtype=np.float32)
        arr = normalize_image_array_for_mnist(arr)
        return torch.tensor(arr, dtype=torch.float32).reshape(1, 1, 28, 28)
    if "image" in first_row:
        image = parse_possible_json(first_row["image"])
        arr = np.array(image, dtype=np.float32)
        if arr.max() > 1.0:
            arr = arr / 255.0
        if arr.ndim == 1 and arr.size == 784:
            arr = normalize_image_array_for_mnist(arr)
            return torch.tensor(arr, dtype=torch.float32).reshape(1, 1, 28, 28)
        if arr.ndim == 2:
            if arr.shape == (28, 28):
                arr = normalize_image_array_for_mnist(arr)
            return torch.tensor(arr, dtype=torch.float32).unsqueeze(0).unsqueeze(0)
        if arr.ndim == 3:
            return torch.tensor(arr, dtype=torch.float32).permute(2, 0, 1).unsqueeze(0)
        if arr.ndim == 4:
            return torch.tensor(arr, dtype=torch.float32)
        raise HTTPException(status_code=400, detail=f"Unsupported image array shape for torch model: {arr.shape}")
    try:
        arr = df.to_numpy(dtype=np.float32)
    except Exception as exc:
        raise HTTPException(status_code=400, detail={"message": "Cannot convert tabular input to float32 for torch model.", "error": str(exc), "received_columns": list(df.columns)})
    return torch.tensor(arr, dtype=torch.float32)

def make_predictions(loaded: Dict[str, Any], model_input: pd.DataFrame):
    model_type = loaded.get("type")
    model = loaded.get("model")
    if model is None:
        raise HTTPException(status_code=500, detail="Loaded model object is missing.")
    if model_type == "pyfunc":
        try:
            predictions = model.predict(model_input)
            return normalize_prediction_output(predictions)
        except Exception as exc:
            error_detail = {
                "message": "Prediction failed for MLflow pyfunc model.",
                "error": str(exc),
                "hint": "For tabular models, send rows with the same feature names used during training.",
            }
            if isinstance(model_input, pd.DataFrame):
                error_detail["received_columns"] = list(model_input.columns)
            raise HTTPException(status_code=400, detail=error_detail)
    if model_type == "torch":
        try:
            import torch
            input_tensor = prepare_torch_input_from_dataframe(model_input)
            with torch.no_grad():
                output = model(input_tensor)
            if isinstance(output, tuple):
                output = output[0]
            if hasattr(output, "detach"):
                output_np = output.detach().cpu().numpy()
            else:
                output_np = np.array(output)
            if output_np.ndim == 2 and output_np.shape[1] > 1:
                return output_np.argmax(axis=1).astype(int).tolist()
            return normalize_prediction_output(output_np)
        except HTTPException:
            raise
        except Exception as exc:
            error_detail = {"message": "Prediction failed for TorchScript/PyTorch artifact model.", "model_path": loaded.get("model_path"), "torch_model_kind": loaded.get("torch_model_kind"), "error": str(exc)}
            if isinstance(model_input, pd.DataFrame):
                error_detail["received_columns"] = list(model_input.columns)
            raise HTTPException(status_code=400, detail=error_detail)
    raise HTTPException(status_code=500, detail=f"Unsupported loaded model type: {model_type}")

@app.get("/")
def root():
    return {"status": "ok", "message": "Mini MLOps prediction API is running.", "docs": "/docs", "tracking_uri": MLFLOW_TRACKING_URI}

@app.get("/health")
def health():
    return {"status": "ok", "tracking_uri": MLFLOW_TRACKING_URI, "loaded_models_count": len(loaded_models), "loaded_models": list(loaded_models.keys())}

@app.get("/model-info")
def model_info():
    return {
        "tracking_uri": MLFLOW_TRACKING_URI,
        "default_model_name": DEFAULT_MODEL_NAME,
        "default_model_alias": DEFAULT_MODEL_ALIAS,
        "loaded_models_count": len(loaded_models),
        "loaded_models": {key: {"model_uri": value.get("model_uri"), "type": value.get("type"), "version_info": value.get("version_info"), "loaded_at": value.get("loaded_at"), "model_path": value.get("model_path"), "torch_model_kind": value.get("torch_model_kind")} for key, value in loaded_models.items()},
    }

@app.post("/predict")
def predict(request: PredictionRequest):
    model_name = normalize_text(request.model_name) or DEFAULT_MODEL_NAME
    model_alias = normalize_text(request.model_alias) or DEFAULT_MODEL_ALIAS or "production"
    if not model_name:
        raise HTTPException(status_code=400, detail={"message": "model_name is required because DEFAULT_MODEL_NAME is not configured."})
    prediction_mode, model_input = prepare_prediction_input(request)
    loaded, model_uri, version_info = load_model_from_registry(model_name=model_name, model_alias=model_alias)
    predictions = make_predictions(loaded, model_input)
    if prediction_mode == "vision_pixels":
        input_columns = ["pixels"]
    elif prediction_mode == "vision_image":
        input_columns = ["image"]
    else:
        input_columns = list(model_input.columns)
    return {"status": "success", "model_uri": model_uri, "model_name": model_name, "model_alias": model_alias, "model_type": loaded.get("type"), "torch_model_kind": loaded.get("torch_model_kind"), "version_info": version_info, "prediction_mode": prediction_mode, "rows_count": len(model_input), "input_columns": input_columns, "predictions": predictions}

@app.post("/predict-image")
async def predict_image(image_file: UploadFile = File(...), model_name: str = Form(...), model_alias: str = Form("production"), image_size: int = Form(28), grayscale: bool = Form(True), auto_invert: bool = Form(True)):
    try:
        model_name = normalize_text(model_name)
        model_alias = normalize_text(model_alias) or "production"
        if not model_name:
            raise ValueError("model_name is required.")
        if image_size <= 0:
            raise ValueError("image_size must be positive.")
        content = await image_file.read()
        if not content:
            raise ValueError("Uploaded image is empty.")
        image = Image.open(io.BytesIO(content))
        if grayscale:
            image = image.convert("L")
        else:
            image = image.convert("RGB")
        image = image.resize((image_size, image_size))
        image_array = np.array(image).astype("float32") / 255.0
        if auto_invert and grayscale and image_array.mean() > 0.5:
            image_array = 1.0 - image_array
        loaded, model_uri, version_info = load_model_from_registry(model_name=model_name, model_alias=model_alias)
        if grayscale and image_size == 28:
            pixels = image_array.reshape(-1).astype(float).tolist()
            model_input = pd.DataFrame([{"pixels": pixels}])
            prediction_mode = "image_file_to_pixels"
            input_columns = ["pixels"]
        else:
            model_input = pd.DataFrame([{"image": image_array.tolist()}])
            prediction_mode = "image_file"
            input_columns = ["image"]
        predictions = make_predictions(loaded, model_input)
        return {"status": "success", "model_uri": model_uri, "model_name": model_name, "model_alias": model_alias, "model_type": loaded.get("type"), "torch_model_kind": loaded.get("torch_model_kind"), "version_info": version_info, "prediction_mode": prediction_mode, "image_filename": image_file.filename, "image_size": [image_size, image_size], "grayscale": grayscale, "auto_invert": auto_invert, "input_columns": input_columns, "predictions": predictions}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail={"message": "Image prediction failed.", "error": str(exc)})

@app.post("/predict-text")
def predict_text(request: TextPredictionRequest):
    try:
        model_name = normalize_text(request.model_name) or DEFAULT_MODEL_NAME
        model_alias = normalize_text(request.model_alias) or DEFAULT_MODEL_ALIAS or "production"
        text = normalize_text(request.text)
        if not model_name:
            raise ValueError("model_name is required.")
        if not text:
            raise ValueError("text is required.")
        loaded, model_uri, version_info = load_model_from_registry(model_name=model_name, model_alias=model_alias)
        model_input = pd.DataFrame([{"text": text}])
        predictions = make_predictions(loaded, model_input)
        return {"status": "success", "model_uri": model_uri, "model_name": model_name, "model_alias": model_alias, "model_type": loaded.get("type"), "version_info": version_info, "prediction_mode": "text", "input_columns": ["text"], "predictions": predictions}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail={"message": "Text prediction failed.", "error": str(exc)})

@app.post("/clear-cache")
def clear_cache():
    loaded_models.clear()
    return {"status": "success", "message": "Model cache cleared successfully.", "loaded_models_count": len(loaded_models)}
