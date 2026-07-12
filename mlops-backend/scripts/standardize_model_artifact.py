import argparse
import json
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional

import joblib
import mlflow
import mlflow.pyfunc
import mlflow.sklearn
from mlflow.tracking import MlflowClient


AUTO_SKLEARN_EXTENSIONS = {
    ".pkl",
    ".joblib",
    ".pickle",
    ".sav",
}

KERAS_EXTENSIONS = {
    ".h5",
    ".keras",
}

PYTORCH_EXTENSIONS = {
    ".pt",
    ".pth",
}

ONNX_EXTENSIONS = {
    ".onnx",
}

ALL_SUPPORTED_EXTENSIONS = (
    AUTO_SKLEARN_EXTENSIONS
    | KERAS_EXTENSIONS
    | PYTORCH_EXTENSIONS
    | ONNX_EXTENSIONS
)

PREFERRED_MODEL_NAMES = [
    "model.pkl",
    "model.joblib",
    "model.pickle",
    "model.sav",
    "trained_model.pkl",
    "trained_model.joblib",
    "final_model.pkl",
    "final_model.joblib",
    "best_model.pkl",
    "best_model.joblib",
    "pipeline.pkl",
    "pipeline.joblib",
    "model.h5",
    "model.keras",
    "model.onnx",
    "model.pt",
    "model.pth",
]


def print_json(result: Dict[str, Any]) -> None:
    print(json.dumps(result, indent=2, ensure_ascii=False))


def has_text(value: Optional[str]) -> bool:
    return value is not None and str(value).strip() != ""


def safe_short_text(value: Any, limit: int = 3500) -> str:
    text = "" if value is None else str(value)
    if len(text) <= limit:
        return text
    return text[:limit]


def has_mlmodel_file(path: Path) -> bool:
    if not path.exists():
        return False

    if path.is_file() and path.name == "MLmodel":
        return True

    if path.is_dir() and (path / "MLmodel").exists():
        return True

    return False


def download_artifacts_safely(
    run_id: str,
    artifact_path: str,
    dst_path: str,
) -> Path:
    """
    Essaie d'abord de télécharger artifact_path=model.
    Si ce dossier n'existe pas, télécharge tous les artifacts du run.
    """
    try:
        local_artifact_path = mlflow.artifacts.download_artifacts(
            run_id=run_id,
            artifact_path=artifact_path,
            dst_path=dst_path,
        )
        return Path(local_artifact_path)
    except Exception as exc:
        print(
            "[STANDARDIZER] Could not download artifact_path="
            f"{artifact_path}. Falling back to full run artifacts. Reason: {exc}"
        )

        local_artifact_path = mlflow.artifacts.download_artifacts(
            run_id=run_id,
            dst_path=dst_path,
        )
        return Path(local_artifact_path)


def find_all_model_artifacts(root: Path) -> List[Path]:
    candidates: List[Path] = []

    ignored_parts = {
        "__pycache__",
        ".git",
        ".venv",
        "venv",
        "env",
        "site-packages",
        "logs",
    }

    for ext in ALL_SUPPORTED_EXTENSIONS:
        for path in root.rglob(f"*{ext}"):
            if not path.is_file():
                continue

            if set(path.parts).intersection(ignored_parts):
                continue

            candidates.append(path)

    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates


def choose_best_candidate(candidates: List[Path]) -> Optional[Path]:
    if not candidates:
        return None

    for preferred_name in PREFERRED_MODEL_NAMES:
        for candidate in candidates:
            if candidate.name.lower() == preferred_name.lower():
                return candidate

    sklearn_candidates = [
        candidate
        for candidate in candidates
        if candidate.suffix.lower() in AUTO_SKLEARN_EXTENSIONS
    ]

    if sklearn_candidates:
        return sklearn_candidates[0]

    keras_candidates = [
        candidate
        for candidate in candidates
        if candidate.suffix.lower() in KERAS_EXTENSIONS
    ]

    if keras_candidates:
        return keras_candidates[0]

    onnx_candidates = [
        candidate
        for candidate in candidates
        if candidate.suffix.lower() in ONNX_EXTENSIONS
    ]

    if onnx_candidates:
        return onnx_candidates[0]

    pytorch_candidates = [
        candidate
        for candidate in candidates
        if candidate.suffix.lower() in PYTORCH_EXTENSIONS
    ]

    if pytorch_candidates:
        return pytorch_candidates[0]

    return candidates[0]


def read_requirements(root: Path, extra_requirements: Optional[List[str]] = None) -> List[str]:
    possible_files = [
        root / "requirements.txt",
        root / "model" / "requirements.txt",
        root / "outputs" / "requirements.txt",
        root / "metadata" / "requirements.txt",
    ]

    requirements: List[str] = []

    for req_file in possible_files:
        if not req_file.exists():
            continue

        for line in req_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            clean = line.strip()

            if not clean or clean.startswith("#"):
                continue

            if clean not in requirements:
                requirements.append(clean)

    defaults = [
        "mlflow",
        "cloudpickle",
        "joblib",
        "pandas",
        "numpy",
    ]

    for item in defaults:
        if item not in requirements:
            requirements.append(item)

    if extra_requirements:
        for item in extra_requirements:
            if item not in requirements:
                requirements.append(item)

    return requirements


def find_schema_file(root: Path) -> Optional[Path]:
    possible_names = [
        "schema.json",
        "model_schema.json",
        "features.json",
        "metadata.json",
    ]

    for name in possible_names:
        found = list(root.rglob(name))
        if found:
            return found[0]

    return None


def safe_read_json(path: Optional[Path]) -> Dict[str, Any]:
    if path is None or not path.exists():
        return {}

    try:
        return json.loads(path.read_text(encoding="utf-8", errors="ignore"))
    except Exception:
        return {}


def get_latest_version_for_run(
    client: MlflowClient,
    registered_model_name: str,
    run_id: str,
) -> Optional[str]:
    try:
        versions = client.search_model_versions(f"name = '{registered_model_name}'")
    except Exception:
        return None

    matched = []

    for version in versions:
        if version.run_id == run_id:
            matched.append(version)

    if not matched:
        return None

    matched.sort(key=lambda v: int(v.version), reverse=True)
    return matched[0].version


def set_production_alias_safe(
    client: MlflowClient,
    registered_model_name: str,
    version: Optional[str],
) -> None:
    if not has_text(version):
        return

    client.set_registered_model_alias(
        registered_model_name,
        "production",
        version,
    )


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
) -> tuple[Any, Dict[str, Any]]:
    if visited is None:
        visited = set()
    if depth > 8:
        raise RuntimeError("Maximum nested model depth exceeded.")
    if loaded_object is None:
        raise RuntimeError("Loaded artifact is None.")

    object_id = id(loaded_object)
    if object_id in visited:
        raise RuntimeError("Circular reference detected inside model bundle.")
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
            except RuntimeError:
                continue
        raise RuntimeError(
            "The artifact is a dictionary, but no predictive object was found. "
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
            except RuntimeError:
                continue

    for attribute_name in PREDICTIVE_MODEL_KEYS:
        if hasattr(loaded_object, attribute_name):
            try:
                model, metadata = extract_predictive_model(
                    getattr(loaded_object, attribute_name), depth + 1, visited.copy()
                )
                metadata["_model_bundle_attribute"] = attribute_name
                return model, metadata
            except RuntimeError:
                continue

    raise RuntimeError(
        "Loaded object is not predictive and no nested predictive object was found. "
        f"type={type(loaded_object).__name__}"
    )

def validate_pickle_like_model(model_path: Path) -> Any:
    model = joblib.load(model_path)

    if not hasattr(model, "predict") and not callable(model):
        raise RuntimeError(
            "Loaded object is not predictive. "
            f"It has no predict() method and is not callable. type={type(model)}"
        )

    return model


def apply_common_mlflow_tags(
    source_model_file: Path,
    target_artifact_path: str,
    standardization_type: str,
    artifact_root: Path,
) -> None:
    schema_file = find_schema_file(artifact_root)
    schema = safe_read_json(schema_file)

    mlflow.set_tag("mlops.standardized", "true")
    mlflow.set_tag("mlops.standardized.type", standardization_type)
    mlflow.set_tag("mlops.standardized.source_file", source_model_file.name)
    mlflow.set_tag("mlops.standardized.target_artifact_path", target_artifact_path)

    if schema:
        mlflow.set_tag(
            "mlops.detected_schema",
            json.dumps(schema, ensure_ascii=False)[:4000],
        )


def standardize_pickle_like_model(
    model_path: Path,
    artifact_root: Path,
    run_id: str,
    registered_model_name: str,
    target_artifact_path: str,
    set_production_alias: bool,
) -> Dict[str, Any]:
    client = MlflowClient()

    model, bundle_metadata = validate_pickle_like_model(model_path)

    requirements = read_requirements(
        artifact_root,
        extra_requirements=[
            "scikit-learn",
            "imbalanced-learn",
            "xgboost",
            "lightgbm",
        ],
    )

    with mlflow.start_run(run_id=run_id):
        apply_common_mlflow_tags(
            source_model_file=model_path,
            target_artifact_path=target_artifact_path,
            standardization_type="sklearn_joblib",
            artifact_root=artifact_root,
        )

        if bundle_metadata:
            mlflow.set_tag(
                "mlops.bundle.metadata",
                json.dumps(bundle_metadata, ensure_ascii=False, default=str)[:4000],
            )
            mlflow.set_tag(
                "mlops.bundle.model_key",
                str(
                    bundle_metadata.get(
                        "_model_bundle_key",
                        bundle_metadata.get("_model_bundle_attribute", "direct"),
                    )
                ),
            )

        mlflow.sklearn.log_model(
            sk_model=model,
            artifact_path=target_artifact_path,
            registered_model_name=registered_model_name,
            pip_requirements=requirements,
        )

    new_version = get_latest_version_for_run(
        client=client,
        registered_model_name=registered_model_name,
        run_id=run_id,
    )

    if set_production_alias:
        set_production_alias_safe(
            client=client,
            registered_model_name=registered_model_name,
            version=new_version,
        )

    return {
        "success": True,
        "already_mlflow_model": False,
        "standardized": True,
        "standardization_type": "sklearn_joblib",
        "run_id": run_id,
        "registered_model_name": registered_model_name,
        "new_version": new_version,
        "source_model_file": str(model_path),
        "target_artifact_path": target_artifact_path,
        "message": "Pickle/joblib model artifact standardized successfully as MLflow sklearn model.",
    }


def standardize_keras_model(
    model_path: Path,
    artifact_root: Path,
    run_id: str,
    registered_model_name: str,
    target_artifact_path: str,
    set_production_alias: bool,
) -> Dict[str, Any]:
    try:
        import tensorflow as tf
        import mlflow.keras
    except Exception as exc:
        raise RuntimeError(
            "TensorFlow/Keras standardization requires tensorflow. "
            "Install it in the Python environment used by the backend. "
            f"Original error: {exc}"
        )

    client = MlflowClient()

    model = tf.keras.models.load_model(str(model_path))

    requirements = read_requirements(
        artifact_root,
        extra_requirements=[
            "tensorflow",
        ],
    )

    with mlflow.start_run(run_id=run_id):
        apply_common_mlflow_tags(
            source_model_file=model_path,
            target_artifact_path=target_artifact_path,
            standardization_type="keras",
            artifact_root=artifact_root,
        )

        mlflow.keras.log_model(
            model,
            artifact_path=target_artifact_path,
            registered_model_name=registered_model_name,
            pip_requirements=requirements,
        )

    new_version = get_latest_version_for_run(
        client=client,
        registered_model_name=registered_model_name,
        run_id=run_id,
    )

    if set_production_alias:
        set_production_alias_safe(
            client=client,
            registered_model_name=registered_model_name,
            version=new_version,
        )

    return {
        "success": True,
        "already_mlflow_model": False,
        "standardized": True,
        "standardization_type": "keras",
        "run_id": run_id,
        "registered_model_name": registered_model_name,
        "new_version": new_version,
        "source_model_file": str(model_path),
        "target_artifact_path": target_artifact_path,
        "message": "Keras/TensorFlow model artifact standardized successfully as MLflow keras model.",
    }


class OnnxPyfuncWrapper(mlflow.pyfunc.PythonModel):
    def load_context(self, context):
        import onnxruntime as ort

        self.session = ort.InferenceSession(context.artifacts["onnx_model"])
        self.input_names = [item.name for item in self.session.get_inputs()]
        self.output_names = [item.name for item in self.session.get_outputs()]

    def predict(self, context, model_input):
        import numpy as np
        import pandas as pd

        if isinstance(model_input, pd.DataFrame):
            array = model_input.to_numpy()
        else:
            array = np.asarray(model_input)

        if array.ndim == 1:
            array = array.reshape(1, -1)

        array = array.astype(np.float32)

        feed = {
            self.input_names[0]: array
        }

        outputs = self.session.run(None, feed)

        if len(outputs) == 1:
            return outputs[0]

        return {
            name: value
            for name, value in zip(self.output_names, outputs)
        }


def standardize_onnx_model(
    model_path: Path,
    artifact_root: Path,
    run_id: str,
    registered_model_name: str,
    target_artifact_path: str,
    set_production_alias: bool,
) -> Dict[str, Any]:
    try:
        import onnxruntime  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "ONNX standardization requires onnxruntime. "
            "Install it in the Python environment used by the backend. "
            f"Original error: {exc}"
        )

    client = MlflowClient()

    requirements = read_requirements(
        artifact_root,
        extra_requirements=[
            "onnxruntime",
        ],
    )

    with mlflow.start_run(run_id=run_id):
        apply_common_mlflow_tags(
            source_model_file=model_path,
            target_artifact_path=target_artifact_path,
            standardization_type="onnx_pyfunc",
            artifact_root=artifact_root,
        )

        mlflow.pyfunc.log_model(
            artifact_path=target_artifact_path,
            python_model=OnnxPyfuncWrapper(),
            artifacts={
                "onnx_model": str(model_path),
            },
            registered_model_name=registered_model_name,
            pip_requirements=requirements,
        )

    new_version = get_latest_version_for_run(
        client=client,
        registered_model_name=registered_model_name,
        run_id=run_id,
    )

    if set_production_alias:
        set_production_alias_safe(
            client=client,
            registered_model_name=registered_model_name,
            version=new_version,
        )

    return {
        "success": True,
        "already_mlflow_model": False,
        "standardized": True,
        "standardization_type": "onnx_pyfunc",
        "run_id": run_id,
        "registered_model_name": registered_model_name,
        "new_version": new_version,
        "source_model_file": str(model_path),
        "target_artifact_path": target_artifact_path,
        "message": "ONNX model artifact standardized successfully as MLflow pyfunc model.",
    }


class TorchPyfuncWrapper(mlflow.pyfunc.PythonModel):
    def load_context(self, context):
        import torch

        self.model = torch.load(
            context.artifacts["torch_model"],
            map_location="cpu",
        )

        if isinstance(self.model, dict):
            raise RuntimeError(
                "The .pt/.pth file contains a state_dict/checkpoint dictionary, not a full PyTorch model. "
                "Automatic serving requires a full torch.nn.Module object saved with torch.save(model, path), "
                "or a custom model class/wrapper."
            )

        self.model.eval()

    def predict(self, context, model_input):
        import numpy as np
        import pandas as pd
        import torch

        if isinstance(model_input, pd.DataFrame):
            array = model_input.to_numpy()
        else:
            array = np.asarray(model_input)

        if array.ndim == 1:
            array = array.reshape(1, -1)

        tensor = torch.tensor(array, dtype=torch.float32)

        with torch.no_grad():
            output = self.model(tensor)

        if hasattr(output, "detach"):
            output = output.detach().cpu().numpy()

        return output


def validate_torch_file_is_full_model(model_path: Path) -> None:
    try:
        import torch
    except Exception as exc:
        raise RuntimeError(
            "PyTorch standardization requires torch. "
            "Install torch in the Python environment used by the backend. "
            f"Original error: {exc}"
        )

    loaded = torch.load(str(model_path), map_location="cpu")

    if isinstance(loaded, dict):
        keys_preview = list(loaded.keys())[:20]
        raise RuntimeError(
            "The .pt/.pth file appears to be a state_dict/checkpoint dictionary, "
            "not a full PyTorch model object. "
            f"keys_preview={keys_preview}. "
            "To auto-standardize PyTorch, save the full model with torch.save(model, path), "
            "or add a custom wrapper that knows the model class."
        )

    if not hasattr(loaded, "eval") or not callable(loaded):
        raise RuntimeError(
            "The .pt/.pth file is not a callable torch.nn.Module-like object. "
            f"Loaded type={type(loaded)}."
        )


def standardize_torch_model(
    model_path: Path,
    artifact_root: Path,
    run_id: str,
    registered_model_name: str,
    target_artifact_path: str,
    set_production_alias: bool,
) -> Dict[str, Any]:
    validate_torch_file_is_full_model(model_path)

    client = MlflowClient()

    requirements = read_requirements(
        artifact_root,
        extra_requirements=[
            "torch",
        ],
    )

    with mlflow.start_run(run_id=run_id):
        apply_common_mlflow_tags(
            source_model_file=model_path,
            target_artifact_path=target_artifact_path,
            standardization_type="torch_pyfunc",
            artifact_root=artifact_root,
        )

        mlflow.pyfunc.log_model(
            artifact_path=target_artifact_path,
            python_model=TorchPyfuncWrapper(),
            artifacts={
                "torch_model": str(model_path),
            },
            registered_model_name=registered_model_name,
            pip_requirements=requirements,
        )

    new_version = get_latest_version_for_run(
        client=client,
        registered_model_name=registered_model_name,
        run_id=run_id,
    )

    if set_production_alias:
        set_production_alias_safe(
            client=client,
            registered_model_name=registered_model_name,
            version=new_version,
        )

    return {
        "success": True,
        "already_mlflow_model": False,
        "standardized": True,
        "standardization_type": "torch_pyfunc",
        "run_id": run_id,
        "registered_model_name": registered_model_name,
        "new_version": new_version,
        "source_model_file": str(model_path),
        "target_artifact_path": target_artifact_path,
        "message": "Full PyTorch model artifact standardized successfully as MLflow pyfunc model.",
    }


def unsupported_result(
    model_path: Optional[Path],
    run_id: str,
    registered_model_name: str,
    message: str,
) -> Dict[str, Any]:
    return {
        "success": False,
        "already_mlflow_model": False,
        "standardized": False,
        "standardization_type": None,
        "run_id": run_id,
        "registered_model_name": registered_model_name,
        "new_version": None,
        "source_model_file": str(model_path) if model_path else None,
        "target_artifact_path": None,
        "message": message,
    }


def build_initial_result(args) -> Dict[str, Any]:
    return {
        "success": False,
        "already_mlflow_model": False,
        "standardized": False,
        "standardization_type": None,
        "run_id": args.run_id,
        "registered_model_name": args.registered_model_name,
        "new_version": None,
        "source_model_file": None,
        "target_artifact_path": None,
        "message": None,
    }


def main() -> int:
    parser = argparse.ArgumentParser()

    parser.add_argument("--mlflow_tracking_uri", required=True)
    parser.add_argument("--run_id", required=True)
    parser.add_argument("--registered_model_name", required=True)
    parser.add_argument("--artifact_path", default="model")
    parser.add_argument("--target_artifact_path", default="standardized_model")
    parser.add_argument("--set_production_alias", default="true")

    args = parser.parse_args()
    result = build_initial_result(args)

    try:
        mlflow.set_tracking_uri(args.mlflow_tracking_uri)
        set_alias = args.set_production_alias.strip().lower() == "true"

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)

            artifact_root = download_artifacts_safely(
                run_id=args.run_id,
                artifact_path=args.artifact_path,
                dst_path=str(tmp_dir),
            )

            if has_mlmodel_file(artifact_root):
                try:
                    mlflow.pyfunc.load_model(str(artifact_root))

                    result.update(
                        {
                            "success": True,
                            "already_mlflow_model": True,
                            "standardized": False,
                            "standardization_type": "native_mlflow_pyfunc",
                            "target_artifact_path": args.artifact_path,
                            "message": "Existing valid MLflow pyfunc model detected. No standardization needed.",
                        }
                    )
                    print_json(result)
                    return 0

                except Exception as exc:
                    print(
                        "[STANDARDIZER] MLmodel exists but cannot be loaded as pyfunc. "
                        "Searching for .pkl/.joblib/.pickle/.sav to standardize. "
                        f"Reason: {type(exc).__name__}: {exc}"
                    )

            candidates = find_all_model_artifacts(artifact_root)
            candidate = choose_best_candidate(candidates)

            if candidate is None:
                result.update(
                    unsupported_result(
                        model_path=None,
                        run_id=args.run_id,
                        registered_model_name=args.registered_model_name,
                        message=(
                            "No supported model artifact found. Expected one of: "
                            ".pkl, .joblib, .pickle, .sav, .h5, .keras, .onnx, .pt, .pth."
                        ),
                    )
                )
                print_json(result)
                return 2

            suffix = candidate.suffix.lower()

            if suffix in AUTO_SKLEARN_EXTENSIONS:
                result = standardize_pickle_like_model(
                    model_path=candidate,
                    artifact_root=artifact_root,
                    run_id=args.run_id,
                    registered_model_name=args.registered_model_name,
                    target_artifact_path=args.target_artifact_path,
                    set_production_alias=set_alias,
                )
                print_json(result)
                return 0

            if suffix in KERAS_EXTENSIONS:
                result = standardize_keras_model(
                    model_path=candidate,
                    artifact_root=artifact_root,
                    run_id=args.run_id,
                    registered_model_name=args.registered_model_name,
                    target_artifact_path=args.target_artifact_path,
                    set_production_alias=set_alias,
                )
                print_json(result)
                return 0

            if suffix in ONNX_EXTENSIONS:
                result = standardize_onnx_model(
                    model_path=candidate,
                    artifact_root=artifact_root,
                    run_id=args.run_id,
                    registered_model_name=args.registered_model_name,
                    target_artifact_path=args.target_artifact_path,
                    set_production_alias=set_alias,
                )
                print_json(result)
                return 0

            if suffix in PYTORCH_EXTENSIONS:
                result = standardize_torch_model(
                    model_path=candidate,
                    artifact_root=artifact_root,
                    run_id=args.run_id,
                    registered_model_name=args.registered_model_name,
                    target_artifact_path=args.target_artifact_path,
                    set_production_alias=set_alias,
                )
                print_json(result)
                return 0

            result.update(
                unsupported_result(
                    model_path=candidate,
                    run_id=args.run_id,
                    registered_model_name=args.registered_model_name,
                    message=f"Unsupported model artifact format: {suffix}",
                )
            )
            print_json(result)
            return 3

    except Exception as exc:
        result["message"] = safe_short_text(
            f"Standardizer exception: {type(exc).__name__}: {exc}"
        )
        print_json(result)
        return 1


if __name__ == "__main__":
    sys.exit(main())