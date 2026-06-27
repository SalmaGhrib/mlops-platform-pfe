import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
import traceback
import ast
import joblib
import mlflow
import mlflow.pyfunc
import mlflow.sklearn
import pandas as pd
from mlflow.tracking import MlflowClient
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
import re

try:
    from mlflow.entities import ViewType
except Exception:
    ViewType = None

from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.metrics import (
    accuracy_score,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_score,
    r2_score,
    recall_score,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from src.custom_models.custom_config_loader import load_custom_config
from src.custom_models.generic_pyfunc_wrapper import GenericPyFuncWrapper
from src.custom_models.inline_project_manager import (
    create_inline_dataset,
    create_inline_workspace,
)
from src.custom_models.promote_model import promote_best_version
from src.custom_models.universal_model_adapter import (
    create_dataset_aliases,
    run_user_model_with_universal_adapter,
)
from src.custom_models.algorithm_detector import detect_algorithms_from_file

try:
    from src.custom_models.algorithm_replacement import (
        replace_algorithm_in_source,
        print_replacement_report,
    )
except Exception:
    replace_algorithm_in_source = None
    print_replacement_report = None


# ============================================================
# PYTORCH WRAPPER (reused from platform_runner)
# ============================================================

class TorchPyFuncWrapper(mlflow.pyfunc.PythonModel):
    """
    Wrapper PyTorch compatible avec :
    - données tabulaires numériques
    - MNIST pixels : {"pixels": [784 valeurs]}
    - images préconverties : {"image": [[[...]]]}
    """

    def load_context(self, context):
        try:
            import torch
        except ImportError as exc:
            raise RuntimeError("PyTorch is required to log .pt/.pth models") from exc

        model_path = context.artifacts["model_path"]
        self.model_path = model_path

        try:
            self.model = torch.jit.load(model_path, map_location="cpu")
            self.model_kind = "torchscript"
            print(f"[TorchPyFuncWrapper] Loaded TorchScript model: {model_path}")
        except Exception:
            self.model = torch.load(model_path, map_location="cpu")
            self.model_kind = "torch_load"
            print(f"[TorchPyFuncWrapper] Loaded torch model: {model_path}")

        if hasattr(self.model, "eval"):
            self.model.eval()

    def _parse_possible_json_list(self, value):
        import json

        if isinstance(value, str):
            value = value.strip()
            if value.startswith("[") or value.startswith("{"):
                return json.loads(value)

        return value

    def _prepare_pixels_input(self, pixels):
        import numpy as np
        import torch

        pixels = self._parse_possible_json_list(pixels)

        arr = np.array(pixels, dtype=np.float32)

        if arr.size != 784:
            raise ValueError(
                f"MNIST/image pixels input expects exactly 784 values, received {arr.size}."
            )

        return torch.tensor(arr, dtype=torch.float32).reshape(1, 1, 28, 28)

    def _prepare_image_input(self, image):
        import numpy as np
        import torch

        image = self._parse_possible_json_list(image)

        arr = np.array(image, dtype=np.float32)

        if arr.ndim == 1 and arr.size == 784:
            return torch.tensor(arr, dtype=torch.float32).reshape(1, 1, 28, 28)

        if arr.ndim == 2:
            return torch.tensor(arr, dtype=torch.float32).unsqueeze(0).unsqueeze(0)

        if arr.ndim == 3:
            # HWC -> NCHW
            return torch.tensor(arr, dtype=torch.float32).permute(2, 0, 1).unsqueeze(0)

        if arr.ndim == 4:
            return torch.tensor(arr, dtype=torch.float32)

        raise ValueError(f"Unsupported image array shape: {arr.shape}")

    def _prepare_tabular_input(self, model_input):
        import numpy as np
        import torch
        import pandas as pd

        if isinstance(model_input, pd.DataFrame):
            arr = model_input.to_numpy()

            if arr.dtype == object:
                try:
                    arr = arr.astype(np.float32)
                except Exception as exc:
                    raise ValueError(
                        "Cannot convert tabular input to float32. "
                        "For image models, send 'pixels' with 784 values."
                    ) from exc

            return torch.tensor(arr, dtype=torch.float32)

        if isinstance(model_input, list):
            return torch.tensor(model_input, dtype=torch.float32)

        if hasattr(model_input, "values"):
            return torch.tensor(model_input.values, dtype=torch.float32)

        return torch.tensor(model_input, dtype=torch.float32)

    def _prepare_input(self, model_input):
        import pandas as pd

        if isinstance(model_input, dict):
            model_input = pd.DataFrame([model_input])

        if isinstance(model_input, pd.DataFrame):
            if "pixels" in model_input.columns:
                pixels = model_input["pixels"].iloc[0]
                return self._prepare_pixels_input(pixels)

            if "image" in model_input.columns:
                image = model_input["image"].iloc[0]
                return self._prepare_image_input(image)

        return self._prepare_tabular_input(model_input)

    def predict(self, context, model_input):
        import torch
        import numpy as np

        input_tensor = self._prepare_input(model_input)

        with torch.no_grad():
            output = self.model(input_tensor)

        if isinstance(output, tuple):
            output = output[0]

        if isinstance(output, torch.Tensor):
            output_np = output.detach().cpu().numpy()

            if output_np.ndim == 2 and output_np.shape[1] > 1:
                return np.argmax(output_np, axis=1).astype(int).tolist()

            if output_np.ndim == 1:
                return output_np.tolist()

            return output_np.tolist()

        return output

# ============================================================
# BASIC UTILS
# ============================================================

def get_nested(config: Dict[str, Any], paths: list[list[str]], default: Any = None) -> Any:
    for path in paths:
        current: Any = config
        ok = True

        for key in path:
            if not isinstance(current, dict) or key not in current:
                ok = False
                break
            current = current[key]

        if ok and current is not None:
            return current

    return default


def safe_str(value: Any, default: str = "") -> str:
    if value is None:
        return default

    text = str(value).strip()

    if not text:
        return default

    return text


def parse_metrics_from_stdout(stdout: str) -> Dict[str, float]:
    metrics: Dict[str, float] = {}

    for line in (stdout or "").splitlines():
        line = line.strip()

        if "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip().lower()
        value = value.strip()

        if not key:
            continue

        try:
            metrics[key] = float(value)
        except Exception:
            continue

    return metrics


def prepare_outputs_dir(workspace: Path) -> Path:
    outputs_dir = workspace / "outputs"

    if outputs_dir.exists():
        shutil.rmtree(outputs_dir)

    outputs_dir.mkdir(parents=True, exist_ok=True)
    return outputs_dir


def find_all_model_artifacts(workspace: Path, outputs_dir: Path) -> list[Path]:
    """
    Find all model artifacts in the workspace with supported extensions.
    Returns sorted list (most recent first).
    """
    supported_extensions = [
        ".pkl", ".joblib", ".pickle",  # Pickle formats
        ".pt", ".pth",                  # PyTorch
        ".h5", ".keras",                # Keras/TensorFlow
        ".onnx",                        # ONNX
    ]
    
    candidates: list[Path] = []
    
    # Search in outputs_dir first (priority)
    for ext in supported_extensions:
        candidates.extend(outputs_dir.glob(f"*{ext}"))
    
    # Then search in workspace root
    for ext in supported_extensions:
        candidates.extend(workspace.glob(f"*{ext}"))
    
    # Then search recursively
    for ext in supported_extensions:
        for p in workspace.rglob(f"*{ext}"):
            if "metadata" not in str(p) and p not in candidates:
                candidates.append(p)
    
    # Sort by modification time (most recent first)
    candidates_sorted = sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True)
    
    if candidates_sorted:
        print(f"[MODEL DETECTION] Found {len(candidates_sorted)} model candidates:")
        for i, p in enumerate(candidates_sorted):
            ext = p.suffix.lower()
            mtime = p.stat().st_mtime
            print(f"  {i+1}. {p.name} ({ext}) - modified: {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(mtime))}")
    
    return candidates_sorted


def select_best_model(candidates: list[Path]) -> Optional[Path]:
    """
    Select the best model from candidates.
    Priority: .pt/.pth (PyTorch) > .h5/.keras > .pkl/.joblib > .onnx > others
    """
    if not candidates:
        return None
    
    priority_order = [
        (".pt", ".pth"),       # PyTorch - high priority
        (".h5", ".keras"),     # Keras - high priority
        (".pkl", ".joblib"),   # Pickle - medium priority
        (".onnx",),            # ONNX - lower priority
    ]
    
    for extensions in priority_order:
        for candidate in candidates:
            if candidate.suffix.lower() in extensions:
                print(f"[MODEL SELECTION] Selected model: {candidate.name} ({candidate.suffix.lower()})")
                return candidate
    
    # Fallback to most recent
    print(f"[MODEL SELECTION] No priority match, using most recent: {candidates[0].name}")
    return candidates[0]

def get_model_wrapper_and_log_model(
    model_path: Path,
    registered_model_name: str,
) -> Optional[str]:
    """
    Log model to MLflow with the appropriate strategy based on file extension.

    FIX IMPORTANT POUR PYTORCH / TORCHSCRIPT :
    Pour les fichiers .pt / .pth, on ne fait PAS mlflow.pyfunc.log_model()
    avec TorchPyFuncWrapper, car MLflow essaie de sérialiser le wrapper avec cloudpickle
    et échoue sur les objets TorchScript (__torch__.SimpleNet).

    À la place :
    - on loggue le fichier .pt comme artefact MLflow dans artifacts/model/
    - on retourne runs:/<run_id>/model
    - ensuite register_model_version_explicitly() crée la version registry depuis cet URI.
    """
    if model_path is None:
        raise ValueError("model_path is required.")

    model_path = Path(model_path)

    if not model_path.exists():
        raise FileNotFoundError(f"Model file not found: {model_path}")

    ext = model_path.suffix.lower()

    print(f"[MODEL LOGGING] Logging model with extension: {ext}")

    active_run = mlflow.active_run()

    if active_run is None:
        raise RuntimeError(
            "No active MLflow run. "
            "get_model_wrapper_and_log_model() must be called inside mlflow.start_run()."
        )

    run_id = active_run.info.run_id

    try:
        # ============================================================
        # PYTORCH / TORCHSCRIPT FIX
        # ============================================================
        if ext in {".pt", ".pth"}:
            print("[MODEL LOGGING] PyTorch/TorchScript model detected.")
            print("[MODEL LOGGING] Logging .pt/.pth as artifact under artifact_path='model'.")
            print("[MODEL LOGGING] This avoids MLflow cloudpickle serialization errors.")

            mlflow.log_artifact(
                local_path=str(model_path),
                artifact_path="model",
            )

            logged_model_uri = f"runs:/{run_id}/model"

            mlflow.set_tag("model_flavor", "pytorch_torchscript_artifact")
            mlflow.set_tag("model_artifact_path", "model")
            mlflow.set_tag("model_file", model_path.name)
            mlflow.set_tag("model_extension", ext)
            mlflow.set_tag("model_registry_source_type", "artifact_folder")
            mlflow.set_tag("prediction_supported", "true")
            mlflow.set_tag("prediction_loader", "torch.jit.load")
            mlflow.set_tag("mlflow_model_uri", logged_model_uri)

            print(f"[MODEL LOGGING] PyTorch artifact logged successfully: {logged_model_uri}")

            return logged_model_uri

        # ============================================================
        # SKLEARN / PICKLE
        # ============================================================
        elif ext in {".pkl", ".joblib", ".pickle"}:
            print("[MODEL LOGGING] Pickle/sklearn model detected.")

            try:
                model = joblib.load(model_path)

                logged_model_info = mlflow.sklearn.log_model(
                    sk_model=model,
                    artifact_path="model",
                    registered_model_name=registered_model_name,
                )

                mlflow.set_tag("model_flavor", "sklearn")
                mlflow.set_tag("model_artifact_path", "model")
                mlflow.set_tag("model_file", model_path.name)
                mlflow.set_tag("model_extension", ext)
                mlflow.set_tag("prediction_supported", "true")
                mlflow.set_tag("mlflow_model_uri", logged_model_info.model_uri)

                print(f"[MODEL LOGGING] Sklearn model logged successfully: {logged_model_info.model_uri}")

                return logged_model_info.model_uri

            except Exception as sklearn_exc:
                print(f"[MODEL LOGGING WARNING] Sklearn logging failed: {sklearn_exc}")
                print("[MODEL LOGGING] Falling back to GenericPyFuncWrapper.")

                logged_model_info = mlflow.pyfunc.log_model(
                    artifact_path="model",
                    python_model=GenericPyFuncWrapper(),
                    artifacts={"model_path": str(model_path)},
                    registered_model_name=registered_model_name,
                    pip_requirements=[
                        "cloudpickle",
                        "joblib",
                        "numpy",
                        "pandas",
                        "scikit-learn",
                    ],
                )

                mlflow.set_tag("model_flavor", "generic_pyfunc")
                mlflow.set_tag("model_artifact_path", "model")
                mlflow.set_tag("model_file", model_path.name)
                mlflow.set_tag("model_extension", ext)
                mlflow.set_tag("prediction_supported", "true")
                mlflow.set_tag("mlflow_model_uri", logged_model_info.model_uri)

                print(f"[MODEL LOGGING] Generic pyfunc model logged successfully: {logged_model_info.model_uri}")

                return logged_model_info.model_uri

        # ============================================================
        # KERAS / H5
        # ============================================================
        elif ext in {".h5", ".keras"}:
            print("[MODEL LOGGING] Keras/TensorFlow model detected.")
            print("[MODEL LOGGING] Logging as artifact only under artifact_path='model'.")

            mlflow.log_artifact(
                local_path=str(model_path),
                artifact_path="model",
            )

            logged_model_uri = f"runs:/{run_id}/model"

            mlflow.set_tag("model_flavor", "keras_artifact")
            mlflow.set_tag("model_artifact_path", "model")
            mlflow.set_tag("model_file", model_path.name)
            mlflow.set_tag("model_extension", ext)
            mlflow.set_tag("prediction_supported", "false")
            mlflow.set_tag("model_artifact_status", "artifact_only")
            mlflow.set_tag("mlflow_model_uri", logged_model_uri)

            return logged_model_uri

        # ============================================================
        # ONNX
        # ============================================================
        elif ext == ".onnx":
            print("[MODEL LOGGING] ONNX model detected.")
            print("[MODEL LOGGING] Logging as artifact only under artifact_path='model'.")

            mlflow.log_artifact(
                local_path=str(model_path),
                artifact_path="model",
            )

            logged_model_uri = f"runs:/{run_id}/model"

            mlflow.set_tag("model_flavor", "onnx_artifact")
            mlflow.set_tag("model_artifact_path", "model")
            mlflow.set_tag("model_file", model_path.name)
            mlflow.set_tag("model_extension", ext)
            mlflow.set_tag("prediction_supported", "false")
            mlflow.set_tag("model_artifact_status", "artifact_only")
            mlflow.set_tag("mlflow_model_uri", logged_model_uri)

            return logged_model_uri

        # ============================================================
        # UNKNOWN FORMAT
        # ============================================================
        else:
            print(f"[MODEL LOGGING] Unsupported model extension {ext}.")
            print("[MODEL LOGGING] Logging as artifact only under artifact_path='model'.")

            mlflow.log_artifact(
                local_path=str(model_path),
                artifact_path="model",
            )

            logged_model_uri = f"runs:/{run_id}/model"

            mlflow.set_tag("model_flavor", "unknown_artifact")
            mlflow.set_tag("model_artifact_path", "model")
            mlflow.set_tag("model_file", model_path.name)
            mlflow.set_tag("model_extension", ext)
            mlflow.set_tag("prediction_supported", "false")
            mlflow.set_tag("model_artifact_status", "artifact_only")
            mlflow.set_tag("mlflow_model_uri", logged_model_uri)

            return logged_model_uri

    except Exception as exc:
        print(f"[MODEL LOGGING ERROR] Failed to log model: {exc}")
        traceback.print_exc()
        raise


def save_metrics_files(outputs_dir: Path, metrics: Dict[str, float]) -> None:
    metrics_json_path = outputs_dir / "metrics.json"
    metrics_txt_path = outputs_dir / "metrics.txt"

    with metrics_json_path.open("w", encoding="utf-8") as file:
        json.dump(metrics, file, indent=2)

    with metrics_txt_path.open("w", encoding="utf-8") as file:
        for metric_name, metric_value in metrics.items():
            file.write(f"{metric_name}={metric_value}\n")


def infer_direction(primary_metric: Optional[str], configured_direction: Optional[str]) -> str:
    metric = str(primary_metric or "").strip().lower()
    direction = str(configured_direction or "").strip().lower()

    min_metrics = {
        "rmse",
        "mse",
        "mae",
        "loss",
        "log_loss",
        "error",
    }

    max_metrics = {
        "r2",
        "r2_score",
        "accuracy",
        "f1",
        "f1_score",
        "f1_weighted",
        "precision",
        "precision_weighted",
        "recall",
        "recall_weighted",
    }

    if metric in min_metrics:
        return "min"

    if metric in max_metrics:
        return "max"

    if direction in {"min", "max"}:
        return direction

    return "max"


def infer_task_type(config: Dict[str, Any]) -> str:
    task_type = get_nested(
        config,
        [
            ["task", "type"],
            ["project_type"],
            ["model", "project_type"],
            ["model", "task_type"],
        ],
        None,
    )

    if task_type is not None and str(task_type).strip():
        normalized = str(task_type).strip().lower()

        if normalized not in {"regression", "classification"}:
            raise ValueError("project_type/task.type must be 'regression' or 'classification'.")

        return normalized

    primary_metric = get_nested(
        config,
        [
            ["metrics", "primary_metric"],
            ["primary_metric"],
            ["model", "primary_metric"],
        ],
        None,
    )

    metric = str(primary_metric or "").strip().lower()

    if metric in {"rmse", "mse", "mae", "r2", "r2_score"}:
        print("[STANDARD FALLBACK] Inferred regression from primary metric.")
        return "regression"

    if metric in {
        "accuracy",
        "f1",
        "f1_score",
        "f1_weighted",
        "precision",
        "precision_weighted",
        "recall",
        "recall_weighted",
    }:
        print("[STANDARD FALLBACK] Inferred classification from primary metric.")
        return "classification"

    print("[STANDARD FALLBACK] project_type not provided. Defaulting to classification.")
    return "classification"


def infer_target_column(config: Dict[str, Any], df: pd.DataFrame) -> str:
    target_column = get_nested(
        config,
        [
            ["data", "target_column"],
            ["dataset", "target_column"],
            ["target_column"],
            ["model", "target_column"],
        ],
        None,
    )

    if target_column and str(target_column).strip():
        target_column = str(target_column).strip()

        if target_column not in df.columns:
            raise ValueError(
                f"Target column '{target_column}' not found. "
                f"Available columns: {list(df.columns)}"
            )

        return target_column

    common_targets = [
        "target",
        "label",
        "y",
        "class",
        "default",
        "outcome",
        "churn",
        "price",
        "prix_euros",
        "rent_price",
    ]

    for candidate in common_targets:
        if candidate in df.columns:
            print(f"[STANDARD FALLBACK] target_column not provided. Using '{candidate}'.")
            return candidate

    fallback = str(df.columns[-1])
    print(f"[STANDARD FALLBACK] target_column not provided. Using last column '{fallback}'.")
    return fallback

TABULAR_EXTENSIONS = {".csv", ".tsv"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tiff", ".tif", ".webp"}
TEXT_EXTENSIONS = {".txt", ".json", ".jsonl", ".csv"}


def normalize_data_type(value: Any) -> str:
    if value is None or str(value).strip() == "":
        return "TABULAR"

    normalized = str(value).strip().upper()

    if normalized in {"TABULAR", "TABLE", "CSV", "TSV"}:
        return "TABULAR"

    if normalized in {"IMAGE", "IMAGES", "VISION"}:
        return "IMAGE"

    if normalized in {"TEXT", "NLP", "LLM"}:
        return "TEXT"

    print(f"[DATA TYPE WARNING] Unsupported data_type={value}. Defaulting to TABULAR.")
    return "TABULAR"


def normalize_task_type(value: Any, fallback: str = "classification") -> str:
    if value is None or str(value).strip() == "":
        return fallback

    normalized = str(value).strip().lower().replace("-", "_").replace(" ", "_")

    mapping = {
        "sentiment": "sentiment_analysis",
        "question_answering": "qa",
        "llm": "llm_finetuning",
    }

    return mapping.get(normalized, normalized)


def validate_dataset_for_data_type(dataset_path: Optional[Path], data_type: str) -> None:
    if dataset_path is None:
        print("[DATASET VALIDATION] No dataset provided.")
        return

    path = Path(dataset_path)

    if not path.exists():
        raise FileNotFoundError(f"Dataset file not found: {path}")

    ext = path.suffix.lower()
    data_type = normalize_data_type(data_type)

    if data_type == "TABULAR":
        if ext not in TABULAR_EXTENSIONS:
            raise ValueError(
                f"Unsupported TABULAR dataset format: {ext}. Allowed: .csv, .tsv"
            )

    elif data_type == "IMAGE":
        if ext not in IMAGE_EXTENSIONS:
            raise ValueError(
                f"Unsupported IMAGE dataset format: {ext}. "
                "Allowed: .jpg, .jpeg, .png, .bmp, .gif, .tiff, .tif, .webp"
            )

    elif data_type == "TEXT":
        if ext not in TEXT_EXTENSIONS:
            raise ValueError(
                f"Unsupported TEXT dataset format: {ext}. Allowed: .txt, .json, .jsonl, .csv"
            )

    print(f"[DATASET VALIDATION] OK data_type={data_type}, file={path.name}")


def log_data_type_tags_to_mlflow(data_type: str, task_type: str) -> None:
    data_type = normalize_data_type(data_type)
    task_type = normalize_task_type(task_type)

    mlflow.set_tag("data_type", data_type)
    mlflow.set_tag("task_type", task_type)
    mlflow.set_tag("input_type", data_type.lower())

    if data_type == "TABULAR":
        mlflow.set_tag("prediction_endpoint", "predict")
        mlflow.set_tag("supported_prediction_input", "json_features")
        mlflow.set_tag("supported_dataset_formats", "csv,tsv")

    elif data_type == "IMAGE":
        mlflow.set_tag("prediction_endpoint", "predict-image")
        mlflow.set_tag("image_supported", "true")
        mlflow.set_tag("supported_image_formats", "jpg,jpeg,png,bmp,gif,tiff,tif,webp")
        mlflow.set_tag("supported_prediction_input", "image_file_or_pixels")

    elif data_type == "TEXT":
        mlflow.set_tag("prediction_endpoint", "predict-text")
        mlflow.set_tag("text_supported", "true")
        mlflow.set_tag("supported_text_formats", "txt,json,jsonl,csv")
        mlflow.set_tag("supported_prediction_input", "text")

# ============================================================
# DATASET ALIASING FOR USER SCRIPTS
# ============================================================

def extract_csv_aliases_from_user_script(script_path: Path) -> list[str]:
    """
    Détecte automatiquement les noms de fichiers CSV écrits en dur
    dans le script utilisateur.

    Exemple :
        DATASET_PATH = "text_sentiment_dataset.csv"
        pd.read_csv("train.csv")
        pd.read_csv("data/my_file.csv")
    """
    aliases: list[str] = []

    if script_path is None or not Path(script_path).exists():
        return aliases

    try:
        content = Path(script_path).read_text(encoding="utf-8", errors="ignore")

        matches = re.findall(
            r"""['"]([^'"]+\.csv)['"]""",
            content,
            flags=re.IGNORECASE
        )

        for raw_value in matches:
            value = str(raw_value or "").strip()

            if not value:
                continue

            lowered = value.lower()

            if lowered.startswith("http://") or lowered.startswith("https://"):
                continue

            alias_path = Path(value)

            if alias_path.is_absolute():
                value = alias_path.name

            value = value.replace("\\", "/").strip("/")

            if value and value not in aliases:
                aliases.append(value)

    except Exception as exc:
        print(f"[DATASET ALIAS WARNING] Could not inspect user script for CSV names: {exc}")

    return aliases


def create_robust_dataset_aliases(
        workspace: Path,
        dataset_path: Optional[Path],
        script_path: Optional[Path] = None,
) -> list[str]:
    """
    Crée des alias standards + les alias détectés dans le script utilisateur.

    But :
    éviter FileNotFoundError quand le script fait :
        pd.read_csv("text_sentiment_dataset.csv")

    alors que Kubeflow a stocké le dataset sous un nom temporaire.
    """
    aliases_created: list[str] = []

    if dataset_path is None:
        print("[DATASET ALIAS] No dataset provided. Alias creation skipped.")
        return aliases_created

    workspace = Path(workspace).resolve()
    source = Path(dataset_path).resolve()

    if not source.exists():
        raise FileNotFoundError(f"Dataset not found: {source}")

    workspace.mkdir(parents=True, exist_ok=True)

    data_dir = workspace / "data"
    data_dir.mkdir(parents=True, exist_ok=True)

    standard_aliases = [
        "dataset.csv",
        "data.csv",
        "train.csv",
        "input.csv",
        "uploaded_dataset.csv",
    ]

    script_aliases = extract_csv_aliases_from_user_script(script_path) if script_path else []

    all_aliases: list[str] = []

    for alias in standard_aliases + script_aliases:
        alias = str(alias or "").strip().replace("\\", "/").strip("/")

        if not alias:
            continue

        if alias not in all_aliases:
            all_aliases.append(alias)

    for alias in all_aliases:
        try:
            target = (workspace / alias).resolve()

            # Sécurité : empêcher de copier hors du workspace.
            if not str(target).startswith(str(workspace)):
                target = workspace / Path(alias).name

            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

            aliases_created.append(str(target))
            print(f"[DATASET ALIAS] created: {target}")

            target_in_data = data_dir / Path(alias).name

            if target_in_data.resolve() != target.resolve():
                shutil.copy2(source, target_in_data)
                aliases_created.append(str(target_in_data))
                print(f"[DATASET ALIAS] created: {target_in_data}")

        except Exception as exc:
            print(f"[DATASET ALIAS WARNING] Failed to create alias '{alias}': {exc}")

    standard_dataset_path = workspace / "dataset.csv"

    os.environ["DATA_PATH"] = str(standard_dataset_path)
    os.environ["DATASET_PATH"] = str(standard_dataset_path)
    os.environ["MLOPS_DATASET_PATH"] = str(standard_dataset_path)
    os.environ["DATASET_FILE_PATH"] = str(standard_dataset_path)

    print(f"[DATASET ALIAS] original dataset path: {source}")
    print(f"[DATASET ALIAS] standard dataset path: {standard_dataset_path}")
    print(f"[DATASET ALIAS] all aliases: {aliases_created}")

    return aliases_created


def build_user_script_environment(
        outputs_dir: Path,
        workspace: Path,
        data_type: str,
        task_type: str,
        dataset_path: Optional[Path],
) -> Dict[str, str]:
    env = {
        **os.environ,
        "OUTPUT_DIR": str(outputs_dir),
        "DATA_TYPE": normalize_data_type(data_type),
        "TASK_TYPE": normalize_task_type(task_type),
    }

    if dataset_path is not None:
        standard_dataset_path = Path(workspace) / "dataset.csv"

        env["DATA_PATH"] = str(standard_dataset_path)
        env["DATASET_PATH"] = str(standard_dataset_path)
        env["MLOPS_DATASET_PATH"] = str(standard_dataset_path)
        env["DATASET_FILE_PATH"] = str(standard_dataset_path)

    return env
# ============================================================
# USER SCRIPT EXECUTION
# ============================================================

def run_training_script(
    workspace: Path,
    entrypoint: str,
    dataset_path: Optional[Path],
    data_type: str = "TABULAR",
    task_type: str = "classification",
) -> Tuple[subprocess.CompletedProcess, Path, list[str]]:
    script_path = workspace / entrypoint

    if not script_path.exists():
        raise FileNotFoundError(f"Training script not found: {script_path}")

    outputs_dir = prepare_outputs_dir(workspace)

    if dataset_path is not None:
        create_robust_dataset_aliases(
            workspace=workspace,
            dataset_path=dataset_path,
            script_path=script_path,
        )

    standard_dataset_path = workspace / "dataset.csv" if dataset_path is not None else None

    command_with_args = [
        sys.executable,
        entrypoint,
        "--output_dir",
        str(outputs_dir),
    ]

    if standard_dataset_path is not None:
        command_with_args.extend(["--data_path", str(standard_dataset_path)])

    env = build_user_script_environment(
        outputs_dir=outputs_dir,
        workspace=workspace,
        data_type=data_type,
        task_type=task_type,
        dataset_path=dataset_path,
    )

    result = subprocess.run(
        command_with_args,
        cwd=str(workspace),
        capture_output=True,
        text=True,
        check=False,
        env=env,
    )

    if result.returncode != 0 and (
        "unrecognized arguments" in (result.stderr or "")
        or "error: unrecognized arguments" in (result.stderr or "")
    ):
        fallback_command = [sys.executable, entrypoint]

        result = subprocess.run(
            fallback_command,
            cwd=str(workspace),
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )

        return result, outputs_dir, fallback_command

    return result, outputs_dir, command_with_args
# ============================================================
# STANDARD MODEL LOADING
# ============================================================

def load_user_model_from_entrypoint(workspace: Path, entrypoint: str):
    script_path = workspace / entrypoint

    if not script_path.exists():
        raise FileNotFoundError(f"User model file not found: {script_path}")

    module_name = f"user_custom_model_{int(time.time() * 1000)}"

    spec = importlib.util.spec_from_file_location(module_name, str(script_path))

    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot import user model file: {script_path}")

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)

    for function_name in ["build_model", "create_model", "get_model"]:
        if hasattr(module, function_name):
            factory = getattr(module, function_name)

            if not callable(factory):
                continue

            model = factory()

            if model is None:
                raise ValueError(f"{function_name}() returned None.")

            if not hasattr(model, "fit"):
                raise ValueError(
                    f"{function_name}() must return a sklearn-like model with fit()."
                )

            print(f"[STANDARD FALLBACK] Loaded model from {function_name}().")
            return model

    if hasattr(module, "MODEL"):
        model = getattr(module, "MODEL")

        if model is not None and hasattr(model, "fit"):
            print("[STANDARD FALLBACK] Loaded model from MODEL variable.")
            return model

    raise ValueError(
        "The uploaded Python file did not create a recognized model artifact and does not expose "
        "build_model(), create_model(), get_model(), or MODEL.\n\n"
        "Accepted formats:\n"
        "1) def build_model(): return sklearn_model\n"
        "2) MODEL = sklearn_model\n"
        "3) script saves a supported model artifact with one of the extensions: "
        ".pkl, .joblib, .pickle, .pt, .pth, .h5, .keras, .onnx"
    )


def build_preprocessor(X: pd.DataFrame) -> ColumnTransformer:
    numeric_features = X.select_dtypes(include=["number", "bool"]).columns.tolist()
    categorical_features = X.select_dtypes(exclude=["number", "bool"]).columns.tolist()

    transformers = []

    if numeric_features:
        numeric_pipeline = Pipeline(
            steps=[
                ("imputer", SimpleImputer(strategy="median")),
                ("scaler", StandardScaler()),
            ]
        )
        transformers.append(("num", numeric_pipeline, numeric_features))

    if categorical_features:
        try:
            encoder = OneHotEncoder(handle_unknown="ignore", sparse_output=False)
        except TypeError:
            encoder = OneHotEncoder(handle_unknown="ignore", sparse=False)

        categorical_pipeline = Pipeline(
            steps=[
                ("imputer", SimpleImputer(strategy="most_frequent")),
                ("encoder", encoder),
            ]
        )
        transformers.append(("cat", categorical_pipeline, categorical_features))

    if not transformers:
        raise ValueError("No usable feature columns found in dataset.")

    return ColumnTransformer(transformers=transformers)


def compute_standard_metrics(task_type: str, y_test, y_pred) -> Dict[str, float]:
    task_type = str(task_type or "").strip().lower()

    if task_type == "regression":
        return {
            "rmse": float(mean_squared_error(y_test, y_pred) ** 0.5),
            "mae": float(mean_absolute_error(y_test, y_pred)),
            "r2": float(r2_score(y_test, y_pred)),
        }

    return {
        "accuracy": float(accuracy_score(y_test, y_pred)),
        "f1_score": float(f1_score(y_test, y_pred, average="weighted", zero_division=0)),
        "f1_weighted": float(f1_score(y_test, y_pred, average="weighted", zero_division=0)),
        "precision_weighted": float(precision_score(y_test, y_pred, average="weighted", zero_division=0)),
        "recall_weighted": float(recall_score(y_test, y_pred, average="weighted", zero_division=0)),
    }


def dataframe_safe_train_test_split(
    X: pd.DataFrame,
    y: pd.Series,
    test_size: float,
    random_state: int,
    shuffle: bool,
    stratify,
):
    feature_columns = list(X.columns)

    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=test_size,
        random_state=random_state,
        shuffle=shuffle,
        stratify=stratify,
    )

    if not isinstance(X_train, pd.DataFrame):
        X_train = pd.DataFrame(X_train, columns=feature_columns)

    if not isinstance(X_test, pd.DataFrame):
        X_test = pd.DataFrame(X_test, columns=feature_columns)

    return X_train, X_test, y_train, y_test


def model_contains_column_transformer(model: Any) -> bool:
    if isinstance(model, ColumnTransformer):
        return True

    if isinstance(model, Pipeline):
        for _, step in model.steps:
            if model_contains_column_transformer(step):
                return True

    if hasattr(model, "transformers"):
        return True

    if hasattr(model, "steps"):
        try:
            for _, step in model.steps:
                if model_contains_column_transformer(step):
                    return True
        except Exception:
            pass

    return False


def should_wrap_user_model_with_platform_preprocessor(model: Any) -> bool:
    if isinstance(model, Pipeline):
        return False

    if model_contains_column_transformer(model):
        return False

    return True


def build_fallback_training_model(model: Any, X: pd.DataFrame):
    if should_wrap_user_model_with_platform_preprocessor(model):
        print("[STANDARD FALLBACK] User model is a plain estimator. Adding platform preprocessor.")
        preprocessor = build_preprocessor(X)
        return Pipeline(
            steps=[
                ("preprocessor", preprocessor),
                ("model", model),
            ]
        )

    print("[STANDARD FALLBACK] User model already looks like a pipeline/preprocessed model. Fitting it directly.")
    return model


def train_standard_model_if_missing_model_pkl(
    workspace: Path,
    entrypoint: str,
    dataset_path: Optional[Path],
    outputs_dir: Path,
    config: Dict[str, Any],
) -> Dict[str, float]:
    if dataset_path is None:
        raise ValueError(
            "Dataset is required for standard fallback training because no recognized model artifact was created."
        )

    if not dataset_path.exists():
        raise FileNotFoundError(f"Dataset file not found: {dataset_path}")

    print("========== STANDARD FALLBACK TRAINING ==========")
    print("The user script did not create a recognized model artifact.")
    print("Trying standard platform training using build_model/create_model/get_model/MODEL.")
    print("================================================")

    df = pd.read_csv(dataset_path)

    if df.empty:
        raise ValueError("Dataset is empty.")

    target_column = infer_target_column(config, df)
    task_type = infer_task_type(config)

    print(f"[STANDARD FALLBACK] target_column={target_column}")
    print(f"[STANDARD FALLBACK] task_type={task_type}")

    X = df.drop(columns=[target_column]).copy()
    y = df[target_column].copy()

    if not isinstance(X, pd.DataFrame):
        X = pd.DataFrame(X)

    model = load_user_model_from_entrypoint(workspace, entrypoint)
    training_model = build_fallback_training_model(model, X)

    split_cfg = config.get("split", {}) if isinstance(config.get("split"), dict) else {}

    test_size = float(split_cfg.get("test_size", 0.2))
    random_state = int(split_cfg.get("random_state", 42))
    shuffle = bool(split_cfg.get("shuffle", True))

    if not 0 < test_size < 1:
        raise ValueError("split.test_size must be between 0 and 1.")

    stratify = None

    if task_type == "classification" and shuffle:
        try:
            value_counts = y.value_counts()
            if len(value_counts) > 1 and value_counts.min() >= 2:
                stratify = y
            else:
                print("[STANDARD FALLBACK] Stratify disabled because at least one class has less than 2 samples.")
        except Exception as exc:
            print(f"[STANDARD FALLBACK] Stratify disabled: {exc}")
            stratify = None

    X_train, X_test, y_train, y_test = dataframe_safe_train_test_split(
        X=X,
        y=y,
        test_size=test_size,
        random_state=random_state,
        shuffle=shuffle,
        stratify=stratify,
    )

    print(f"[STANDARD FALLBACK] X_train type={type(X_train).__name__}, shape={getattr(X_train, 'shape', None)}")
    print(f"[STANDARD FALLBACK] X_test type={type(X_test).__name__}, shape={getattr(X_test, 'shape', None)}")
    print(f"[STANDARD FALLBACK] Feature columns={list(X_train.columns)}")

    training_model.fit(X_train, y_train)
    y_pred = training_model.predict(X_test)

    metrics = compute_standard_metrics(task_type, y_test, y_pred)

    model_path = outputs_dir / "model.pkl"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(training_model, model_path)

    if not model_path.exists():
        raise FileNotFoundError(f"Standard fallback failed to create model file: {model_path}")

    save_metrics_files(outputs_dir, metrics)

    print("========== STANDARD FALLBACK METRICS ==========")
    for metric_name, metric_value in metrics.items():
        print(f"{metric_name}={metric_value}")
    print(f"model_path={model_path}")
    print("================================================")

    return metrics


# ============================================================
# ALGORITHM REPLACEMENT
# ============================================================

def normalize_algorithm_replacements(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []

    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]

    if isinstance(value, str):
        cleaned = value.strip()

        if not cleaned:
            return []

        parsed = json.loads(cleaned)

        if not isinstance(parsed, list):
            raise ValueError("algorithm_replacements must be a list.")

        return [item for item in parsed if isinstance(item, dict)]

    raise ValueError("algorithm_replacements must be a list or JSON string.")


def extract_target_algorithm_from_replacement(replacement: Dict[str, Any]) -> Optional[str]:
    possible_keys = [
        "target_algorithm",
        "new_algorithm",
        "new_class",
        "newClass",
        "to",
        "to_algorithm",
        "replacement_algorithm",
        "algorithm",
        "model_class",
        "class_name",
        "className",
    ]

    for key in possible_keys:
        value = replacement.get(key)

        if isinstance(value, str) and value.strip():
            return value.strip()

    return None


def apply_algorithm_injection_if_requested(
    workspace: Path,
    entrypoint: str,
    config: Dict[str, Any],
) -> tuple[str, Dict[str, Any]]:
    replacements = normalize_algorithm_replacements(
        config.get("algorithm_replacements", [])
    )

    if not replacements:
        return entrypoint, {
            "enabled": False,
            "requested_replacements": [],
            "changes_made": [],
            "replacement_reports": [],
            "warnings": ["No algorithm replacement requested. Using original entrypoint."],
            "original_entrypoint": entrypoint,
            "executed_entrypoint": entrypoint,
        }

    if replace_algorithm_in_source is None:
        return entrypoint, {
            "enabled": False,
            "requested_replacements": replacements,
            "changes_made": [],
            "replacement_reports": [],
            "warnings": ["Algorithm replacement module unavailable. Using original entrypoint."],
            "original_entrypoint": entrypoint,
            "executed_entrypoint": entrypoint,
        }

    original_script_path = workspace / entrypoint

    if not original_script_path.exists():
        raise FileNotFoundError(
            f"Entrypoint file not found for algorithm replacement: {original_script_path}"
        )

    modified_code = original_script_path.read_text(encoding="utf-8")

    changes_made: list[dict[str, Any]] = []
    replacement_reports: list[dict[str, Any]] = []
    warnings: list[str] = []

    for replacement in replacements:
        target_algorithm = extract_target_algorithm_from_replacement(replacement)

        if not target_algorithm:
            warning = (
                "Algorithm replacement ignored because target algorithm is missing. "
                f"Replacement={replacement}"
            )
            print(f"[ALGORITHM REPLACEMENT WARNING] {warning}")
            warnings.append(warning)
            continue

        try:
            modified_code, report = replace_algorithm_in_source(
                source_code=modified_code,
                target_algorithm=target_algorithm,
            )

            if print_replacement_report is not None:
                print_replacement_report(report)

            report_dict = {
                "original_algorithm": report.original_algorithm,
                "new_algorithm": report.new_algorithm,
                "old_params": report.old_params,
                "preserved_params": report.preserved_params,
                "removed_params": report.removed_params,
                "added_params": report.added_params,
                "final_params": report.final_params,
                "tested_param_sets": report.tested_param_sets,
                "import_statement": report.import_statement,
                "model_code": report.model_code,
            }

            replacement_reports.append(report_dict)

            changes_made.append(
                {
                    "from": report.original_algorithm,
                    "to": report.new_algorithm,
                    "preserved_params": report.preserved_params,
                    "removed_params": report.removed_params,
                    "added_params": report.added_params,
                    "final_params": report.final_params,
                    "tested_param_sets_count": len(report.tested_param_sets),
                }
            )

        except Exception as exc:
            warning = (
                f"Algorithm replacement failed but will not stop the run. "
                f"target_algorithm={target_algorithm}, replacement={replacement}, error={exc}"
            )
            print(f"[ALGORITHM REPLACEMENT WARNING] {warning}")
            warnings.append(warning)

    if not changes_made:
        return entrypoint, {
            "enabled": False,
            "requested_replacements": replacements,
            "changes_made": [],
            "replacement_reports": [],
            "warnings": warnings or ["No valid algorithm replacement was applied."],
            "original_entrypoint": entrypoint,
            "executed_entrypoint": entrypoint,
        }

    modified_entrypoint = f"modified_{Path(entrypoint).name}"
    modified_script_path = workspace / modified_entrypoint
    modified_script_path.write_text(modified_code, encoding="utf-8")

    return modified_entrypoint, {
        "enabled": True,
        "requested_replacements": replacements,
        "changes_made": changes_made,
        "replacement_reports": replacement_reports,
        "warnings": warnings,
        "original_entrypoint": entrypoint,
        "executed_entrypoint": modified_entrypoint,
        "modified_script_path": str(modified_script_path),
    }




def _algorithm_call_name(node: Any) -> Optional[str]:
    """
    Returns a readable class/function name from an AST call node.
    """
    try:
        import ast

        if isinstance(node, ast.Name):
            return node.id

        if isinstance(node, ast.Attribute):
            parent = _algorithm_call_name(node.value)
            if parent:
                return f"{parent}.{node.attr}"
            return node.attr

    except Exception:
        return None

    return None


def detect_algorithms_in_python_file(script_path: Path) -> list[dict[str, Any]]:
    """
    Detects sklearn/xgboost/lightgbm/catboost-like estimators in a Python file.
    It supports multiple algorithms in the same run.
    It is intentionally safe: if parsing fails, it returns [].
    """
    detected: list[dict[str, Any]] = []

    if script_path is None or not script_path.exists():
        return detected

    try:
        import ast

        source = script_path.read_text(encoding="utf-8")
        tree = ast.parse(source)

        estimator_keywords = (
            "classifier",
            "regressor",
            "regression",
            "svc",
            "svr",
            "xgb",
            "lgbm",
            "catboost",
            "randomforest",
            "gradientboosting",
            "extratrees",
            "decisiontree",
            "kneighbors",
            "logistic",
            "linearregression",
            "ridge",
            "lasso",
            "elasticnet",
            "naivebayes",
            "gaussiannb",
            "multinomialnb",
            "adaboost",
        )

        ignored_calls = {
            "Pipeline",
            "ColumnTransformer",
            "StandardScaler",
            "OneHotEncoder",
            "SimpleImputer",
            "MinMaxScaler",
            "RobustScaler",
            "LabelEncoder",
            "train_test_split",
            "DataFrame",
            "Series",
        }

        def is_probable_estimator(name: str) -> bool:
            if not name:
                return False

            short_name = name.split(".")[-1]

            if short_name in ignored_calls:
                return False

            lowered = short_name.lower()

            if any(keyword in lowered for keyword in estimator_keywords):
                return True

            if short_name.endswith("Classifier") or short_name.endswith("Regressor"):
                return True

            return False

        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue

            call_name = _algorithm_call_name(node.func)

            if not call_name or not is_probable_estimator(call_name):
                continue

            var_name = None
            parent_assign = None

            # Lightweight parent discovery is not available from ast directly,
            # so we do a second pass below for assignments. Here we keep class_name.
            detected.append({
                "var_name": var_name,
                "class_name": call_name.split(".")[-1],
                "full_name": call_name,
                "library": ".".join(call_name.split(".")[:-1]) if "." in call_name else None,
            })

        # Better var_name extraction for direct assignments:
        for node in ast.walk(tree):
            if not isinstance(node, ast.Assign):
                continue

            value = node.value

            if not isinstance(value, ast.Call):
                continue

            call_name = _algorithm_call_name(value.func)

            if not call_name or not is_probable_estimator(call_name):
                continue

            for target in node.targets:
                if isinstance(target, ast.Name):
                    var_name = target.id
                elif isinstance(target, ast.Attribute):
                    var_name = target.attr
                else:
                    var_name = None

                detected.append({
                    "var_name": var_name,
                    "class_name": call_name.split(".")[-1],
                    "full_name": call_name,
                    "library": ".".join(call_name.split(".")[:-1]) if "." in call_name else None,
                })

        # Deduplicate while preserving order.
        unique: list[dict[str, Any]] = []
        seen = set()

        for item in detected:
            key = (
                item.get("var_name"),
                item.get("class_name"),
                item.get("full_name"),
            )

            if key in seen:
                continue

            seen.add(key)
            unique.append(item)

        return unique

    except Exception as exc:
        print(f"[ALGORITHM METADATA WARNING] Could not detect algorithms in {script_path}: {exc}")
        return []


def build_algorithm_metadata_for_mlflow(
    workspace: Path,
    algorithm_injection_info: Dict[str, Any],
) -> Dict[str, Any]:
    """
    Builds metadata that supports 0, 1, or many algorithms and replacements.
    Stored later as MLflow tags.
    """
    algorithm_injection_info = algorithm_injection_info or {}

    original_entrypoint = algorithm_injection_info.get("original_entrypoint")
    executed_entrypoint = algorithm_injection_info.get("executed_entrypoint") or original_entrypoint

    original_path = workspace / original_entrypoint if original_entrypoint else None
    executed_path = workspace / executed_entrypoint if executed_entrypoint else original_path

    detected_algorithms = detect_algorithms_from_file(str(original_path)).get("detected", [])
    used_algorithms = detect_algorithms_from_file(str(executed_path)).get("detected", [])

    replacements: list[dict[str, Any]] = []

    changes_made = algorithm_injection_info.get("changes_made") or []
    replacement_reports = algorithm_injection_info.get("replacement_reports") or []

    for change in changes_made:
        if not isinstance(change, dict):
            continue

        replacements.append({
            "from": change.get("from"),
            "to": change.get("to"),
            "preserved_params": change.get("preserved_params", {}),
            "removed_params": change.get("removed_params", {}),
            "added_params": change.get("added_params", {}),
            "final_params": change.get("final_params", {}),
            "tested_param_sets_count": change.get("tested_param_sets_count"),
            "status": "replaced",
        })

    if not replacements and replacement_reports:
        for report in replacement_reports:
            if not isinstance(report, dict):
                continue

            replacements.append({
                "from": report.get("original_algorithm"),
                "to": report.get("new_algorithm"),
                "old_params": report.get("old_params", {}),
                "preserved_params": report.get("preserved_params", {}),
                "removed_params": report.get("removed_params", {}),
                "added_params": report.get("added_params", {}),
                "final_params": report.get("final_params", {}),
                "tested_param_sets_count": len(report.get("tested_param_sets") or []),
                "status": "replaced",
            })

    return {
        "detected_algorithms": detected_algorithms,
        "used_algorithms": used_algorithms,
        "algorithm_replacements": replacements,
        "algorithm_replacement_count": len(replacements),
        "has_algorithm_replacement": len(replacements) > 0,
    }


def log_algorithm_metadata_to_mlflow(metadata: Dict[str, Any]) -> None:
    """
    Logs algorithm metadata to MLflow tags.
    Values are JSON strings so Angular can display any number of algorithms.
    """
    metadata = metadata or {}

    detected_algorithms = metadata.get("detected_algorithms") or []
    used_algorithms = metadata.get("used_algorithms") or []
    algorithm_replacements = metadata.get("algorithm_replacements") or []
    algorithm_replacement_count = int(metadata.get("algorithm_replacement_count") or 0)
    has_algorithm_replacement = bool(metadata.get("has_algorithm_replacement"))

    mlflow.set_tag(
        "detected_algorithms",
        json.dumps(detected_algorithms, ensure_ascii=False),
    )

    mlflow.set_tag(
        "used_algorithms",
        json.dumps(used_algorithms, ensure_ascii=False),
    )

    mlflow.set_tag(
        "algorithm_replacements",
        json.dumps(algorithm_replacements, ensure_ascii=False),
    )

    mlflow.set_tag("algorithm_replacement_count", str(algorithm_replacement_count))
    mlflow.set_tag("has_algorithm_replacement", str(has_algorithm_replacement).lower())



def sanitize_mlflow_metric_name(name: str) -> str:
    cleaned = str(name or "").strip().lower()
    cleaned = cleaned.replace(" ", "_").replace("-", "_")
    cleaned = "".join(ch for ch in cleaned if ch.isalnum() or ch in {"_", ".", "/"})
    return cleaned or "metric"


def sanitize_metrics_for_mlflow(metrics: Dict[str, Any]) -> Dict[str, float]:
    """
    MLflow accepte seulement des valeurs numériques.
    Cette fonction évite qu'une métrique texte/None casse le run.
    """
    sanitized: Dict[str, float] = {}

    for raw_key, raw_value in (metrics or {}).items():
        key = sanitize_mlflow_metric_name(raw_key)

        try:
            value = float(raw_value)
        except Exception:
            print(f"[MLFLOW METRIC WARNING] Ignored non-numeric metric {raw_key}={raw_value}")
            continue

        if value != value or value in {float("inf"), float("-inf")}:
            print(f"[MLFLOW METRIC WARNING] Ignored invalid metric {raw_key}={raw_value}")
            continue

        sanitized[key] = value

    return sanitized


def get_experiment_view_type_all():
    if ViewType is not None:
        return ViewType.ALL
    return 3


def safe_set_mlflow_experiment(
    experiment_name: str,
    tracking_uri: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Remplace mlflow.set_experiment(experiment_name).

    Corrige le cas MLflow :
    Cannot set a deleted experiment 'xxx' as the active experiment.

    Comportement :
    - si l'experiment actif existe : on l'utilise
    - si l'experiment existe en deleted : on le restaure
    - si la restauration échoue : on crée un nom fallback unique
    - sinon : on crée/utilise normalement l'experiment demandé
    """
    requested_name = safe_str(experiment_name, "default")

    if tracking_uri:
        mlflow.set_tracking_uri(tracking_uri)

    client = MlflowClient(tracking_uri=tracking_uri)

    print(f"[MLFLOW] Requested experiment name: {requested_name}")

    try:
        experiments = client.search_experiments(view_type=get_experiment_view_type_all())
    except Exception as exc:
        print(f"[MLFLOW WARNING] Could not search all experiments: {exc}")
        experiments = []

    matching = [exp for exp in experiments if getattr(exp, "name", None) == requested_name]

    active_matches = [
        exp for exp in matching
        if str(getattr(exp, "lifecycle_stage", "")).lower() == "active"
    ]

    if active_matches:
        exp = active_matches[0]
        mlflow.set_experiment(requested_name)
        print(f"[MLFLOW] experiment exists active: id={exp.experiment_id}, name={requested_name}")
        return {
            "name": requested_name,
            "experiment_id": str(exp.experiment_id),
            "status": "active_existing",
            "requested_name": requested_name,
        }

    deleted_matches = [
        exp for exp in matching
        if str(getattr(exp, "lifecycle_stage", "")).lower() == "deleted"
    ]

    if deleted_matches:
        exp = deleted_matches[0]
        print(
            f"[MLFLOW] experiment exists deleted, restoring: "
            f"id={exp.experiment_id}, name={requested_name}"
        )

        try:
            client.restore_experiment(str(exp.experiment_id))
            mlflow.set_experiment(requested_name)
            print(f"[MLFLOW] restored experiment: id={exp.experiment_id}, name={requested_name}")
            return {
                "name": requested_name,
                "experiment_id": str(exp.experiment_id),
                "status": "restored_deleted",
                "requested_name": requested_name,
            }
        except Exception as restore_exc:
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            fallback_name = f"{requested_name}_restored_{timestamp}"

            print(
                "[MLFLOW WARNING] Could not restore deleted experiment. "
                f"requested={requested_name}, error={restore_exc}"
            )
            print(f"[MLFLOW] fallback experiment name created: {fallback_name}")

            mlflow.set_experiment(fallback_name)
            fallback_exp = client.get_experiment_by_name(fallback_name)

            return {
                "name": fallback_name,
                "experiment_id": str(fallback_exp.experiment_id) if fallback_exp else None,
                "status": "fallback_created",
                "requested_name": requested_name,
                "restore_error": str(restore_exc),
            }

    mlflow.set_experiment(requested_name)
    created_or_existing = client.get_experiment_by_name(requested_name)

    print(
        f"[MLFLOW] experiment created or selected: "
        f"id={getattr(created_or_existing, 'experiment_id', None)}, name={requested_name}"
    )

    return {
        "name": requested_name,
        "experiment_id": str(created_or_existing.experiment_id) if created_or_existing else None,
        "status": "created_or_selected",
        "requested_name": requested_name,
    }


def merge_algorithm_metadata_with_adapter_result(
    base_metadata: Dict[str, Any],
    adapter_result: Dict[str, Any],
) -> Dict[str, Any]:
    """
    L'adapter universel peut avoir une vision plus exacte après remplacement/exécution.
    On fusionne donc ses sorties avec les métadonnées initiales.
    """
    base_metadata = base_metadata or {}
    adapter_result = adapter_result or {}

    detected = (
        adapter_result.get("detected_algorithms")
        or base_metadata.get("detected_algorithms")
        or []
    )

    used = (
        adapter_result.get("used_algorithms")
        or base_metadata.get("used_algorithms")
        or []
    )

    replacements = (
        adapter_result.get("algorithm_replacements")
        or base_metadata.get("algorithm_replacements")
        or []
    )

    try:
        replacement_count = int(
            adapter_result.get("algorithm_replacement_count")
            if adapter_result.get("algorithm_replacement_count") is not None
            else len(replacements)
        )
    except Exception:
        replacement_count = len(replacements)

    has_replacement = bool(
        adapter_result.get("has_algorithm_replacement")
        if adapter_result.get("has_algorithm_replacement") is not None
        else replacement_count > 0
    )

    return {
        **base_metadata,
        "detected_algorithms": detected,
        "used_algorithms": used,
        "algorithm_replacements": replacements,
        "algorithm_replacement_count": replacement_count,
        "has_algorithm_replacement": has_replacement,
    }

# ============================================================
# MLFLOW HELPERS
# ============================================================

def validate_model(model_uri: str, config: Dict[str, Any]) -> Dict[str, Any]:
    """
    Validate model only when it is loadable as MLflow pyfunc.

    Pour les modèles PyTorch/TorchScript loggés comme artefacts,
    models:/name/version pointe vers un dossier contenant model.pt,
    pas vers un modèle pyfunc MLflow.

    Donc si mlflow.pyfunc.load_model() échoue, on skippe proprement
    la validation au lieu de faire échouer tout le pipeline Kubeflow.
    """
    validation_cfg = config.get("validation", {}) if isinstance(config.get("validation"), dict) else {}

    enabled = validation_cfg.get("enabled", False)
    strict = validation_cfg.get("strict", False)
    input_example = validation_cfg.get("input_example")

    if not enabled:
        return {"status": "disabled"}

    if input_example is None:
        if strict:
            raise ValueError("validation.input_example is required because strict=true.")

        return {
            "status": "skipped",
            "reason": "No input_example",
        }

    try:
        model = mlflow.pyfunc.load_model(model_uri)

    except Exception as exc:
        message = str(exc)

        print(
            "[VALIDATION WARNING] Could not load model as MLflow pyfunc. "
            f"Validation skipped. model_uri={model_uri}, error={message}"
        )

        if strict:
            raise

        return {
            "status": "skipped",
            "reason": "model_not_pyfunc_or_artifact_only",
            "error": message,
        }

    if isinstance(input_example, list):
        df = pd.DataFrame(input_example)

    elif isinstance(input_example, dict):
        df = pd.DataFrame([input_example])

    else:
        raise ValueError("validation.input_example must be dict or list of dicts.")

    predictions = model.predict(df)

    if hasattr(predictions, "tolist"):
        predictions = predictions.tolist()

    return {
        "status": "success",
        "predictions": predictions,
    }


def get_model_version_for_run(
    client: MlflowClient,
    registered_model_name: str,
    run_id: str,
):
    """
    MLflow Registry can take a few seconds to expose the new version.
    We retry and prefer the version linked to the exact run_id.
    """
    last_versions = []

    for attempt in range(20):
        versions = client.search_model_versions(f"name='{registered_model_name}'")
        last_versions = versions or []

        versions_for_run = [
            version for version in last_versions
            if getattr(version, "run_id", None) == run_id
        ]

        if versions_for_run:
            return max(versions_for_run, key=lambda v: int(v.version))

        time.sleep(1)

    if last_versions:
        print(
            "[MLFLOW WARNING] No model version matched the exact run_id. "
            "Using latest version as fallback."
        )
        return max(last_versions, key=lambda v: int(v.version))

    raise RuntimeError(f"No model version found for {registered_model_name}")



def normalize_registered_model_name_for_mlflow(value: Any, fallback: str = "custom_model") -> str:
    """
    One project = one stable MLflow Registered Model name.
    Prevents names such as 'model' or 'xxx_model_model'.
    """
    name = safe_str(value, fallback).lower()
    name = "".join(ch if ch.isalnum() else "_" for ch in name)
    while "__" in name:
        name = name.replace("__", "_")
    name = name.strip("_") or fallback

    while name.endswith("_model_model"):
        name = name[:-len("_model")]

    if not name.endswith("_model"):
        name = f"{name}_model"

    return name


def ensure_registered_model_exists(client: MlflowClient, registered_model_name: str) -> None:
    try:
        client.get_registered_model(registered_model_name)
        return
    except Exception:
        pass

    try:
        client.create_registered_model(registered_model_name)
        print(f"[MLFLOW REGISTRY] Registered model created: {registered_model_name}")
    except Exception as exc:
        message = str(exc).lower()
        if "already exists" not in message and "resource_already_exists" not in message:
            raise


def wait_until_model_version_ready(
    client: MlflowClient,
    registered_model_name: str,
    version: str,
    timeout_seconds: int = 60,
):
    deadline = time.time() + timeout_seconds
    last_info = None

    while time.time() < deadline:
        try:
            last_info = client.get_model_version(
                name=registered_model_name,
                version=str(version),
            )
            status = str(getattr(last_info, "status", "") or "").upper()

            if status in {"READY", ""}:
                return last_info

            if status == "FAILED_REGISTRATION":
                raise RuntimeError(
                    f"Model version registration failed for {registered_model_name} v{version}"
                )
        except Exception as exc:
            if "FAILED_REGISTRATION" in str(exc):
                raise

        time.sleep(1)

    return last_info

def register_model_version_explicitly(
    client: MlflowClient,
    registered_model_name: str,
    run_id: str,
    logged_model_uri: str,
):
    """
    Crée explicitement une version dans le MLflow Model Registry
    à partir du vrai URI retourné par get_model_wrapper_and_log_model().
    """

    if not registered_model_name:
        raise ValueError("registered_model_name is required.")

    if not run_id:
        raise ValueError("run_id is required.")

    if not logged_model_uri:
        raise ValueError("logged_model_uri is required.")

    ensure_registered_model_exists(client, registered_model_name)

    versions = client.search_model_versions(f"name='{registered_model_name}'") or []

    same_run_versions = [
        v for v in versions
        if getattr(v, "run_id", None) == run_id
    ]

    if same_run_versions:
        version = max(same_run_versions, key=lambda v: int(v.version))

        print(
            f"[MLFLOW REGISTRY] Existing version found: "
            f"{registered_model_name} v{version.version} for run {run_id}"
        )

        return version

    print(
        f"[MLFLOW REGISTRY] Creating model version from logged_model_uri: "
        f"name={registered_model_name}, source={logged_model_uri}, run_id={run_id}"
    )

    created_version = client.create_model_version(
        name=registered_model_name,
        source=logged_model_uri,
        run_id=run_id,
    )

    wait_until_model_version_ready(
        client=client,
        registered_model_name=registered_model_name,
        version=str(created_version.version),
        timeout_seconds=90,
    )

    return created_version
    
def set_model_alias_safely(
    client: MlflowClient,
    registered_model_name: str,
    alias: str,
    version: str,
) -> None:
    alias = safe_str(alias, "production")

    client.set_registered_model_alias(
        name=registered_model_name,
        alias=alias,
        version=str(version),
    )
    print(f"[MLFLOW REGISTRY] Alias @{alias} -> {registered_model_name} v{version}")



def log_model_version_tags(
    client: MlflowClient,
    registered_model_name: str,
    version: str,
    run_id: str,
    validation_result: Dict[str, Any],
    project_name: str,
    model_name: str,
    run_name: str,
    alias: str,
    primary_metric: Optional[str],
    direction: str,
    spring_experiment_id: Optional[str],
    kubeflow_run_id: Optional[str],
    kubeflow_run_name: Optional[str],
):
    tags = {
        "run_id": run_id,
        "validation_status": str(validation_result.get("status")),
        "project_name": project_name,
        "model_name": model_name,
        "run_name": run_name,
        "alias": alias,
        "direction": direction,
    }

    if primary_metric:
        tags["primary_metric"] = primary_metric

    if spring_experiment_id:
        tags["spring_experiment_id"] = spring_experiment_id

    if kubeflow_run_id:
        tags["kubeflow_run_id"] = kubeflow_run_id

    if kubeflow_run_name:
        tags["kubeflow_run_name"] = kubeflow_run_name

    for key, value in tags.items():
        client.set_model_version_tag(
            name=registered_model_name,
            version=version,
            key=key,
            value=str(value),
        )


def is_auto_promotion_enabled(config: Dict[str, Any]) -> bool:
    value = get_nested(
        config,
        [
            ["registry", "auto_promote_best_model"],
            ["registry", "autoPromoteBestModel"],
            ["model", "auto_promote_best_model"],
            ["model", "autoPromoteBestModel"],
            ["auto_promote_best_model"],
            ["autoPromoteBestModel"],
        ],
        True,
    )

    if isinstance(value, bool):
        return value

    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes", "y", "on"}

    return bool(value)


def extract_runtime_ids(config: Dict[str, Any], run_name: str) -> tuple[Optional[str], Optional[str], Optional[str]]:
    spring_experiment_id = get_nested(
        config,
        [
            ["spring_experiment_id"],
            ["springExperimentId"],
            ["experiment", "spring_experiment_id"],
            ["experiment", "id"],
        ],
        None,
    )

    kubeflow_run_id = get_nested(
        config,
        [
            ["kubeflow_run_id"],
            ["kubeflowRunId"],
            ["kubeflow", "run_id"],
        ],
        None,
    )

    kubeflow_run_name = get_nested(
        config,
        [
            ["kubeflow_run_name"],
            ["kubeflowRunName"],
            ["kubeflow", "run_name"],
        ],
        run_name,
    )

    spring_experiment_id = safe_str(spring_experiment_id, "")
    kubeflow_run_id = safe_str(kubeflow_run_id, "")
    kubeflow_run_name = safe_str(kubeflow_run_name, run_name)

    return (
        spring_experiment_id or None,
        kubeflow_run_id or None,
        kubeflow_run_name or None,
    )


# ============================================================
# MAIN PIPELINE
# ============================================================

def run_custom_model_pipeline(config_path: str) -> Dict[str, Any]:
    """
    Main Kubeflow custom training runner.

    Corrections :
    - corrige l'indentation cassée
    - utilise load_custom_config()
    - désactive définitivement l'injection/remplacement automatique
    - garde uniquement la détection des algorithmes/modules
    - supporte data_type : TABULAR / IMAGE / TEXT
    - ajoute les tags MLflow pour choisir le bon mode de prédiction
    """
    config = load_custom_config(config_path)

    workspace = create_inline_workspace(config)

    run_name = safe_str(
        get_nested(
            config,
            [
                ["run_name"],
                ["runName"],
                ["experiment", "run_name"],
                ["experiment", "runName"],
                ["model", "run_name"],
                ["model", "runName"],
            ],
            None,
        ),
        "custom_run",
    )

    model_name = safe_str(
        get_nested(
            config,
            [
                ["model_name"],
                ["modelName"],
                ["model", "name"],
                ["model", "model_name"],
                ["model", "modelName"],
            ],
            None,
        ),
        "custom_model",
    )

    project_name = safe_str(
        get_nested(
            config,
            [
                ["project_name"],
                ["projectName"],
                ["project", "name"],
                ["project", "project_name"],
                ["model", "project_name"],
            ],
            None,
        ),
        model_name,
    )

    custom_code = config.get("custom_code") if isinstance(config.get("custom_code"), dict) else {}

    entrypoint = safe_str(
        custom_code.get("entrypoint")
        or config.get("entrypoint")
        or get_nested(config, [["code", "entrypoint"], ["model", "entrypoint"]], None),
        "",
    )

    if not entrypoint:
        raise ValueError("Missing custom_code.entrypoint in custom config.")

    data_type = normalize_data_type(
        config.get("data_type")
        or config.get("dataType")
        or get_nested(
            config,
            [["project", "data_type"], ["project", "dataType"], ["model", "data_type"]],
            "TABULAR",
        )
    )

    task_type = normalize_task_type(
        config.get("task_type")
        or config.get("taskType")
        or get_nested(
            config,
            [["project", "task_type"], ["project", "taskType"], ["model", "task_type"]],
            config.get("project_type", "classification"),
        ),
        fallback=str(config.get("project_type", "classification")),
    )

    project_type = safe_str(
        get_nested(
            config,
            [
                ["project_type"],
                ["projectType"],
                ["project", "project_type"],
                ["project", "projectType"],
                ["model", "project_type"],
            ],
            None,
        ),
        "classification",
    ).lower()

    if project_type not in {"classification", "regression"}:
        project_type = "regression" if task_type == "regression" else "classification"

    tracking_uri = safe_str(
        get_nested(
            config,
            [
                ["mlflow", "tracking_uri"],
                ["mlflow", "trackingUri"],
                ["tracking_uri"],
                ["trackingUri"],
            ],
            None,
        ),
        os.getenv("MLFLOW_TRACKING_URI", "http://mlflow-service.mlops.svc.cluster.local:5000"),
    )

    experiment_name = safe_str(
        get_nested(
            config,
            [
                ["mlflow", "experiment_name"],
                ["mlflow", "experimentName"],
                ["experiment_name"],
                ["experimentName"],
                ["project", "name"],
                ["project_name"],
                ["projectName"],
            ],
            None,
        ),
        project_name,
    )

    registered_model_name = safe_str(
        get_nested(
            config,
            [
                ["registry", "registered_model_name"],
                ["registry", "registeredModelName"],
                ["registered_model_name"],
                ["registeredModelName"],
                ["model", "registered_model_name"],
                ["model", "registeredModelName"],
            ],
            None,
        ),
        model_name,
    )

    registered_model_name = normalize_registered_model_name_for_mlflow(
        registered_model_name,
        fallback=project_name or model_name or "custom_model",
    )

    alias = safe_str(
        get_nested(
            config,
            [
                ["registry", "alias"],
                ["registry", "model_alias"],
                ["registry", "modelAlias"],
                ["model_alias"],
                ["modelAlias"],
                ["alias"],
            ],
            None,
        ),
        "production",
    )

    primary_metric = safe_str(
        get_nested(
            config,
            [
                ["metrics", "primary_metric"],
                ["metrics", "primaryMetric"],
                ["primary_metric"],
                ["primaryMetric"],
                ["model", "primary_metric"],
                ["project", "primary_metric"],
            ],
            None,
        ),
        "rmse" if project_type == "regression" else "accuracy",
    ).lower()

    configured_direction = get_nested(
        config,
        [
            ["metrics", "direction"],
            ["metric_direction"],
            ["metricDirection"],
            ["direction"],
        ],
        None,
    )

    direction = infer_direction(primary_metric, configured_direction)

    spring_experiment_id, kubeflow_run_id, kubeflow_run_name = extract_runtime_ids(
        config,
        run_name,
    )

    print(f"[CUSTOM RUNNER] data_type={data_type}")
    print(f"[CUSTOM RUNNER] task_type={task_type}")
    print(f"[CUSTOM RUNNER] project_type={project_type}")
    print(f"[CUSTOM RUNNER] workspace={workspace}")
    print(f"[CUSTOM RUNNER] entrypoint={entrypoint}")

    dataset_path = create_inline_dataset(config, workspace)
    validate_dataset_for_data_type(dataset_path, data_type)

    algorithm_injection_info = {
        "enabled": False,
        "requested_replacements": [],
        "changes_made": [],
        "replacement_reports": [],
        "warnings": ["Algorithm injection disabled. Running original script."],
        "original_entrypoint": entrypoint,
        "executed_entrypoint": entrypoint,
    }

    algorithm_metadata = build_algorithm_metadata_for_mlflow(
        workspace=workspace,
        algorithm_injection_info=algorithm_injection_info,
    )

    alias_paths = create_robust_dataset_aliases(
        workspace=workspace,
        dataset_path=dataset_path,
        script_path=workspace / entrypoint,
    )

    print(f"[CUSTOM RUNNER] dataset_path={dataset_path}")
    print(f"[CUSTOM RUNNER] dataset aliases={alias_paths}")

    user_run_result = run_user_model_with_universal_adapter(
        workspace=workspace,
        entrypoint=entrypoint,
        dataset_path=dataset_path,
        config=config,
        algorithm_metadata=algorithm_metadata,
    )

    print("========== UNIVERSAL ADAPTER RESULT ==========")
    print(json.dumps(user_run_result, indent=2, ensure_ascii=False))
    print("===========================================")

    algorithm_metadata = merge_algorithm_metadata_with_adapter_result(
        base_metadata=algorithm_metadata,
        adapter_result=user_run_result,
    )

    outputs_dir = Path(user_run_result.get("outputs_dir") or (workspace / "outputs"))
    outputs_dir.mkdir(parents=True, exist_ok=True)

    mlflow.set_tracking_uri(tracking_uri)

    mlflow_experiment_info = safe_set_mlflow_experiment(
        experiment_name=experiment_name,
        tracking_uri=tracking_uri,
    )

    requested_experiment_name = experiment_name
    experiment_name = mlflow_experiment_info["name"]

    print("========== CUSTOM MODEL CONFIG ==========")
    print(f"Project name: {project_name}")
    print(f"Model name: {model_name}")
    print(f"Run name: {run_name}")
    print(f"Experiment: {experiment_name}")
    print(f"Requested experiment: {requested_experiment_name}")
    print(f"MLflow experiment info: {mlflow_experiment_info}")
    print(f"Registered model: {registered_model_name}")
    print(f"Alias: {alias}")
    print(f"Workspace: {workspace}")
    print(f"Entrypoint executed: {entrypoint}")
    print(f"Algorithm injection enabled: {algorithm_injection_info.get('enabled')}")
    print(f"Algorithm injection warnings: {algorithm_injection_info.get('warnings')}")
    print(f"Detected algorithms: {algorithm_metadata.get('detected_algorithms')}")
    print(f"Used algorithms: {algorithm_metadata.get('used_algorithms')}")
    print(f"Algorithm replacements: {algorithm_metadata.get('algorithm_replacements')}")
    print(f"Dataset path: {dataset_path}")
    print(f"Primary metric: {primary_metric}")
    print(f"Direction: {direction}")
    print(f"Spring experiment ID: {spring_experiment_id}")
    print(f"Kubeflow run ID: {kubeflow_run_id}")
    print(f"Kubeflow run name: {kubeflow_run_name}")
    print("=========================================")

    run_id = None
    logged_model_uri = None
    metrics: Dict[str, float] = {}
    model_path: Optional[Path] = None

    with mlflow.start_run(run_name=run_name) as run:
        run_id = run.info.run_id

        mlflow.set_tag("project_name", project_name)
        mlflow.set_tag("model_name", model_name)
        mlflow.set_tag("run_name", run_name)
        mlflow.set_tag("registered_model_name", registered_model_name)
        mlflow.set_tag("alias", alias)
        mlflow.set_tag("primary_metric", primary_metric or "")
        mlflow.set_tag("direction", direction)
        mlflow.set_tag("runner", "universal-custom-model-runner")
        mlflow.set_tag("runner_contract", "universal_model_adapter_multi_strategy")
        mlflow.set_tag("project_type", project_type)

        mlflow.set_tag("requested_experiment_name", requested_experiment_name)
        mlflow.set_tag("mlflow_experiment_status", str(mlflow_experiment_info.get("status", "")))
        mlflow.set_tag("mlflow_experiment_id", str(mlflow_experiment_info.get("experiment_id", "")))

        log_data_type_tags_to_mlflow(data_type, task_type)

        mlflow.set_tag("algorithm_injection_enabled", "false")
        mlflow.set_tag("replacement_enabled", "false")
        mlflow.set_tag("algorithm_original_entrypoint", str(algorithm_injection_info.get("original_entrypoint", "")))
        mlflow.set_tag("algorithm_executed_entrypoint", str(algorithm_injection_info.get("executed_entrypoint", entrypoint)))
        mlflow.set_tag("algorithm_changes_count", "0")

        if spring_experiment_id:
            mlflow.set_tag("spring_experiment_id", spring_experiment_id)

        if kubeflow_run_id:
            mlflow.set_tag("kubeflow_run_id", kubeflow_run_id)

        if kubeflow_run_name:
            mlflow.set_tag("kubeflow_run_name", kubeflow_run_name)

        log_algorithm_metadata_to_mlflow(algorithm_metadata)

        mlflow.log_text(
            json.dumps(algorithm_injection_info, indent=2, ensure_ascii=False),
            artifact_file="algorithm_injection/injection_result.json",
        )

        mlflow.log_text(
            json.dumps(algorithm_metadata, indent=2, ensure_ascii=False),
            artifact_file="algorithm_injection/algorithm_metadata.json",
        )

        mlflow.log_text(user_run_result.get("stdout", ""), artifact_file="logs/stdout.log")
        mlflow.log_text(user_run_result.get("stderr", ""), artifact_file="logs/stderr.log")
        mlflow.log_metric("return_code", float(user_run_result.get("return_code", 0)))

        model_path_str = user_run_result.get("model_path")
        model_path = Path(model_path_str) if model_path_str and isinstance(model_path_str, str) else None

        metrics = sanitize_metrics_for_mlflow(user_run_result.get("metrics", {}) or {})
        execution_mode = user_run_result.get("execution_mode", "UNKNOWN")

        print("========== RUNTIME EXECUTION INFO ==========")
        print(f"[RUNTIME] Script original executed: {entrypoint}")
        print("[RUNTIME] Injection disabled: True")
        print(f"[RUNTIME] Execution mode: {execution_mode}")
        print(f"[RUNTIME] Return code: {user_run_result.get('return_code', 0)}")
        print(f"[RUNTIME] Universal adapter status: {user_run_result.get('status', 'unknown')}")
        print("==========================================")

        if user_run_result.get("warnings"):
            for warning in user_run_result.get("warnings", []):
                print(f"[RUNTIME WARNING] {warning}")

        if model_path is None or not model_path.exists():
            print("[MODEL DETECTION] Model not found by universal adapter, scanning workspace...")
            model_candidates = find_all_model_artifacts(workspace, outputs_dir)

            if model_candidates:
                selected_model = select_best_model(model_candidates)

                if selected_model:
                    model_path = selected_model
                    print(f"[MODEL DETECTION] Auto-selected model: {selected_model.name}")
            else:
                supported_exts = [
                    ".pkl",
                    ".joblib",
                    ".pickle",
                    ".pt",
                    ".pth",
                    ".h5",
                    ".keras",
                    ".onnx",
                ]

                error_msg = (
                    "Aucun fichier modèle détecté après exécution du script.\n"
                    "Sauvegardez votre modèle avec une extension supportée :\n"
                    f"{', '.join(supported_exts)}\n\n"
                    "Exemple pour PyTorch :\n"
                    "  torch.save(model, 'model_vision.pt')\n\n"
                    "Exemple pour scikit-learn :\n"
                    "  joblib.dump(model, 'model.pkl')"
                )

                print(f"[ERROR] {error_msg}")
                mlflow.set_tag("training_status", "failed")
                mlflow.set_tag("failure_reason", "no_model_detected")
                mlflow.log_text(error_msg, artifact_file="logs/error.txt")
                raise FileNotFoundError(error_msg)

        if model_path and model_path.exists():
            mlflow.set_tag("model_creation_mode", f"{execution_mode}")
            mlflow.set_tag("model_file", model_path.name)
            mlflow.set_tag("main_model_file", str(model_path))
            mlflow.set_tag("model_extension", model_path.suffix.lower())

        mlflow.set_tag("training_status", "success")
        mlflow.set_tag("execution_mode", execution_mode)
        mlflow.set_tag("dataset_aliases", json.dumps(alias_paths, ensure_ascii=False))
        mlflow.set_tag("universal_runner_status", user_run_result.get("status", "unknown"))

        if metrics:
            mlflow.log_metrics(metrics)

        primary_metric_value = None

        if primary_metric and primary_metric in metrics:
            primary_metric_value = metrics[primary_metric]
            mlflow.set_tag("primary_metric_value", str(primary_metric_value))

        save_metrics_files(outputs_dir, metrics)

        mlflow.log_artifacts(str(outputs_dir), artifact_path="raw_outputs")

        print(f"[MODEL LOGGING] Preparing to log model: {model_path.name}")

        logged_model_uri = get_model_wrapper_and_log_model(
            model_path=model_path,
            registered_model_name=registered_model_name,
        )

        if logged_model_uri:
            mlflow.set_tag("mlflow_model_uri", logged_model_uri)
            mlflow.set_tag("model_artifact_status", "logged")
            mlflow.set_tag("prediction_supported", "true")
            print(f"[MODEL LOGGING] Model logged successfully: {logged_model_uri}")
        else:
            ext = model_path.suffix.lower()

            if ext in {".h5", ".keras", ".onnx"}:
                print(
                    f"[MODEL LOGGING] Model format {ext} is not supported for runtime prediction. "
                    "Artifact saved only."
                )
                mlflow.set_tag("prediction_supported", "false")
                mlflow.set_tag("model_artifact_status", "artifact_only")
            else:
                print("[MODEL LOGGING] Model logging returned None, using fallback generic wrapper")

                try:
                    logged_model_info = mlflow.pyfunc.log_model(
                        artifact_path="model",
                        python_model=GenericPyFuncWrapper(),
                        artifacts={"model_path": str(model_path)},
                        registered_model_name=registered_model_name,
                        pip_requirements=[
                            "mlflow==3.1.1",
                            "cloudpickle",
                            "joblib",
                            "numpy",
                            "pandas",
                            "scikit-learn",
                        ],
                    )

                    logged_model_uri = logged_model_info.model_uri
                    mlflow.set_tag("mlflow_model_uri", logged_model_uri)
                    mlflow.set_tag("model_artifact_status", "logged_fallback")
                    mlflow.set_tag("prediction_supported", "true")
                    print(f"[MODEL LOGGING] Model logged with fallback: {logged_model_uri}")

                except Exception as exc:
                    print(f"[MODEL LOGGING ERROR] Fallback logging failed: {exc}")
                    traceback.print_exc()
                    mlflow.set_tag("prediction_supported", "false")
                    mlflow.set_tag("model_artifact_status", "artifact_only")

    client = MlflowClient(tracking_uri=tracking_uri)

    if logged_model_uri:
        # Force explicit Model Registry registration.
        # This is the important fix: the MLflow UI may show a Logged Model named "model",
        # but the real registry version must be created under registered_model_name.
        latest_version = register_model_version_explicitly(
            client=client,
            registered_model_name=registered_model_name,
            run_id=run_id,
            logged_model_uri=logged_model_uri,
        )

        latest_model_uri = f"models:/{registered_model_name}/{latest_version.version}"

        validation_result = validate_model(latest_model_uri, config)

        log_model_version_tags(
            client=client,
            registered_model_name=registered_model_name,
            version=str(latest_version.version),
            run_id=run_id,
            validation_result=validation_result,
            project_name=project_name,
            model_name=model_name,
            run_name=run_name,
            alias=alias,
            primary_metric=primary_metric,
            direction=direction,
            spring_experiment_id=spring_experiment_id,
            kubeflow_run_id=kubeflow_run_id,
            kubeflow_run_name=kubeflow_run_name,
        )

        auto_promotion_enabled = is_auto_promotion_enabled(config)

        if auto_promotion_enabled:
            try:
                selected_version, promotion_info = promote_best_version(
                    tracking_uri=tracking_uri,
                    model_name=registered_model_name,
                    alias=alias,
                    primary_metric=primary_metric,
                    direction=direction,
                )

                if selected_version is None:
                    selected_version = latest_version

                production_model_uri = f"models:/{registered_model_name}@{alias}"

            except Exception as exc:
                print(f"[MLFLOW REGISTRY WARNING] promote_best_version failed: {exc}")
                selected_version = latest_version
                set_model_alias_safely(
                    client=client,
                    registered_model_name=registered_model_name,
                    alias=alias,
                    version=str(latest_version.version),
                )
                production_model_uri = f"models:/{registered_model_name}@{alias}"
                promotion_info = {
                    "strategy": "fallback_latest_version",
                    "reason": str(exc),
                    "selected_version": str(latest_version.version),
                    "metric": primary_metric,
                    "direction": direction,
                }
        else:
            selected_version = latest_version
            set_model_alias_safely(
                client=client,
                registered_model_name=registered_model_name,
                alias=alias,
                version=str(latest_version.version),
            )
            production_model_uri = f"models:/{registered_model_name}@{alias}"
            promotion_info = {
                "strategy": "latest_version_alias",
                "reason": "auto_promotion_disabled",
                "selected_version": str(latest_version.version),
                "metric": primary_metric,
                "direction": direction,
            }
    else:
        latest_version = None
        latest_model_uri = None
        validation_result = {
            "status": "skipped",
            "reason": "model_not_logged",
        }
        selected_version = None
        production_model_uri = ""
        promotion_info = {
            "strategy": "skipped",
            "reason": "no_model_logged",
        }
        auto_promotion_enabled = False

    primary_metric_value = None

    if primary_metric and primary_metric in metrics:
        primary_metric_value = metrics[primary_metric]

    with mlflow.start_run(run_id=run_id):
        log_algorithm_metadata_to_mlflow(algorithm_metadata)
        log_data_type_tags_to_mlflow(data_type, task_type)

        if latest_version is not None:
            mlflow.set_tag("model_version", str(latest_version.version))

        mlflow.set_tag("auto_promotion_enabled", str(auto_promotion_enabled).lower())
        mlflow.set_tag("production_alias", alias if auto_promotion_enabled else "")
        mlflow.set_tag("production_version", str(selected_version.version) if selected_version else "")
        mlflow.set_tag("production_model_uri", production_model_uri)

        if spring_experiment_id:
            mlflow.set_tag("spring_experiment_id", spring_experiment_id)

        if kubeflow_run_id:
            mlflow.set_tag("kubeflow_run_id", kubeflow_run_id)

        if kubeflow_run_name:
            mlflow.set_tag("kubeflow_run_name", kubeflow_run_name)

    print("========== CUSTOM PIPELINE RESULT ==========")
    print(f"Run ID: {run_id}")
    print(f"Experiment: {experiment_name}")
    print(f"Registered model: {registered_model_name}")
    print(f"Latest version: {latest_version.version if latest_version else 'none'}")
    print(f"Production version: {selected_version.version if selected_version else 'none'}")
    print(f"Alias: {alias}")
    print(f"MLflow model URI: {logged_model_uri}")
    print(f"Production model URI: {production_model_uri}")
    print(f"Primary metric: {primary_metric}")
    print(f"Primary metric value: {primary_metric_value}")
    print(f"Spring experiment ID: {spring_experiment_id}")
    print(f"Kubeflow run ID: {kubeflow_run_id}")
    print(f"Kubeflow run name: {kubeflow_run_name}")
    print(f"Validation: {validation_result}")
    print(f"Promotion: {promotion_info}")
    print("============================================")

    print("status=COMPLETED")
    print(f"mlflow_run_id={run_id}")
    print(f"run_id={run_id}")
    print(f"experiment_name={experiment_name}")
    print(f"registered_model_name={registered_model_name}")
    print(f"model_version={latest_version.version if latest_version else ''}")
    print(f"production_version={selected_version.version if selected_version else ''}")
    print(f"production_alias={alias}")
    print(f"production_model_uri={production_model_uri}")

    if spring_experiment_id:
        print(f"spring_experiment_id={spring_experiment_id}")

    if kubeflow_run_id:
        print(f"kubeflow_run_id={kubeflow_run_id}")

    if kubeflow_run_name:
        print(f"kubeflow_run_name={kubeflow_run_name}")

    if primary_metric_value is not None:
        print(f"primary_metric_value={primary_metric_value}")

    return {
        "status": "COMPLETED",
        "run_id": run_id,
        "mlflow_run_id": run_id,
        "experiment_name": experiment_name,
        "requested_experiment_name": requested_experiment_name,
        "mlflow_experiment_info": mlflow_experiment_info,
        "registered_model_name": registered_model_name,
        "model_version": str(latest_version.version) if latest_version else "",
        "production_version": str(selected_version.version) if selected_version else "",
        "alias": alias,
        "model_uri": logged_model_uri,
        "main_model_file": str(model_path) if model_path is not None else None,
        "production_model_uri": production_model_uri,
        "primary_metric": primary_metric,
        "primary_metric_value": primary_metric_value,
        "validation": validation_result,
        "promotion": promotion_info,
        "metrics": metrics,
        "algorithm_injection": algorithm_injection_info,
        "algorithm_metadata": algorithm_metadata,
        "detected_algorithms": algorithm_metadata.get("detected_algorithms", []),
        "used_algorithms": algorithm_metadata.get("used_algorithms", []),
        "algorithm_replacements": algorithm_metadata.get("algorithm_replacements", []),
        "algorithm_replacement_count": algorithm_metadata.get("algorithm_replacement_count", 0),
        "has_algorithm_replacement": algorithm_metadata.get("has_algorithm_replacement", False),
        "spring_experiment_id": spring_experiment_id,
        "kubeflow_run_id": kubeflow_run_id,
        "kubeflow_run_name": kubeflow_run_name,
        "data_type": data_type,
        "task_type": task_type,
        "input_type": data_type.lower(),
    }