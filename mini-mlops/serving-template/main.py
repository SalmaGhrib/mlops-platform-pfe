import io
import os
import time
import json
import pickle

import joblib
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


# ============================================================
# CONFIGURATION
# ============================================================

MLFLOW_TRACKING_URI = os.getenv(
    "MLFLOW_TRACKING_URI",
    "http://mlflow-service.mlops.svc.cluster.local:5000"
).strip()

DEFAULT_MODEL_NAME = os.getenv("DEFAULT_MODEL_NAME", "").strip()
DEFAULT_MODEL_ALIAS = os.getenv("DEFAULT_MODEL_ALIAS", "production").strip()

mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

app = FastAPI(
    title="Mini MLOps Generic Prediction API",
    description=(
        "Generic prediction API for MLflow production models. "
        "Supports MLflow PyFunc, pickle/joblib bundles and TorchScript/PyTorch artifacts."
    ),
    version="6.2.1-universal-prediction",
)

loaded_models: Dict[str, Dict[str, Any]] = {}


# ============================================================
# REQUEST MODELS
# ============================================================

class PredictionRequest(BaseModel):
    rows: Optional[List[Dict[str, Any]]] = Field(
        default=None,
        description="List of input rows for tabular models or rows containing pixels/image."
    )

    pixels: Optional[List[float]] = Field(
        default=None,
        description="List of 784 pixels for MNIST/vision prediction."
    )

    model_name: Optional[str] = Field(default=None)
    model_alias: Optional[str] = Field(default=None)


class TextPredictionRequest(BaseModel):
    text: str = Field(..., description="Text input for NLP prediction.")
    model_name: Optional[str] = Field(default=None)
    model_alias: Optional[str] = Field(default=None)


# ============================================================
# BASIC HELPERS
# ============================================================

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


def find_torch_model_file(local_dir: str) -> str:
    """
    Cherche un fichier .pt ou .pth dans le dossier téléchargé depuis MLflow.

    Cas attendu après ta correction du custom_runner :
    models:/losshb_model@production
      -> dossier local
          -> model/
              -> model.pt
    """
    local_path = Path(local_dir)

    if not local_path.exists():
        raise FileNotFoundError(f"Downloaded artifact directory does not exist: {local_dir}")

    candidates = list(local_path.rglob("*.pt")) + list(local_path.rglob("*.pth"))

    if not candidates:
        available_files = [str(p.relative_to(local_path)) for p in local_path.rglob("*") if p.is_file()]

        raise FileNotFoundError(
            "No .pt/.pth TorchScript model found inside downloaded MLflow artifact. "
            f"local_dir={local_dir}, available_files={available_files}"
        )

    candidates = sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True)

    selected = candidates[0]

    print(f"[TORCH LOADER] Selected torch model file: {selected}")

    return str(selected)



def resolve_mlflow_model_path_from_downloaded_dir(local_dir: str) -> str:
    """
    Retourne le bon dossier local à charger avec mlflow.pyfunc.load_model.

    Cas supportés :
    1. local_dir/MLmodel
    2. local_dir/model/MLmodel
    """
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

    available_files = [
        str(p.relative_to(local_path))
        for p in local_path.rglob("*")
        if p.is_file()
    ]

    raise FileNotFoundError(
        "MLmodel file not found after downloading artifact. "
        f"local_dir={local_dir}, available_files={available_files}"
    )


def list_available_files(local_dir: Optional[str]) -> List[str]:
    if not local_dir:
        return []

    local_path = Path(local_dir)

    if not local_path.exists():
        return []

    return [
        str(p.relative_to(local_path))
        for p in local_path.rglob("*")
        if p.is_file()
    ]


PICKLE_EXTENSIONS = {".pkl", ".joblib", ".pickle", ".sav"}
PREDICTIVE_MODEL_KEYS = [
    "model", "pipeline", "estimator", "predictor", "classifier", "regressor",
    "trained_model", "best_model", "final_model", "network", "module",
]


def is_predictive_object(value: Any) -> bool:
    return value is not None and (hasattr(value, "predict") or callable(value))


def extract_predictive_model(
    loaded_object: Any,
    depth: int = 0,
    visited: Optional[set] = None,
) -> Tuple[Any, Dict[str, Any]]:
    if visited is None:
        visited = set()
    if depth > 8:
        raise ValueError("Maximum nested model depth exceeded.")
    if loaded_object is None:
        raise ValueError("Loaded artifact is None.")

    object_id = id(loaded_object)
    if object_id in visited:
        raise ValueError("Circular reference detected inside model bundle.")
    visited.add(object_id)

    if is_predictive_object(loaded_object):
        return loaded_object, {}

    if isinstance(loaded_object, dict):
        metadata = {
            key: value for key, value in loaded_object.items()
            if key not in PREDICTIVE_MODEL_KEYS
        }
        ordered_keys = [key for key in PREDICTIVE_MODEL_KEYS if key in loaded_object]
        ordered_keys += [key for key in loaded_object if key not in ordered_keys]
        for key in ordered_keys:
            try:
                model, nested_metadata = extract_predictive_model(
                    loaded_object[key], depth + 1, visited.copy()
                )
                return model, {
                    **metadata,
                    **nested_metadata,
                    "_model_bundle_key": key,
                }
            except ValueError:
                continue
        raise ValueError(
            "No predictive model found inside dictionary. "
            f"Available keys: {list(loaded_object.keys())}"
        )

    if isinstance(loaded_object, (list, tuple)):
        for index, candidate in enumerate(loaded_object):
            try:
                model, metadata = extract_predictive_model(
                    candidate, depth + 1, visited.copy()
                )
                metadata["_model_bundle_index"] = index
                return model, metadata
            except ValueError:
                continue

    for attribute_name in PREDICTIVE_MODEL_KEYS:
        if hasattr(loaded_object, attribute_name):
            try:
                model, metadata = extract_predictive_model(
                    getattr(loaded_object, attribute_name), depth + 1, visited.copy()
                )
                metadata["_model_bundle_attribute"] = attribute_name
                return model, metadata
            except ValueError:
                continue

    raise ValueError(
        "Loaded artifact does not contain a predictive model. "
        f"type={type(loaded_object).__name__}"
    )


def find_pickle_model_file(local_dir: str) -> str:
    local_path = Path(local_dir)
    if not local_path.exists():
        raise FileNotFoundError(f"Artifact directory does not exist: {local_dir}")

    candidates = [
        path for path in local_path.rglob("*")
        if path.is_file() and path.suffix.lower() in PICKLE_EXTENSIONS
    ]
    if not candidates:
        raise FileNotFoundError(
            "No pickle/joblib model found. "
            f"available_files={list_available_files(local_dir)}"
        )

    preferred_names = [
        "model.pkl", "model.joblib", "pipeline.pkl", "pipeline.joblib",
        "trained_model.pkl", "best_model.pkl", "final_model.pkl",
    ]
    for preferred_name in preferred_names:
        for candidate in candidates:
            if candidate.name.lower() == preferred_name:
                return str(candidate)

    candidates.sort(key=lambda path: path.stat().st_mtime, reverse=True)
    return str(candidates[0])


def load_pickle_artifact(local_dir: str) -> Tuple[Any, Dict[str, Any], str]:
    model_path = find_pickle_model_file(local_dir)
    try:
        loaded_object = joblib.load(model_path)
    except Exception as joblib_error:
        try:
            with open(model_path, "rb") as file:
                loaded_object = pickle.load(file)
        except Exception as pickle_error:
            raise RuntimeError(
                f"Unable to load {model_path} with joblib or pickle. "
                f"joblib_error={joblib_error}; pickle_error={pickle_error}"
            ) from pickle_error

    model, metadata = extract_predictive_model(loaded_object)

    if not is_predictive_object(model):
        raise TypeError(
            "Extracted object is not predictive. "
            f"type={type(model).__name__}"
        )

    print(
        "[PICKLE LOADER] Predictive object extracted. "
        f"path={model_path}, container_type={type(loaded_object).__name__}, "
        f"model_type={type(model).__name__}, metadata_keys={list(metadata.keys())}"
    )
    return model, metadata, model_path


# ============================================================
# MODEL LOADING
# ============================================================

def load_model_from_registry(
    model_name: str,
    model_alias: str = "production",
) -> Tuple[Dict[str, Any], str, Dict[str, Any]]:
    """
    Charge un modèle depuis MLflow Registry.

    Cette version est plus robuste :
    1. Elle essaie d'abord mlflow.pyfunc.load_model(models:/name@alias).
    2. Si ça échoue, elle télécharge l'artifact et détecte où se trouve MLmodel.
       - local_dir/MLmodel
       - local_dir/model/MLmodel
    3. Elle recharge le modèle pyfunc depuis le bon chemin local.
    4. Seulement si ce n'est vraiment pas un modèle MLflow, elle essaie le fallback TorchScript.
    """
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

    # ------------------------------------------------------------
    # 1) Essai direct depuis MLflow Registry
    # ------------------------------------------------------------
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

    # ------------------------------------------------------------
    # 2) Essai robuste : download_artifacts puis détection de MLmodel
    # ------------------------------------------------------------
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

    # ------------------------------------------------------------
    # 3) Fallback TorchScript artifact-only
    # ------------------------------------------------------------
    # ------------------------------------------------------------
    # 3) Fallback pickle / joblib bundle
    # ------------------------------------------------------------
    try:
        if local_dir is None:
            local_dir = mlflow.artifacts.download_artifacts(model_uri)
        raw_model, metadata, raw_model_path = load_pickle_artifact(local_dir)
        loaded = {
            "type": "raw_predictive",
            "model": raw_model,
            "metadata": metadata,
            "model_uri": model_uri,
            "version_info": version_info,
            "local_dir": local_dir,
            "model_path": raw_model_path,
            "loaded_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        loaded_models[cache_key] = loaded
        print(f"[MODEL LOADER] Pickle/joblib model loaded successfully: {raw_model_path}")
        return loaded, model_uri, version_info
    except Exception as pickle_exc:
        pyfunc_errors.append(f"pickle_fallback_error={repr(pickle_exc)}")
        print(f"[MODEL LOADER] Pickle/joblib fallback failed: {repr(pickle_exc)}")

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
                "hint": (
                    "If MLmodel exists but pyfunc loading fails, the model path is now resolved correctly. "
                    "The remaining common cause is a missing Python dependency in the serving image, "
                    "for example imbalanced-learn, xgboost, lightgbm, tensorflow, torch, etc."
                ),
            },
        )


# ============================================================
# INPUT PREPARATION
# ============================================================

def validate_pixels(pixels: Any) -> List[float]:
    if not isinstance(pixels, list):
        raise HTTPException(
            status_code=400,
            detail="pixels must be a list of exactly 784 numeric values."
        )

    if len(pixels) != 784:
        raise HTTPException(
            status_code=400,
            detail=f"pixels must contain exactly 784 numeric values. Received {len(pixels)}."
        )

    validated_pixels: List[float] = []

    for index, value in enumerate(pixels):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise HTTPException(
                status_code=400,
                detail=f"Each pixel must be numeric. Invalid value at index {index}: {value}"
            )

        validated_pixels.append(float(value))

    return validated_pixels


def dataframe_from_rows(rows: List[Dict[str, Any]]) -> pd.DataFrame:
    if rows is None or len(rows) == 0:
        raise HTTPException(
            status_code=400,
            detail="rows must contain at least one input row."
        )

    df = pd.DataFrame(rows)

    if df.empty:
        raise HTTPException(
            status_code=400,
            detail="Input dataframe is empty after converting rows."
        )

    return df


def prepare_prediction_input(request: PredictionRequest) -> Tuple[str, pd.DataFrame]:
    if request.pixels is not None:
        if request.rows:
            raise HTTPException(
                status_code=400,
                detail="Do not provide both pixels and rows."
            )

        pixels = validate_pixels(request.pixels)

        return "vision_pixels", pd.DataFrame([{"pixels": pixels}])

    if request.rows is None:
        raise HTTPException(
            status_code=400,
            detail="You must provide rows for tabular models or pixels for image/MNIST models."
        )

    df = dataframe_from_rows(request.rows)

    if "pixels" in df.columns:
        for index, pixels in enumerate(df["pixels"]):
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
    """
    Reproduit le preprocessing du train.py MNIST :

    transforms.ToTensor() -> valeurs entre 0 et 1
    transforms.Normalize((0.1307,), (0.3081,))

    Cette fonction gère aussi le cas fréquent d'une image noire sur fond blanc :
    si la moyenne est très élevée, on inverse l'image automatiquement.
    """
    arr = arr.astype(np.float32)

    if arr.max() > 1.0:
        arr = arr / 255.0

    # Si l'image est majoritairement blanche, on suppose fond blanc + chiffre noir.
    # MNIST attend plutôt fond noir + chiffre clair.
    if arr.mean() > 0.5:
        arr = 1.0 - arr

    arr = (arr - 0.1307) / 0.3081

    return arr


def prepare_torch_input_from_dataframe(df: pd.DataFrame):
    import torch

    if df is None or df.empty:
        raise HTTPException(
            status_code=400,
            detail="Input dataframe is empty."
        )

    first_row = df.iloc[0].to_dict()

    # ------------------------------------------------------------
    # Cas 1 : pixels MNIST
    # ------------------------------------------------------------
    if "pixels" in first_row:
        pixels = parse_possible_json(first_row["pixels"])
        pixels = validate_pixels(pixels)

        arr = np.array(pixels, dtype=np.float32)
        arr = normalize_image_array_for_mnist(arr)

        tensor = torch.tensor(arr, dtype=torch.float32).reshape(1, 1, 28, 28)

        return tensor

    # ------------------------------------------------------------
    # Cas 2 : image sous forme liste 2D/3D/4D
    # ------------------------------------------------------------
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
            # HWC -> NCHW
            return torch.tensor(arr, dtype=torch.float32).permute(2, 0, 1).unsqueeze(0)

        if arr.ndim == 4:
            return torch.tensor(arr, dtype=torch.float32)

        raise HTTPException(
            status_code=400,
            detail=f"Unsupported image array shape for torch model: {arr.shape}"
        )

    # ------------------------------------------------------------
    # Cas 3 : features tabulaires numériques
    # ------------------------------------------------------------
    try:
        arr = df.to_numpy(dtype=np.float32)
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Cannot convert tabular input to float32 for torch model.",
                "error": str(exc),
                "received_columns": list(df.columns),
            },
        )

    return torch.tensor(arr, dtype=torch.float32)


# ============================================================
# PREDICTION
# ============================================================

def prepare_input_for_loaded_model(
    loaded: Dict[str, Any],
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    metadata = loaded.get("metadata") or {}
    feature_columns = (
        metadata.get("feature_columns")
        or metadata.get("features")
        or metadata.get("input_columns")
        or metadata.get("columns")
    )

    model = loaded.get("model")
    if not feature_columns and model is not None:
        detected_columns = getattr(model, "feature_names_in_", None)
        if detected_columns is not None:
            feature_columns = list(detected_columns)

    if not feature_columns:
        return dataframe

    feature_columns = [str(column) for column in feature_columns]
    target_column = (
        metadata.get("target_column")
        or metadata.get("target")
        or metadata.get("label_column")
    )
    if target_column and target_column in dataframe.columns:
        dataframe = dataframe.drop(columns=[target_column])

    missing_columns = [
        column for column in feature_columns if column not in dataframe.columns
    ]
    if missing_columns:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Some required model features are missing.",
                "missing_columns": missing_columns,
                "expected_columns": feature_columns,
                "received_columns": list(dataframe.columns),
            },
        )

    return dataframe[feature_columns]


def predict_with_raw_model(loaded: Dict[str, Any], model_input: pd.DataFrame) -> Any:
    model = loaded.get("model")
    prepared_input = prepare_input_for_loaded_model(loaded, model_input)
    if hasattr(model, "predict"):
        return model.predict(prepared_input)
    if callable(model):
        return model(prepared_input)
    raise TypeError(
        f"Extracted object is not predictive: {type(model).__name__}"
    )


def make_predictions(loaded: Dict[str, Any], model_input: pd.DataFrame):
    model_type = loaded.get("type")
    model = loaded.get("model")

    if model is None:
        raise HTTPException(
            status_code=500,
            detail="Loaded model object is missing."
        )

    # ------------------------------------------------------------
    # Cas MLflow pyfunc
    # ------------------------------------------------------------
    if model_type == "pyfunc":
        try:
            predictions = model.predict(model_input)
            return normalize_prediction_output(predictions)

        except Exception as exc:
            # A pyfunc wrapper can load successfully while wrapping a dictionary.
            # In that case, retry with the real predictive object from the artifact.
            try:
                local_dir = loaded.get("local_dir")
                if not local_dir:
                    local_dir = mlflow.artifacts.download_artifacts(loaded["model_uri"])
                raw_model, metadata, raw_model_path = load_pickle_artifact(local_dir)
                fallback_loaded = {
                    **loaded,
                    "type": "raw_predictive",
                    "model": raw_model,
                    "metadata": metadata,
                    "model_path": raw_model_path,
                    "local_dir": local_dir,
                }
                predictions = predict_with_raw_model(fallback_loaded, model_input)
                return normalize_prediction_output(predictions)
            except Exception as fallback_exc:
                raw_model_fallback_error = str(fallback_exc)
            error_detail = {
                "message": "Prediction failed for MLflow pyfunc model.",
                "error": str(exc),
                "hint": (
                    "For tabular models, send rows with the same feature names used during training. "
                    "For vision pyfunc models, send pixels with exactly 784 values or use /predict-image."
                ),
            }

            if isinstance(model_input, pd.DataFrame):
                error_detail["received_columns"] = list(model_input.columns)

            error_detail["raw_model_fallback_error"] = raw_model_fallback_error
            raise HTTPException(status_code=400, detail=error_detail)

    # ------------------------------------------------------------
    # Cas PyTorch / TorchScript artifact
    # ------------------------------------------------------------
    # ------------------------------------------------------------
    # Cas modèle sklearn/XGBoost/LightGBM extrait d'un bundle
    # ------------------------------------------------------------
    if model_type == "raw_predictive":
        try:
            predictions = predict_with_raw_model(loaded, model_input)
            return normalize_prediction_output(predictions)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(
                status_code=400,
                detail={
                    "message": "Prediction failed for extracted pickle/joblib model.",
                    "error": str(exc),
                    "model_type": type(model).__name__,
                    "model_path": loaded.get("model_path"),
                    "metadata_keys": list((loaded.get("metadata") or {}).keys()),
                    "received_columns": list(model_input.columns),
                },
            )

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

            # Classification multi-classes : logits shape = [batch, classes]
            if output_np.ndim == 2 and output_np.shape[1] > 1:
                return output_np.argmax(axis=1).astype(int).tolist()

            # Sortie simple
            return normalize_prediction_output(output_np)

        except HTTPException:
            raise

        except Exception as exc:
            error_detail = {
                "message": "Prediction failed for TorchScript/PyTorch artifact model.",
                "model_path": loaded.get("model_path"),
                "torch_model_kind": loaded.get("torch_model_kind"),
                "error": str(exc),
                "hint": (
                    "For MNIST models, use pixels with exactly 784 values "
                    "or upload a 28x28 grayscale image through /predict-image."
                ),
            }

            if isinstance(model_input, pd.DataFrame):
                error_detail["received_columns"] = list(model_input.columns)

            raise HTTPException(status_code=400, detail=error_detail)

    raise HTTPException(
        status_code=500,
        detail=f"Unsupported loaded model type: {model_type}"
    )


# ============================================================
# ROUTES
# ============================================================

@app.get("/")
def root():
    return {
        "status": "ok",
        "message": "Mini MLOps prediction API is running.",
        "docs": "/docs",
        "tracking_uri": MLFLOW_TRACKING_URI,
    }


@app.get("/health")
def health():
    return {
        "status": "ok",
        "tracking_uri": MLFLOW_TRACKING_URI,
        "loaded_models_count": len(loaded_models),
        "loaded_models": list(loaded_models.keys()),
    }


@app.get("/model-info")
def model_info():
    return {
        "tracking_uri": MLFLOW_TRACKING_URI,
        "default_model_name": DEFAULT_MODEL_NAME,
        "default_model_alias": DEFAULT_MODEL_ALIAS,
        "loaded_models_count": len(loaded_models),
        "loaded_models": {
            key: {
                "model_uri": value.get("model_uri"),
                "type": value.get("type"),
                "version_info": value.get("version_info"),
                "loaded_at": value.get("loaded_at"),
                "model_path": value.get("model_path"),
                "torch_model_kind": value.get("torch_model_kind"),
            }
            for key, value in loaded_models.items()
        },
    }


@app.post("/predict")
def predict(request: PredictionRequest):
    model_name = normalize_text(request.model_name) or DEFAULT_MODEL_NAME
    model_alias = normalize_text(request.model_alias) or DEFAULT_MODEL_ALIAS or "production"

    if not model_name:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "model_name is required because DEFAULT_MODEL_NAME is not configured.",
                "example": {
                    "model_name": "losshb_model",
                    "model_alias": "production",
                    "pixels": [0.0] * 784,
                },
            },
        )

    prediction_mode, model_input = prepare_prediction_input(request)

    loaded, model_uri, version_info = load_model_from_registry(
        model_name=model_name,
        model_alias=model_alias,
    )

    predictions = make_predictions(loaded, model_input)

    input_columns = []
    if prediction_mode == "vision_pixels":
        input_columns = ["pixels"]
    elif prediction_mode == "vision_image":
        input_columns = ["image"]
    elif isinstance(model_input, pd.DataFrame):
        input_columns = list(model_input.columns)

    rows_count = len(model_input) if isinstance(model_input, pd.DataFrame) else 1

    return {
        "status": "success",
        "model_uri": model_uri,
        "model_name": model_name,
        "model_alias": model_alias,
        "model_type": loaded.get("type"),
        "torch_model_kind": loaded.get("torch_model_kind"),
        "version_info": version_info,
        "prediction_mode": prediction_mode,
        "rows_count": rows_count,
        "input_columns": input_columns,
        "predictions": predictions,
    }


@app.post("/predict-image")
async def predict_image(
    image_file: UploadFile = File(...),
    model_name: str = Form(...),
    model_alias: str = Form("production"),
    image_size: int = Form(28),
    grayscale: bool = Form(True),
    auto_invert: bool = Form(True),
):
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

        # Pour MNIST : si fond blanc + chiffre noir, on inverse automatiquement.
        if auto_invert and grayscale and image_array.mean() > 0.5:
            image_array = 1.0 - image_array

        loaded, model_uri, version_info = load_model_from_registry(
            model_name=model_name,
            model_alias=model_alias,
        )

        if grayscale and image_size == 28:
            pixels = image_array.reshape(-1).astype(float).tolist()
            model_input = pd.DataFrame([{"pixels": pixels}])
            prediction_mode = "image_file_to_pixels"
            input_columns = ["pixels"]
        else:
            image_payload = image_array.tolist()
            model_input = pd.DataFrame([{"image": image_payload}])
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
            "predictions": predictions,
        }

    except HTTPException:
        raise

    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Image prediction failed.",
                "error": str(exc),
                "hint": (
                    "For MNIST-like models, use grayscale=true and image_size=28. "
                    "For your TorchScript model, the API will load model.pt with torch.jit.load."
                ),
            },
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

        loaded, model_uri, version_info = load_model_from_registry(
            model_name=model_name,
            model_alias=model_alias,
        )

        model_input = pd.DataFrame([{"text": text}])
        predictions = make_predictions(loaded, model_input)

        return {
            "status": "success",
            "model_uri": model_uri,
            "model_name": model_name,
            "model_alias": model_alias,
            "model_type": loaded.get("type"),
            "version_info": version_info,
            "prediction_mode": "text",
            "input_columns": ["text"],
            "predictions": predictions,
        }

    except HTTPException:
        raise

    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Text prediction failed.",
                "error": str(exc),
                "hint": "The selected model must support a text input column.",
            },
        )


@app.post("/clear-cache")
def clear_cache():
    loaded_models.clear()

    return {
        "status": "success",
        "message": "Model cache cleared successfully.",
        "loaded_models_count": len(loaded_models),
    }