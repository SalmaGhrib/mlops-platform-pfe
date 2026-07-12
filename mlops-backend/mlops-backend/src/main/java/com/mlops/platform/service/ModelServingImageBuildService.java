package com.mlops.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import com.mlops.platform.entity.MlExperiment;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

@Service
public class ModelServingImageBuildService {

    /*
     * Template interne sûr.
     * Cette version empêche définitivement les images dédiées de repartir avec
     * un ancien main.py qui demande --config ou qui charge mal MLflow.
     */
    private static final String SAFE_SERVING_TEMPLATE_VERSION = "2026-07-05-fastapi-universal-prediction-v4";

    private static final String SAFE_DOCKERFILE_TEMPLATE = """
FROM python:3.10-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN python -m pip install --upgrade pip && python -m pip install --no-cache-dir -r /app/requirements.txt

COPY main.py /app/main.py

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
            """;

    private static final String SAFE_MAIN_PY_TEMPLATE = """
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
MODEL_URI = os.getenv("MODEL_URI", "").strip()
MODEL_NAME_ENV = os.getenv("MODEL_NAME", "").strip()
REGISTERED_MODEL_NAME_ENV = os.getenv("REGISTERED_MODEL_NAME", "").strip()

def extract_model_name_from_uri(model_uri: str) -> str:
    if model_uri is None:
        return ""
    model_uri = str(model_uri).strip()
    if not model_uri.startswith("models:/"):
        return ""
    clean = model_uri.replace("models:/", "", 1)
    if "@" in clean:
        return clean.split("@", 1)[0].strip()
    parts = clean.split("/")
    return parts[0].strip() if parts else ""

DEFAULT_MODEL_NAME = (
    os.getenv("DEFAULT_MODEL_NAME", "").strip()
    or MODEL_NAME_ENV
    or REGISTERED_MODEL_NAME_ENV
    or extract_model_name_from_uri(MODEL_URI)
)
DEFAULT_MODEL_ALIAS = os.getenv("DEFAULT_MODEL_ALIAS", os.getenv("MODEL_ALIAS", "production")).strip() or "production"

mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

app = FastAPI(
    title="Mini MLOps Generic Prediction API",
    description="Generic prediction API for MLflow production models.",
    version="6.2.0-universal-prediction",
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

def pixels_to_feature_row(pixels: Any) -> Dict[str, float]:
    validated_pixels = validate_pixels(pixels)
    return {f"pixel_{index}": float(value) for index, value in enumerate(validated_pixels)}

def pixels_dataframe_to_feature_columns(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty or "pixels" not in df.columns:
        return df

    rows: List[Dict[str, float]] = []

    for value in df["pixels"].tolist():
        parsed_value = parse_possible_json(value)
        rows.append(pixels_to_feature_row(parsed_value))

    return pd.DataFrame(rows)

def build_image_model_input_from_pixels(
    pixels: Any,
    loaded: Optional[Dict[str, Any]] = None
) -> Tuple[pd.DataFrame, str, List[str]]:
    validated_pixels = validate_pixels(pixels)
    loaded_type = normalize_text((loaded or {}).get("type")).lower()

    if loaded_type == "torch":
        df = pd.DataFrame([{"pixels": validated_pixels}])
        return df, "image_file_to_pixels", ["pixels"]

    df = pd.DataFrame([pixels_to_feature_row(validated_pixels)])
    return df, "image_file_to_pixel_columns", list(df.columns)

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
            pixel_columns_retry_error = None

            if isinstance(model_input, pd.DataFrame) and "pixels" in model_input.columns:
                try:
                    converted_input = pixels_dataframe_to_feature_columns(model_input)
                    predictions = model.predict(converted_input)
                    return normalize_prediction_output(predictions)
                except Exception as retry_exc:
                    pixel_columns_retry_error = str(retry_exc)

            error_detail = {
                "message": "Prediction failed for MLflow pyfunc model.",
                "error": str(exc),
                "hint": "For image models trained with pixel_0..pixel_783 columns, /predict-image automatically converts uploaded images to pixel feature columns.",
            }
            if isinstance(model_input, pd.DataFrame):
                error_detail["received_columns"] = list(model_input.columns)
            if pixel_columns_retry_error:
                error_detail["pixel_columns_retry_error"] = pixel_columns_retry_error
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
async def predict_image(
    image_file: UploadFile = File(...),
    model_name: str = Form(None),
    model_alias: str = Form("production"),
    image_size: int = Form(28),
    grayscale: bool = Form(True),
    auto_invert: bool = Form(True)
):
    try:
        model_name = normalize_text(model_name) or DEFAULT_MODEL_NAME
        model_alias = normalize_text(model_alias) or DEFAULT_MODEL_ALIAS or "production"

        if not model_name:
            raise ValueError("model_name is required because DEFAULT_MODEL_NAME is not configured.")

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

        raw_image_array = np.array(image).astype("float32")

        # This server must preserve the same scale used during classical image training.
        # For sklearn/pyfunc image models trained from CSV columns pixel_0..pixel_783,
        # the expected values are generally raw pixels in [0, 255].
        #
        # Therefore:
        # - pyfunc/sklearn image input receives pixel_0..pixel_783 with raw 0..255 values
        # - torch image input receives "pixels" and is normalized later by prepare_torch_input_from_dataframe
        if auto_invert and grayscale and raw_image_array.mean() > 127.5:
            raw_image_array = 255.0 - raw_image_array

        loaded, model_uri, version_info = load_model_from_registry(
            model_name=model_name,
            model_alias=model_alias
        )

        if grayscale and image_size == 28:
            pixels = raw_image_array.reshape(-1).astype(float).tolist()

            if len(pixels) != 784:
                raise ValueError(f"Expected 784 pixels after preprocessing, got {len(pixels)}.")

            model_input, prediction_mode, input_columns = build_image_model_input_from_pixels(
                pixels,
                loaded
            )
        else:
            # Generic image input for non-MNIST image models.
            # Keep a normalized nested image array because this format is usually expected
            # by deep-learning image models that do not use pixel_0..pixel_N tabular columns.
            normalized_image_array = raw_image_array

            if normalized_image_array.size > 0 and normalized_image_array.max() > 1.0:
                normalized_image_array = normalized_image_array / 255.0

            model_input = pd.DataFrame([{"image": normalized_image_array.tolist()}])
            prediction_mode = "image_file"
            input_columns = ["image"]

        predictions = make_predictions(loaded, model_input)

        return {
            "status": "success",
            "model_uri": model_uri,
            "model_name": model_name,
            "model_alias": model_alias,
            "model_type": loaded.get("type"),
            "torch_model_kind": loaded.get("torch_model_kind"),
            "version_info": version_info,
            "prediction_mode": prediction_mode,
            "image_filename": image_file.filename,
            "image_size": [image_size, image_size],
            "grayscale": grayscale,
            "auto_invert": auto_invert,
            "input_columns": input_columns,
            "predictions": predictions
        }

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Image prediction failed.",
                "error": str(exc)
            }
        )

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
            """;


@Value("${app.kubernetes.namespace:mlops}")
private String namespace;

@Value("${app.serving.template-dir:/tmp/mlops/serving-template}")
private String servingTemplateDir;

@Value("${app.serving.build-dir:/tmp/mlops/generated-serving-images}")
private String servingBuildDir;

@Value("${app.mlflow.tracking-uri:${mlflow.tracking.uri:http://mlflow-service:5000}}")
private String localMlflowTrackingUrl;

@Value("${app.serving.reuse-image-if-same-requirements:false}")
private boolean reuseImageIfSameRequirements;

@Value("${app.serving.compatible-pyarrow-version:17.0.0}")
private String compatiblePyarrowVersion;

@Value("${app.kaniko.image:gcr.io/kaniko-project/executor:latest}")
private String kanikoImage;

@Value("${app.kaniko.docker-config-secret:kaniko-docker-config}")
private String kanikoDockerConfigSecret;

@Value("${app.kaniko.cache-repo:acrmlopssalma.azurecr.io/mlops-kaniko-cache}")
private String kanikoCacheRepo;

@Value("${app.serving.kaniko.timeout-seconds:1200}")
private long servingKanikoTimeoutSeconds;
    

    @Value("${app.docker.registry:acrmlopssalma.azurecr.io}")
    private String dockerRegistry;

    public String buildServingImageForModel(
        String registeredModelName,
        String modelVersion,
        String modelUri,
        String dataType
) {
    return buildServingImageForModel(
            null,
            registeredModelName,
            modelVersion,
            modelUri,
            dataType
    );
}

public String buildServingImageForModel(
        MlExperiment experiment,
        String registeredModelName,
        String modelVersion,
        String modelUri,
        String dataType
) {
    validateInputs(registeredModelName, modelVersion, modelUri);

    String safeModelName = sanitizeForDockerName(registeredModelName);
    String safeVersion = sanitizeForDockerName(modelVersion);

    String imageTag = safeModelName.replace("_", "-") + "-v" + safeVersion;

    String imageName = dockerRegistry.trim()
            + "/mlops-serving:"
            + imageTag;

    String baseImage = resolveServingBaseImage(experiment);

    Path templateDir = Paths.get(servingTemplateDir).toAbsolutePath().normalize();
    Path buildRoot = Paths.get(servingBuildDir).toAbsolutePath().normalize();

    Path finalBuildContext = buildRoot.resolve("serving-" + safeModelName + "-v" + safeVersion);

    Path tempBuildContext = buildRoot.resolve(
            "serving-" + safeModelName + "-v" + safeVersion + "-tmp-" + System.currentTimeMillis()
    );

    String configMapName = buildServingConfigMapName(safeModelName, safeVersion);
    String jobName = buildServingKanikoJobName(safeModelName, safeVersion);

    try {
        System.out.println("[SERVING_IMAGE] =======================================");
        System.out.println("[SERVING_IMAGE] Building dedicated serving image with Kaniko");
        System.out.println("[SERVING_IMAGE] model=" + registeredModelName);
        System.out.println("[SERVING_IMAGE] version=" + modelVersion);
        System.out.println("[SERVING_IMAGE] modelUri=" + modelUri);
        System.out.println("[SERVING_IMAGE] dataType=" + dataType);
        System.out.println("[SERVING_IMAGE] baseImage=" + baseImage);
        System.out.println("[SERVING_IMAGE] destinationImage=" + imageName);
        System.out.println("[SERVING_IMAGE] configMap=" + configMapName);
        System.out.println("[SERVING_IMAGE] kanikoJob=" + jobName);
        System.out.println("[SERVING_IMAGE] buildContext=" + finalBuildContext);
        System.out.println("[SERVING_IMAGE] =======================================");

        Files.createDirectories(buildRoot);

        if (Files.exists(tempBuildContext)) {
            deleteDirectory(tempBuildContext);
        }

        Files.createDirectories(tempBuildContext);

        /*
         * Important :
         * Pour CUSTOM_DOCKERFILE, on part de l'image custom training.
         * Cette image contient déjà les dépendances exactes du modèle.
         *
         * Exemple :
         * FROM docker.io/salmaghrib2003/mlops-custom:aks1-exp-11
         */
        copyTemplateFiles(tempBuildContext, baseImage);

        List<String> finalRequirements = generateServingRequirementsForBaseImage(
                baseImage,
                modelUri,
                templateDir
        );

        writeRequirements(tempBuildContext, finalRequirements);

        String requirementsHash = computeRequirementsHash(finalRequirements);

        writeBuildMetadata(
                tempBuildContext,
                registeredModelName,
                modelVersion,
                modelUri,
                dataType,
                imageName,
                finalRequirements,
                requirementsHash
        );

        validateBuildContext(tempBuildContext);

        if (Files.exists(finalBuildContext)) {
            deleteDirectory(finalBuildContext);
        }

        Files.move(tempBuildContext, finalBuildContext, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("[SERVING_IMAGE] Build context ready: " + finalBuildContext);

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            ensureKanikoDockerSecretExists(client);

            deleteExistingKanikoJobIfExists(client, jobName);
            deleteExistingConfigMapIfExists(client, configMapName);

            createServingBuildContextConfigMap(
                    client,
                    configMapName,
                    finalBuildContext,
                    registeredModelName,
                    modelVersion,
                    imageName
            );

            Job job = buildServingKanikoJob(
                    jobName,
                    configMapName,
                    imageName,
                    modelUri
            );

            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .resource(job)
                    .create();

            System.out.println("[SERVING_IMAGE_KANIKO] Kaniko job created. "
                    + "jobName=" + jobName
                    + ", image=" + imageName);

            waitForKanikoJobCompletion(client, jobName);

            System.out.println("[SERVING_IMAGE_KANIKO] Kaniko build completed successfully. "
                    + "jobName=" + jobName
                    + ", image=" + imageName);

            return imageName;
        }

    } catch (Exception e) {
        try {
            if (Files.exists(tempBuildContext)) {
                deleteDirectory(tempBuildContext);
            }
        } catch (Exception cleanupException) {
            System.out.println("[SERVING_IMAGE] Failed to clean temp folder: " + cleanupException.getMessage());
        }

        String fullMessage = buildFullErrorMessage(e);

        System.out.println("[SERVING_IMAGE] Failed to build serving image with Kaniko.");
        System.out.println("[SERVING_IMAGE] Reason: " + fullMessage);
        e.printStackTrace();

        throw new RuntimeException(
                "Failed to build dedicated serving image for model "
                        + registeredModelName
                        + " version "
                        + modelVersion
                        + ". Reason: "
                        + fullMessage,
                e
        );
    }
}

    private void validateInputs(String registeredModelName, String modelVersion, String modelUri) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new IllegalArgumentException("registeredModelName is required.");
        }

        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion is required.");
        }

        if (modelUri == null || modelUri.isBlank()) {
            throw new IllegalArgumentException("modelUri is required.");
        }
    }

    private void validateTemplate(Path templateDir) {
        if (templateDir == null || !Files.exists(templateDir)) {
            System.out.println("[SERVING_IMAGE] serving-template folder not found. Embedded safe FastAPI template will be used. templateDir="
                    + templateDir);
            return;
        }

        Path baseRequirements = templateDir.resolve("requirements.base.txt");

        if (!Files.exists(baseRequirements)) {
            System.out.println("[SERVING_IMAGE] requirements.base.txt not found. Fallback requirements will be used.");
        } else if (isEmptyFile(baseRequirements)) {
            System.out.println("[SERVING_IMAGE] requirements.base.txt is empty. Fallback requirements will be used.");
        }

        System.out.println("[SERVING_IMAGE] Embedded safe FastAPI Dockerfile/main.py will be used. Old template Dockerfile/main.py will be ignored.");
    }

    private void copyTemplateFiles(Path buildContext, String baseImage) throws Exception {
    Path targetDockerfile = buildContext.resolve("Dockerfile");
    Path targetMainPy = buildContext.resolve("main.py");

    String dockerfile = buildSafeServingDockerfile(baseImage);

    Files.writeString(
            targetDockerfile,
            dockerfile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
    );

    Files.writeString(
            targetMainPy,
            SAFE_MAIN_PY_TEMPLATE,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
    );

    assertFileExistsAndNotEmpty(targetDockerfile, "generated safe Dockerfile");
    assertFileExistsAndNotEmpty(targetMainPy, "generated safe main.py");

    System.out.println("[SERVING_IMAGE] Safe Dockerfile generated at: " + targetDockerfile);
    System.out.println("[SERVING_IMAGE] Safe main.py generated at: " + targetMainPy);
    System.out.println("[SERVING_IMAGE] Serving template version=" + SAFE_SERVING_TEMPLATE_VERSION);
}

private String buildSafeServingDockerfile(String baseImage) {
    String cleanBaseImage = hasText(baseImage)
            ? baseImage.trim()
            : "python:3.10-slim";

    return """
FROM %s

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt /app/requirements-serving.txt

RUN python -m pip install --upgrade pip \\
    && python -m pip install --no-cache-dir -r /app/requirements-serving.txt

COPY main.py /app/main.py

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
            """.formatted(cleanBaseImage);
}

private String resolveServingBaseImage(MlExperiment experiment) {
    if (experiment != null && hasText(experiment.getCustomImageUri())) {
        String customImage = experiment.getCustomImageUri().trim();

        System.out.println("[SERVING_IMAGE] Using custom training image as serving base image. "
                + "experimentId=" + experiment.getId()
                + ", customImageUri=" + customImage);

        return customImage;
    }

    System.out.println("[SERVING_IMAGE] No custom training image found. "
            + "Fallback to python:3.10-slim base image.");

    return "python:3.10-slim";
}

private List<String> generateServingRequirementsForBaseImage(
        String baseImage,
        String modelUri,
        Path templateDir
) {
    /*
     * Si on part de l'image custom training, elle contient déjà les dépendances du modèle :
     * numpy, pandas, mlflow, sklearn, torch, etc.
     *
     * Donc on n'installe que les dépendances nécessaires au serveur FastAPI.
     * Cela évite d'écraser numpy/pandas/mlflow avec de mauvaises versions.
     */
    if (hasText(baseImage) && !baseImage.trim().startsWith("python:")) {
        List<String> servingOnly = servingOnlyRequirements();

        System.out.println("[SERVING_IMAGE] Custom base image detected. "
                + "Only serving API requirements will be installed.");

        for (String req : servingOnly) {
            System.out.println("[SERVING_IMAGE]   " + req);
        }

        return servingOnly;
    }

    /*
     * Si on n'a pas d'image custom, on utilise l'ancienne logique :
     * requirements MLflow + fallback.
     */
    List<String> finalRequirements = generateFinalRequirements(modelUri, templateDir);

    if (finalRequirements == null || finalRequirements.isEmpty()) {
        finalRequirements = fallbackRequirements();
    }

    return finalRequirements;
}

private List<String> servingOnlyRequirements() {
    return List.of(
            "fastapi==0.115.6",
            "uvicorn[standard]==0.32.1",
            "python-multipart==0.0.20",
            "pydantic==2.10.4",
            "pillow==10.4.0"
    );
}

private void ensureKanikoDockerSecretExists(KubernetesClient client) {
    if (!hasText(kanikoDockerConfigSecret)) {
        throw new RuntimeException("Kaniko Docker config secret name is empty.");
    }

    boolean exists = client.secrets()
            .inNamespace(namespace)
            .withName(kanikoDockerConfigSecret.trim())
            .get() != null;

    if (!exists) {
        throw new RuntimeException(
                "Kaniko Docker config secret not found in namespace "
                        + namespace
                        + ": "
                        + kanikoDockerConfigSecret
                        + ". Please create the secret before building serving images."
        );
    }
}

private void createServingBuildContextConfigMap(
        KubernetesClient client,
        String configMapName,
        Path buildContext,
        String registeredModelName,
        String modelVersion,
        String imageName
) throws Exception {
    Map<String, String> data = new LinkedHashMap<>();

    data.put("Dockerfile", Files.readString(buildContext.resolve("Dockerfile"), StandardCharsets.UTF_8));
    data.put("main.py", Files.readString(buildContext.resolve("main.py"), StandardCharsets.UTF_8));
    data.put("requirements.txt", Files.readString(buildContext.resolve("requirements.txt"), StandardCharsets.UTF_8));
    data.put("serving-build-metadata.json", Files.readString(buildContext.resolve("serving-build-metadata.json"), StandardCharsets.UTF_8));

    ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .addToLabels("app", "mlops-serving-image-build")
                .addToLabels("mlops.phase", "serving-image-build")
                .addToLabels("mlops.model", sanitizeForKubernetesLabel(registeredModelName))
                .addToLabels("mlops.model.version", sanitizeForKubernetesLabel(modelVersion))
            .endMetadata()
            .withData(data)
            .build();

    client.configMaps()
            .inNamespace(namespace)
            .resource(configMap)
            .create();

    System.out.println("[SERVING_IMAGE_KANIKO] Build context ConfigMap created. "
            + "configMap=" + configMapName
            + ", image=" + imageName);
}

private Job buildServingKanikoJob(
        String jobName,
        String configMapName,
        String destinationImage,
        String modelUri
) {
    List<String> args = new ArrayList<>();

    args.add("--dockerfile=/kaniko-context/Dockerfile");
    args.add("--context=dir:///kaniko-context");
    args.add("--destination=" + destinationImage);
    args.add("--push-retry=5");
    args.add("--image-download-retry=3");
    args.add("--image-fs-extract-retry=3");
    args.add("--cache=true");
    args.add("--cache-repo=" + kanikoCacheRepo);
    args.add("--cache-ttl=168h");
    args.add("--cache-copy-layers=true");
    args.add("--build-arg=MODEL_URI=" + modelUri);
    args.add("--build-arg=MLFLOW_TRACKING_URI=" + localMlflowTrackingUrl);

    return new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(namespace)
                .addToLabels("app", "mlops-serving-image-build")
                .addToLabels("mlops.phase", "serving-image-build")
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(3600)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", "mlops-serving-image-build")
                        .addToLabels("job-name", jobName)
                        .addToLabels("mlops.phase", "serving-image-build")
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")

                        .addNewVolume()
                            .withName("build-context-configmap")
                            .withNewConfigMap()
                                .withName(configMapName)
                            .endConfigMap()
                        .endVolume()

                        .addNewVolume()
                            .withName("build-context-workdir")
                            .withNewEmptyDir()
                            .endEmptyDir()
                        .endVolume()

                        .addNewVolume()
                            .withName("kaniko-docker-config")
                            .withNewSecret()
                                .withSecretName(kanikoDockerConfigSecret)
                            .endSecret()
                        .endVolume()

                        .addNewInitContainer()
                            .withName("prepare-context")
                            .withImage("busybox:1.36")
                            .withCommand("sh", "-c")
                            .withArgs("cp -L /config/Dockerfile /kaniko-context/Dockerfile && cp -L /config/main.py /kaniko-context/main.py && cp -L /config/requirements.txt /kaniko-context/requirements.txt && cp -L /config/serving-build-metadata.json /kaniko-context/serving-build-metadata.json && ls -la /kaniko-context")
                            .addNewVolumeMount()
                                .withName("build-context-configmap")
                                .withMountPath("/config")
                            .endVolumeMount()
                            .addNewVolumeMount()
                                .withName("build-context-workdir")
                                .withMountPath("/kaniko-context")
                            .endVolumeMount()
                        .endInitContainer()

                        .addNewContainer()
                            .withName("kaniko")
                            .withImage(kanikoImage)
                            .withArgs(args)
                            .addNewVolumeMount()
                                .withName("build-context-workdir")
                                .withMountPath("/kaniko-context")
                            .endVolumeMount()
                            .addNewVolumeMount()
                                .withName("kaniko-docker-config")
                                .withMountPath("/kaniko/.docker")
                            .endVolumeMount()
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
}

private void waitForKanikoJobCompletion(KubernetesClient client, String jobName) {
    long timeoutAt = System.currentTimeMillis() + (servingKanikoTimeoutSeconds * 1000L);

    while (System.currentTimeMillis() < timeoutAt) {
        Job job = client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .get();

        if (job == null) {
            throw new RuntimeException("Kaniko serving image build job disappeared: " + jobName);
        }

        String status = getJobStatus(job);

        System.out.println("[SERVING_IMAGE_KANIKO] Waiting for Kaniko job. "
                + "jobName=" + jobName
                + ", status=" + status);

        if ("COMPLETED".equals(status)) {
            System.out.println("[SERVING_IMAGE_KANIKO] Job completed. jobName=" + jobName);
            return;
        }

        if ("FAILED".equals(status)) {
            String logs = getKanikoJobLogs(client, jobName);
            throw new RuntimeException(
                    "Kaniko serving image build failed. jobName="
                            + jobName
                            + ". Logs: "
                            + limitForLog(logs, 6000)
            );
        }

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Kaniko job: " + jobName, e);
        }
    }

    String logs = getKanikoJobLogs(client, jobName);

    throw new RuntimeException(
            "Timeout while waiting for Kaniko serving image build. jobName="
                    + jobName
                    + ", timeoutSeconds="
                    + servingKanikoTimeoutSeconds
                    + ". Logs: "
                    + limitForLog(logs, 6000)
    );
}

private String getJobStatus(Job job) {
    if (job == null) {
        return "UNKNOWN";
    }

    if (job.getStatus() != null && job.getStatus().getConditions() != null) {
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (!"True".equalsIgnoreCase(condition.getStatus())) {
                continue;
            }

            if ("Complete".equalsIgnoreCase(condition.getType())) {
                return "COMPLETED";
            }

            if ("Failed".equalsIgnoreCase(condition.getType())) {
                return "FAILED";
            }
        }
    }

    Integer succeeded = job.getStatus() != null ? job.getStatus().getSucceeded() : null;
    Integer failed = job.getStatus() != null ? job.getStatus().getFailed() : null;
    Integer active = job.getStatus() != null ? job.getStatus().getActive() : null;

    if (succeeded != null && succeeded > 0) {
        return "COMPLETED";
    }

    if (failed != null && failed > 0) {
        return "FAILED";
    }

    if (active != null && active > 0) {
        return "RUNNING";
    }

    return "PENDING";
}

private String getKanikoJobLogs(KubernetesClient client, String jobName) {
    try {
        List<Pod> pods = client.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list()
                .getItems();

        if (pods == null || pods.isEmpty()) {
            return "";
        }

        StringBuilder logs = new StringBuilder();

        for (Pod pod : pods) {
            if (pod == null || pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                continue;
            }

            String podName = pod.getMetadata().getName();

            logs.append("===== POD: ")
                    .append(podName)
                    .append(" =====\n");

            try {
                String podLogs = client.pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .inContainer("kaniko")
                        .getLog();

                logs.append(podLogs != null ? podLogs : "");
            } catch (Exception e) {
                logs.append("Failed to read kaniko logs for pod ")
                        .append(podName)
                        .append(": ")
                        .append(e.getMessage())
                        .append("\n");
            }

            logs.append("\n");
        }

        return logs.toString();

    } catch (Exception e) {
        return "Failed to read Kaniko serving build logs: " + e.getMessage();
    }
}

private void deleteExistingKanikoJobIfExists(KubernetesClient client, String jobName) {
    try {
        client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .delete();
    } catch (Exception ignored) {
    }
}

private void deleteExistingConfigMapIfExists(KubernetesClient client, String configMapName) {
    try {
        client.configMaps()
                .inNamespace(namespace)
                .withName(configMapName)
                .delete();
    } catch (Exception ignored) {
    }
}

private String buildServingConfigMapName(String safeModelName, String safeVersion) {
    String name = "mlops-serving-context-" + safeModelName + "-" + safeVersion;
    return sanitizeKubernetesName(name, 63);
}

private String buildServingKanikoJobName(String safeModelName, String safeVersion) {
    String name = "mlops-serving-build-" + safeModelName + "-" + safeVersion;
    return sanitizeKubernetesName(name, 63);
}

private String sanitizeKubernetesName(String value, int maxLength) {
    String clean = value == null ? "mlops-job" : value.toLowerCase(Locale.ROOT).trim();

    clean = clean.replaceAll("[^a-z0-9-]", "-");
    clean = clean.replaceAll("-+", "-");
    clean = clean.replaceAll("^-+", "");
    clean = clean.replaceAll("-+$", "");

    if (clean.isBlank()) {
        clean = "mlops-job";
    }

    if (clean.length() > maxLength) {
        clean = clean.substring(0, maxLength);
        clean = clean.replaceAll("-+$", "");
    }

    if (clean.isBlank()) {
        clean = "mlops-job";
    }

    return clean;
}

private String sanitizeForKubernetesLabel(String value) {
    if (value == null || value.isBlank()) {
        return "unknown";
    }

    String clean = value.trim()
            .replaceAll("[^A-Za-z0-9_.-]", "-");

    if (clean.length() > 63) {
        clean = clean.substring(0, 63);
    }

    clean = clean.replaceAll("^-+", "");
    clean = clean.replaceAll("-+$", "");

    return clean.isBlank() ? "unknown" : clean;
}

private String limitForLog(String value, int maxLength) {
    if (value == null) {
        return "";
    }

    if (value.length() <= maxLength) {
        return value;
    }

    return value.substring(0, maxLength) + "... [truncated]";
}

private boolean hasText(String value) {
    return value != null && !value.isBlank();
}

    private List<String> generateFinalRequirements(String modelUri, Path templateDir) {
        List<String> baseRequirements = readRequirementsFile(
                templateDir != null ? templateDir.resolve("requirements.base.txt") : null
        );
        List<String> modelRequirements = fetchMlflowModelRequirements(modelUri);

        LinkedHashMap<String, String> merged = new LinkedHashMap<>();

        for (String requirement : fallbackRequirements()) {
            addRequirement(merged, requirement);
        }

        for (String requirement : baseRequirements) {
            addRequirement(merged, requirement);
        }

        for (String requirement : modelRequirements) {
            addRequirement(merged, requirement);
        }

        fixKnownDependencyConflicts(merged);

        List<String> result = new ArrayList<>(merged.values());

        System.out.println("[SERVING_IMAGE] Final requirements:");
        for (String req : result) {
            System.out.println("[SERVING_IMAGE]   " + req);
        }

        return result;
    }

    private List<String> fallbackRequirements() {
        return List.of(
                "fastapi==0.115.6",
                "uvicorn[standard]==0.32.1",
                "python-multipart==0.0.20",
                "pandas==2.2.2",
                "numpy==1.26.4",
                "joblib==1.4.2",
                "cloudpickle==3.0.0",
                "mlflow==2.17.2",
                "pyarrow==17.0.0",
                "scikit-learn==1.5.1",
                "scipy==1.13.1",
                "pillow==10.4.0",
                "imbalanced-learn==0.12.3"
        );
    }

    private void fixKnownDependencyConflicts(LinkedHashMap<String, String> merged) {
        if (merged == null || merged.isEmpty()) {
            return;
        }

        String mlflowRequirement = merged.get("mlflow");
        Integer mlflowMajor = parseMajorVersion(mlflowRequirement);

        if (mlflowMajor != null && mlflowMajor == 2) {
            String fixedPyarrow = "pyarrow==" + compatiblePyarrowVersion;

            System.out.println("[SERVING_IMAGE] MLflow 2.x detected: " + mlflowRequirement);
            System.out.println("[SERVING_IMAGE] Forcing compatible pyarrow: " + fixedPyarrow);

            merged.put("pyarrow", fixedPyarrow);
        }

        if (!merged.containsKey("pyarrow")) {
            String fixedPyarrow = "pyarrow==" + compatiblePyarrowVersion;
            System.out.println("[SERVING_IMAGE] pyarrow missing. Adding safe default: " + fixedPyarrow);
            merged.put("pyarrow", fixedPyarrow);
        }

        merged.remove("python");
        merged.remove("pip");
        merged.remove("setuptools");
        merged.remove("wheel");
    }

    private Integer parseMajorVersion(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return null;
        }

        String clean = requirement.trim().toLowerCase();

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^[a-z0-9_.\\-\\[\\]]+\\s*(==|~=|>=|<=|>|<)\\s*([0-9]+)")
                .matcher(clean);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(2));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readRequirementsFile(Path path) {
        try {
            if (path == null || !Files.exists(path) || isEmptyFile(path)) {
                return new ArrayList<>();
            }

            return Files.readAllLines(path, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("#"))
                    .toList();

        } catch (Exception e) {
            System.out.println("[SERVING_IMAGE] Failed to read requirements file: " + path);
            System.out.println("[SERVING_IMAGE] Reason: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> fetchMlflowModelRequirements(String modelUri) {
        String pythonCode =
                "import mlflow, pathlib\n"
                        + "mlflow.set_tracking_uri('" + escapePython(localMlflowTrackingUrl) + "')\n"
                        + "req_path = mlflow.pyfunc.get_model_dependencies('" + escapePython(modelUri) + "', format='pip')\n"
                        + "print(req_path)\n";

        Path tempScript = null;

        try {
            tempScript = Files.createTempFile("mlflow-model-deps-", ".py");
            Files.writeString(tempScript, pythonCode, StandardCharsets.UTF_8);

            ProcessBuilder processBuilder = new ProcessBuilder("python", tempScript.toString());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            List<String> outputLines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                    System.out.println("[SERVING_IMAGE][MLFLOW_DEPS] " + line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.out.println("[SERVING_IMAGE] MLflow dependencies command failed. Fallback requirements will be used.");
                return new ArrayList<>();
            }

            String requirementsPath = findLastExistingPath(outputLines);

            if (requirementsPath == null) {
                System.out.println("[SERVING_IMAGE] MLflow dependencies path not found. Fallback requirements will be used.");
                return new ArrayList<>();
            }

            return readRequirementsFile(Paths.get(requirementsPath));

        } catch (Exception e) {
            System.out.println("[SERVING_IMAGE] Failed to fetch MLflow requirements. Fallback requirements will be used.");
            System.out.println("[SERVING_IMAGE] Reason: " + e.getMessage());
            return new ArrayList<>();

        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String findLastExistingPath(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        ListIterator<String> iterator = lines.listIterator(lines.size());

        while (iterator.hasPrevious()) {
            String line = iterator.previous();

            if (line == null || line.isBlank()) {
                continue;
            }

            String clean = line.trim();

            try {
                if (Files.exists(Paths.get(clean))) {
                    return clean;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private void addRequirement(LinkedHashMap<String, String> merged, String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return;
        }

        String clean = requirement.trim();

        if (clean.startsWith("#")) {
            return;
        }

        String lower = clean.toLowerCase(Locale.ROOT);

        if (lower.startsWith("-r ")
                || lower.startsWith("--requirement")
                || lower.startsWith("-e ")
                || lower.startsWith("--editable")
                || lower.startsWith("git+")
                || lower.startsWith("http://")
                || lower.startsWith("https://")) {
            System.out.println("[SERVING_IMAGE] Ignored unsupported requirement line: " + clean);
            return;
        }

        String name = packageName(clean);

        if (name.isBlank()) {
            return;
        }

        if (isUnsupportedRuntimeRequirement(name)) {
            System.out.println("[SERVING_IMAGE] Ignored runtime/system requirement: " + clean);
            return;
        }

        merged.put(name, clean);
    }

    private boolean isUnsupportedRuntimeRequirement(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return true;
        }

        String clean = packageName.trim().toLowerCase();

        return clean.equals("python")
                || clean.equals("pip")
                || clean.equals("setuptools")
                || clean.equals("wheel");
    }

    private String packageName(String requirement) {
        if (requirement == null) {
            return "";
        }

        String clean = requirement.trim().toLowerCase();

        int markerIndex = clean.indexOf(";");
        if (markerIndex >= 0) {
            clean = clean.substring(0, markerIndex).trim();
        }

        String[] separators = {"==", ">=", "<=", "~=", "!=", ">", "<", "["};

        int cutIndex = clean.length();

        for (String separator : separators) {
            int index = clean.indexOf(separator);
            if (index >= 0) {
                cutIndex = Math.min(cutIndex, index);
            }
        }

        String name = clean.substring(0, cutIndex).trim();

        name = name.replace("_", "-");

        return name;
    }

    private void writeRequirements(Path buildContext, List<String> requirements) throws Exception {
        Path requirementsPath = buildContext.resolve("requirements.txt");

        Files.write(
                requirementsPath,
                requirements,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        assertFileExistsAndNotEmpty(requirementsPath, "generated requirements.txt");

        System.out.println("[SERVING_IMAGE] requirements.txt generated: " + requirementsPath);
    }

    private void writeBuildMetadata(
            Path buildContext,
            String registeredModelName,
            String modelVersion,
            String modelUri,
            String dataType,
            String imageName,
            List<String> requirements,
            String requirementsHash
    ) throws Exception {
        Path metadataPath = buildContext.resolve("serving-build-metadata.json");

        String json = "{\n"
                + "  \"registeredModelName\": \"" + jsonEscape(registeredModelName) + "\",\n"
                + "  \"modelVersion\": \"" + jsonEscape(modelVersion) + "\",\n"
                + "  \"modelUri\": \"" + jsonEscape(modelUri) + "\",\n"
                + "  \"dataType\": \"" + jsonEscape(dataType) + "\",\n"
                + "  \"imageName\": \"" + jsonEscape(imageName) + "\",\n"
                + "  \"requirementsHash\": \"" + jsonEscape(requirementsHash) + "\",\n"
                + "  \"servingTemplateVersion\": \"" + jsonEscape(SAFE_SERVING_TEMPLATE_VERSION) + "\",\n"
                + "  \"entrypoint\": \"uvicorn main:app --host 0.0.0.0 --port 8000\",\n"
                + "  \"createdAt\": \"" + LocalDateTime.now() + "\",\n"
                + "  \"requirementsCount\": " + (requirements != null ? requirements.size() : 0) + "\n"
                + "}\n";

        Files.writeString(metadataPath, json, StandardCharsets.UTF_8);

        assertFileExistsAndNotEmpty(metadataPath, "generated serving-build-metadata.json");

        System.out.println("[SERVING_IMAGE] metadata generated: " + metadataPath);
    }

    private void validateBuildContext(Path buildContext) {
        assertFileExistsAndNotEmpty(buildContext.resolve("Dockerfile"), "Dockerfile before docker build");
        assertFileExistsAndNotEmpty(buildContext.resolve("main.py"), "main.py before docker build");
        assertFileExistsAndNotEmpty(buildContext.resolve("requirements.txt"), "requirements.txt before docker build");
        assertFileExistsAndNotEmpty(buildContext.resolve("serving-build-metadata.json"), "serving-build-metadata.json before docker build");
    }

    private void assertFileExistsAndNotEmpty(Path path, String label) {
        if (path == null || !Files.exists(path)) {
            throw new IllegalStateException(label + " not found: " + path);
        }

        if (isEmptyFile(path)) {
            throw new IllegalStateException(label + " is empty: " + path);
        }
    }

    private boolean isEmptyFile(Path path) {
        try {
            return Files.size(path) == 0;
        } catch (Exception e) {
            return true;
        }
    }

    private void runCommand(List<String> command, Path workingDirectory) {
        try {
            System.out.println("[SERVING_IMAGE][COMMAND] " + String.join(" ", command));
            System.out.println("[SERVING_IMAGE][WORKDIR] " + workingDirectory);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workingDirectory.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    System.out.println("[SERVING_IMAGE][DOCKER] " + line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException(
                        "Command failed with exit code "
                                + exitCode
                                + ": "
                                + String.join(" ", command)
                                + ". Output: "
                                + output
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to run command: " + String.join(" ", command)
                            + ". Reason: " + e.getMessage(),
                    e
            );
        }
    }

    private void deleteDirectory(Path directory) throws Exception {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to delete path: " + path, e);
                        }
                    });
        }
    }

    private String buildFullErrorMessage(Throwable e) {
        if (e == null) {
            return "Unknown error.";
        }

        StringBuilder builder = new StringBuilder();

        Throwable current = e;
        int depth = 0;

        while (current != null && depth < 6) {
            if (depth > 0) {
                builder.append(" | Caused by ");
            }

            builder.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage());

            current = current.getCause();
            depth++;
        }

        return builder.toString();
    }

    private String sanitizeForDockerName(String value) {
        String clean = value == null ? "model" : value.toLowerCase(Locale.ROOT).trim();

        clean = clean.replaceAll("[^a-z0-9_.-]", "-");
        clean = clean.replaceAll("-+", "-");

        if (clean.startsWith("-")) {
            clean = clean.substring(1);
        }

        if (clean.endsWith("-")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        if (clean.isBlank()) {
            clean = "model";
        }

        return clean;
    }

    private String escapePython(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String computeRequirementsHash(List<String> requirements) {
        try {
            if (requirements == null || requirements.isEmpty()) {
                return "empty";
            }

            List<String> normalized = requirements.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("#"))
                    .map(String::toLowerCase)
                    .sorted()
                    .toList();

            String content = SAFE_SERVING_TEMPLATE_VERSION + "\n" + String.join("\n", normalized);

            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();

            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }

            return hex.toString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to compute requirements hash: " + e.getMessage(), e);
        }
    }

    private Optional<String> findReusableImageWithSameRequirements(
            Path buildRoot,
            String safeModelName,
            String currentSafeVersion,
            String currentRequirementsHash
    ) {
        if (buildRoot == null || !Files.exists(buildRoot)) {
            return Optional.empty();
        }

        if (safeModelName == null || safeModelName.isBlank()) {
            return Optional.empty();
        }

        if (currentRequirementsHash == null || currentRequirementsHash.isBlank()) {
            return Optional.empty();
        }

        try (var stream = Files.list(buildRoot)) {
            List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String folderName = path.getFileName().toString();
                        return folderName.startsWith(safeModelName + "-v");
                    })
                    .filter(path -> {
                        String folderName = path.getFileName().toString();
                        return !folderName.equalsIgnoreCase(safeModelName + "-v" + currentSafeVersion);
                    })
                    .sorted(Comparator.comparing(
                            path -> path.toFile().lastModified(),
                            Comparator.reverseOrder()
                    ))
                    .toList();

            for (Path candidate : candidates) {
                Path requirementsPath = candidate.resolve("requirements.txt");
                Path metadataPath = candidate.resolve("serving-build-metadata.json");

                if (!hasCompatibleServingTemplateVersion(metadataPath)) {
                    System.out.println("[SERVING_IMAGE] Reuse skipped because serving template version is old or missing. candidate="
                            + candidate);
                    continue;
                }

                if (!Files.exists(requirementsPath) || isEmptyFile(requirementsPath)) {
                    continue;
                }

                List<String> candidateRequirements = readRequirementsFile(requirementsPath);
                String candidateHash = computeRequirementsHash(candidateRequirements);

                if (!currentRequirementsHash.equals(candidateHash)) {
                    continue;
                }

                String imageFromMetadata = readImageNameFromMetadata(metadataPath);

                if (imageFromMetadata != null && !imageFromMetadata.isBlank()) {
                    return Optional.of(imageFromMetadata.trim());
                }

                String imageFromFolder = inferImageNameFromFolder(candidate.getFileName().toString());

                if (imageFromFolder != null && !imageFromFolder.isBlank()) {
                    return Optional.of(imageFromFolder);
                }
            }

            return Optional.empty();

        } catch (Exception e) {
            System.out.println("[SERVING_IMAGE] Failed to search reusable image.");
            System.out.println("[SERVING_IMAGE] Reason: " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean hasCompatibleServingTemplateVersion(Path metadataPath) {
        String version = readMetadataField(metadataPath, "servingTemplateVersion");
        return SAFE_SERVING_TEMPLATE_VERSION.equals(version);
    }

    private String readMetadataField(Path metadataPath, String fieldName) {
        try {
            if (metadataPath == null || fieldName == null || !Files.exists(metadataPath) || isEmptyFile(metadataPath)) {
                return null;
            }

            String content = Files.readString(metadataPath, StandardCharsets.UTF_8);

            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\\"" + java.util.regex.Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                    .matcher(content);

            if (matcher.find()) {
                return matcher.group(1);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private String readImageNameFromMetadata(Path metadataPath) {
        return readMetadataField(metadataPath, "imageName");
    }

    private String inferImageNameFromFolder(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return null;
        }

        String cleanFolderName = folderName.trim();
        String dockerName = cleanFolderName.replace("_", "-");

        return dockerRegistry + "/mlops-serving-" + dockerName + ":latest";
    }
}
