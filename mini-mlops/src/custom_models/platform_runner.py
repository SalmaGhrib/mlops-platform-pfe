from __future__ import annotations

import argparse
import json
import importlib.util
import math
import os
import pickle
import re
import shutil
import subprocess
import sys
import time
import traceback
import tarfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

import joblib
import mlflow
import mlflow.pyfunc
import mlflow.sklearn
from mlflow.tracking import MlflowClient

from src.custom_models.generic_pyfunc_wrapper import GenericPyFuncWrapper
from src.custom_models.import_detector import analyze_script_imports


SUPPORTED_MODEL_EXTENSIONS = {
    ".pkl",
    ".joblib",
    ".pickle",
    ".pt",
    ".pth",
    ".h5",
    ".keras",
    ".onnx",
    ".sav",
    ".cbm",
    ".bst",
    ".ubj",
    ".torchscript",
}

METRIC_REGEX = re.compile(
    r"(?<![A-Za-z0-9_])"
    r"(?P<key>"
    r"val_accuracy|val_loss|f1_score|r2_score|roc_auc|"
    r"accuracy|precision|recall|rmse|mse|mae|loss|auc|acc|f1|r2"
    r")"
    r"(?![A-Za-z0-9_])"
    r"\s*[:=]\s*"
    r"(?P<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)",
    re.IGNORECASE,
)

FRENCH_METRIC_ALIASES = {
    "accuracy sur le test set": "accuracy",
    "accuracy moyenne cv": "accuracy",
    "cv accuracy mean": "accuracy",
    "score de précision": "precision",
    "précision": "precision",
    "precision": "precision",
    "rappel": "recall",
    "recall": "recall",
    "f1": "f1_score",
    "f1 score": "f1_score",
    "f1_score": "f1_score",
    "rmse": "rmse",
    "mse": "mse",
    "mae": "mae",
    "r2": "r2",
    "r2_score": "r2",

}

# PLATFORM_RECOVERY=true
#
# Marqueur de compatibilité lu par KubernetesBuildService.
# La récupération conserve éventuellement un artefact pour audit, mais :
# - un script utilisateur en erreur reste FAILED ;
# - aucune ModelVersion officielle n'est créée pour un run échoué ;
# - l'alias production reste exclusivement géré par le backend.
PLATFORM_RECOVERY = True
PLATFORM_RECOVERY_ENABLED = True


@dataclass
class RunResult:
    status: str
    return_code: int
    stdout: str
    stderr: str
    metrics: Dict[str, float]
    model_paths: List[str]
    logged_model_uri: Optional[str]
    error: Optional[Dict[str, Any]]
    analysis: Optional[Dict[str, Any]]


@dataclass
class UserMlflowModelCandidate:
    name: str
    version: str
    source: str
    run_id: Optional[str]
    creation_timestamp: int


class TorchPyFuncWrapper(mlflow.pyfunc.PythonModel):
    def _load_source_module(self, source_path: Optional[str]):
        if not source_path:
            return None
        spec = importlib.util.spec_from_file_location("platform_user_model_module", source_path)
        if spec is None or spec.loader is None:
            return None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _rebuild_checkpoint(self, checkpoint: Dict[str, Any], source_path: Optional[str]):
        import torch
        module = self._load_source_module(source_path)
        if module is None:
            raise RuntimeError("PyTorch checkpoint requires the training source file to rebuild the model class.")
        class_name = checkpoint.get("architecture") or checkpoint.get("class_name") or checkpoint.get("model_class")
        if not class_name or not hasattr(module, str(class_name)):
            raise RuntimeError(f"Model class {class_name!r} was not found in the training source.")
        model_class = getattr(module, str(class_name))
        constructor_args = checkpoint.get("constructor_args") or {}
        number_of_classes = checkpoint.get("number_of_classes") or checkpoint.get("num_classes")
        attempts = []
        if isinstance(constructor_args, dict):
            attempts.append(((), constructor_args))
        if number_of_classes is not None:
            attempts.extend([
                ((int(number_of_classes),), {}),
                ((), {"number_of_classes": int(number_of_classes)}),
                ((), {"num_classes": int(number_of_classes)}),
                ((), {"n_classes": int(number_of_classes)}),
            ])
        attempts.append(((), {}))
        last_error = None
        model = None
        for args, kwargs in attempts:
            try:
                model = model_class(*args, **kwargs)
                break
            except Exception as exc:
                last_error = exc
        if model is None:
            raise RuntimeError(f"Could not instantiate {class_name}: {last_error}")
        state = checkpoint.get("model_state_dict") or checkpoint.get("state_dict")
        if state is None:
            raise RuntimeError("Checkpoint dictionary does not contain model_state_dict/state_dict.")
        model.load_state_dict(state)
        self.image_size = checkpoint.get("image_size")
        self.labels = checkpoint.get("labels") or []
        return model

    def load_context(self, context):
        try:
            import torch
        except ImportError as exc:
            raise RuntimeError("PyTorch is required to load .pt/.pth models") from exc
        loaded = torch.load(context.artifacts["model_path"], map_location="cpu")
        if isinstance(loaded, dict):
            loaded = self._rebuild_checkpoint(loaded, context.artifacts.get("source_file"))
        self.model = loaded
        if hasattr(self.model, "eval"):
            self.model.eval()

    def _prepare_input(self, model_input):
        if hasattr(model_input, "to_numpy"):
            return model_input.to_numpy()
        if isinstance(model_input, list):
            return model_input
        if hasattr(model_input, "values"):
            return model_input.values
        return model_input

    def predict(self, context, model_input):
        import numpy as np
        import torch
        input_data = self._prepare_input(model_input)
        if not isinstance(input_data, torch.Tensor):
            input_data = torch.tensor(np.asarray(input_data), dtype=torch.float32)
        if input_data.ndim == 1:
            input_data = input_data.unsqueeze(0)
        image_size = getattr(self, "image_size", None)
        if image_size and input_data.ndim == 2:
            size = int(image_size[0] if isinstance(image_size, (list, tuple)) else image_size)
            expected = 3 * size * size
            if input_data.shape[1] == expected:
                input_data = input_data.reshape(-1, 3, size, size)
        with torch.no_grad():
            output = self.model(input_data)
        if isinstance(output, torch.Tensor):
            return output.detach().cpu().numpy()
        return output


def prepare_outputs_dir(workspace: Path) -> Path:
    outputs_dir = workspace / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    return outputs_dir


def normalize_metric_name(name: str) -> str:
    name = name.strip().lower()

    aliases = {
        "acc": "accuracy",
        "f1": "f1_score",
        "r2_score": "r2",
        "roc_auc": "auc",
    }

    return aliases.get(name, name)


def extract_json_metrics(text: str) -> Dict[str, float]:
    """
    Extract metric dictionaries printed as JSON by user scripts.

    JSON values have priority over loose regex parsing because they are less
    ambiguous and avoid interpreting the "2" in "r2_score" as a metric value.
    """
    metrics: Dict[str, float] = {}
    decoder = json.JSONDecoder()
    allowed_metrics = {
        "loss",
        "val_loss",
        "accuracy",
        "val_accuracy",
        "precision",
        "recall",
        "f1_score",
        "rmse",
        "mse",
        "mae",
        "r2",
        "auc",
    }

    source = text or ""

    for index, character in enumerate(source):
        if character != "{":
            continue

        try:
            value, _ = decoder.raw_decode(source[index:])
        except Exception:
            continue

        if not isinstance(value, dict):
            continue

        for key, raw_value in value.items():
            if isinstance(raw_value, bool):
                continue

            if not isinstance(raw_value, (int, float)):
                continue

            normalized_key = normalize_metric_name(str(key))

            if normalized_key not in allowed_metrics:
                continue

            numeric_value = float(raw_value)

            if math.isfinite(numeric_value):
                metrics[normalized_key] = numeric_value

    return metrics


def parse_metrics(stdout: str) -> Dict[str, float]:
    """
    Parse metrics printed by heterogeneous user scripts.

    Priority:
    1. complete JSON dictionaries;
    2. explicit key=value or key:value lines;
    3. human-readable French aliases.
    """
    metrics: Dict[str, float] = {}
    source = stdout or ""

    for raw_line in source.splitlines():
        line = raw_line.strip()
        if not line:
            continue

        match = METRIC_REGEX.search(line)
        if match:
            key = normalize_metric_name(match.group("key"))
            try:
                value = float(match.group("value"))
                if math.isfinite(value):
                    metrics[key] = value
                    continue
            except ValueError:
                pass

        lower_line = (
            line.lower()
            .replace("é", "e")
            .replace("è", "e")
            .replace("ê", "e")
        )

        for alias, canonical in FRENCH_METRIC_ALIASES.items():
            normalized_alias = (
                alias.lower()
                .replace("é", "e")
                .replace("è", "e")
                .replace("ê", "e")
            )

            if normalized_alias not in lower_line:
                continue

            number_match = re.search(
                r"([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)",
                line,
            )

            if number_match:
                try:
                    value = float(number_match.group(1))
                    if math.isfinite(value):
                        metrics[normalize_metric_name(canonical)] = value
                except ValueError:
                    pass

            break

    metrics.update(extract_json_metrics(source))
    return metrics


def _clean_env_value(value: Optional[str]) -> str:
    value = str(value or "").strip()
    if value.lower() in {"", "none", "null", "undefined", "-"}:
        return ""
    return value


def _truthy_env(name: str, default: bool = True) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return str(raw).strip().lower() not in {"false", "0", "no", "off"}


def get_target_column_from_platform() -> str:
    """
    Colonne cible configurée côté plateforme.
    L'utilisateur n'a pas besoin de la coder dans son script.
    """
    return _clean_env_value(
        os.environ.get("TARGET_COLUMN")
        or os.environ.get("ML_TARGET_COLUMN")
        or os.environ.get("DATASET_TARGET_COLUMN")
    )


def get_project_type_from_platform() -> str:
    value = _clean_env_value(
        os.environ.get("PROJECT_TYPE")
        or os.environ.get("PROBLEM_TYPE")
        or os.environ.get("ML_PROBLEM_TYPE")
    ).lower().replace("-", "_").replace(" ", "_")

    if value in {"regression", "regressor"}:
        return "regression"

    if value in {"classification", "classifier", "image_classification", "sentiment", "sentiment_analysis"}:
        return "classification"

    return value


def get_platform_metric_priorities() -> List[str]:
    """
    Ordre de priorité choisi dans la plateforme.
    Le script utilisateur peut ne rien imprimer : la plateforme calcule les métriques
    compatibles si elle connaît target_column + dataset.
    """
    raw_values = [
        os.environ.get("PRIMARY_METRIC"),
        os.environ.get("PRIMARY_METRIC_1"),
        os.environ.get("PRIMARY_METRIC_2"),
        os.environ.get("PRIMARY_METRIC_3"),
        os.environ.get("ML_PRIMARY_METRIC"),
    ]

    priorities: List[str] = []
    for raw in raw_values:
        normalized = normalize_metric_name(_clean_env_value(raw))
        if normalized and normalized not in priorities:
            priorities.append(normalized)

    if priorities:
        return priorities

    project_type = get_project_type_from_platform()
    if project_type == "regression":
        return ["rmse", "mae", "r2", "mse"]

    return ["f1_score", "accuracy", "precision", "recall"]


def merge_metrics(base: Dict[str, float], extra: Dict[str, float], overwrite: bool = False) -> Dict[str, float]:
    merged: Dict[str, float] = dict(base or {})
    for key, value in (extra or {}).items():
        normalized = normalize_metric_name(str(key))
        if not normalized:
            continue
        try:
            numeric_value = float(value)
        except Exception:
            continue
        if overwrite or normalized not in merged:
            merged[normalized] = numeric_value
    return merged


def load_metrics_json(workspace: Path) -> Dict[str, float]:
    """
    Permet aux utilisateurs avancés de sauvegarder simplement un metrics.json,
    sans écrire du code MLflow.
    """
    candidates = [
        workspace / "metrics.json",
        workspace / "outputs" / "metrics.json",
        workspace / "artifacts" / "metrics.json",
        workspace / "model" / "metrics.json",
        Path("/opt/ml/model/metrics.json"),
        Path("/app/model/metrics.json"),
        Path("/app/outputs/metrics.json"),
        Path("/app/artifacts/metrics.json"),
    ]

    metrics: Dict[str, float] = {}

    for path in candidates:
        if not path.exists() or not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                continue
            parsed = {}
            for key, value in data.items():
                try:
                    parsed[normalize_metric_name(str(key))] = float(value)
                except Exception:
                    pass
            if parsed:
                print(f"[PLATFORM_METRICS] metrics loaded from {path}: {parsed}")
                metrics = merge_metrics(metrics, parsed)
        except Exception as exc:
            print(f"[PLATFORM_METRICS] could not read metrics file {path}: {exc}")

    return metrics


def find_dataset_for_auto_metrics(workspace: Path, dataset_path: Optional[Path]) -> Optional[Path]:
    candidates: List[Path] = []

    if dataset_path is not None:
        candidates.append(dataset_path)

    env_candidates = [
        os.environ.get("DATASET_PATH"),
        os.environ.get("DATA_PATH"),
        os.environ.get("MLOPS_DATASET_PATH"),
        os.environ.get("MLOPS_DATA_PATH"),
    ]

    for raw in env_candidates:
        cleaned = _clean_env_value(raw)
        if cleaned:
            candidates.append(Path(cleaned))

    candidates.extend([
        workspace / "dataset.csv",
        workspace / "data.csv",
        workspace / "train.csv",
        workspace / "input.csv",
        workspace / "data" / "dataset.csv",
        workspace / "data" / "iris.csv",
    ])

    for candidate in candidates:
        try:
            candidate = candidate.resolve()
            if candidate.exists() and candidate.is_file():
                return candidate
        except Exception:
            continue

    try:
        csv_files = sorted(workspace.rglob("*.csv"), key=lambda p: p.stat().st_size, reverse=True)
        for csv_file in csv_files:
            lower_name = csv_file.name.lower()
            if lower_name in {"metrics.csv", "predictions.csv"}:
                continue
            return csv_file.resolve()
    except Exception:
        pass

    return None


def infer_target_column(df) -> Optional[str]:
    configured = get_target_column_from_platform()
    if configured:
        if configured in df.columns:
            return configured
        print(f"[PLATFORM_METRICS] configured target column '{configured}' not found. Available columns={list(df.columns)}")
        return None

    if not _truthy_env("PLATFORM_AUTO_INFER_TARGET", True):
        return None

    common_targets = ["target", "label", "class", "y", "price", "output", "result"]
    for name in common_targets:
        if name in df.columns:
            print(f"[PLATFORM_METRICS] target column auto-inferred: {name}")
            return name

    if len(df.columns) >= 2:
        inferred = str(df.columns[-1])
        print(f"[PLATFORM_METRICS] target column auto-inferred as last column: {inferred}")
        return inferred

    return None


def infer_problem_type_from_data(y_true, metric_priorities: List[str]) -> str:
    project_type = get_project_type_from_platform()
    if project_type in {"regression", "classification"}:
        return project_type

    if any(metric in {"rmse", "mae", "mse", "r2"} for metric in metric_priorities):
        return "regression"

    if any(metric in {"accuracy", "precision", "recall", "f1_score", "f1"} for metric in metric_priorities):
        return "classification"

    try:
        unique_count = y_true.nunique(dropna=True)
        dtype_name = str(y_true.dtype).lower()
        is_numeric = any(token in dtype_name for token in ["int", "float", "double"])
        if is_numeric and unique_count > 20:
            return "regression"
    except Exception:
        pass

    return "classification"


def load_pickle_or_joblib(path: Path) -> Any:
    try:
        return joblib.load(path)
    except Exception:
        with path.open("rb") as file:
            return pickle.load(file)


def _object_feature_names(obj: Any) -> Optional[List[str]]:
    if obj is None:
        return None
    names = getattr(obj, "feature_names_in_", None)
    if names is None:
        return None
    try:
        return [str(name) for name in list(names)]
    except Exception:
        return None


def _align_dataframe_for_object(X, obj: Any):
    try:
        import pandas as pd
    except Exception:
        return X

    if not isinstance(X, pd.DataFrame):
        return X

    expected = _object_feature_names(obj)
    if not expected:
        return X

    missing = [column for column in expected if column not in X.columns]
    if missing:
        raise ValueError("Missing columns for automatic evaluation: " + ", ".join(missing[:20]))

    extra = [column for column in X.columns if column not in expected]
    if extra:
        print("[PLATFORM_METRICS] ignored extra columns: " + ", ".join(extra[:20]))

    return X[expected]


def _apply_transformer_if_present(transformer: Any, X, label: str):
    if transformer is None:
        return X
    if hasattr(transformer, "transform"):
        X = _align_dataframe_for_object(X, transformer)
        print(f"[PLATFORM_METRICS] applying {label}.transform()")
        return transformer.transform(X)
    if callable(transformer):
        print(f"[PLATFORM_METRICS] applying callable {label}")
        return transformer(X)
    return X


def predict_with_artifacts_for_metrics(model_path: Path, companion_artifacts: Dict[str, str], X):
    model = load_pickle_or_joblib(model_path)

    preprocessor = None
    vectorizer = None
    scaler = None

    if companion_artifacts.get("preprocessor_path"):
        preprocessor = load_pickle_or_joblib(Path(companion_artifacts["preprocessor_path"]))
    if companion_artifacts.get("vectorizer_path"):
        vectorizer = load_pickle_or_joblib(Path(companion_artifacts["vectorizer_path"]))
    if companion_artifacts.get("scaler_path"):
        scaler = load_pickle_or_joblib(Path(companion_artifacts["scaler_path"]))

    X_work = X.copy() if hasattr(X, "copy") else X

    try:
        X_work = _align_dataframe_for_object(X_work, model)
        return model.predict(X_work)
    except Exception as raw_error:
        print(f"[PLATFORM_METRICS] direct model.predict failed, trying companion artifacts: {raw_error}")

    X_work = X.copy() if hasattr(X, "copy") else X
    X_work = _apply_transformer_if_present(preprocessor, X_work, "preprocessor")
    X_work = _apply_transformer_if_present(vectorizer, X_work, "vectorizer")
    X_work = _apply_transformer_if_present(scaler, X_work, "scaler")

    if hasattr(model, "predict"):
        return model.predict(X_work)

    if callable(model):
        return model(X_work)

    raise ValueError("Model is neither callable nor has predict().")


def auto_evaluate_metrics_from_platform(
    workspace: Path,
    dataset_path: Optional[Path],
    primary_model_path: Optional[Path],
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Dict[str, float]:
    """
    Calcule automatiquement les métriques selon la configuration du projet.

    Objectif : l'utilisateur n'a pas besoin d'écrire accuracy/rmse dans son script.
    La plateforme utilise PROJECT_TYPE + TARGET_COLUMN + PRIMARY_METRIC_1/2/3.
    """
    if primary_model_path is None:
        return {}

    if primary_model_path.suffix.lower() not in {".pkl", ".joblib", ".pickle"}:
        print(f"[PLATFORM_METRICS] automatic metrics skipped for non pickle/joblib model: {primary_model_path}")
        return {}

    dataset_for_metrics = find_dataset_for_auto_metrics(workspace, dataset_path)
    if dataset_for_metrics is None:
        print("[PLATFORM_METRICS] automatic metrics skipped: dataset not found.")
        return {}

    try:
        import pandas as pd
        from sklearn.metrics import (
            accuracy_score,
            f1_score,
            mean_absolute_error,
            mean_squared_error,
            precision_score,
            r2_score,
            recall_score,
        )

        df = pd.read_csv(dataset_for_metrics)
        if df.empty:
            print("[PLATFORM_METRICS] automatic metrics skipped: dataset is empty.")
            return {}

        target_column = infer_target_column(df)
        if not target_column:
            print("[PLATFORM_METRICS] automatic metrics skipped: target column is not configured or inferable.")
            return {}

        X = df.drop(columns=[target_column])
        y_true = df[target_column]

        priorities = get_platform_metric_priorities()
        problem_type = infer_problem_type_from_data(y_true, priorities)

        y_pred = predict_with_artifacts_for_metrics(
            model_path=primary_model_path,
            companion_artifacts=companion_artifacts or {},
            X=X,
        )

        metrics: Dict[str, float] = {}
        if problem_type == "regression":
            mse = float(mean_squared_error(y_true, y_pred))
            metrics["mse"] = mse
            metrics["rmse"] = float(math.sqrt(max(mse, 0.0)))
            metrics["mae"] = float(mean_absolute_error(y_true, y_pred))
            metrics["r2"] = float(r2_score(y_true, y_pred))
        else:
            metrics["accuracy"] = float(accuracy_score(y_true, y_pred))
            metrics["precision"] = float(precision_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["recall"] = float(recall_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["f1_score"] = float(f1_score(y_true, y_pred, average="weighted", zero_division=0))

        print(
            "[PLATFORM_METRICS] automatic metrics computed. "
            f"dataset={dataset_for_metrics}, target={target_column}, problem_type={problem_type}, priorities={priorities}"
        )
        for name, value in metrics.items():
            print(f"[PLATFORM_METRIC_AUTO] {name}={value}")

        return metrics

    except Exception as exc:
        print(f"[PLATFORM_METRICS] automatic metrics failed: {exc}")
        return {}



AUXILIARY_ARTIFACT_KEYWORDS = {
    "scaler",
    "standardscaler",
    "minmaxscaler",
    "encoder",
    "label_encoder",
    "onehot",
    "preprocessor",
    "preprocess",
    "vectorizer",
    "tokenizer",
    "classes",
    "metadata",
    "metrics",
    "report",
}


def is_auxiliary_model_artifact(path: Path) -> bool:
    name = path.stem.lower()
    full_name = path.name.lower()

    if full_name in {"model.pkl", "model.joblib", "model.pickle"}:
        return False

    return any(keyword in name for keyword in AUXILIARY_ARTIFACT_KEYWORDS)


def model_artifact_score(path: Path) -> int:
    """
    Score élevé = meilleur candidat pour le modèle principal.
    On favorise outputs/model.* puis artifacts/model.* et on pénalise les scalers.
    """
    lower_parts = [part.lower() for part in path.parts]
    name = path.name.lower()
    stem = path.stem.lower()

    score = 0

    normalized_path = str(path).replace("\\", "/").lower()

    if normalized_path.startswith("/opt/ml/model/"):
        score += 5000

    if "outputs" in lower_parts:
        score += 1000

    if "artifacts" in lower_parts:
        score += 700

    if name in {"model.pkl", "model.joblib", "model.pickle"}:
        score += 1000

    if stem == "model":
        score += 500

    if "model" in stem:
        score += 250

    if is_auxiliary_model_artifact(path):
        score -= 2000

    try:
        score += int(path.stat().st_mtime)
    except Exception:
        pass

    return score


def get_model_search_roots(workspace: Path) -> List[Path]:
    """
    Return every supported location where user scripts commonly save models.

    The most important AKS-compatible location is /opt/ml/model.
    """
    configured_dirs = [
        os.environ.get("MODEL_OUTPUT_DIR"),
        os.environ.get("ML_OUTPUT_DIR"),
        os.environ.get("OUTPUT_DIR"),
        os.environ.get("MLOPS_DEFAULT_OUTPUT_DIR"),
    ]

    roots: List[Path] = [
        Path("/opt/ml/model"),
        workspace / "outputs",
        workspace / "output",
        workspace / "artifacts",
        workspace / "artifact",
        workspace / "model",
        workspace / "models",
        workspace / "saved_model",
        workspace / "checkpoints",
        workspace,
        Path("/app/model"),
        Path("/app/models"),
        Path("/app/outputs"),
        Path("/app/output"),
        Path("/app/artifacts"),
        Path("/app/artifact"),
        Path("/app/saved_model"),
        Path("/app/checkpoints"),
    ]

    accepted_dirs = _clean_env_value(
        os.environ.get("MLOPS_ACCEPTED_MODEL_DIRS")
    )

    if accepted_dirs:
        roots.extend(
            Path(item.strip())
            for item in accepted_dirs.split(",")
            if item.strip()
        )

    roots.extend(
        Path(value.strip())
        for value in configured_dirs
        if value and str(value).strip()
    )

    unique_roots: List[Path] = []
    seen: set[str] = set()

    for root in roots:
        try:
            resolved = root.expanduser().resolve()
        except Exception:
            resolved = root.expanduser().absolute()

        key = str(resolved)

        if key in seen:
            continue

        seen.add(key)
        unique_roots.append(resolved)

    return unique_roots


def find_model_artifacts(workspace: Path) -> List[Path]:
    """
    Discover predictive model files created by the user script.

    Custom Docker scripts frequently save their final model to /opt/ml/model,
    which is outside /workspace.
    """
    ignored_dirs = {
        ".git",
        "__pycache__",
        "mlruns",
        ".venv",
        "venv",
        "env",
        ".ipynb_checkpoints",
    }

    discovered: List[Path] = []
    seen: set[str] = set()
    search_roots = get_model_search_roots(workspace)

    print(
        "[PLATFORM_MODEL] Search roots="
        + str([str(path) for path in search_roots])
    )

    for root in search_roots:
        try:
            if not root.exists():
                continue

            candidates = [root] if root.is_file() else list(root.rglob("*"))

            for path in candidates:
                try:
                    if not path.is_file():
                        continue

                    if path.suffix.lower() not in SUPPORTED_MODEL_EXTENSIONS:
                        continue

                    if any(part in ignored_dirs for part in path.parts):
                        continue

                    resolved = path.resolve()
                    key = str(resolved)

                    if key in seen:
                        continue

                    seen.add(key)
                    discovered.append(resolved)

                except Exception as exc:
                    print(
                        "[PLATFORM_MODEL] Could not inspect candidate "
                        f"{path}: {exc}"
                    )

        except Exception as exc:
            print(
                "[PLATFORM_MODEL] Could not scan model root "
                f"{root}: {exc}"
            )

    ordered = sorted(
        discovered,
        key=lambda path: model_artifact_score(path),
        reverse=True,
    )

    print(
        "[PLATFORM_MODEL] Discovered model artifacts="
        + str([str(path) for path in ordered])
    )

    return ordered


def select_primary_model_artifact(model_artifacts: List[Path]) -> Optional[Path]:
    for path in model_artifacts:
        if not is_auxiliary_model_artifact(path):
            return path

    return model_artifacts[0] if model_artifacts else None


def find_companion_artifacts(
    primary_model_path: Path,
    model_artifacts: List[Path],
) -> Dict[str, str]:
    """
    Trouve les artefacts nécessaires à la prédiction :
    scaler, preprocessor, vectorizer, encoder...
    """
    companions: Dict[str, str] = {}

    for path in model_artifacts:
        if path == primary_model_path:
            continue

        name = path.stem.lower()

        if "scaler" in name and "scaler_path" not in companions:
            companions["scaler_path"] = str(path)
        elif ("preprocess" in name or "preprocessor" in name) and "preprocessor_path" not in companions:
            companions["preprocessor_path"] = str(path)
        elif ("vectorizer" in name or "tfidf" in name) and "vectorizer_path" not in companions:
            companions["vectorizer_path"] = str(path)
        elif ("encoder" in name or "label_encoder" in name) and "encoder_path" not in companions:
            companions["encoder_path"] = str(path)

    return companions



def resolve_platform_registered_model_name(
    experiment_name: str,
    explicit_registered_model_name: Optional[str] = None,
) -> str:
    """
    Nom officiel du modèle attendu par la plateforme.
    Priorité :
    1. argument --registered-model-name
    2. variable PLATFORM_REGISTERED_MODEL_NAME
    3. variable MLFLOW_REGISTERED_MODEL_NAME
    4. variable REGISTERED_MODEL_NAME
    5. experiment_name
    """
    if explicit_registered_model_name and explicit_registered_model_name.strip():
        return explicit_registered_model_name.strip()

    env_name = (
        os.environ.get("PLATFORM_REGISTERED_MODEL_NAME")
        or os.environ.get("MLFLOW_REGISTERED_MODEL_NAME")
        or os.environ.get("REGISTERED_MODEL_NAME")
    )

    if env_name and env_name.strip():
        return env_name.strip()

    return experiment_name.strip() if experiment_name else "platform_model"


def find_user_created_mlflow_model(
    client: MlflowClient,
    start_time_ms: int,
    end_time_ms: int,
    platform_registered_model_name: str,
) -> Optional[UserMlflowModelCandidate]:
    """
    Si le script utilisateur a déjà créé un modèle dans MLflow avec son propre nom,
    cette fonction cherche une version créée pendant l'exécution du script.
    """
    try:
        candidates: List[UserMlflowModelCandidate] = []

        registered_models = client.search_registered_models()

        for registered_model in registered_models:
            model_name = registered_model.name

            if not model_name:
                continue

            # On ignore le modèle officiel de la plateforme.
            # Ici on cherche surtout les modèles créés par le script user.
            if model_name == platform_registered_model_name:
                continue

            try:
                versions = client.search_model_versions(f"name = '{model_name}'")
            except Exception as exc:
                print(
                    "[PLATFORM_MODEL] Could not search versions for model "
                    f"{model_name}: {exc}"
                )
                continue

            for version in versions:
                creation_timestamp = int(
                    getattr(version, "creation_timestamp", 0) or 0
                )

                # Marge de sécurité de 2 minutes pour éviter les petits décalages de temps.
                if creation_timestamp < start_time_ms - 120_000:
                    continue

                if creation_timestamp > end_time_ms + 120_000:
                    continue

                source = getattr(version, "source", None)
                version_number = getattr(version, "version", None)
                run_id = getattr(version, "run_id", None)

                if not source or not version_number:
                    continue

                candidates.append(
                    UserMlflowModelCandidate(
                        name=model_name,
                        version=str(version_number),
                        source=str(source),
                        run_id=str(run_id) if run_id else None,
                        creation_timestamp=creation_timestamp,
                    )
                )

        if not candidates:
            return None

        candidates.sort(key=lambda item: item.creation_timestamp, reverse=True)
        return candidates[0]

    except Exception as exc:
        print(f"[PLATFORM_MODEL] Could not search user-created MLflow models: {exc}")
        return None



def has_useful_training_metrics(metrics: Dict[str, float]) -> bool:
    """
    Une métrique technique comme return_code ne suffit pas à valider un modèle.
    """
    if not metrics:
        return False

    return any(
        key
        and normalize_metric_name(str(key)) != "return_code"
        and value is not None
        and math.isfinite(float(value))
        for key, value in metrics.items()
    )


def is_successful_registration_candidate(
    result: subprocess.CompletedProcess,
    metrics: Dict[str, float],
    model_artifacts: List[Path],
) -> bool:
    """
    Règle centrale de versioning.

    Une ModelVersion MLflow officielle n'est créée que si :
    - le script utilisateur retourne 0 ;
    - au moins un artefact modèle a été trouvé ;
    - au moins une métrique métier valide est disponible.

    Ainsi, les runs FAILED/CANCELLED ne consomment aucune version officielle.
    """
    if result is None or int(result.returncode) != 0:
        return False

    if not model_artifacts:
        return False

    if not has_useful_training_metrics(metrics):
        return False

    return True


def find_existing_model_version_for_run(
    client: MlflowClient,
    registered_model_name: str,
    run_id: str,
) -> Optional[str]:
    """
    Rend l'enregistrement idempotent :
    deux passages du runner sur le même run ne créent pas deux versions.
    """
    name = _clean_env_value(registered_model_name)
    clean_run_id = _clean_env_value(run_id)

    if not name or not clean_run_id:
        return None

    try:
        versions = client.search_model_versions(
            f"name = '{name.replace(chr(39), chr(92) + chr(39))}'"
        )
    except Exception as exc:
        print(
            "[PLATFORM_MODEL] Could not check existing versions for "
            f"{name}: {exc}"
        )
        return None

    for version in versions:
        version_run_id = _clean_env_value(getattr(version, "run_id", None))
        if version_run_id and version_run_id == clean_run_id:
            existing = _clean_env_value(getattr(version, "version", None))
            if existing:
                return existing

    return None


def register_platform_model_once(
    model_uri: str,
    registered_model_name: str,
    platform_run_id: str,
) -> str:
    """
    Crée au maximum une ModelVersion officielle pour un run réussi.
    """
    client = MlflowClient()

    existing_version = find_existing_model_version_for_run(
        client=client,
        registered_model_name=registered_model_name,
        run_id=platform_run_id,
    )

    if existing_version:
        print(
            "[PLATFORM_MODEL] Existing official model version reused: "
            f"{registered_model_name} v{existing_version}, run_id={platform_run_id}"
        )
        return existing_version

    registered = mlflow.register_model(
        model_uri=model_uri,
        name=registered_model_name,
    )

    return str(registered.version)


def finalize_platform_run(
    run_id: str,
    success: bool,
    reason: Optional[str] = None,
) -> None:
    """
    Aligne le statut MLflow du run avec le statut réel de la plateforme.
    """
    clean_run_id = _clean_env_value(run_id)
    if not clean_run_id:
        return

    client = MlflowClient()
    final_status = "FINISHED" if success else "FAILED"
    validation_status = "PASSED" if success else "FAILED"

    try:
        client.set_tag(clean_run_id, "validation_status", validation_status)
        client.set_tag(clean_run_id, "platform_final_status", final_status)
        client.set_tag(
            clean_run_id,
            "platform_registration_eligible",
            str(bool(success)).lower(),
        )

        if reason:
            client.set_tag(
                clean_run_id,
                "platform_failure_reason",
                str(reason)[:5000],
            )

        client.set_terminated(clean_run_id, status=final_status)

    except Exception as exc:
        print(
            "[PLATFORM] Could not finalize MLflow run status "
            f"for {clean_run_id}: {exc}"
        )


def tag_registered_version_as_candidate(
    registered_model_name: str,
    model_version: str,
    platform_run_id: str,
    platform_experiment_id: str,
    source_type: str,
) -> None:
    """Mark a newly registered version as a candidate only.

    The runner never assigns the ``production`` alias. Promotion belongs to the
    backend after metric validation and comparison with the current production
    version. Keeping this responsibility in one place avoids divergent states
    between MLflow and MySQL.
    """
    name = _clean_env_value(registered_model_name)
    version = _clean_env_value(model_version)
    if not name or not version:
        raise ValueError("registered model name and model version are required")

    client = MlflowClient()
    tags = {
        "platform_run_id": str(platform_run_id),
        "platform_experiment_id": str(platform_experiment_id),
        "validation_status": "PENDING",
        "promotion_owner": "BACKEND",
        "platform_model_source_type": source_type,
    }
    for key, value in tags.items():
        client.set_model_version_tag(
            name=name,
            version=version,
            key=key,
            value=str(value),
        )

    model_uri = f"models:/{name}/{version}"
    mlflow.set_tag("model_version", version)
    mlflow.set_tag("registered_model_version", version)
    mlflow.set_tag("platform_model_registered", "true")
    mlflow.set_tag("validation_status", "PENDING")
    mlflow.set_tag("promotion_owner", "BACKEND")
    mlflow.set_tag("platform_model_uri", model_uri)

    # Stable machine-readable markers consumed by the Java backend and useful
    # when diagnosing a Kubernetes Job from its logs.
    print(f"registered_model_name={name}")
    print(f"model_version={version}")
    print(f"candidate_version={version}")
    print("validation_status=PENDING")
    print("promotion_owner=BACKEND")
    print(f"model_uri={model_uri}")
    print(
        "[PLATFORM_MODEL] Version registered as PENDING candidate. "
        "Production promotion is delegated to the backend."
    )


def register_user_mlflow_model_as_platform_model(
    candidate: UserMlflowModelCandidate,
    platform_registered_model_name: str,
    platform_run_id: str,
    platform_experiment_id: str,
) -> Optional[Dict[str, str]]:
    """Re-register a model created by user MLflow code under the platform name.

    This path follows exactly the same candidate lifecycle as locally detected
    model files: register, tag PENDING, and let the backend promote it.
    """
    try:
        source_uri = f"models:/{candidate.name}/{candidate.version}"
        print(
            "[PLATFORM_MODEL] User MLflow model detected: "
            f"{candidate.name} v{candidate.version}, source={candidate.source}"
        )

        version = register_platform_model_once(
            model_uri=source_uri,
            registered_model_name=platform_registered_model_name,
            platform_run_id=platform_run_id,
        )
        platform_uri = f"models:/{platform_registered_model_name}/{version}"

        mlflow.log_param("platform_model_source_type", "USER_MLFLOW_MODEL")
        mlflow.log_param("user_model_name", candidate.name)
        mlflow.log_param("user_model_version", candidate.version)
        mlflow.log_param("user_model_run_id", candidate.run_id or "")
        mlflow.log_param("platform_registered_model_name", platform_registered_model_name)
        mlflow.log_param("platform_model_uri", platform_uri)
        mlflow.log_param("platform_model_version", version)

        tag_registered_version_as_candidate(
            registered_model_name=platform_registered_model_name,
            model_version=version,
            platform_run_id=platform_run_id,
            platform_experiment_id=platform_experiment_id,
            source_type="USER_MLFLOW_MODEL",
        )

        print(
            "[PLATFORM_MODEL] User model re-registered successfully as candidate: "
            f"{platform_registered_model_name} v{version}"
        )
        return {"uri": platform_uri, "version": version}

    except Exception as exc:
        print(
            "[PLATFORM_MODEL] Failed to re-register user MLflow model "
            f"{candidate.name} v{candidate.version} as "
            f"{platform_registered_model_name}: {exc}"
        )
        return None


def format_error_info(stderr: str) -> Optional[Dict[str, Any]]:
    if not stderr:
        return None

    stderr_lower = stderr.lower()

    if "the following arguments are required" in stderr_lower:
        return {
            "phase": "USER_SCRIPT",
            "cause": "argument obligatoire manquant",
            "message": "Le script attend un argument obligatoire, par exemple --data_path.",
            "suggestion": (
                "La plateforme a essayé plusieurs formats d'exécution. "
                "Vérifiez que le script accepte --data_path ou qu'il peut lire dataset.csv."
            ),
            "details": stderr.strip().splitlines()[-5:],
        }

    if "unrecognized arguments" in stderr_lower:
        return {
            "phase": "USER_SCRIPT",
            "cause": "arguments non reconnus",
            "message": "Le script ne reconnaît pas certains arguments passés par la plateforme.",
            "suggestion": (
                "La plateforme essaie automatiquement plusieurs formats. "
                "Si l'erreur persiste, vérifiez argparse dans train.py."
            ),
            "details": stderr.strip().splitlines()[-5:],
        }

    if "modulenotfounderror" in stderr_lower:
        match = re.search(r"No module named ['\"](?P<module>[^'\"]+)['\"]", stderr)
        module = match.group("module") if match else None

        return {
            "phase": "USER_SCRIPT",
            "cause": "dépendance manquante",
            "module": module,
            "message": "Le script a échoué car une dépendance est manquante.",
            "suggestion": (
                "Si vous êtes en Runtime standard : utilisez Dockerfile personnalisé. "
                "Sinon, ajoutez ce module dans requirements.txt ou dans l'image standard."
            ),
            "details": stderr.strip().splitlines()[-5:],
        }

    if "syntaxerror" in stderr_lower:
        return {
            "phase": "USER_SCRIPT",
            "cause": "erreur de syntaxe",
            "message": "Erreur de syntaxe détectée dans train.py.",
            "suggestion": "Corrigez la syntaxe du script Python.",
            "details": stderr.strip().splitlines()[-5:],
        }

    if "no such file or directory" in stderr_lower or "filenotfounderror" in stderr_lower:
        return {
            "phase": "USER_SCRIPT",
            "cause": "fichier introuvable",
            "message": "Un fichier attendu est manquant ou le chemin est incorrect.",
            "suggestion": (
                "Vérifiez le chemin du dataset et des fichiers utilisés dans le script. "
                "La plateforme crée automatiquement dataset.csv, data.csv, train.csv et input.csv."
            ),
            "details": stderr.strip().splitlines()[-5:],
        }

    return {
        "phase": "USER_SCRIPT",
        "cause": "erreur inconnue",
        "message": "Le script a échoué pendant son exécution.",
        "details": stderr.strip().splitlines()[-5:],
    }



USER_MLFLOW_FAILURE_PATTERNS = (
    "mlflow registration failed",
    "mlflow.exceptions.mlflowexception",
    "mlflow.pyfunc.log_model",
    "mlflow.sklearn.log_model",
    "mlflow registration",
    "expected examples to be list",
    "input_example",
    "registered model",
    "set_registered_model_alias",
    "register_pyfunc_model",
)


def is_recoverable_user_mlflow_error(stderr: str, stdout: str) -> bool:
    """
    Détecte les cas où le script utilisateur a bien entraîné/sauvegardé un modèle,
    mais a échoué uniquement parce qu'il a essayé de gérer MLflow lui-même.

    Dans ce cas, la plateforme peut récupérer le modèle local, le logger, l'enregistrer
    dans MLflow Registry et considérer le run comme réussi avec warning.
    """
    text = f"{stdout or ''}\n{stderr or ''}".lower()
    return any(pattern in text for pattern in USER_MLFLOW_FAILURE_PATTERNS)


def build_recovered_mlflow_warning(
    result: subprocess.CompletedProcess,
    model_artifacts: List[Path],
) -> Dict[str, Any]:
    return {
        "phase": "USER_SCRIPT_MLFLOW_RECOVERY",
        "cause": "user_mlflow_registration_failed_but_model_was_found",
        "message": (
            "Le script utilisateur a échoué pendant sa propre logique MLflow, "
            "mais la plateforme a trouvé un modèle local et peut assurer "
            "l'enregistrement MLflow automatiquement."
        ),
        "original_return_code": result.returncode,
        "model_artifacts": [str(path) for path in model_artifacts],
        "suggestion": (
            "L'utilisateur n'a pas besoin d'écrire du code MLflow. "
            "Il suffit de sauvegarder un modèle dans outputs/model.pkl, "
            "outputs/model.joblib ou un format supporté."
        ),
    }


ARCHIVE_EXTENSIONS = {".zip", ".tar", ".tgz", ".gz"}


def _safe_extract_zip(archive_path: Path, destination: Path) -> None:
    destination = destination.resolve()
    with zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination):
                raise ValueError(f"Unsafe archive entry: {entry.filename}")
        archive.extractall(destination)


def _safe_extract_tar(archive_path: Path, destination: Path) -> None:
    destination = destination.resolve()
    with tarfile.open(archive_path) as archive:
        for member in archive.getmembers():
            target = (destination / member.name).resolve()
            if not target.is_relative_to(destination):
                raise ValueError(f"Unsafe archive entry: {member.name}")
        archive.extractall(destination)


def materialize_dataset_source(workspace: Path, dataset_path: Optional[Path]) -> Optional[Path]:
    """Return a usable file or directory for CSV, ZIP, TAR/TGZ and folder datasets."""
    if dataset_path is None:
        return None
    source = dataset_path.resolve()
    if not source.exists():
        raise FileNotFoundError(f"Dataset not found: {source}")
    if source.is_dir():
        return source

    lower_name = source.name.lower()
    if lower_name.endswith('.zip'):
        target = workspace / '.platform_dataset'
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True, exist_ok=True)
        _safe_extract_zip(source, target)
        children = [item for item in target.iterdir() if item.name != '__MACOSX']
        return children[0] if len(children) == 1 and children[0].is_dir() else target

    if lower_name.endswith(('.tar', '.tar.gz', '.tgz')):
        target = workspace / '.platform_dataset'
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True, exist_ok=True)
        _safe_extract_tar(source, target)
        children = [item for item in target.iterdir()]
        return children[0] if len(children) == 1 and children[0].is_dir() else target
    return source


def materialize_dataset_alias(source: Path, destination: Path) -> None:
    source = source.resolve()
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() or destination.is_symlink():
        return
    try:
        destination.symlink_to(source, target_is_directory=source.is_dir())
    except Exception:
        if source.is_dir():
            shutil.copytree(source, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(source, destination)


def extract_hardcoded_dataset_paths(script_path: Optional[Path]) -> List[str]:
    """
    Détecte les chemins de datasets écrits en dur dans les scripts utilisateurs.

    Exemple supporté :
    DATA_PATH = os.path.join("data", "iris.csv")
    pd.read_csv("data/iris.csv")
    pd.read_csv('iris.csv')
    """
    if script_path is None:
        return []

    try:
        code = script_path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return []

    candidates: List[str] = []

    # pd.read_csv("...csv"), open("...csv"), variable = "...csv"
    for match in re.finditer(
        r"""["'](?P<path>[^"']+\.(?:csv|tsv|xlsx|xls|parquet|json))["']""",
        code,
        re.IGNORECASE,
    ):
        value = match.group("path").strip()
        if value and not value.startswith(("http://", "https://", "s3://", "wasbs://")):
            candidates.append(value)

    # os.path.join("data", "iris.csv")
    for match in re.finditer(
        r"""os\.path\.join\(\s*["'](?P<dir>[^"']+)["']\s*,\s*["'](?P<file>[^"']+\.(?:csv|tsv|xlsx|xls|parquet|json))["']\s*\)""",
        code,
        re.IGNORECASE,
    ):
        candidates.append(str(Path(match.group("dir")) / match.group("file")))

    # Dédupliquer en gardant l'ordre.
    unique: List[str] = []
    seen = set()
    for candidate in candidates:
        normalized = candidate.replace("\\", "/").strip("/")
        if normalized and normalized not in seen:
            seen.add(normalized)
            unique.append(normalized)

    return unique


def create_dataset_aliases(
    workspace: Path,
    dataset_path: Optional[Path],
    script_path: Optional[Path] = None,
) -> Optional[Path]:
    """Expose uploaded CSV/files, archives and complete dataset folders under common paths."""
    if dataset_path is None:
        return None

    workspace = workspace.resolve()
    source = materialize_dataset_source(workspace, dataset_path)
    if source is None:
        return None

    if source.is_dir():
        aliases = ["data", "dataset", "datasets", "input_data"]
        for alias in aliases:
            materialize_dataset_alias(source, workspace / alias)
        print(f"[PLATFORM_DATASET] Directory dataset ready: source={source}")
        return source

    original_name = source.name or "dataset.csv"
    aliases = [
        original_name, "dataset.csv", "data.csv", "train.csv", "input.csv",
        f"data/{original_name}", "data/dataset.csv", "data/data.csv", "data/train.csv",
        f"datasets/{original_name}", "datasets/dataset.csv",
    ]
    aliases.extend(extract_hardcoded_dataset_paths(script_path))
    seen = set()
    for alias in aliases:
        normalized = str(alias or '').replace('\\', '/').strip()
        if not normalized or normalized.startswith('/') or '..' in Path(normalized).parts:
            continue
        if normalized in seen:
            continue
        seen.add(normalized)
        try:
            materialize_dataset_alias(source, workspace / normalized)
            print(f"[PLATFORM_DATASET] Alias created: {workspace / normalized}")
        except Exception as exc:
            print(f"[PLATFORM_DATASET] Could not create alias {normalized}: {exc}")
    return source

def build_script_commands(
    script_path: Path,
    dataset_path: Optional[Path],
    output_dir: Path,
) -> List[List[str]]:
    commands: List[List[str]] = []

    script = str(script_path)

    if dataset_path is not None:
        data = str(dataset_path)
        out = str(output_dir)

        commands.extend(
            [
                [
                    sys.executable,
                    script,
                    "--data_path",
                    data,
                    "--output_dir",
                    out,
                ],
                [
                    sys.executable,
                    script,
                    "--data-path",
                    data,
                    "--output-dir",
                    out,
                ],
                [
                    sys.executable,
                    script,
                    "--dataset_path",
                    data,
                    "--output_dir",
                    out,
                ],
                [
                    sys.executable,
                    script,
                    "--dataset",
                    data,
                    "--output_dir",
                    out,
                ],
                [
                    sys.executable,
                    script,
                    "--input",
                    data,
                    "--output",
                    out,
                ],
                [
                    sys.executable,
                    script,
                    data,
                    out,
                ],
            ]
        )

    commands.append([sys.executable, script])

    return commands


def is_argument_error(stderr: str) -> bool:
    stderr_lower = (stderr or "").lower()

    return (
        "usage:" in stderr_lower
        or "the following arguments are required" in stderr_lower
        or "unrecognized arguments" in stderr_lower
        or "error: argument" in stderr_lower
    )


def run_user_script(
    workspace: Path,
    entrypoint: str,
    dataset_path: Optional[Path],
    platform_run_id: Optional[str] = None,
    platform_experiment_id: Optional[str] = None,
) -> subprocess.CompletedProcess:
    workspace = workspace.resolve()
    script_path = workspace / entrypoint

    if not script_path.exists():
        raise FileNotFoundError(f"Training script not found: {script_path}")

    output_dir = prepare_outputs_dir(workspace)

    if dataset_path is not None:
        dataset_path = dataset_path.resolve()

    dataset_path = create_dataset_aliases(
        workspace, dataset_path, script_path=script_path
    )

    env = {
        **os.environ,
        "OUTPUT_DIR": str(output_dir),
        "MLOPS_OUTPUT_DIR": str(output_dir),
        "WORKSPACE_DIR": str(workspace),
        "PLATFORM_OUTPUT_DIR": str(output_dir),
        "PLATFORM_MANAGED_MLFLOW": "true",
        "PLATFORM_ENTRYPOINT": entrypoint,
    }
    if platform_run_id:
        env["PLATFORM_MLFLOW_RUN_ID"] = str(platform_run_id)
        env["MLFLOW_RUN_ID"] = str(platform_run_id)
    if platform_experiment_id:
        env["PLATFORM_MLFLOW_EXPERIMENT_ID"] = str(platform_experiment_id)
        env["MLFLOW_EXPERIMENT_ID"] = str(platform_experiment_id)

    if dataset_path is not None:
        env.update(
            {
                "DATA_PATH": str(dataset_path),
                "DATASET_PATH": str(dataset_path),
                "MLOPS_DATA_PATH": str(dataset_path),
                "MLOPS_DATASET_PATH": str(dataset_path),
            }
        )

    commands = build_script_commands(
        script_path=script_path,
        dataset_path=dataset_path,
        output_dir=output_dir,
    )

    last_result: Optional[subprocess.CompletedProcess] = None

    for index, command in enumerate(commands, start=1):
        print(f"[PLATFORM] Trying command {index}/{len(commands)}: {' '.join(command)}")

        timeout_seconds = int(os.environ.get("PLATFORM_SCRIPT_TIMEOUT_SECONDS", "7200"))
        try:
            result = subprocess.run(
                command,
                cwd=str(workspace),
                capture_output=True,
                text=True,
                env=env,
                timeout=timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = (exc.stderr or "") + f"\nTraining timed out after {timeout_seconds} seconds."
            result = subprocess.CompletedProcess(
                args=command,
                returncode=124,
                stdout=stdout,
                stderr=stderr,
            )

        last_result = result

        print(f"[PLATFORM] Command return_code={result.returncode}")

        if result.stdout:
            print("[PLATFORM] User script stdout:")
            print(result.stdout)

        if result.stderr:
            print("[PLATFORM] User script stderr:")
            print(result.stderr)

        if result.returncode == 0:
            print("[PLATFORM] User script executed successfully.")
            return result

        if is_argument_error(result.stderr):
            print("[PLATFORM] Argument mismatch detected. Trying another command format...")
            continue

        print("[PLATFORM] Script failed with a real execution error. Stop retrying.")
        return result

    if last_result is None:
        raise RuntimeError("No command was executed for user script.")

    return last_result


def try_log_pickle_model(
    model_path: Path,
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Optional[str]:
    companion_artifacts = companion_artifacts or {}

    # Si le modèle a un scaler/preprocessor séparé, il faut absolument utiliser
    # un wrapper pyfunc. Sinon la prédiction servirait un modèle incomplet.
    if companion_artifacts:
        try:
            artifacts = {"model_path": str(model_path)}
            artifacts.update(companion_artifacts)

            mlflow.pyfunc.log_model(
                artifact_path="model",
                python_model=GenericPyFuncWrapper(),
                artifacts=artifacts,
            )
            return mlflow.get_artifact_uri("model")
        except Exception as exc:
            print(
                "[PLATFORM_MODEL] pyfunc log_model with companion artifacts failed "
                f"for {model_path}: {exc}"
            )

    # Cas simple : pipeline sklearn complet ou modèle sklearn standalone.
    try:
        model = joblib.load(model_path)

        if hasattr(model, "predict"):
            mlflow.sklearn.log_model(model, artifact_path="model")
            return mlflow.get_artifact_uri("model")
    except Exception as first_exc:
        print(f"[PLATFORM_MODEL] sklearn log_model failed for {model_path}: {first_exc}")

    # Fallback générique.
    try:
        with open(model_path, "rb") as file:
            pickle.load(file)

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=GenericPyFuncWrapper(),
            artifacts={"model_path": str(model_path)},
        )
        return mlflow.get_artifact_uri("model")
    except Exception as second_exc:
        print(
            f"[PLATFORM_MODEL] generic pyfunc log_model failed for "
            f"{model_path}: {second_exc}"
        )
        return None


def _find_training_source(model_path: Path) -> Optional[Path]:
    entrypoint = _clean_env_value(os.environ.get("PLATFORM_ENTRYPOINT"))
    candidates = []
    if entrypoint:
        candidates.append(Path(os.environ.get("WORKSPACE_DIR", ".")) / entrypoint)
    for parent in [model_path.parent, *model_path.parents[:4]]:
        candidates.extend([parent / "train.py", parent / "training.py", parent / "main.py"])
    for candidate in candidates:
        if candidate.exists() and candidate.is_file():
            return candidate.resolve()
    return None


def try_log_pytorch_model(model_path: Path) -> Optional[str]:
    try:
        artifacts = {"model_path": str(model_path)}
        source_file = _find_training_source(model_path)
        if source_file:
            artifacts["source_file"] = str(source_file)
        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=TorchPyFuncWrapper(),
            artifacts=artifacts,
            pip_requirements=["mlflow", "torch", "numpy", "pandas"],
        )
        # Validate now, not only during deployment.
        model_uri = mlflow.get_artifact_uri("model")
        mlflow.pyfunc.load_model(model_uri)
        return model_uri
    except Exception as exc:
        print(f"[PLATFORM_MODEL] pytorch pyfunc log/load failed for {model_path}: {exc}")
        return None


def log_model_artifact(
    model_path: Path,
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    extension = model_path.suffix.lower()
    result: Dict[str, Any] = {
        "path": str(model_path),
        "extension": extension,
        "logged_model_uri": None,
        "artifact_only": False,
        "companion_artifacts": companion_artifacts or {},
    }

    if extension in {".pkl", ".joblib", ".pickle"}:
        logged_uri = try_log_pickle_model(model_path, companion_artifacts)
        if logged_uri:
            result["logged_model_uri"] = logged_uri
            return result

    if extension in {".pt", ".pth"}:
        logged_uri = try_log_pytorch_model(model_path)
        if logged_uri:
            result["logged_model_uri"] = logged_uri
            return result

    mlflow.log_artifact(str(model_path), artifact_path="model_artifacts")
    result["artifact_only"] = True
    return result




def build_model_manifest(
    primary_model_path: Path,
    companion_artifacts: Dict[str, str],
    metrics: Dict[str, float],
    workspace: Path,
) -> Dict[str, Any]:
    """Build a portable manifest used by serving and audit components."""
    def relative_or_absolute(path_value: str) -> str:
        path = Path(path_value).resolve()
        try:
            return str(path.relative_to(workspace.resolve()))
        except Exception:
            return str(path)

    return {
        "schema_version": 1,
        "primary_model": relative_or_absolute(str(primary_model_path)),
        "primary_model_extension": primary_model_path.suffix.lower(),
        "companion_artifacts": {
            key: relative_or_absolute(value)
            for key, value in (companion_artifacts or {}).items()
        },
        "metrics": {
            normalize_metric_name(str(key)): float(value)
            for key, value in (metrics or {}).items()
            if key is not None and value is not None
        },
        "runtime_mode": _clean_env_value(os.environ.get("RUNTIME_MODE")) or "CUSTOM_DOCKERFILE",
        "project_type": get_project_type_from_platform(),
        "target_column": get_target_column_from_platform(),
        "registered_model_name": _clean_env_value(
            os.environ.get("PLATFORM_REGISTERED_MODEL_NAME")
        ),
        "promotion_owner": "BACKEND",
        "validation_status": "PENDING",
    }

def run_standard_runtime(
    script_path: Path,
    dataset_path: Optional[Path],
    tracking_uri: str,
    experiment_name: str,
    run_name: str,
    workspace: Optional[Path] = None,
    registered_model_name: Optional[str] = None,
) -> RunResult:
    workspace = workspace or script_path.parent
    workspace = workspace.resolve()

    script_path = script_path.resolve()

    script_code = script_path.read_text(encoding="utf-8")
    analysis = analyze_script_imports(script_code)

    if not analysis["supported"]:
        return RunResult(
            status="UNSUPPORTED_DEPENDENCIES",
            return_code=1,
            stdout="",
            stderr="",
            metrics={},
            model_paths=[],
            logged_model_uri=None,
            error={
                "message": (
                    "Votre script utilise des dépendances non disponibles dans le Runtime standard."
                ),
                "unsupported_imports": analysis["unsupportedImports"],
                "suggestion": "Veuillez utiliser le mode Dockerfile personnalisé.",
            },
            analysis=analysis,
        )

    if not tracking_uri or not tracking_uri.strip():
        raise ValueError("MLflow tracking URI is required")

    os.environ.setdefault("GIT_PYTHON_REFRESH", "quiet")
    mlflow.set_tracking_uri(tracking_uri)

    if experiment_name:
        mlflow.set_experiment(experiment_name)

    platform_registered_model_name = resolve_platform_registered_model_name(
        experiment_name=experiment_name,
        explicit_registered_model_name=registered_model_name,
    )

    os.environ["MLFLOW_TRACKING_URI"] = tracking_uri
    os.environ["PLATFORM_EXPERIMENT_NAME"] = experiment_name
    os.environ["PLATFORM_REGISTERED_MODEL_NAME"] = platform_registered_model_name
    os.environ["PLATFORM_OUTPUT_DIR"] = str(workspace / "outputs")

    # Create one official platform run and keep it RUNNING while the user script
    # executes. This avoids a transient FINISHED state before validation.
    experiment = mlflow.get_experiment_by_name(experiment_name)
    if experiment is None:
        experiment_id = mlflow.create_experiment(experiment_name)
    else:
        experiment_id = experiment.experiment_id

    initial_tags = {
        "mlflow.runName": run_name,
        "run_name": run_name,
        "platform_runner": "true",
        "mlops.managed": "true",
        "validation_status": "PENDING",
        "promotion_owner": "BACKEND",
        "platform_recovery": "true",
        "platform_registration_eligible": "false",
        "registered_model_name": platform_registered_model_name,
        "platform_registered_model_name": platform_registered_model_name,
    }

    spring_experiment_id = (
        os.environ.get("SPRING_EXPERIMENT_ID")
        or os.environ.get("MLFLOW_TAG_SPRING_EXPERIMENT_ID")
    )
    if spring_experiment_id:
        initial_tags["spring_experiment_id"] = str(spring_experiment_id)

    initial_run = MlflowClient().create_run(
        experiment_id=str(experiment_id),
        tags=initial_tags,
        run_name=run_name,
    )
    platform_run_id = initial_run.info.run_id
    platform_experiment_id = initial_run.info.experiment_id

    # Exposé aussi au handler d'exception principal afin d'éviter un deuxième run.
    os.environ["PLATFORM_MLFLOW_RUN_ID"] = platform_run_id
    os.environ["PLATFORM_MLFLOW_EXPERIMENT_ID"] = str(platform_experiment_id)

    script_start_time_ms = int(time.time() * 1000)

    result = run_user_script(
        workspace=workspace,
        entrypoint=str(script_path.name),
        dataset_path=dataset_path,
        platform_run_id=platform_run_id,
        platform_experiment_id=platform_experiment_id,
    )

    script_end_time_ms = int(time.time() * 1000)

    parsed_output_metrics = parse_metrics(
        f"{result.stdout or ''}\n{result.stderr or ''}"
    )
    metrics = merge_metrics(
        parsed_output_metrics,
        load_metrics_json(workspace),
        overwrite=True,
    )
    model_artifacts = find_model_artifacts(workspace)

    if model_artifacts:
        print(
            "[PLATFORM_MODEL] model_artifacts_count="
            f"{len(model_artifacts)}"
        )
    else:
        print("[PLATFORM_MODEL] model_artifacts_count=0")

    # Métriques automatiques calculées par la plateforme.
    # Elles complètent les métriques imprimées par le script, sans les écraser.
    pre_primary_model_path = select_primary_model_artifact(model_artifacts)
    pre_companion_artifacts = (
        find_companion_artifacts(pre_primary_model_path, model_artifacts)
        if pre_primary_model_path
        else {}
    )
    auto_metrics = auto_evaluate_metrics_from_platform(
        workspace=workspace,
        dataset_path=dataset_path,
        primary_model_path=pre_primary_model_path,
        companion_artifacts=pre_companion_artifacts,
    )
    metrics = merge_metrics(metrics, auto_metrics, overwrite=False)

    logged_model_uri: Optional[str] = None
    platform_model_registered = False
    platform_model_version: Optional[str] = None

    registration_eligible = is_successful_registration_candidate(
        result=result,
        metrics=metrics,
        model_artifacts=model_artifacts,
    )

    recoverable_user_mlflow_failure = (
        result.returncode != 0
        and bool(model_artifacts)
        and is_recoverable_user_mlflow_error(result.stderr, result.stdout)
    )

    if not registration_eligible:
        print(
            "[PLATFORM_VERSIONING] Official model registration disabled for this run. "
            f"return_code={result.returncode}, "
            f"model_artifacts={len(model_artifacts)}, "
            f"useful_metrics={has_useful_training_metrics(metrics)}"
        )

    error_info = format_error_info(result.stderr) if result.returncode != 0 else None
    recovery_warning = (
        build_recovered_mlflow_warning(result, model_artifacts)
        if recoverable_user_mlflow_failure
        else None
    )

    with mlflow.start_run(run_id=platform_run_id):
        mlflow.set_tag("platform_runner", "true")
        mlflow.set_tag("mlops.managed", "true")
        mlflow.set_tag("platform_recovery", "true")
        mlflow.set_tag("runtime_mode", os.environ.get("RUNTIME_MODE", "CUSTOM_DOCKERFILE"))
        mlflow.set_tag("registered_model_name", platform_registered_model_name)
        mlflow.set_tag("platform_registered_model_name", platform_registered_model_name)
        mlflow.set_tag("run_name", run_name)

        spring_experiment_id = os.environ.get("SPRING_EXPERIMENT_ID") or os.environ.get("MLFLOW_TAG_SPRING_EXPERIMENT_ID")
        if spring_experiment_id:
            mlflow.set_tag("spring_experiment_id", spring_experiment_id)

        project_id = os.environ.get("PROJECT_ID")
        if project_id:
            mlflow.set_tag("project_id", project_id)

        training_job_name = os.environ.get("TRAINING_JOB_NAME") or os.environ.get("KUBERNETES_JOB_NAME") or os.environ.get("HOSTNAME")
        if training_job_name:
            mlflow.set_tag("training_job_name", training_job_name)
            mlflow.set_tag("kubeflow_run_name", training_job_name)

        mlflow.log_param("entrypoint", script_path.name)
        mlflow.log_param("workspace", str(workspace))
        mlflow.log_param("platform_registered_model_name", platform_registered_model_name)

        if dataset_path is not None:
            mlflow.log_param("dataset_path", str(dataset_path))

        mlflow.log_metric("return_code", float(result.returncode))
        mlflow.log_param("original_return_code", str(result.returncode))
        mlflow.log_param(
            "recoverable_user_mlflow_failure",
            str(bool(recoverable_user_mlflow_failure)).lower(),
        )

        mlflow.log_text(result.stdout or "", artifact_file="logs/stdout.log")
        mlflow.log_text(result.stderr or "", artifact_file="logs/stderr.log")

        if error_info:
            mlflow.log_text(
                json.dumps(error_info, indent=2, ensure_ascii=False, default=str),
                artifact_file="logs/error_summary.json",
            )

        if recovery_warning:
            print("[PLATFORM_RECOVERY] User script failed during its own MLflow logic.")
            print("[PLATFORM_RECOVERY] Local model artifact found. Platform will try to register it.")
            mlflow.log_text(
                json.dumps(
                    recovery_warning,
                    indent=2,
                    ensure_ascii=False,
                    default=str,
                ),
                artifact_file="logs/recovered_user_mlflow_failure.json",
            )

        if metrics:
            mlflow.log_metrics(metrics)
            mlflow.log_text(
                json.dumps(metrics, indent=2, ensure_ascii=False),
                artifact_file="logs/parsed_metrics.json",
            )

        if model_artifacts:
            print("[PLATFORM_MODEL] Local model artifact detected.")
            print(
                "[PLATFORM_MODEL] detected_model_files="
                f"{[str(path) for path in model_artifacts]}"
            )

            primary_model_path = select_primary_model_artifact(model_artifacts)
            companion_artifacts = (
                find_companion_artifacts(primary_model_path, model_artifacts)
                if primary_model_path
                else {}
            )

            artifact_results: List[Dict[str, Any]] = []

            if primary_model_path:
                print(f"[PLATFORM_MODEL] selected_primary_model={primary_model_path}")
                print(f"[PLATFORM_MODEL] companion_artifacts={companion_artifacts}")

                artifact_result = log_model_artifact(
                    primary_model_path,
                    companion_artifacts=companion_artifacts,
                )
                artifact_results.append(artifact_result)
                try:
                    mlflow.log_artifact(str(primary_model_path), artifact_path="raw_model")
                    manifest = build_model_manifest(
                        primary_model_path, companion_artifacts, metrics, workspace
                    )
                    mlflow.log_text(
                        json.dumps(manifest, indent=2, ensure_ascii=False, default=str),
                        artifact_file="platform_model/model-manifest.json",
                    )
                    mlflow.log_artifact(str(script_path), artifact_path="source")
                    requirements_file = workspace / "requirements.txt"
                    dockerfile = workspace / "Dockerfile"
                    if requirements_file.exists():
                        mlflow.log_artifact(str(requirements_file), artifact_path="source")
                    if dockerfile.exists():
                        mlflow.log_artifact(str(dockerfile), artifact_path="source")
                except Exception as exc:
                    print(f"[PLATFORM_MODEL] Could not log manifest/source artifacts: {exc}")

                if artifact_result.get("logged_model_uri"):
                    logged_model_uri = artifact_result["logged_model_uri"]

                # Log des fichiers auxiliaires pour audit, sans les prendre pour des modèles.
                for artifact_path in model_artifacts:
                    if artifact_path == primary_model_path:
                        continue
                    try:
                        mlflow.log_artifact(str(artifact_path), artifact_path="raw_user_artifacts")
                    except Exception as exc:
                        print(f"[PLATFORM_MODEL] Could not log auxiliary artifact {artifact_path}: {exc}")

            if logged_model_uri and registration_eligible:
                try:
                    platform_model_version = register_platform_model_once(
                        model_uri=logged_model_uri,
                        registered_model_name=platform_registered_model_name,
                        platform_run_id=platform_run_id,
                    )

                    mlflow.log_param(
                        "platform_model_source_type",
                        "LOCAL_MODEL_FILE",
                    )
                    platform_model_registered = True

                    mlflow.log_param(
                        "platform_model_version",
                        platform_model_version,
                    )

                    tag_registered_version_as_candidate(
                        registered_model_name=platform_registered_model_name,
                        model_version=platform_model_version,
                        platform_run_id=platform_run_id,
                        platform_experiment_id=str(platform_experiment_id),
                        source_type="LOCAL_MODEL_FILE",
                    )

                    print(
                        "[PLATFORM_MODEL] Local model registered as platform model: "
                        f"{platform_registered_model_name} v{platform_model_version}"
                    )

                except Exception as exc:
                    print(
                        "[PLATFORM_MODEL] Failed to register local model as platform model: "
                        f"{exc}"
                    )
            elif logged_model_uri and not registration_eligible:
                print(
                    "[PLATFORM_VERSIONING] Model artifact was logged for audit, "
                    "but no official ModelVersion was created because the run "
                    "did not satisfy the success criteria."
                )

            mlflow.log_text(
                json.dumps(artifact_results, indent=2, default=str, ensure_ascii=False),
                artifact_file="logs/model_artifacts.json",
            )

        else:
            print("[PLATFORM_MODEL] No local model artifact detected.")
            print("[PLATFORM_MODEL] Searching user-created MLflow models...")

            client = MlflowClient()

            user_model_candidate = find_user_created_mlflow_model(
                client=client,
                start_time_ms=script_start_time_ms,
                end_time_ms=script_end_time_ms,
                platform_registered_model_name=platform_registered_model_name,
            )

            if user_model_candidate and registration_eligible:
                user_registration = register_user_mlflow_model_as_platform_model(
                    candidate=user_model_candidate,
                    platform_registered_model_name=platform_registered_model_name,
                    platform_run_id=platform_run_id,
                    platform_experiment_id=str(platform_experiment_id),
                )

                if user_registration:
                    logged_model_uri = user_registration["uri"]
                    platform_model_version = user_registration["version"]
                    platform_model_registered = True

                mlflow.log_text(
                    json.dumps(
                        {
                            "strategy": "USER_MLFLOW_MODEL_RE_REGISTERED",
                            "user_model_name": user_model_candidate.name,
                            "user_model_version": user_model_candidate.version,
                            "user_model_source": user_model_candidate.source,
                            "user_model_run_id": user_model_candidate.run_id,
                            "platform_registered_model_name": platform_registered_model_name,
                            "platform_model_uri": logged_model_uri,
                        },
                        indent=2,
                        ensure_ascii=False,
                    ),
                    artifact_file="logs/model_artifacts.json",
                )

            elif user_model_candidate and not registration_eligible:
                print(
                    "[PLATFORM_VERSIONING] User-created MLflow model detected, "
                    "but it was not re-registered under the official platform name "
                    "because the run failed validation."
                )
                mlflow.log_text(
                    json.dumps(
                        {
                            "strategy": "USER_MLFLOW_MODEL_IGNORED",
                            "reason": "RUN_NOT_SUCCESSFUL",
                            "user_model_name": user_model_candidate.name,
                            "user_model_version": user_model_candidate.version,
                            "user_model_run_id": user_model_candidate.run_id,
                        },
                        indent=2,
                        ensure_ascii=False,
                    ),
                    artifact_file="logs/model_artifacts.json",
                )
            else:
                print("[PLATFORM_MODEL] No model candidate detected.")

                mlflow.log_text(
                    "No model artifact detected and no user-created MLflow model found.",
                    artifact_file="logs/model_artifacts.json",
                )

    # Un script qui retourne une erreur reste FAILED.
    # Même si un artefact local a été récupéré pour audit, aucune version officielle
    # ne doit être créée ni comptée.
    recovered_success = False

    require_registered_model = (
        os.environ.get("PLATFORM_REQUIRE_MODEL_ARTIFACT", "true").strip().lower()
        not in {"false", "0", "no", "off"}
    )

    no_registered_model_error: Optional[Dict[str, Any]] = None

    if result.returncode == 0 and require_registered_model and not platform_model_registered:
        no_registered_model_error = {
            "phase": "PLATFORM_MODEL_REGISTRATION",
            "cause": "no_registered_model_created",
            "message": (
                "Le script s'est exécuté sans erreur, mais la plateforme n'a pas pu "
                "enregistrer de version de modèle dans MLflow Registry."
            ),
            "suggestion": (
                "Le script doit produire au moins un artefact prédictif supporté "
                "(.pkl, .joblib, .pt, .pth, .keras, .h5, .onnx, .cbm, .bst, .ubj). "
                "La plateforme se charge de sa détection et de son enregistrement MLflow."
            ),
            "detected_model_files": [str(path) for path in model_artifacts],
            "logged_model_uri": logged_model_uri,
            "platform_model_registered": platform_model_registered,
        }

        print("[PLATFORM_MODEL] No platform model version was registered.")
        print(json.dumps(no_registered_model_error, indent=2, ensure_ascii=False, default=str))

    final_success = (
        result.returncode == 0
        and has_useful_training_metrics(metrics)
        and (
            not require_registered_model
            or platform_model_registered
        )
    )

    if result.returncode == 0 and not has_useful_training_metrics(metrics):
        no_registered_model_error = {
            "phase": "PLATFORM_METRICS_VALIDATION",
            "cause": "no_useful_metric",
            "message": (
                "Le script s'est terminé, mais aucune métrique métier valide "
                "n'a été produite ou calculée. Aucune version ne sera créée."
            ),
            "suggestion": (
                "Configurez correctement TARGET_COLUMN et PROJECT_TYPE, "
                "ou produisez un fichier metrics.json."
            ),
        }

    final_status = "SUCCESS" if final_success else "FAILED"
    effective_return_code = 0 if final_success else (result.returncode or 1)

    finalize_platform_run(
        run_id=platform_run_id,
        success=final_success,
        reason=(
            None
            if final_success
            else json.dumps(
                no_registered_model_error or error_info or {
                    "cause": "training_not_eligible_for_registration"
                },
                ensure_ascii=False,
                default=str,
            )
        ),
    )


    return RunResult(
        status=final_status,
        return_code=effective_return_code,
        stdout=result.stdout,
        stderr=result.stderr,
        metrics=metrics,
        model_paths=[str(path) for path in model_artifacts],
        logged_model_uri=logged_model_uri,
        error=None if final_success else (no_registered_model_error or error_info),
        analysis=analysis,
    )

def main() -> int:
    parser = argparse.ArgumentParser(description="Standard runtime platform runner")
    parser.add_argument("--script", required=True, help="Chemin du script Python train.py")
    parser.add_argument(
        "--tracking-uri",
        required=False,
        default=os.environ.get("MLFLOW_TRACKING_URI", ""),
    )
    parser.add_argument(
        "--experiment-name",
        required=False,
        default=os.environ.get("MLFLOW_EXPERIMENT_NAME", "default"),
    )
    parser.add_argument(
        "--run-name",
        required=False,
        default=os.environ.get("MLFLOW_RUN_NAME", "standard-runtime"),
    )
    parser.add_argument(
        "--registered-model-name",
        required=False,
        default=os.environ.get("PLATFORM_REGISTERED_MODEL_NAME", ""),
    )
    parser.add_argument("--dataset-path", required=False)
    parser.add_argument("--workspace", required=False)

    args = parser.parse_args()

    script_path = Path(args.script)
    workspace = Path(args.workspace) if args.workspace else script_path.parent

    dataset_path: Optional[Path] = None
    if args.dataset_path:
        raw_dataset_path = Path(args.dataset_path)

        if raw_dataset_path.is_absolute():
            dataset_path = raw_dataset_path
        else:
            dataset_path = workspace / raw_dataset_path

    try:
        result = run_standard_runtime(
            script_path=script_path,
            dataset_path=dataset_path,
            tracking_uri=args.tracking_uri,
            experiment_name=args.experiment_name,
            run_name=args.run_name,
            workspace=workspace,
            registered_model_name=args.registered_model_name,
        )

        print(json.dumps(result.__dict__, indent=2, default=str, ensure_ascii=False))

        if result.status != "SUCCESS":
            return 1

        return 0

    except Exception as exc:
        error = {
            "phase": "PLATFORM_RUNNER",
            "cause": type(exc).__name__,
            "message": str(exc),
            "traceback": traceback.format_exc().splitlines()[-20:],
        }

        print("ERREUR PLATFORM RUNNER :", exc)
        print(json.dumps(error, indent=2, ensure_ascii=False))
        traceback.print_exc()

        try:
            tracking_uri = args.tracking_uri
            if tracking_uri:
                mlflow.set_tracking_uri(tracking_uri)

            existing_run_id = _clean_env_value(
                os.environ.get("PLATFORM_MLFLOW_RUN_ID")
            )

            if existing_run_id:
                client = MlflowClient()
                client.set_tag(existing_run_id, "platform_runner", "true")
                client.set_tag(existing_run_id, "mlops.managed", "true")
                client.set_tag(existing_run_id, "run_name", args.run_name)
                client.set_tag(existing_run_id, "validation_status", "FAILED")
                client.set_tag(existing_run_id, "platform_final_status", "FAILED")
                client.set_tag(
                    existing_run_id,
                    "platform_registration_eligible",
                    "false",
                )
                client.set_tag(
                    existing_run_id,
                    "platform_failure_reason",
                    json.dumps(error, ensure_ascii=False, default=str)[:5000],
                )
                client.log_metric(existing_run_id, "return_code", 1.0)
                client.set_terminated(existing_run_id, status="FAILED")
            else:
                if args.experiment_name:
                    mlflow.set_experiment(args.experiment_name)

                with mlflow.start_run(run_name=args.run_name):
                    mlflow.set_tag("platform_runner", "true")
                    mlflow.set_tag("mlops.managed", "true")
                    mlflow.set_tag("run_name", args.run_name)
                    mlflow.set_tag("validation_status", "FAILED")
                    mlflow.set_tag("platform_final_status", "FAILED")
                    mlflow.set_tag(
                        "platform_registration_eligible",
                        "false",
                    )
                    mlflow.log_text(
                        json.dumps(error, indent=2, ensure_ascii=False),
                        artifact_file="logs/platform_error.json",
                    )
                    mlflow.log_metric("return_code", 1.0)
        except Exception:
            pass

        return 1


if __name__ == "__main__":
    raise SystemExit(main())