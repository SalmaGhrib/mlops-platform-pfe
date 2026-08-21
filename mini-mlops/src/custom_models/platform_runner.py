from __future__ import annotations

import argparse
import ast
import csv
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
from src.custom_models import model_artifact_detector as model_detector


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


SUPPORTED_COMPANION_FILENAMES = {
    "model_metadata.json": "metadata_path",
    "metadata.json": "metadata_path",
    "class_names.json": "classes_path",
    "classes.json": "classes_path",
    "labels.json": "classes_path",
    "metrics.json": "metrics_path",
    "training_history.json": "history_path",
    "history.json": "history_path",
}

SUPPORTED_COMPANION_EXTENSIONS = {
    ".json",
    ".txt",
    ".yaml",
    ".yml",
    ".csv",
    ".npy",
    ".npz",
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
    """
    Wrapper PyTorch générique.

    Il sait charger :
    - un module TorchScript sauvegardé avec torch.jit.save ;
    - un modèle PyTorch complet sauvegardé avec torch.save ;
    - un checkpoint state_dict accompagné du fichier source d'entraînement.

    Les métadonnées de prétraitement sont exposées dans ``self.metadata`` afin
    que le serveur de serving puisse déterminer le format d'entrée image.
    """

    def _load_source_module(self, source_path: Optional[str]):
        if not source_path:
            return None

        spec = importlib.util.spec_from_file_location(
            "platform_user_model_module",
            source_path,
        )

        if spec is None or spec.loader is None:
            return None

        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _load_json_file(self, path_value: Optional[str]) -> Dict[str, Any]:
        if not path_value:
            return {}

        path = Path(path_value)

        if not path.exists() or not path.is_file():
            return {}

        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            return payload if isinstance(payload, dict) else {}
        except Exception as exc:
            print(
                "[PLATFORM_MODEL] Could not read PyTorch metadata "
                f"from {path}: {exc}"
            )
            return {}

    def _load_class_names(self, path_value: Optional[str]) -> List[str]:
        if not path_value:
            return []

        path = Path(path_value)

        if not path.exists() or not path.is_file():
            return []

        try:
            payload = json.loads(path.read_text(encoding="utf-8"))

            if isinstance(payload, list):
                return [str(item) for item in payload]

            if isinstance(payload, dict):
                values = (
                    payload.get("class_names")
                    or payload.get("classes")
                    or payload.get("labels")
                    or []
                )
                if isinstance(values, list):
                    return [str(item) for item in values]
        except Exception as exc:
            print(
                "[PLATFORM_MODEL] Could not read class names "
                f"from {path}: {exc}"
            )

        return []

    def _rebuild_checkpoint(
        self,
        checkpoint: Dict[str, Any],
        source_path: Optional[str],
    ):
        import torch

        module = self._load_source_module(source_path)

        if module is None:
            raise RuntimeError(
                "PyTorch checkpoint requires the training source file "
                "to rebuild the model class."
            )

        class_name = (
            checkpoint.get("architecture")
            or checkpoint.get("class_name")
            or checkpoint.get("model_class")
            or checkpoint.get("model_type")
        )

        if not class_name or not hasattr(module, str(class_name)):
            raise RuntimeError(
                f"Model class {class_name!r} was not found "
                "in the training source."
            )

        model_class = getattr(module, str(class_name))
        constructor_args = checkpoint.get("constructor_args") or {}
        number_of_classes = (
            checkpoint.get("number_of_classes")
            or checkpoint.get("num_classes")
        )

        attempts = []

        if isinstance(constructor_args, dict):
            attempts.append(((), constructor_args))

        if number_of_classes is not None:
            attempts.extend(
                [
                    ((int(number_of_classes),), {}),
                    ((), {"number_of_classes": int(number_of_classes)}),
                    ((), {"num_classes": int(number_of_classes)}),
                    ((), {"n_classes": int(number_of_classes)}),
                ]
            )

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
            raise RuntimeError(
                f"Could not instantiate {class_name}: {last_error}"
            )

        state = (
            checkpoint.get("model_state_dict")
            or checkpoint.get("state_dict")
        )

        if state is None:
            raise RuntimeError(
                "Checkpoint dictionary does not contain "
                "model_state_dict/state_dict."
            )

        model.load_state_dict(state)

        self.image_size = checkpoint.get("image_size")
        self.labels = (
            checkpoint.get("labels")
            or checkpoint.get("class_names")
            or []
        )

        # Le checkpoint constitue une dernière source de vérité. Cela permet
        # de servir correctement les anciens modèles même si le fichier
        # model_metadata.json n'a pas été attaché à leur version MLflow.
        if not isinstance(getattr(self, "metadata", None), dict):
            self.metadata = {}

        if self.image_size and not self.metadata.get("image_size"):
            self.metadata["image_size"] = self.image_size

        if self.labels and not self.metadata.get("class_names"):
            self.metadata["class_names"] = list(self.labels)

        if self.image_size and not self.metadata.get("input_shape"):
            try:
                size = int(self.image_size)
                self.metadata["input_shape"] = [3, size, size]
            except (TypeError, ValueError):
                pass

        if self.image_size and not self.metadata.get("preprocessing"):
            try:
                size = int(self.image_size)
                self.metadata["preprocessing"] = {
                    "type": "image",
                    "resize": [size, size],
                    "color_mode": "RGB",
                    "layout": "NCHW",
                    "dtype": "float32",
                    "rescale": "0_1",
                    "normalize": {
                        "mean": [0.5, 0.5, 0.5],
                        "std": [0.5, 0.5, 0.5],
                    },
                }
            except (TypeError, ValueError):
                pass

        return model

    def load_context(self, context):
        try:
            import torch
        except ImportError as exc:
            raise RuntimeError(
                "PyTorch is required to load .pt/.pth models"
            ) from exc

        artifacts = dict(context.artifacts or {})
        model_path = artifacts["model_path"]

        self.metadata = self._load_json_file(
            artifacts.get("metadata_path")
        )

        # Fallback robuste : certaines versions MLflow conservent les métadonnées
        # du modèle dans le champ Model.metadata, mais pas forcément dans
        # context.artifacts si un ancien package a été enregistré.
        if not self.metadata:
            embedded_metadata = getattr(self, "embedded_metadata", None)
            if isinstance(embedded_metadata, dict):
                self.metadata = dict(embedded_metadata)

        class_names = self._load_class_names(
            artifacts.get("classes_path")
        )

        if class_names and not self.metadata.get("class_names"):
            self.metadata["class_names"] = class_names

        self.preprocessing = (
            self.metadata.get("preprocessing")
            if isinstance(self.metadata, dict)
            else {}
        ) or {}

        self.image_size = (
            self.metadata.get("image_size")
            or self.preprocessing.get("image_size")
            or self.preprocessing.get("resize")
        )

        self.labels = (
            self.metadata.get("class_names")
            or self.metadata.get("classes")
            or self.metadata.get("labels")
            or class_names
            or []
        )

        # Priorité à TorchScript : torch.jit.load évite de dépendre de la
        # classe Python d'entraînement lors du serving.
        try:
            loaded = torch.jit.load(model_path, map_location="cpu")
            print(
                "[PLATFORM_MODEL] TorchScript model loaded successfully: "
                f"{model_path}"
            )
        except Exception:
            loaded = torch.load(model_path, map_location="cpu")

            if isinstance(loaded, dict):
                loaded = self._rebuild_checkpoint(
                    loaded,
                    artifacts.get("source_file"),
                )

        self.model = loaded

        if hasattr(self.model, "eval"):
            self.model.eval()

        print(
            "[PLATFORM_MODEL] PyTorch metadata loaded. "
            f"metadata_keys={sorted(self.metadata.keys())}, "
            f"labels_count={len(self.labels)}"
        )

    def _prepare_input(self, model_input):
        if hasattr(model_input, "to_numpy"):
            return model_input.to_numpy()

        if isinstance(model_input, list):
            return model_input

        if hasattr(model_input, "values"):
            return model_input.values

        return model_input

    def _resolve_image_size(self) -> Optional[int]:
        value = getattr(self, "image_size", None)

        if isinstance(value, (list, tuple)) and value:
            value = value[0]

        try:
            return int(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    def predict(self, context, model_input):
        import numpy as np
        import torch

        input_data = self._prepare_input(model_input)

        if not isinstance(input_data, torch.Tensor):
            input_data = torch.tensor(
                np.asarray(input_data),
                dtype=torch.float32,
            )

        if input_data.ndim == 1:
            input_data = input_data.unsqueeze(0)

        image_size = self._resolve_image_size()

        if image_size and input_data.ndim == 2:
            expected = 3 * image_size * image_size

            if input_data.shape[1] == expected:
                input_data = input_data.reshape(
                    -1,
                    3,
                    image_size,
                    image_size,
                )

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
    """Return one stable metric name across scripts, MLflow and the backend."""
    normalized = re.sub(
        r"[^a-zA-Z0-9]+",
        "_",
        str(name or "").strip().lower(),
    ).strip("_")

    aliases = {
        "acc": "accuracy",
        "accuracy_score": "accuracy",
        "test_accuracy": "accuracy",
        "validation_accuracy": "accuracy",
        "val_accuracy": "accuracy",
        "model_accuracy": "accuracy",
        "classification_accuracy": "accuracy",

        "f1": "f1_score",
        "f1score": "f1_score",
        "f1_weighted": "f1_score",
        "weighted_f1": "f1_score",
        "macro_f1": "f1_score",
        "f1_macro": "f1_score",

        "precision": "precision_score",
        "precision_weighted": "precision_score",
        "weighted_precision": "precision_score",
        "macro_precision": "precision_score",

        "recall": "recall_score",
        "recall_weighted": "recall_score",
        "weighted_recall": "recall_score",
        "macro_recall": "recall_score",

        "r2_score": "r2",
        "roc_auc": "auc",
        "auc_score": "auc",
        "root_mean_squared_error": "rmse",
        "mean_squared_error": "mse",
        "mean_absolute_error": "mae",
    }

    return aliases.get(normalized, normalized)


TECHNICAL_METRICS = {
    "return_code",
    "exit_code",
    "duration",
    "execution_time",
    "execution_duration",
    "train_rows",
    "test_rows",
}


def sanitize_metrics(payload: Dict[str, Any]) -> Dict[str, float]:
    """Keep only finite numeric business metrics."""
    clean: Dict[str, float] = {}

    for raw_name, raw_value in (payload or {}).items():
        if isinstance(raw_value, bool):
            continue

        name = normalize_metric_name(str(raw_name))
        if not name or name in TECHNICAL_METRICS:
            continue

        try:
            value = float(raw_value)
        except (TypeError, ValueError):
            continue

        if math.isfinite(value):
            clean[name] = value

    return clean


def extract_json_metrics(text: str) -> Dict[str, float]:
    """
    Extract metrics from every valid JSON object printed by a user script.

    Accepted forms:
      {"accuracy": 0.91}
      {"metrics": {"accuracy": 0.91}}
      {"result": {"metrics": {"accuracy": 0.91}}}
    """
    source = text or ""
    decoder = json.JSONDecoder()
    merged: Dict[str, float] = {}

    def visit(value: Any) -> None:
        nonlocal merged

        if isinstance(value, dict):
            direct = sanitize_metrics(value)
            if direct:
                merged = merge_metrics(merged, direct, overwrite=True)

            nested_metrics = value.get("metrics")
            if isinstance(nested_metrics, dict):
                merged = merge_metrics(
                    merged,
                    sanitize_metrics(nested_metrics),
                    overwrite=True,
                )

            for nested_value in value.values():
                if isinstance(nested_value, (dict, list)):
                    visit(nested_value)

        elif isinstance(value, list):
            for item in value:
                visit(item)

    for index, character in enumerate(source):
        if character != "{":
            continue

        try:
            value, _ = decoder.raw_decode(source[index:])
        except json.JSONDecodeError:
            continue

        visit(value)

    if merged:
        print(f"[PLATFORM_METRICS] extracted from stdout: {merged}")

    return merged


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
    metrics = sanitize_metrics(metrics)

    if metrics:
        for name, value in sorted(metrics.items()):
            print(f"[PLATFORM_METRIC_AUTO] {name}={value}")
    else:
        print("[PLATFORM_METRICS] no business metric extracted from stdout/stderr")

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


def get_data_type_from_platform() -> str:
    value = _clean_env_value(
        os.environ.get("DATA_TYPE")
        or os.environ.get("ML_DATA_TYPE")
        or os.environ.get("PROJECT_DATA_TYPE")
        or os.environ.get("MODEL_DATA_TYPE")
    ).lower().replace("-", "_").replace(" ", "_")

    aliases = {
        "images": "image",
        "image_classification": "image",
        "vision": "image",
        "computer_vision": "image",
        "text_nlp": "text",
        "nlp": "text",
        "tabular_data": "tabular",
        "table": "tabular",
    }
    return aliases.get(value, value)


def is_image_project() -> bool:
    data_type = get_data_type_from_platform()
    project_type = get_project_type_from_platform()

    return (
        data_type == "image"
        or project_type == "image_classification"
        or _truthy_env("PLATFORM_IMAGE_MODEL", False)
    )


def _read_json_dict(path: Path) -> Dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return payload if isinstance(payload, dict) else {}
    except Exception as exc:
        print(f"[PLATFORM_MODEL] Could not read JSON file {path}: {exc}")
        return {}


def _read_class_names_payload(path: Path) -> List[str]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []

    if isinstance(payload, list):
        return [str(item) for item in payload]

    if isinstance(payload, dict):
        values = (
            payload.get("class_names")
            or payload.get("classes")
            or payload.get("labels")
            or []
        )
        if isinstance(values, list):
            return [str(item) for item in values]

    return []


def ensure_model_metadata_artifact(
    primary_model_path: Path,
    companion_artifacts: Dict[str, str],
    workspace: Path,
) -> Dict[str, str]:
    """
    Garantit qu'un modèle image possède un fichier model_metadata.json exploitable.

    La fonction privilégie toujours un fichier fourni par l'utilisateur. Si aucun
    fichier n'existe, elle peut générer un manifeste minimal uniquement lorsque
    suffisamment d'informations fiables sont disponibles dans les variables
    d'environnement ou dans class_names.json.
    """
    companions = dict(companion_artifacts or {})
    metadata_value = companions.get("metadata_path")

    if metadata_value:
        metadata_path = Path(metadata_value)
        if metadata_path.exists() and metadata_path.is_file():
            payload = _read_json_dict(metadata_path)
            if payload:
                print(
                    "[PLATFORM_METADATA] Existing metadata retained: "
                    f"{metadata_path}, keys={sorted(payload.keys())}"
                )
                return companions

    if not is_image_project():
        return companions

    class_names: List[str] = []
    classes_value = companions.get("classes_path")
    if classes_value:
        class_path = Path(classes_value)
        if class_path.exists() and class_path.is_file():
            class_names = _read_class_names_payload(class_path)

    image_size_raw = _clean_env_value(
        os.environ.get("IMAGE_SIZE")
        or os.environ.get("MODEL_IMAGE_SIZE")
        or os.environ.get("INPUT_IMAGE_SIZE")
    )

    image_size: Optional[int] = None
    if image_size_raw:
        try:
            image_size = int(float(image_size_raw))
        except (TypeError, ValueError):
            image_size = None

    mean_raw = _clean_env_value(os.environ.get("IMAGE_NORMALIZE_MEAN"))
    std_raw = _clean_env_value(os.environ.get("IMAGE_NORMALIZE_STD"))

    def parse_float_list(raw: str) -> List[float]:
        if not raw:
            return []
        values: List[float] = []
        for item in re.split(r"[,;\s]+", raw):
            if not item:
                continue
            try:
                values.append(float(item))
            except ValueError:
                return []
        return values

    mean = parse_float_list(mean_raw)
    std = parse_float_list(std_raw)

    # Ne jamais inventer silencieusement un prétraitement. Sans image_size,
    # le serving ne peut pas garantir une prédiction correcte.
    if image_size is None:
        print(
            "[PLATFORM_METADATA] Image metadata missing and IMAGE_SIZE is not "
            "configured. A broken image model will not be registered."
        )
        return companions

    metadata_dir = workspace / "outputs"
    metadata_dir.mkdir(parents=True, exist_ok=True)
    generated_path = metadata_dir / "model_metadata.json"

    payload: Dict[str, Any] = {
        "schema_version": 1,
        "model_format": primary_model_path.suffix.lower().lstrip("."),
        "data_type": "image",
        "image_size": image_size,
        "input_shape": [3, image_size, image_size],
        "class_names": class_names,
        "preprocessing": {
            "type": "image",
            "resize": [image_size, image_size],
            "color_mode": "RGB",
        },
    }

    if len(mean) in {1, 3} and len(std) == len(mean):
        payload["preprocessing"]["normalize"] = {
            "mean": mean,
            "std": std,
        }

    generated_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    companions["metadata_path"] = str(generated_path.resolve())

    print(
        "[PLATFORM_METADATA] Generated metadata artifact: "
        f"{generated_path}, keys={sorted(payload.keys())}"
    )
    return companions

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

            # Accept both {"accuracy": 0.9} and {"metrics": {"accuracy": 0.9}}.
            if isinstance(data.get("metrics"), dict):
                data = data["metrics"]

            parsed = sanitize_metrics(data)
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
        os.environ.get("PLATFORM_MATERIALIZED_DATASET_PATH"),
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

    # Handle ZIP extraction: extract dataset ZIP to /workspace/dataset and find
    # the actual data file inside (CSV, TSV, TXT).
    for candidate in candidates:
        try:
            candidate = candidate.resolve()

            if candidate.exists() and candidate.is_file():
                if candidate.suffix.lower() in {".csv", ".tsv", ".txt"}:
                    return candidate

                if candidate.suffix.lower() == ".zip":
                    # Extract ZIP to /workspace/dataset
                    dataset_dir = workspace / "dataset"
                    if dataset_dir.exists():
                        import shutil
                        shutil.rmtree(dataset_dir)
                    dataset_dir.mkdir(parents=True, exist_ok=True)
                    try:
                        with zipfile.ZipFile(candidate, "r") as zip_ref:
                            zip_ref.extractall(dataset_dir)
                    except Exception:
                        pass
                    # After extraction, look for data files in the dataset dir
                    if dataset_dir.exists():
                        nested_files = sorted(
                            [
                                path.resolve()
                                for path in dataset_dir.rglob("*")
                                if path.is_file()
                                and path.suffix.lower() in {".csv", ".tsv", ".txt"}
                                and path.name.lower()
                                not in {
                                    "metrics.csv",
                                    "predictions.csv",
                                    "history.csv",
                                }
                            ],
                            key=lambda path: path.stat().st_size,
                            reverse=True,
                        )
                        if nested_files:
                            return nested_files[0]
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
        try:
            return model.predict(X_work)
        except Exception as transformed_error:
            print(f"[PLATFORM_METRICS] transformed model.predict failed: {transformed_error}")

            # Common STANDARD_RUNTIME case: the user trained with pandas.get_dummies
            # but only persisted the estimator. Recreate deterministic dummy columns
            # and align them with feature_names_in_ when available.
            try:
                import pandas as pd

                if isinstance(X, pd.DataFrame):
                    encoded = pd.get_dummies(X, drop_first=False)
                    expected = _object_feature_names(model)
                    if expected:
                        for column in expected:
                            if column not in encoded.columns:
                                encoded[column] = 0
                        encoded = encoded[[column for column in expected]]
                    elif getattr(model, "n_features_in_", None) == encoded.shape[1]:
                        pass
                    else:
                        encoded = None

                    if encoded is not None:
                        print("[PLATFORM_METRICS] retrying prediction with pandas.get_dummies alignment")
                        return model.predict(encoded)
            except Exception as dummy_error:
                print(f"[PLATFORM_METRICS] dummy-encoding fallback failed: {dummy_error}")

            raise transformed_error

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
            metrics["precision_score"] = float(precision_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["recall_score"] = float(recall_score(y_true, y_pred, average="weighted", zero_division=0))
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


def find_model_artifacts(
    workspace: Path,
    stdout_text: Optional[str] = None,
) -> List[Path]:
    """
    Discover predictive model files created by the user script.

    Custom Docker scripts frequently save their final model to /opt/ml/model,
    /outputs, /app/outputs or the workspace outputs directory. Detection is
    generic (multi-format, multi-root), deterministic and deduplicated.
    """
    stdout_hint = model_detector.model_path_hint_from_stdout(stdout_text)

    extra_roots: List[Path] = []
    configured_dirs = [
        os.environ.get("MODEL_OUTPUT_DIR"),
        os.environ.get("ML_OUTPUT_DIR"),
        os.environ.get("MLOPS_DEFAULT_OUTPUT_DIR"),
    ]
    for value in configured_dirs:
        if value and str(value).strip():
            extra_roots.append(Path(str(value).strip()))

    discovered = model_detector.discover_model_artifacts(
        workspace=workspace,
        stdout_hint=stdout_hint,
        extra_roots=extra_roots,
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


def canonicalize_primary_artifact(
    primary_model_path: Path,
    workspace: Path,
) -> Path:
    """
    Copie l'artefact retenu vers l'emplacement canonique
    <workspace>/outputs/platform_model/ avant son enregistrement MLflow.

    Garantit un chemin stable, persisté hors des emplacements éphémères
    (/outputs, /opt/ml/model), et réutilise la copie si le contenu est
    identique (hash).
    """
    try:
        canonical_path, digest = model_detector.canonicalize_artifact(
            primary_model_path,
            workspace,
        )
        print(
            "[MODEL_ARTIFACT_CANONICALIZED] "
            f"runId={_clean_env_value(os.environ.get('PLATFORM_MLFLOW_RUN_ID'))}, "
            f"detected_path={primary_model_path}, "
            f"canonical_path={canonical_path}, "
            f"sha256={digest}"
        )
        return canonical_path
    except Exception as exc:
        print(
            "[MODEL_ARTIFACT_CANONICALIZED] failed to canonicalize "
            f"{primary_model_path}: {exc}"
        )
        return primary_model_path


def _companion_artifact_score(path: Path, primary_model_path: Path) -> int:
    score = 0
    name = path.name.lower()
    stem = path.stem.lower()

    if path.parent == primary_model_path.parent:
        score += 1000

    if "outputs" in [part.lower() for part in path.parts]:
        score += 500

    if name == "model_metadata.json":
        score += 5000
    elif name in {"class_names.json", "classes.json", "labels.json"}:
        score += 4000
    elif name == "metrics.json":
        score += 3000
    elif name in {"training_history.json", "history.json"}:
        score += 2000

    if "metadata" in stem:
        score += 1000
    if "class" in stem or "label" in stem:
        score += 800
    if "preprocess" in stem:
        score += 700
    if "scaler" in stem:
        score += 600
    if "vectorizer" in stem or "tfidf" in stem:
        score += 600
    if "encoder" in stem:
        score += 600

    try:
        score += int(path.stat().st_mtime)
    except Exception:
        pass

    return score



def find_companion_artifacts(
    primary_model_path: Path,
    model_artifacts: List[Path],
    workspace: Optional[Path] = None,
) -> Dict[str, str]:
    """
    Détecte de manière déterministe les artefacts nécessaires au serving.

    Les noms exacts sont résolus avant toute recherche générique afin que
    model_metadata.json ne soit jamais perdu au profit d'un fichier plus récent.
    """
    primary_model_path = primary_model_path.resolve()
    companions: Dict[str, str] = {}
    seen_files: set[str] = set()

    roots: List[Path] = [
        primary_model_path.parent,
        primary_model_path.parent.parent,
    ]

    if workspace is not None:
        workspace = workspace.resolve()
        roots.extend(
            [
                workspace / "outputs",
                workspace / "output",
                workspace / "artifacts",
                workspace / "artifact",
                workspace / "model",
                workspace / "models",
                workspace,
            ]
        )

    roots.extend(
        [
            Path("/opt/ml/model"),
            Path("/app/outputs"),
            Path("/app/output"),
            Path("/app/artifacts"),
            Path("/app/model"),
            Path("/app/models"),
        ]
    )

    unique_roots: List[Path] = []
    seen_roots: set[str] = set()

    for root in roots:
        try:
            resolved = root.expanduser().resolve()
        except Exception:
            resolved = root.expanduser().absolute()

        key = str(resolved)
        if key in seen_roots:
            continue

        seen_roots.add(key)
        unique_roots.append(resolved)

    # 1) Recherche déterministe des noms exacts, par ordre de priorité.
    exact_names_by_key = {
        "metadata_path": ["model_metadata.json", "metadata.json"],
        "classes_path": ["class_names.json", "classes.json", "labels.json"],
        "metrics_path": ["metrics.json"],
        "history_path": ["training_history.json", "history.json"],
    }

    for artifact_key, names in exact_names_by_key.items():
        found: Optional[Path] = None

        for root in unique_roots:
            if not root.exists() or not root.is_dir():
                continue

            for name in names:
                direct = root / name
                if direct.exists() and direct.is_file():
                    found = direct.resolve()
                    break

                try:
                    nested_matches = sorted(
                        (
                            path.resolve()
                            for path in root.rglob(name)
                            if path.is_file()
                        ),
                        key=lambda path: _companion_artifact_score(
                            path,
                            primary_model_path,
                        ),
                        reverse=True,
                    )
                except Exception as exc:
                    print(
                        "[PLATFORM_MODEL] Could not search exact companion "
                        f"{name} under {root}: {exc}"
                    )
                    nested_matches = []

                if nested_matches:
                    found = nested_matches[0]
                    break

            if found is not None:
                break

        if found is not None:
            companions[artifact_key] = str(found)
            seen_files.add(str(found))
            print(
                "[PLATFORM_ARTIFACT] Exact companion found: "
                f"{artifact_key}={found}"
            )

    # 2) Recherche générique des transformateurs et autres fichiers auxiliaires.
    generic_candidates: List[Path] = []

    def add_candidate(path: Path) -> None:
        try:
            resolved = path.resolve()
        except Exception:
            resolved = path.absolute()

        key = str(resolved)
        if (
            key in seen_files
            or resolved == primary_model_path
            or not resolved.exists()
            or not resolved.is_file()
        ):
            return

        seen_files.add(key)
        generic_candidates.append(resolved)

    for path in model_artifacts:
        add_candidate(path)

    for root in unique_roots:
        try:
            if not root.exists() or not root.is_dir():
                continue

            for path in root.rglob("*"):
                if not path.is_file():
                    continue

                suffix = path.suffix.lower()
                stem = path.stem.lower()
                name = path.name.lower()

                if name in SUPPORTED_COMPANION_FILENAMES:
                    add_candidate(path)
                    continue

                if (
                    suffix in SUPPORTED_COMPANION_EXTENSIONS
                    or suffix in {".pkl", ".joblib", ".pickle"}
                ) and any(
                    keyword in stem
                    for keyword in AUXILIARY_ARTIFACT_KEYWORDS
                ):
                    add_candidate(path)

        except Exception as exc:
            print(
                "[PLATFORM_MODEL] Could not scan companion root "
                f"{root}: {exc}"
            )

    generic_candidates.sort(
        key=lambda path: _companion_artifact_score(
            path,
            primary_model_path,
        ),
        reverse=True,
    )

    for path in generic_candidates:
        name = path.name.lower()
        stem = path.stem.lower()

        fixed_key = SUPPORTED_COMPANION_FILENAMES.get(name)
        if fixed_key and fixed_key not in companions:
            companions[fixed_key] = str(path)
            continue

        if "scaler" in stem and "scaler_path" not in companions:
            companions["scaler_path"] = str(path)
        elif (
            "preprocess" in stem or "preprocessor" in stem
        ) and "preprocessor_path" not in companions:
            companions["preprocessor_path"] = str(path)
        elif (
            "vectorizer" in stem or "tfidf" in stem
        ) and "vectorizer_path" not in companions:
            companions["vectorizer_path"] = str(path)
        elif (
            "encoder" in stem or "label_encoder" in stem
        ) and "encoder_path" not in companions:
            companions["encoder_path"] = str(path)
        elif "tokenizer" in stem and "tokenizer_path" not in companions:
            companions["tokenizer_path"] = str(path)

    print(
        "[PLATFORM_MODEL] Companion artifacts discovered="
        f"{json.dumps(companions, ensure_ascii=False, sort_keys=True)}"
    )
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

        # Certains serveurs MLflow renvoient run_id vide dans
        # search_model_versions. On s'appuie alors sur le tag
        # platform_run_id, posé de façon fiable à l'enregistrement, pour
        # garantir l'unicité 1 run = 1 version.
        tags = getattr(version, "tags", None) or {}
        tagged_run_id = _clean_env_value(tags.get("platform_run_id"))
        if tagged_run_id and tagged_run_id == clean_run_id:
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



ARCHIVE_EXTENSIONS = {".zip", ".tar", ".tgz", ".gz", ".tar.gz"}
TABULAR_DATASET_EXTENSIONS = {
    ".csv",
    ".tsv",
    ".txt",
    ".json",
    ".jsonl",
    ".parquet",
    ".xlsx",
    ".xls",
}
IMAGE_DATASET_EXTENSIONS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".bmp",
    ".gif",
    ".webp",
    ".tif",
    ".tiff",
}
DATASET_PREPARATION_EXTENSIONS = {".py"}

PREPARED_DATASET_MARKER_REGEX = re.compile(
    r"(?im)^\\s*(?:PLATFORM_DATASET_PATH|DATASET_PATH|DATASET_OUTPUT_PATH)"
    r"\\s*=\\s*(?P<path>.+?)\\s*$"
)


@dataclass
class DatasetMaterialization:
    source_type: str
    original_path: str
    materialized_path: str
    generated: bool
    validation: Dict[str, Any]


def _positive_int_env(name: str, default: int) -> int:
    raw = _clean_env_value(os.environ.get(name))
    if not raw:
        return default

    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default

    return value if value > 0 else default


def _archive_limits() -> tuple[int, int, float]:
    """
    Limites de sécurité contre les archives corrompues et les ZIP bombs.

    Elles restent configurables pour les grands datasets réels.
    """
    max_files = _positive_int_env(
        "PLATFORM_DATASET_MAX_FILES",
        250_000,
    )
    max_uncompressed_bytes = _positive_int_env(
        "PLATFORM_DATASET_MAX_UNCOMPRESSED_BYTES",
        20 * 1024 * 1024 * 1024,
    )
    max_compression_ratio = float(
        os.environ.get(
            "PLATFORM_DATASET_MAX_COMPRESSION_RATIO",
            "250",
        )
    )

    if max_compression_ratio <= 0:
        max_compression_ratio = 250.0

    return max_files, max_uncompressed_bytes, max_compression_ratio


def _ensure_within_directory(
    destination: Path,
    member_name: str,
) -> Path:
    destination = destination.resolve()
    target = (destination / member_name).resolve()

    if not target.is_relative_to(destination):
        raise ValueError(
            f"Unsafe archive entry outside destination: {member_name}"
        )

    return target


def _safe_extract_zip(
    archive_path: Path,
    destination: Path,
) -> None:
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)

    max_files, max_uncompressed_bytes, max_ratio = _archive_limits()

    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()

        if len(entries) > max_files:
            raise ValueError(
                "Archive rejected: too many entries "
                f"({len(entries)} > {max_files})."
            )

        total_uncompressed = 0

        for entry in entries:
            _ensure_within_directory(
                destination,
                entry.filename,
            )

            total_uncompressed += max(0, int(entry.file_size or 0))

            if total_uncompressed > max_uncompressed_bytes:
                raise ValueError(
                    "Archive rejected: uncompressed size exceeds "
                    f"{max_uncompressed_bytes} bytes."
                )

            compressed_size = max(1, int(entry.compress_size or 0))
            ratio = float(entry.file_size or 0) / compressed_size

            if ratio > max_ratio:
                raise ValueError(
                    "Archive rejected: suspicious compression ratio "
                    f"for {entry.filename}: {ratio:.2f}."
                )

        archive.extractall(destination)


def _safe_extract_tar(
    archive_path: Path,
    destination: Path,
) -> None:
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)

    max_files, max_uncompressed_bytes, _ = _archive_limits()

    with tarfile.open(archive_path, mode="r:*") as archive:
        members = archive.getmembers()

        if len(members) > max_files:
            raise ValueError(
                "Archive rejected: too many entries "
                f"({len(members)} > {max_files})."
            )

        total_uncompressed = 0

        for member in members:
            _ensure_within_directory(
                destination,
                member.name,
            )

            # Les liens symboliques, hard links et fichiers spéciaux sont
            # rejetés afin d'éviter toute sortie du répertoire d'extraction.
            if (
                member.issym()
                or member.islnk()
                or member.isdev()
                or member.isfifo()
            ):
                raise ValueError(
                    "Archive rejected: unsupported special entry "
                    f"{member.name}."
                )

            total_uncompressed += max(0, int(member.size or 0))

            if total_uncompressed > max_uncompressed_bytes:
                raise ValueError(
                    "Archive rejected: uncompressed size exceeds "
                    f"{max_uncompressed_bytes} bytes."
                )

        archive.extractall(destination, members=members)


def _clean_dataset_directory(directory: Path) -> None:
    if directory.exists():
        shutil.rmtree(directory)

    directory.mkdir(
        parents=True,
        exist_ok=True,
    )


def _visible_children(directory: Path) -> List[Path]:
    ignored_names = {
        "__MACOSX",
        ".DS_Store",
        "Thumbs.db",
        ".gitkeep",
    }

    return sorted(
        [
            item
            for item in directory.iterdir()
            if item.name not in ignored_names
            and not item.name.startswith("._")
        ],
        key=lambda item: item.name.lower(),
    )


def _collapse_single_directory(directory: Path) -> Path:
    current = directory.resolve()

    # Plusieurs archives contiennent une ou deux couches de dossiers inutiles.
    # On les retire sans dépasser une profondeur raisonnable.
    for _ in range(4):
        children = _visible_children(current)

        if len(children) != 1 or not children[0].is_dir():
            break

        current = children[0].resolve()

    return current


def _is_archive(path: Path) -> bool:
    lower_name = path.name.lower()

    return (
        lower_name.endswith(".zip")
        or lower_name.endswith(".tar")
        or lower_name.endswith(".tar.gz")
        or lower_name.endswith(".tgz")
    )


def _validate_tabular_file(path: Path) -> Dict[str, Any]:
    if not path.exists() or not path.is_file():
        raise FileNotFoundError(
            f"Tabular dataset not found: {path}"
        )

    if path.stat().st_size <= 0:
        raise ValueError(
            f"Tabular dataset is empty: {path}"
        )

    suffix = path.suffix.lower()
    report: Dict[str, Any] = {
        "kind": "tabular",
        "filename": path.name,
        "extension": suffix,
        "size_bytes": path.stat().st_size,
    }

    if suffix not in {".csv", ".tsv", ".txt"}:
        return report

    sample_bytes = path.read_bytes()[:256 * 1024]

    decoded: Optional[str] = None
    encoding_used: Optional[str] = None

    for encoding in (
        "utf-8-sig",
        "utf-8",
        "latin-1",
    ):
        try:
            decoded = sample_bytes.decode(encoding)
            encoding_used = encoding
            break
        except UnicodeDecodeError:
            continue

    if decoded is None:
        raise ValueError(
            f"Could not decode tabular dataset: {path}"
        )

    non_empty_lines = [
        line
        for line in decoded.splitlines()
        if line.strip()
    ]

    if len(non_empty_lines) < 2:
        raise ValueError(
            "Tabular dataset must contain a header and at least one row."
        )

    delimiter = "\t" if suffix == ".tsv" else None

    if delimiter is None:
        try:
            dialect = csv.Sniffer().sniff(
                "\n".join(non_empty_lines[:20]),
                delimiters=",;\t|",
            )
            delimiter = dialect.delimiter
        except csv.Error:
            delimiter = ","

    reader = csv.reader(
        non_empty_lines[:100],
        delimiter=delimiter,
    )
    rows = list(reader)

    if not rows or len(rows[0]) < 2:
        raise ValueError(
            "Tabular dataset must contain at least two columns."
        )

    expected_columns = len(rows[0])
    malformed_rows = sum(
        1
        for row in rows[1:]
        if len(row) != expected_columns
    )

    report.update(
        {
            "encoding": encoding_used,
            "delimiter": delimiter,
            "sample_columns": expected_columns,
            "sample_rows": max(0, len(rows) - 1),
            "malformed_sample_rows": malformed_rows,
        }
    )

    print(
        "[PLATFORM_DATASET] Tabular validation passed: "
        f"{json.dumps(report, ensure_ascii=False)}"
    )

    return report


def _count_dataset_files(
    directory: Path,
) -> tuple[int, int, int]:
    total_files = 0
    image_files = 0
    tabular_files = 0

    for path in directory.rglob("*"):
        if not path.is_file():
            continue

        total_files += 1
        suffix = path.suffix.lower()

        if suffix in IMAGE_DATASET_EXTENSIONS:
            image_files += 1

        if suffix in TABULAR_DATASET_EXTENSIONS:
            tabular_files += 1

    return total_files, image_files, tabular_files


def _detect_image_split_structure(
    directory: Path,
) -> Dict[str, Any]:
    split_aliases = {
        "train": ("train", "training"),
        "val": ("val", "valid", "validation"),
        "test": ("test", "testing"),
    }

    resolved_splits: Dict[str, Path] = {}

    lower_children = {
        child.name.lower(): child
        for child in _visible_children(directory)
        if child.is_dir()
    }

    for canonical_name, aliases in split_aliases.items():
        for alias in aliases:
            match = lower_children.get(alias)
            if match is not None:
                resolved_splits[canonical_name] = match
                break

    if not resolved_splits:
        return {}

    class_sets: Dict[str, List[str]] = {}
    image_counts: Dict[str, int] = {}

    for split_name, split_path in resolved_splits.items():
        class_dirs = [
            child
            for child in _visible_children(split_path)
            if child.is_dir()
        ]

        class_sets[split_name] = sorted(
            child.name
            for child in class_dirs
        )

        image_counts[split_name] = sum(
            1
            for file_path in split_path.rglob("*")
            if file_path.is_file()
            and file_path.suffix.lower()
            in IMAGE_DATASET_EXTENSIONS
        )

    return {
        "splits": {
            name: str(path)
            for name, path in resolved_splits.items()
        },
        "classes_by_split": class_sets,
        "images_by_split": image_counts,
    }


def _validate_dataset_directory(
    directory: Path,
) -> Dict[str, Any]:
    if not directory.exists() or not directory.is_dir():
        raise FileNotFoundError(
            f"Dataset directory not found: {directory}"
        )

    total_files, image_files, tabular_files = _count_dataset_files(
        directory
    )

    if total_files == 0:
        raise ValueError(
            f"Dataset directory is empty: {directory}"
        )

    report: Dict[str, Any] = {
        "kind": "directory",
        "total_files": total_files,
        "image_files": image_files,
        "tabular_files": tabular_files,
    }

    image_structure = _detect_image_split_structure(directory)

    if image_structure:
        report["image_structure"] = image_structure

        classes_by_split = image_structure.get(
            "classes_by_split",
            {},
        )
        train_classes = set(
            classes_by_split.get("train", [])
        )

        if len(train_classes) < 2:
            raise ValueError(
                "Image classification dataset must contain at least "
                "two class folders in train/."
            )

        for split_name in ("val", "test"):
            split_classes = set(
                classes_by_split.get(split_name, [])
            )

            if split_classes and split_classes != train_classes:
                raise ValueError(
                    "Image dataset class mismatch between train and "
                    f"{split_name}: train={sorted(train_classes)}, "
                    f"{split_name}={sorted(split_classes)}"
                )

    elif image_files > 0:
        class_dirs = [
            child
            for child in _visible_children(directory)
            if child.is_dir()
        ]

        if len(class_dirs) < 2:
            print(
                "[PLATFORM_DATASET] Image directory detected without "
                "train/val/test split. The user training script remains "
                "responsible for splitting the data."
            )

    elif tabular_files == 0:
        raise ValueError(
            "Dataset directory contains no supported image or tabular file."
        )

    print(
        "[PLATFORM_DATASET] Directory validation passed: "
        f"{json.dumps(report, ensure_ascii=False)}"
    )

    return report


def _resolve_declared_prepared_dataset(
    stdout: str,
    output_root: Path,
    workspace: Path,
) -> Optional[Path]:
    """
    Lit un chemin explicite produit par le script de préparation.

    Formats reconnus dans stdout :
      PLATFORM_DATASET_PATH=/chemin/dataset.zip
      DATASET_OUTPUT_PATH=dataset/
      DATASET_PATH=data.csv

    Pour des raisons de sécurité, le chemin déclaré doit rester dans le
    workspace du Job ou dans le répertoire de préparation dédié.
    """
    match = PREPARED_DATASET_MARKER_REGEX.search(stdout or "")
    if not match:
        return None

    raw_value = match.group("path").strip().strip('"').strip("'")
    if not raw_value:
        return None

    declared = Path(raw_value).expanduser()
    if not declared.is_absolute():
        declared = (output_root / declared).resolve()
    else:
        declared = declared.resolve()

    allowed_roots = [
        output_root.resolve(),
        workspace.resolve(),
    ]

    if not any(
        declared == root or declared.is_relative_to(root)
        for root in allowed_roots
    ):
        raise ValueError(
            "Le script de préparation a déclaré un dataset hors du "
            f"workspace autorisé : {declared}"
        )

    if not declared.exists():
        raise FileNotFoundError(
            "Le script de préparation a déclaré un dataset introuvable : "
            f"{declared}"
        )

    print(
        "[PLATFORM_DATASET] Explicit prepared dataset marker resolved: "
        f"{declared}"
    )
    return declared


def _choose_prepared_dataset_candidate(
    output_root: Path,
    workspace: Optional[Path] = None,
    stdout: str = "",
) -> Optional[Path]:
    """
    Choisit de façon déterministe le dataset produit par un script .py.

    Priorités :
    1. chemin explicitement déclaré dans stdout ;
    2. dataset.zip / dataset.csv / data.csv ;
    3. dossier dataset/ ;
    4. dossier avec train/val/test ;
    5. plus gros fichier tabulaire ;
    6. unique dossier non vide.
    """
    output_root = output_root.resolve()
    workspace = (workspace or output_root.parent).resolve()

    declared = _resolve_declared_prepared_dataset(
        stdout=stdout,
        output_root=output_root,
        workspace=workspace,
    )
    if declared is not None:
        return declared

    exact_candidates = [
        output_root / "dataset.zip",
        output_root / "dataset.tar.gz",
        output_root / "dataset.tgz",
        output_root / "dataset.csv",
        output_root / "data.csv",
        output_root / "train.csv",
        output_root / "dataset",
        output_root / "data",
        output_root / "prepared_data" / "dataset.zip",
        output_root / "prepared_data" / "dataset",
    ]

    for candidate in exact_candidates:
        if candidate.exists():
            return candidate.resolve()

    split_roots: List[Path] = []

    directories = [output_root]
    directories.extend(
        path
        for path in output_root.rglob("*")
        if path.is_dir()
    )

    for directory in directories:
        try:
            child_names = {
                child.name.lower()
                for child in _visible_children(directory)
                if child.is_dir()
            }
        except OSError:
            continue

        if (
            {"train", "val", "test"}.issubset(child_names)
            or {"train", "validation", "test"}.issubset(child_names)
            or {"training", "validation", "testing"}.issubset(child_names)
        ):
            split_roots.append(directory.resolve())

    if split_roots:
        split_roots.sort(
            key=lambda path: (
                len(path.parts),
                str(path).lower(),
            )
        )
        return split_roots[0]

    tabular_candidates = [
        path.resolve()
        for path in output_root.rglob("*")
        if path.is_file()
        and path.suffix.lower() in TABULAR_DATASET_EXTENSIONS
        and path.name.lower()
        not in {
            "metrics.csv",
            "predictions.csv",
            "history.csv",
        }
    ]

    if tabular_candidates:
        tabular_candidates.sort(
            key=lambda path: (
                path.stat().st_size,
                str(path).lower(),
            ),
            reverse=True,
        )
        return tabular_candidates[0]

    visible = _visible_children(output_root)

    if len(visible) == 1:
        return visible[0].resolve()

    non_empty_directories = []
    for child in visible:
        if not child.is_dir():
            continue
        try:
            if any(child.rglob("*")):
                non_empty_directories.append(child.resolve())
        except OSError:
            continue

    if len(non_empty_directories) == 1:
        return non_empty_directories[0]

    return None


def _is_dataset_preparation_argument_error(
    stderr: str,
) -> bool:
    text = (stderr or "").lower()

    return (
        "usage:" in text
        or "unrecognized arguments" in text
        or "the following arguments are required" in text
        or "error: argument" in text
    )


def _write_dataset_preparation_logs(
    workspace: Path,
    attempt_index: int,
    command: List[str],
    result: subprocess.CompletedProcess,
) -> None:
    """Conserve les logs de préparation pour l'audit MLflow et le diagnostic."""
    try:
        log_dir = prepare_outputs_dir(workspace) / "dataset_preparation_logs"
        log_dir.mkdir(parents=True, exist_ok=True)

        payload = {
            "attempt": attempt_index,
            "command": command,
            "return_code": int(result.returncode),
        }
        (log_dir / f"attempt-{attempt_index}.json").write_text(
            json.dumps(payload, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        (log_dir / f"attempt-{attempt_index}.stdout.log").write_text(
            result.stdout or "",
            encoding="utf-8",
        )
        (log_dir / f"attempt-{attempt_index}.stderr.log").write_text(
            result.stderr or "",
            encoding="utf-8",
        )
    except Exception as exc:
        print(
            "[PLATFORM_DATASET] Could not persist preparation logs: "
            f"{exc}"
        )


def execute_dataset_preparation_script(
    script_path: Path,
    workspace: Path,
) -> Path:
    """
    Exécute un script de préparation dans le Job Kubernetes isolé.

    Contrats supportés :
      python prepare.py --output-dir <dir>
      python prepare.py --output_dir <dir>
      python prepare.py --output <dir>
      python prepare.py <dir>
      python prepare.py

    Le script peut produire :
      - un dossier d'images ;
      - un CSV/TSV/JSON/Parquet/Excel ;
      - une archive ZIP/TAR/TGZ.

    Il peut aussi imprimer :
      PLATFORM_DATASET_PATH=<chemin>

    Le fichier .py n'est jamais transmis à train.py. Seul le dataset généré
    et validé est transmis à l'étape d'entraînement.
    """
    script_path = script_path.expanduser().resolve()
    workspace = workspace.expanduser().resolve()

    if not script_path.exists() or not script_path.is_file():
        raise FileNotFoundError(
            f"Dataset preparation script not found: {script_path}"
        )

    if script_path.suffix.lower() != ".py":
        raise ValueError(
            "Dataset preparation source must be a Python file: "
            f"{script_path}"
        )

    max_script_bytes = _positive_int_env(
        "PLATFORM_DATASET_SCRIPT_MAX_BYTES",
        5 * 1024 * 1024,
    )
    if script_path.stat().st_size > max_script_bytes:
        raise ValueError(
            "Dataset preparation script is too large: "
            f"{script_path.stat().st_size} bytes."
        )

    output_root = workspace / ".platform_prepared_dataset"
    timeout_seconds = _positive_int_env(
        "PLATFORM_DATASET_PREPARATION_TIMEOUT_SECONDS",
        1800,
    )

    env = {
        **os.environ,
        "PLATFORM_DATASET_PREPARATION_MODE": "true",
        "DATASET_OUTPUT_DIR": str(output_root),
        "OUTPUT_DIR": str(output_root),
        "PLATFORM_DATASET_OUTPUT_DIR": str(output_root),
        "PLATFORM_PREPARE_OUTPUT_DIR": str(output_root),
        "WORKSPACE_DIR": str(workspace),
        "PYTHONUNBUFFERED": "1",
    }

    # Le dossier du script reste importable si le préparateur utilise des
    # modules compagnons présents dans le même upload.
    existing_pythonpath = _clean_env_value(env.get("PYTHONPATH"))
    pythonpath_parts = [
        str(script_path.parent),
        str(workspace),
    ]
    if existing_pythonpath:
        pythonpath_parts.append(existing_pythonpath)
    env["PYTHONPATH"] = os.pathsep.join(pythonpath_parts)

    commands = [
        [
            sys.executable,
            str(script_path),
            "--output-dir",
            str(output_root),
        ],
        [
            sys.executable,
            str(script_path),
            "--output_dir",
            str(output_root),
        ],
        [
            sys.executable,
            str(script_path),
            "--output",
            str(output_root),
        ],
        [
            sys.executable,
            str(script_path),
            str(output_root),
        ],
        [
            sys.executable,
            str(script_path),
        ],
    ]

    last_result: Optional[subprocess.CompletedProcess] = None

    for index, command in enumerate(commands, start=1):
        _clean_dataset_directory(output_root)

        print(
            "[PLATFORM_DATASET] Trying preparation command "
            f"{index}/{len(commands)}: {' '.join(command)}"
        )

        try:
            result = subprocess.run(
                command,
                cwd=str(workspace),
                capture_output=True,
                text=True,
                env=env,
                timeout=timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = exc.stderr or ""

            if isinstance(stdout, bytes):
                stdout = stdout.decode("utf-8", errors="replace")
            if isinstance(stderr, bytes):
                stderr = stderr.decode("utf-8", errors="replace")

            result = subprocess.CompletedProcess(
                args=command,
                returncode=124,
                stdout=stdout,
                stderr=(
                    stderr
                    + "\nDataset preparation timed out after "
                    + str(timeout_seconds)
                    + " seconds."
                ),
            )

        last_result = result
        _write_dataset_preparation_logs(
            workspace=workspace,
            attempt_index=index,
            command=command,
            result=result,
        )

        if result.stdout:
            print(
                "[PLATFORM_DATASET] Preparation stdout:\n"
                + result.stdout
            )

        if result.stderr:
            print(
                "[PLATFORM_DATASET] Preparation stderr:\n"
                + result.stderr
            )

        if result.returncode == 0:
            candidate = _choose_prepared_dataset_candidate(
                output_root=output_root,
                workspace=workspace,
                stdout=result.stdout or "",
            )

            if candidate is None:
                raise RuntimeError(
                    "Dataset preparation script completed successfully "
                    "but produced no supported dataset. Expected dataset.zip, "
                    "dataset/, train/val/test, a tabular file, or the marker "
                    "PLATFORM_DATASET_PATH=<path> under "
                    f"{output_root}."
                )

            print(
                "[PLATFORM_DATASET] Preparation completed. "
                f"Selected output={candidate}"
            )
            return candidate.resolve()

        if _is_dataset_preparation_argument_error(
            result.stderr
        ):
            print(
                "[PLATFORM_DATASET] Preparation argument mismatch; "
                "trying the next supported convention."
            )
            continue

        raise RuntimeError(
            "Dataset preparation script failed with return code "
            f"{result.returncode}. Last stderr lines: "
            + " | ".join(
                (result.stderr or "").strip().splitlines()[-8:]
            )
        )

    raise RuntimeError(
        "Dataset preparation script failed for every supported "
        "argument format. Last return code="
        + str(
            last_result.returncode
            if last_result is not None
            else "none"
        )
    )


def _extract_archive_dataset(
    archive_path: Path,
    workspace: Path,
) -> Path:
    target = (
        workspace.resolve()
        / ".platform_dataset"
    )
    _clean_dataset_directory(target)

    lower_name = archive_path.name.lower()

    if lower_name.endswith(".zip"):
        _safe_extract_zip(
            archive_path,
            target,
        )
    elif lower_name.endswith(
        (
            ".tar",
            ".tar.gz",
            ".tgz",
        )
    ):
        _safe_extract_tar(
            archive_path,
            target,
        )
    else:
        raise ValueError(
            f"Unsupported archive format: {archive_path}"
        )

    collapsed = _collapse_single_directory(target)
    _validate_dataset_directory(collapsed)

    return collapsed


def materialize_dataset_source(
    workspace: Path,
    dataset_path: Optional[Path],
) -> Optional[Path]:
    """
    Retourne une source dataset directement utilisable.

    Formats acceptés :
    - dossier déjà préparé ;
    - ZIP/TAR/TGZ contenant des images ou fichiers tabulaires ;
    - CSV/TSV/JSON/Parquet/Excel ;
    - script Python de préparation générant un dossier, fichier ou ZIP.
    """
    if dataset_path is None:
        return None

    workspace = workspace.resolve()
    source = dataset_path.expanduser().resolve()

    if not source.exists():
        raise FileNotFoundError(
            f"Dataset not found: {source}"
        )

    generated = False
    original_source = source

    if (
        source.is_file()
        and source.suffix.lower()
        in DATASET_PREPARATION_EXTENSIONS
    ):
        print("[DATASET_SOURCE_DETECTED]", flush=True)
        print("[DATASET_PREPARATION_STARTED]", flush=True)
        source = execute_dataset_preparation_script(
            script_path=source,
            workspace=workspace,
        )
        generated = True
        print("[DATASET_PREPARATION_COMPLETED]", flush=True)

    # Le script de préparation peut lui-même produire une archive.
    if source.is_file() and _is_archive(source):
        source = _extract_archive_dataset(
            archive_path=source,
            workspace=workspace,
        )

    elif source.is_dir():
        source = _collapse_single_directory(source)
        _validate_dataset_directory(source)

    elif source.is_file():
        suffix = source.suffix.lower()

        if suffix not in TABULAR_DATASET_EXTENSIONS:
            raise ValueError(
                "Unsupported dataset format: "
                f"{source.name}. Supported formats are directories, "
                "ZIP/TAR/TGZ, Python preparation scripts, CSV/TSV, "
                "JSON/JSONL, Parquet and Excel."
            )

        _validate_tabular_file(source)

    else:
        raise ValueError(
            f"Unsupported dataset source: {source}"
        )

    report = {
        "original_path": str(original_source),
        "materialized_path": str(source),
        "generated": generated,
        "source_type": (
            "directory"
            if source.is_dir()
            else source.suffix.lower().lstrip(".")
        ),
    }

    print("[DATASET_PATH_RESOLVED]", flush=True)
    print("[DATASET_VALIDATED]", flush=True)
    print("[DATASET_MATERIALIZED]", flush=True)

    os.environ[
        "PLATFORM_MATERIALIZED_DATASET_PATH"
    ] = str(source)
    os.environ[
        "PLATFORM_ORIGINAL_DATASET_PATH"
    ] = str(original_source)
    os.environ[
        "PLATFORM_DATASET_WAS_GENERATED"
    ] = str(bool(generated)).lower()

    print(
        "[PLATFORM_DATASET] Materialization completed: "
        f"{json.dumps(report, ensure_ascii=False)}"
    )

    return source


def materialize_dataset_alias(
    source: Path,
    destination: Path,
) -> None:
    source = source.resolve()
    destination = destination.resolve()
    destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    if destination.exists() or destination.is_symlink():
        try:
            if destination.resolve() == source:
                return
        except Exception:
            pass

        # Ne jamais écraser un fichier utilisateur existant.
        print(
            "[PLATFORM_DATASET] Alias skipped because destination "
            f"already exists: {destination}"
        )
        return

    try:
        destination.symlink_to(
            source,
            target_is_directory=source.is_dir(),
        )
    except Exception:
        if source.is_dir():
            shutil.copytree(
                source,
                destination,
                dirs_exist_ok=False,
            )
        else:
            shutil.copy2(
                source,
                destination,
            )


def extract_hardcoded_dataset_paths(
    script_path: Optional[Path],
) -> List[str]:
    """
    Détecte les chemins de datasets écrits en dur dans les scripts utilisateurs.

    Exemples :
      pd.read_csv("data/iris.csv")
      ImageFolder("dataset/train")
      open("inputs/data.csv")
    """
    if script_path is None:
        return []

    try:
        code = script_path.read_text(
            encoding="utf-8",
            errors="ignore",
        )
    except Exception:
        return []

    candidates: List[str] = []

    supported_pattern = (
        r"csv|tsv|txt|json|jsonl|parquet|xlsx|xls|"
        r"zip|tar|tgz"
    )

    for match in re.finditer(
        rf"""["'](?P<path>[^"']+\.(?:{supported_pattern}))["']""",
        code,
        re.IGNORECASE,
    ):
        value = match.group("path").strip()

        if value and not value.startswith(
            (
                "http://",
                "https://",
                "s3://",
                "wasbs://",
                "gs://",
            )
        ):
            candidates.append(value)

    for match in re.finditer(
        rf"""os\.path\.join\(\s*["'](?P<dir>[^"']+)["']\s*,\s*["'](?P<file>[^"']+\.(?:{supported_pattern}))["']\s*\)""",
        code,
        re.IGNORECASE,
    ):
        candidates.append(
            str(
                Path(match.group("dir"))
                / match.group("file")
            )
        )

    unique: List[str] = []
    seen: set[str] = set()

    for candidate in candidates:
        normalized = (
            candidate
            .replace("\\", "/")
            .strip("/")
        )

        if (
            normalized
            and normalized not in seen
            and ".." not in Path(normalized).parts
        ):
            seen.add(normalized)
            unique.append(normalized)

    return unique


def _tabular_aliases(
    source: Path,
) -> List[str]:
    original_name = source.name or "dataset.csv"
    suffix = source.suffix.lower()

    aliases = [
        original_name,
        f"data/{original_name}",
        f"datasets/{original_name}",
        f"input_data/{original_name}",
    ]

    if suffix in {".csv", ".tsv", ".txt"}:
        aliases.extend(
            [
                "dataset.csv",
                "data.csv",
                "train.csv",
                "input.csv",
                "uploaded_dataset.csv",
                "data/dataset.csv",
                "data/data.csv",
                "data/train.csv",
                "datasets/dataset.csv",
            ]
        )

    return aliases


def create_dataset_aliases(
    workspace: Path,
    dataset_path: Optional[Path],
    script_path: Optional[Path] = None,
) -> Optional[Path]:
    """
    Expose le dataset sous des chemins conventionnels sans modifier la source.

    Pour un dossier/ZIP image :
      data/, dataset/, datasets/, input_data/

    Pour un CSV :
      dataset.csv, data.csv, train.csv, input.csv, data/dataset.csv...
    """
    if dataset_path is None:
        return None

    workspace = workspace.resolve()
    source = materialize_dataset_source(
        workspace,
        dataset_path,
    )

    if source is None:
        return None

    # Garde-fou : un fichier Python est une source de préparation, jamais un
    # dataset final. Cette situation indiquerait une régression interne.
    if source.is_file() and source.suffix.lower() == ".py":
        raise RuntimeError(
            "Internal dataset materialization error: a Python preparation "
            "script reached the training stage instead of its generated data."
        )

    if source.is_dir():
        aliases = [
            "data",
            "dataset",
            "datasets",
            "input_data",
        ]

        for alias in aliases:
            materialize_dataset_alias(
                source,
                workspace / alias,
            )

        print(
            "[PLATFORM_DATASET] Directory dataset ready: "
            f"source={source}"
        )

    else:
        aliases = _tabular_aliases(source)
        aliases.extend(
            extract_hardcoded_dataset_paths(
                script_path
            )
        )

        seen: set[str] = set()

        for alias in aliases:
            normalized = (
                str(alias or "")
                .replace("\\", "/")
                .strip()
            )

            if (
                not normalized
                or normalized.startswith("/")
                or ".." in Path(normalized).parts
                or normalized in seen
            ):
                continue

            seen.add(normalized)

            try:
                materialize_dataset_alias(
                    source,
                    workspace / normalized,
                )
                print(
                    "[PLATFORM_DATASET] Alias created: "
                    f"{workspace / normalized}"
                )
            except Exception as exc:
                print(
                    "[PLATFORM_DATASET] Could not create alias "
                    f"{normalized}: {exc}"
                )

    # Manifeste de traçabilité sauvegardé avec les autres outputs.
    try:
        outputs_dir = prepare_outputs_dir(workspace)
        manifest_path = (
            outputs_dir
            / "dataset_materialization.json"
        )

        manifest = {
            "original_dataset_path": str(
                dataset_path.resolve()
            ),
            "materialized_dataset_path": str(source),
            "materialized_is_directory": source.is_dir(),
            "data_type": get_data_type_from_platform(),
            "project_type": get_project_type_from_platform(),
            "created_at_epoch": int(time.time()),
        }

        manifest_path.write_text(
            json.dumps(
                manifest,
                indent=2,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
    except Exception as exc:
        print(
            "[PLATFORM_DATASET] Could not write materialization "
            f"manifest: {exc}"
        )

    return source


def detect_script_cli_args(script_path: Path) -> set[str]:
    """
    Analyse le script avec `ast` pour découvrir les arguments argparse
    qu'il déclare réellement (ex : --output_dir, --data_path).

    La plateforme ne passe jamais un argument que le script ne connaît pas :
    un script qui utilise parse_args() (et non parse_known_args) échouerait
    sinon avec "unrecognized arguments".
    """
    detected: set[str] = set()

    try:
        tree = ast.parse(script_path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"[PLATFORM] Could not parse script for CLI args {script_path}: {exc}")
        return detected

    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue

        if not isinstance(node.func, ast.Attribute):
            continue

        if node.func.attr != "add_argument":
            continue

        for arg in node.args:
            if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                name = arg.value
                if name.startswith("--"):
                    detected.add(name[2:].replace("-", "_"))
                elif name.startswith("-"):
                    detected.add(name[1:].replace("-", "_"))

    return detected


# Alias plateforme -> flag(s) que le script pourrait déclarer.
_DATASET_FLAG_ALIASES = {
    "data_path",
    "data-path",
    "dataset_path",
    "dataset-path",
    "dataset",
    "input",
    "data",
    "train_data",
}
_OUTPUT_FLAG_ALIASES = {
    "output_dir",
    "output-dir",
    "output",
    "out_dir",
    "out",
}


def _command_with_declared_flags(
    script: str,
    dataset_path: Optional[Path],
    output_dir: Path,
    declared: set[str],
) -> List[str]:
    command = [sys.executable, script]

    if dataset_path is not None:
        dataset_flag = next(
            (flag for flag in _DATASET_FLAG_ALIASES if flag in declared),
            None,
        )
        if dataset_flag:
            command.extend([f"--{dataset_flag}", str(dataset_path)])

    output_flag = next(
        (flag for flag in _OUTPUT_FLAG_ALIASES if flag in declared),
        None,
    )
    if output_flag:
        command.extend([f"--{output_flag}", str(output_dir)])

    if len(command) == 2:
        return []

    return command


def build_script_commands(
    script_path: Path,
    dataset_path: Optional[Path],
    output_dir: Path,
) -> List[List[str]]:
    commands: List[List[str]] = []

    script = str(script_path)

    declared = detect_script_cli_args(script_path)

    if declared:
        tailored = _command_with_declared_flags(
            script,
            dataset_path,
            output_dir,
            declared,
        )
        if tailored:
            commands.append(tailored)

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

    # Un script qui ne déclare que --output_dir doit le recevoir, sinon
    # la détection automatique de l'artefact fonctionnera quand même grâce
    # à la variable d'environnement OUTPUT_DIR.
    if declared and _OUTPUT_FLAG_ALIASES & declared and dataset_path is None:
        commands.append(
            [sys.executable, script, "--output_dir", str(output_dir)]
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
    if platform_experiment_id:
        env["PLATFORM_MLFLOW_EXPERIMENT_ID"] = str(platform_experiment_id)

    # The platform owns the official MLflow run. Removing these generic
    # variables prevents user scripts from accidentally hijacking or ending it.
    env.pop("MLFLOW_RUN_ID", None)
    env.pop("MLFLOW_EXPERIMENT_ID", None)

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


def _safe_jsonable(value: Any) -> Any:
    """Convertit des objets arbitraires (estimators, sérialiseurs) en JSON-safe."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(k): _safe_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_safe_jsonable(v) for v in value]
    if hasattr(value, "get_params"):
        try:
            return value.get_params()
        except Exception:
            pass
    return str(value)


def detect_estimator_framework(estimator: Any) -> Optional[str]:
    """
    Détection générique du framework d'un estimateur chargé.
    Retourne un jeton de stratégie d'enregistrement MLflow :
      sklearn | keras | tensorflow | torch | onnx | xgboost | lightgbm | catboost | pyfunc
    """
    if estimator is None:
        return None

    module_name = ""
    try:
        cls = getattr(estimator, "__class__", None) or type(estimator)
        module_name = str(getattr(cls, "__module__", "") or "").lower()
    except Exception:
        module_name = ""

    module_name = module_name.strip().lower()

    if module_name.startswith("xgboost"):
        return "xgboost"
    if module_name.startswith("lightgbm"):
        return "lightgbm"
    if module_name.startswith("catboost"):
        return "catboost"
    if module_name.startswith("tensorflow.keras") or "keras" in module_name:
        return "keras"
    if module_name.startswith("tensorflow") or module_name.startswith("tensorflow_hub"):
        return "tensorflow"
    if module_name.startswith("torch"):
        return "torch"
    if module_name == "onnx" or "onnx" in module_name:
        return "onnx"
    if module_name.startswith("sklearn") or module_name.startswith("sklearn2pmml"):
        return "sklearn"
    if hasattr(estimator, "predict") or callable(estimator):
        return "pyfunc"
    return None


def try_log_pickle_model(
    model_path: Path,
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Optional[str]:
    companion_artifacts = companion_artifacts or {}

    # ------------------------------------------------------------------
    # 1) Chargement CORRECT selon le format :
    #    - .joblib  -> toujours joblib.load(...) (canvas du joblib)
    #    - .pkl     -> pickle.load(...) (peut néanmoins être un objet joblib)
    #    On ne fait JAMAIS pickle.load() sur un fichier joblib, ni joblib.load()
    #    pour forcer un .pkl : on tente le format natif puis on suit la structure.
    # ------------------------------------------------------------------
    obj = None
    extension = model_path.suffix.lower()

    if extension == ".joblib":
        try:
            obj = joblib.load(model_path)
        except Exception as exc:
            print(
                "[MODEL_OBJECT_LOADED] joblib.load failed for "
                f"{model_path}: {exc}"
            )
            obj = None
    else:  # .pkl / .pickle / autre
        try:
            with open(model_path, "rb") as file:
                obj = pickle.load(file)
        except Exception as pickle_exc:
            # Un .pkl peut en réalité avoir été produit par joblib.dump.
            try:
                obj = joblib.load(model_path)
            except Exception:
                print(
                    f"[MODEL_OBJECT_LOADED] pickle.load failed for "
                    f"{model_path}: {pickle_exc}"
                )
                obj = None

    # ------------------------------------------------------------------
    # 2) Extraction STRUCTURELLE (bundle) :
    #    Estimator utilisable directement  -> obj
    #    dict {"model": estimator, ...}   -> estimateur = obj["model"],
    #       les autres clés (labels, classes, image_size, preprocessor,
    #       feature_extractor, feature_names, target, metadata...) sont
    #       conservées comme métadonnées/artefacts compagnons.
    # ------------------------------------------------------------------
    estimator = None
    bundle_metadata: Dict[str, Any] = {}
    companion_keys = {
        "labels", "classes", "class_names", "image_size", "mode",
        "preprocessor", "feature_extractor", "feature_names",
        "target", "metadata", "scaler", "encoder", "model_path",
        "preprocessing", "transform", "tokenizer", "vectorizer",
    }

    if obj is not None and hasattr(obj, "predict"):
        estimator = obj
    elif isinstance(obj, dict):
        for primary_key in ("model", "estimator", "pipeline", "classifier"):
            candidate = obj.get(primary_key)
            if candidate is not None and hasattr(candidate, "predict"):
                estimator = candidate
                for key, value in obj.items():
                    if key != primary_key:
                        bundle_metadata[key] = value
                break

    if estimator is None:
        print(
            f"[MODEL_ESTIMATOR_EXTRACTED] No predict-capable estimator "
            f"found in {model_path} (type={type(obj).__name__ if obj is not None else 'None'})."
        )

    # Extracteur de framework générique (sklearn / pyfunc).
    framework = detect_estimator_framework(estimator)

    print(
        f"[MODEL_FORMAT_DETECTED] extension={extension} format=joblib"
        if extension == ".joblib"
        else f"[MODEL_FORMAT_DETECTED] extension={extension} format=pickle"
    )
    if framework:
        print(f"[MODEL_FRAMEWORK_DETECTED] framework={framework}", flush=True)

    # ------------------------------------------------------------------
    # 3) Enregistrement via la stratégie adaptée (jamais pyfunc en 1re).
    # ------------------------------------------------------------------
    if estimator is not None and framework == "sklearn":
        try:
            mlflow.sklearn.log_model(
                estimator,
                artifact_path="model",
                metadata=bundle_metadata or None,
            )
            return mlflow.get_artifact_uri("model")
        except Exception as exc:
            print(
                f"[MODEL_REGISTRATION_FAILED] mlflow.sklearn.log_model "
                f"failed for {model_path}: {exc}"
            )

    # Wrapper pyfunc avec compagnons s'ils existent (scaler/preprocessor).
    if companion_artifacts or estimator is not None:
        try:
            artifacts = {"model_path": str(model_path)}
            artifacts.update(companion_artifacts)
            if bundle_metadata:
                artifacts["bundle_metadata.json"] = json.dumps(
                    _safe_jsonable(bundle_metadata),
                    ensure_ascii=False,
                    default=str,
                )

            mlflow.pyfunc.log_model(
                artifact_path="model",
                python_model=GenericPyFuncWrapper(),
                artifacts=artifacts,
            )

            if estimator is not None or bundle_metadata:
                print("[MODEL_REGISTRATION_COMPLETED]", flush=True)
            return mlflow.get_artifact_uri("model")
        except Exception as exc:
            print(
                f"[MODEL_REGISTRATION_FAILED] pyfunc log_model failed for "
                f"{model_path}: {exc}"
            )

    print(
        f"[MODEL_REGISTRATION_FAILED] Nothing loadable/usable to log for "
        f"{model_path}."
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


def _load_model_metadata_payload(
    companion_artifacts: Optional[Dict[str, str]],
) -> Dict[str, Any]:
    companion_artifacts = companion_artifacts or {}
    metadata_path = companion_artifacts.get("metadata_path")

    if not metadata_path:
        return {}

    path = Path(metadata_path)

    if not path.exists() or not path.is_file():
        return {}

    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return payload if isinstance(payload, dict) else {}
    except Exception as exc:
        print(
            "[PLATFORM_MODEL] Could not parse model metadata "
            f"{path}: {exc}"
        )
        return {}



def try_log_pytorch_model(
    model_path: Path,
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Optional[str]:
    companion_artifacts = dict(companion_artifacts or {})

    try:
        artifacts = {"model_path": str(model_path.resolve())}
        artifacts.update(companion_artifacts)

        source_file = _find_training_source(model_path)
        if source_file:
            artifacts["source_file"] = str(source_file)

        metadata_payload = _load_model_metadata_payload(
            companion_artifacts
        )

        if is_image_project() and not metadata_payload:
            raise RuntimeError(
                "Image model metadata is missing. Expected a valid "
                "model_metadata.json containing at least image_size and "
                "preprocessing information."
            )

        if is_image_project():
            preprocessing_payload = (
                metadata_payload.get("preprocessing", {})
                if isinstance(metadata_payload.get("preprocessing"), dict)
                else {}
            )
            image_size = (
                metadata_payload.get("image_size")
                or preprocessing_payload.get("image_size")
                or preprocessing_payload.get("resize")
            )
            class_names = (
                metadata_payload.get("class_names")
                or metadata_payload.get("classes")
                or metadata_payload.get("labels")
                or []
            )

            if not image_size:
                raise RuntimeError(
                    "Image model metadata does not contain image_size/resize."
                )

            if not preprocessing_payload:
                raise RuntimeError(
                    "Image model metadata does not contain preprocessing."
                )

            if not class_names:
                raise RuntimeError(
                    "Image model metadata does not contain class_names."
                )

        python_model = TorchPyFuncWrapper()
        # Copie embarquée indépendante. Elle reste disponible après
        # sérialisation/désérialisation même si un ancien backend MLflow ne
        # restitue pas correctement l'artefact metadata_path.
        python_model.embedded_metadata = dict(metadata_payload)
        python_model.metadata = dict(metadata_payload)
        python_model.preprocessing = (
            metadata_payload.get("preprocessing", {})
            if isinstance(metadata_payload, dict)
            else {}
        )

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=python_model,
            artifacts=artifacts,
            pip_requirements=[
                "mlflow",
                "torch",
                "torchvision",
                "numpy",
                "pandas",
                "pillow",
            ],
            metadata=metadata_payload or None,
        )

        model_uri = mlflow.get_artifact_uri("model")
        loaded_model = mlflow.pyfunc.load_model(model_uri)

        unwrapped = loaded_model.unwrap_python_model()
        loaded_metadata = getattr(unwrapped, "metadata", {}) or {}

        print(
            "[PLATFORM_MODEL] Logged PyTorch model metadata keys="
            f"{sorted(loaded_metadata.keys())}"
        )

        if is_image_project() and not loaded_metadata:
            raise RuntimeError(
                "Post-log validation failed: the reloaded image model has "
                "no preprocessing metadata."
            )

        return model_uri

    except Exception as exc:
        print(
            "[PLATFORM_MODEL] pytorch pyfunc log/load failed for "
            f"{model_path}: {exc}"
        )
        return None


def log_model_artifact(
    model_path: Path,
    companion_artifacts: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    extension = model_path.suffix.lower()
    companions = dict(companion_artifacts or {})

    result: Dict[str, Any] = {
        "path": str(model_path),
        "extension": extension,
        "logged_model_uri": None,
        "artifact_only": False,
        "companion_artifacts": companions,
    }

    if extension in {".pkl", ".joblib", ".pickle"}:
        logged_uri = try_log_pickle_model(model_path, companions)
        if logged_uri:
            result["logged_model_uri"] = logged_uri
            return result

    if extension in {".pt", ".pth", ".torchscript"}:
        logged_uri = try_log_pytorch_model(
            model_path,
            companion_artifacts=companions,
        )
        if logged_uri:
            result["logged_model_uri"] = logged_uri
            return result

    mlflow.log_artifact(
        str(model_path),
        artifact_path="model_artifacts",
    )
    result["artifact_only"] = True
    return result

def log_companion_artifacts(
    companion_artifacts: Dict[str, str],
) -> Dict[str, str]:
    """
    Journalise les fichiers compagnons dans une arborescence stable MLflow.

    Le dictionnaire retourné contient les chemins MLflow stockés dans le
    manifeste, et non les chemins locaux éphémères du pod.
    """
    logged: Dict[str, str] = {}

    for key, path_value in (companion_artifacts or {}).items():
        path = Path(path_value)

        if not path.exists() or not path.is_file():
            print(
                "[PLATFORM_ARTIFACT] Companion artifact missing: "
                f"{key}={path}"
            )
            continue

        artifact_path = "platform_model/artifacts"

        try:
            mlflow.log_artifact(
                str(path),
                artifact_path=artifact_path,
            )

            logged[key] = f"{artifact_path}/{path.name}"

            print(
                "[PLATFORM_ARTIFACT] Companion artifact logged: "
                f"{key} -> {logged[key]}"
            )
        except Exception as exc:
            print(
                "[PLATFORM_ARTIFACT] Could not log companion artifact "
                f"{key}={path}: {exc}"
            )

    return logged


def build_model_manifest(
    primary_model_path: Path,
    companion_artifacts: Dict[str, str],
    metrics: Dict[str, float],
    workspace: Path,
) -> Dict[str, Any]:
    """Build a portable manifest used by serving and audit components."""
    def relative_or_absolute(path_value: str) -> str:
        raw = str(path_value or "").strip()

        if raw.startswith("platform_model/"):
            return raw

        path = Path(raw).resolve()

        try:
            return str(path.relative_to(workspace.resolve()))
        except Exception:
            return str(path)

    metadata_payload: Dict[str, Any] = {}

    # Les chemins reçus ici peuvent être des chemins MLflow déjà journalisés.
    # Pour enrichir le manifeste, on relit donc en priorité le fichier local
    # découvert avant journalisation.
    local_companions = find_companion_artifacts(
        primary_model_path=primary_model_path,
        model_artifacts=[primary_model_path],
        workspace=workspace,
    )
    local_metadata_path = local_companions.get("metadata_path")

    if local_metadata_path:
        metadata_payload = _read_json_dict(Path(local_metadata_path))

    preprocessing = (
        metadata_payload.get("preprocessing", {})
        if isinstance(metadata_payload.get("preprocessing"), dict)
        else {}
    )

    manifest: Dict[str, Any] = {
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
        "runtime_mode": _clean_env_value(os.environ.get("RUNTIME_MODE"))
        or "CUSTOM_DOCKERFILE",
        "project_type": get_project_type_from_platform(),
        "target_column": get_target_column_from_platform(),
        "registered_model_name": _clean_env_value(
            os.environ.get("PLATFORM_REGISTERED_MODEL_NAME")
        ),
        "promotion_owner": "BACKEND",
        "validation_status": "PENDING",
    }

    # Les champs sont dupliqués volontairement au niveau racine afin que le
    # serveur de prédiction puisse les lire sans dépendre d'un chemin interne
    # spécifique à MLflow.
    for key in (
        "framework",
        "data_type",
        "model_type",
        "image_size",
        "input_shape",
        "class_names",
    ):
        value = metadata_payload.get(key)
        if value not in (None, "", [], {}):
            manifest[key] = value

    if preprocessing:
        manifest["preprocessing"] = preprocessing

    return manifest

def run_standard_runtime(
    script_path: Path,
    dataset_path: Optional[Path],
    tracking_uri: str,
    experiment_name: str,
    run_name: str,
    workspace: Optional[Path] = None,
    registered_model_name: Optional[str] = None,
    enforce_whitelist: Optional[bool] = None,
) -> RunResult:
    workspace = workspace or script_path.parent
    workspace = workspace.resolve()

    script_path = script_path.resolve()

    runtime_mode = (
        os.environ.get("RUNTIME_MODE", "").strip().upper() or "CUSTOM_DOCKERFILE"
    )

    # La whitelist n'a de sens que pour le runtime standard (STANDARD_RUNTIME).
    # En mode Dockerfile personnalisé (CUSTOM_DOCKERFILE), l'image est construite
    # avec requirements.txt : toutes les dépendances sont disponibles, aucun import
    # ne doit être bloqué par la liste blanche.
    if enforce_whitelist is None:
        enforce_whitelist = runtime_mode == "STANDARD_RUNTIME"

    script_code = script_path.read_text(encoding="utf-8")
    analysis = analyze_script_imports(script_code)

    if enforce_whitelist and not analysis["supported"]:
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

    print("[STEP] TRAINING_RUNNING", flush=True)
    print("[STEP] USER_SCRIPT_RUNNING", flush=True)

    result = run_user_script(
        workspace=workspace,
        entrypoint=str(script_path.name),
        dataset_path=dataset_path,
        platform_run_id=platform_run_id,
        platform_experiment_id=platform_experiment_id,
    )

    script_end_time_ms = int(time.time() * 1000)

    print("[STEP] USER_SCRIPT_COMPLETED", flush=True)

    parsed_output_metrics = parse_metrics(
        f"{result.stdout or ''}\n{result.stderr or ''}"
    )
    metrics = merge_metrics(
        parsed_output_metrics,
        load_metrics_json(workspace),
        overwrite=True,
    )
    metrics = sanitize_metrics(metrics)

    print("[STEP] METRICS_EXTRACTED", flush=True)

    print(
        "[PLATFORM_METRICS] metrics available after script execution: "
        f"{json.dumps(metrics, ensure_ascii=False, sort_keys=True)}"
    )

    model_artifacts = find_model_artifacts(
        workspace,
        stdout_text=f"{result.stdout or ''}\n{result.stderr or ''}",
    )

    if model_artifacts:
        print(
            "[PLATFORM_MODEL] model_artifacts_count="
            f"{len(model_artifacts)}"
        )
        print("[STEP] MODEL_ARTIFACT_DETECTED", flush=True)
    else:
        print("[PLATFORM_MODEL] model_artifacts_count=0")

    # Métriques automatiques calculées par la plateforme.
    # Elles complètent les métriques imprimées par le script, sans les écraser.
    pre_primary_model_path = select_primary_model_artifact(model_artifacts)
    pre_companion_artifacts = (
        find_companion_artifacts(
            pre_primary_model_path,
            model_artifacts,
            workspace=workspace,
        )
        if pre_primary_model_path
        else {}
    )
    if pre_primary_model_path:
        pre_companion_artifacts = ensure_model_metadata_artifact(
            primary_model_path=pre_primary_model_path,
            companion_artifacts=pre_companion_artifacts,
            workspace=workspace,
        )
    auto_metrics = auto_evaluate_metrics_from_platform(
        workspace=workspace,
        dataset_path=dataset_path,
        primary_model_path=pre_primary_model_path,
        companion_artifacts=pre_companion_artifacts,
    )
    metrics = merge_metrics(metrics, auto_metrics, overwrite=False)
    metrics = sanitize_metrics(metrics)

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

        configured_priorities = get_platform_metric_priorities()
        if configured_priorities:
            mlflow.set_tag("primary_metric", configured_priorities[0])
            mlflow.set_tag(
                "metric_priorities",
                json.dumps(configured_priorities, ensure_ascii=False),
            )

        if dataset_path is not None:
            mlflow.log_param("dataset_path", str(dataset_path))

        tracking_client = MlflowClient()
        tracking_client.log_metric(
            platform_run_id,
            "return_code",
            float(result.returncode),
        )
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

        business_metrics = sanitize_metrics(metrics)

        if business_metrics:
            for metric_name, metric_value in business_metrics.items():
                tracking_client.log_metric(
                    platform_run_id,
                    metric_name,
                    float(metric_value),
                )
                print(
                    "[PLATFORM_MLFLOW_METRIC] "
                    f"run_id={platform_run_id}, "
                    f"{metric_name}={metric_value}"
                )

            tracking_client.set_tag(
                platform_run_id,
                "metrics_status",
                "logged",
            )
            tracking_client.set_tag(
                platform_run_id,
                "logged_metric_names",
                json.dumps(
                    sorted(business_metrics.keys()),
                    ensure_ascii=False,
                ),
            )

            mlflow.log_text(
                json.dumps(
                    business_metrics,
                    indent=2,
                    ensure_ascii=False,
                ),
                artifact_file="logs/parsed_metrics.json",
            )

            # Read-after-write verification: a run is not eligible for official
            # versioning unless MLflow really persisted its business metrics.
            persisted_run = tracking_client.get_run(platform_run_id)
            persisted_metrics = sanitize_metrics(
                dict(persisted_run.data.metrics or {})
            )

            missing_metric_names = sorted(
                set(business_metrics) - set(persisted_metrics)
            )
            if missing_metric_names:
                raise RuntimeError(
                    "MLflow did not persist expected metrics: "
                    + ", ".join(missing_metric_names)
                )

            print(
                "[PLATFORM_MLFLOW_METRIC] persisted metrics verified: "
                f"{persisted_metrics}"
            )
        else:
            tracking_client.set_tag(
                platform_run_id,
                "metrics_status",
                "missing",
            )
            tracking_client.set_tag(
                platform_run_id,
                "metrics_warning",
                "No business metric could be parsed or automatically evaluated.",
            )

        if model_artifacts:
            print("[PLATFORM_MODEL] Local model artifact detected.")
            print(
                "[PLATFORM_MODEL] detected_model_files="
                f"{[str(path) for path in model_artifacts]}"
            )

            primary_model_path = select_primary_model_artifact(model_artifacts)
            companion_artifacts = (
                find_companion_artifacts(
                    primary_model_path,
                    model_artifacts,
                    workspace=workspace,
                )
                if primary_model_path
                else {}
            )

            if primary_model_path:
                companion_artifacts = ensure_model_metadata_artifact(
                    primary_model_path=primary_model_path,
                    companion_artifacts=companion_artifacts,
                    workspace=workspace,
                )

            artifact_results: List[Dict[str, Any]] = []

            if primary_model_path:
                canonical_primary = canonicalize_primary_artifact(
                    primary_model_path,
                    workspace,
                )

                print(f"[PLATFORM_MODEL] selected_primary_model={primary_model_path}")
                print(f"[PLATFORM_MODEL] companion_artifacts={companion_artifacts}")

                artifact_result = log_model_artifact(
                    canonical_primary,
                    companion_artifacts=companion_artifacts,
                )
                artifact_results.append(artifact_result)
                try:
                    mlflow.log_artifact(
                        str(primary_model_path),
                        artifact_path="raw_model",
                    )

                    logged_companion_artifacts = log_companion_artifacts(
                        companion_artifacts
                    )

                    manifest = build_model_manifest(
                        canonical_primary,
                        logged_companion_artifacts,
                        metrics,
                        workspace,
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
                    print("[STEP] MLFLOW_REGISTERED", flush=True)

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

    # Validation séparée du dataset : un chemin inexistant produit une erreur
    # claire (DATASET_PATH_MISMATCH) avant même de lancer le script utilisateur.
    if dataset_path is not None and not dataset_path.exists():
        print(
            "[STEP] DATASET_INVALID",
            flush=True,
        )
        print(
            f"[PLATFORM_RUNNER] ERROR: dataset path not found: {dataset_path}",
            flush=True,
        )
        return 1

    runtime_mode = os.environ.get("RUNTIME_MODE", "").strip().upper()

    try:
        result = run_standard_runtime(
            script_path=script_path,
            dataset_path=dataset_path,
            tracking_uri=args.tracking_uri,
            experiment_name=args.experiment_name,
            run_name=args.run_name,
            workspace=workspace,
            registered_model_name=args.registered_model_name,
            enforce_whitelist=None,
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