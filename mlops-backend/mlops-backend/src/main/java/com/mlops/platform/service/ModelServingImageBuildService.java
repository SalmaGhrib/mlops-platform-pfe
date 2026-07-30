package com.mlops.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
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
    private static final String SAFE_SERVING_TEMPLATE_VERSION = "2026-07-30-resilient-image-metadata-v7";

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
            version="6.3.0-resilient-image-metadata",
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
            '''
            Cherche un fichier .pt ou .pth dans le dossier téléchargé depuis MLflow.

            Cas attendu après ta correction du custom_runner :
            models:/losshb_model@production
              -> dossier local
                  -> model/
                      -> model.pt
            '''
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
            '''
            Retourne le bon dossier local à charger avec mlflow.pyfunc.load_model.

            Cas supportés :
            1. local_dir/MLmodel
            2. local_dir/model/MLmodel
            '''
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



        SIDE_CAR_METADATA_NAMES = {
            "model_metadata.json",
            "model-manifest.json",
            "model_manifest.json",
            "model_info.json",
            "metadata.json",
        }


        def _deep_merge_dict(base: Dict[str, Any], extra: Dict[str, Any]) -> Dict[str, Any]:
            result = dict(base or {})
            for key, value in (extra or {}).items():
                if isinstance(value, dict) and isinstance(result.get(key), dict):
                    result[key] = _deep_merge_dict(result[key], value)
                else:
                    result[key] = value
            return result


        def load_sidecar_metadata(local_dir: str, model_path: Optional[str] = None) -> Dict[str, Any]:
            root = Path(local_dir)
            if not root.exists():
                return {}

            candidates: List[Path] = []
            if model_path:
                model_parent = Path(model_path).parent
                candidates.extend(model_parent / name for name in SIDE_CAR_METADATA_NAMES)

            for name in SIDE_CAR_METADATA_NAMES:
                candidates.extend(root.rglob(name))

            merged: Dict[str, Any] = {}
            seen = set()
            for path in candidates:
                try:
                    resolved = path.resolve()
                except Exception:
                    resolved = path
                if resolved in seen or not path.is_file():
                    continue
                seen.add(resolved)
                try:
                    payload = json.loads(path.read_text(encoding="utf-8", errors="ignore"))
                    if isinstance(payload, dict):
                        merged = _deep_merge_dict(merged, payload)
                except Exception as exc:
                    print(f"[METADATA] Ignoring unreadable sidecar {path}: {exc}")

            return merged

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

            model, bundle_metadata = extract_predictive_model(loaded_object)
            sidecar_metadata = load_sidecar_metadata(local_dir, model_path)
            metadata = _deep_merge_dict(sidecar_metadata, bundle_metadata)

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
            '''
            Charge un modèle depuis MLflow Registry.

            Cette version est plus robuste :
            1. Elle essaie d'abord mlflow.pyfunc.load_model(models:/name@alias).
            2. Si ça échoue, elle télécharge l'artifact et détecte où se trouve MLmodel.
               - local_dir/MLmodel
               - local_dir/model/MLmodel
            3. Elle recharge le modèle pyfunc depuis le bon chemin local.
            4. Seulement si ce n'est vraiment pas un modèle MLflow, elle essaie le fallback TorchScript.
            '''
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

                try:
                    local_dir = mlflow.artifacts.download_artifacts(model_uri)
                    sidecar_metadata = load_sidecar_metadata(local_dir)
                except Exception as metadata_exc:
                    local_dir = None
                    sidecar_metadata = {}
                    print(f"[MODEL LOADER] Sidecar metadata preload failed: {metadata_exc}")

                loaded = {
                    "type": "pyfunc",
                    "model": pyfunc_model,
                    "metadata": sidecar_metadata,
                    "model_uri": model_uri,
                    "version_info": version_info,
                    "local_dir": local_dir,
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
            '''
            Reproduit le preprocessing du train.py MNIST :

            transforms.ToTensor() -> valeurs entre 0 et 1
            transforms.Normalize((0.1307,), (0.3081,))

            Cette fonction gère aussi le cas fréquent d'une image noire sur fond blanc :
            si la moyenne est très élevée, on inverse l'image automatiquement.
            '''
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
        # ADAPTIVE IMAGE PREPROCESSING
        # ============================================================

        RGB_STATS_14_COLUMNS = [
            "r_mean", "g_mean", "b_mean",
            "r_std", "g_std", "b_std",
            "r_min", "g_min", "b_min",
            "r_max", "g_max", "b_max",
            "brightness", "dominant_channel",
        ]

        COLOR_STATS_11_COLUMNS = [
            "r_mean", "g_mean", "b_mean",
            "r_std", "g_std", "b_std",
            "h_mean", "s_mean", "v_mean",
            "brightness", "dominant_channel",
        ]


        def _underlying_model_and_metadata(loaded: Dict[str, Any]) -> Tuple[Any, Dict[str, Any]]:
            metadata = dict(loaded.get("metadata") or {})
            model = loaded.get("model")

            if loaded.get("type") == "pyfunc":
                try:
                    local_dir = loaded.get("local_dir") or mlflow.artifacts.download_artifacts(loaded["model_uri"])
                    raw_model, raw_metadata, raw_path = load_pickle_artifact(local_dir)
                    loaded["local_dir"] = local_dir
                    loaded["raw_model"] = raw_model
                    loaded["raw_model_path"] = raw_path
                    loaded["metadata"] = _deep_merge_dict(metadata, raw_metadata)
                    return raw_model, loaded["metadata"]
                except Exception as exc:
                    print(f"[IMAGE PREPROCESSING] Raw model inspection unavailable: {exc}")

            return loaded.get("raw_model") or model, metadata


        def _expected_feature_names(model: Any, metadata: Dict[str, Any]) -> List[str]:
            candidates = [
                metadata.get("feature_names"),
                metadata.get("feature_columns"),
                metadata.get("features"),
                metadata.get("input_columns"),
                metadata.get("columns"),
                (metadata.get("preprocessing") or {}).get("featureNames") if isinstance(metadata.get("preprocessing"), dict) else None,
                (metadata.get("preprocessing") or {}).get("feature_names") if isinstance(metadata.get("preprocessing"), dict) else None,
            ]
            detected = getattr(model, "feature_names_in_", None)
            if detected is not None:
                candidates.insert(0, list(detected))

            for value in candidates:
                if isinstance(value, (list, tuple)) and value:
                    return [str(item) for item in value]
            return []


        def _expected_feature_count(model: Any, metadata: Dict[str, Any], feature_names: List[str]) -> Optional[int]:
            if feature_names:
                return len(feature_names)
            value = getattr(model, "n_features_in_", None)
            if value is not None:
                try:
                    return int(value)
                except Exception:
                    pass
            for key in ("feature_count", "n_features", "input_size"):
                value = metadata.get(key)
                if value is not None:
                    try:
                        return int(value)
                    except Exception:
                        pass
            preprocessing = metadata.get("preprocessing")
            if isinstance(preprocessing, dict):
                for key in ("featureCount", "feature_count"):
                    value = preprocessing.get(key)
                    if value is not None:
                        try:
                            return int(value)
                        except Exception:
                            pass
            return None


        def extract_rgb_statistics_14(image: Image.Image) -> pd.DataFrame:
            rgb = np.asarray(image.convert("RGB").resize((64, 64)), dtype=np.float32) / 255.0
            means = rgb.mean(axis=(0, 1))
            stds = rgb.std(axis=(0, 1))
            mins = rgb.min(axis=(0, 1))
            maxs = rgb.max(axis=(0, 1))
            values = [
                float(means[0]), float(means[1]), float(means[2]),
                float(stds[0]), float(stds[1]), float(stds[2]),
                float(mins[0]), float(mins[1]), float(mins[2]),
                float(maxs[0]), float(maxs[1]), float(maxs[2]),
                float(rgb.mean()), float(np.argmax(means)),
            ]
            return pd.DataFrame([values], columns=RGB_STATS_14_COLUMNS)


        def extract_color_statistics_11(image: Image.Image) -> pd.DataFrame:
            rgb_image = image.convert("RGB").resize((64, 64))
            rgb = np.asarray(rgb_image, dtype=np.float32) / 255.0
            hsv = np.asarray(rgb_image.convert("HSV"), dtype=np.float32) / 255.0
            means = rgb.mean(axis=(0, 1))
            stds = rgb.std(axis=(0, 1))
            hsv_means = hsv.mean(axis=(0, 1))
            values = [
                float(means[0]), float(means[1]), float(means[2]),
                float(stds[0]), float(stds[1]), float(stds[2]),
                float(hsv_means[0]), float(hsv_means[1]), float(hsv_means[2]),
                float(hsv_means[2]), float(np.argmax(means)),
            ]
            return pd.DataFrame([values], columns=COLOR_STATS_11_COLUMNS)


        def image_to_flat_pixels(image: Image.Image, feature_count: int, feature_names: Optional[List[str]] = None) -> pd.DataFrame:
            side = int(round(feature_count ** 0.5))
            if side * side != feature_count:
                raise ValueError(f"Cannot infer a square image shape from feature_count={feature_count}.")
            arr = np.asarray(image.convert("L").resize((side, side)), dtype=np.float32) / 255.0
            values = arr.reshape(-1).astype(float).tolist()
            columns = feature_names if feature_names and len(feature_names) == feature_count else [f"pixel_{i}" for i in range(feature_count)]
            return pd.DataFrame([values], columns=columns)


        def _coerce_positive_int(value: Any) -> Optional[int]:
            if isinstance(value, (list, tuple)) and value:
                value = value[0]
            try:
                parsed = int(value)
                return parsed if parsed > 0 else None
            except (TypeError, ValueError):
                return None


        def _resolve_image_metadata(
            model: Any,
            metadata: Dict[str, Any],
        ) -> Dict[str, Any]:
            '''
            Normalise les métadonnées image sans inventer de valeurs silencieusement.
            Les métadonnées MLflow sont prioritaires; les attributs du wrapper servent
            uniquement de secours.
            '''
            normalized = dict(metadata or {})

            embedded = getattr(model, "metadata", None)
            if isinstance(embedded, dict):
                normalized = _deep_merge_dict(embedded, normalized)

            preprocessing = normalized.get("preprocessing")
            if not isinstance(preprocessing, dict):
                preprocessing = {}

            image_size_value = (
                normalized.get("image_size")
                or preprocessing.get("image_size")
                or preprocessing.get("resize")
                or getattr(model, "image_size", None)
            )

            if isinstance(image_size_value, (list, tuple)):
                if len(image_size_value) >= 2:
                    height = _coerce_positive_int(image_size_value[0])
                    width = _coerce_positive_int(image_size_value[1])
                elif len(image_size_value) == 1:
                    height = width = _coerce_positive_int(image_size_value[0])
                else:
                    height = width = None
            else:
                height = width = _coerce_positive_int(image_size_value)

            resize_value = preprocessing.get("resize") or (
                [height, width] if height and width else None
            )
            if isinstance(resize_value, (int, float, str)):
                side = _coerce_positive_int(resize_value)
                resize_value = [side, side] if side else None

            if isinstance(resize_value, (list, tuple)) and len(resize_value) >= 2:
                resize_h = _coerce_positive_int(resize_value[0])
                resize_w = _coerce_positive_int(resize_value[1])
            else:
                resize_h = resize_w = None

            if not (resize_h and resize_w):
                resize_h, resize_w = height, width

            class_names = (
                normalized.get("class_names")
                or normalized.get("classes")
                or normalized.get("labels")
                or getattr(model, "labels", None)
                or []
            )
            if not isinstance(class_names, (list, tuple)):
                class_names = []

            framework = normalize_text(
                normalized.get("framework")
                or preprocessing.get("framework")
                or type(model).__name__
            ).lower()

            model_type = normalize_text(normalized.get("model_type")).lower()
            data_type = normalize_text(normalized.get("data_type")).lower()

            is_tensor_image = (
                data_type in {"image", "images", "vision"}
                or model_type in {"image_classification", "image-classification"}
                or framework in {"pytorch", "torch", "tensorflow", "keras"}
                or "torch" in type(model).__name__.lower()
            )

            if not is_tensor_image:
                return normalized

            if not (resize_h and resize_w):
                raise HTTPException(
                    status_code=422,
                    detail={
                        "message": "Image model metadata is incomplete: resize/image_size is missing.",
                        "metadata_keys": list(normalized.keys()),
                        "model_type": type(model).__name__,
                    },
                )

            layout = normalize_text(
                preprocessing.get("layout")
                or normalized.get("layout")
                or "NCHW"
            ).upper()

            color_mode = normalize_text(
                preprocessing.get("color_mode")
                or normalized.get("color_mode")
                or "RGB"
            ).upper()

            normalize_cfg = preprocessing.get("normalize")
            if not isinstance(normalize_cfg, dict):
                normalize_cfg = {}

            mean = normalize_cfg.get("mean")
            std = normalize_cfg.get("std")

            normalized["framework"] = normalized.get("framework") or (
                "pytorch" if "torch" in framework else framework
            )
            normalized["data_type"] = normalized.get("data_type") or "image"
            normalized["model_type"] = normalized.get("model_type") or "image_classification"
            normalized["image_size"] = normalized.get("image_size") or [resize_h, resize_w]
            normalized["input_shape"] = normalized.get("input_shape") or [3, resize_h, resize_w]
            normalized["class_names"] = [str(item) for item in class_names]
            normalized["preprocessing"] = {
                **preprocessing,
                "type": preprocessing.get("type") or "image",
                "resize": [resize_h, resize_w],
                "color_mode": color_mode,
                "layout": layout,
                "dtype": preprocessing.get("dtype") or "float32",
                "rescale": preprocessing.get("rescale") or "0_1",
            }

            if mean is not None or std is not None:
                normalized["preprocessing"]["normalize"] = {
                    "mean": mean if mean is not None else [0.0, 0.0, 0.0],
                    "std": std if std is not None else [1.0, 1.0, 1.0],
                }

            return normalized


        def _expand_channel_stats(values: Any, channels: int, default: float) -> np.ndarray:
            if values is None:
                return np.full((channels,), default, dtype=np.float32)

            arr = np.asarray(values, dtype=np.float32).reshape(-1)
            if arr.size == 1:
                arr = np.repeat(arr, channels)

            if arr.size != channels:
                raise HTTPException(
                    status_code=422,
                    detail={
                        "message": "Invalid normalization metadata.",
                        "expected_channels": channels,
                        "received_values": arr.tolist(),
                    },
                )

            return arr


        def image_to_tensor_array(
            image: Image.Image,
            metadata: Dict[str, Any],
        ) -> np.ndarray:
            preprocessing = metadata.get("preprocessing") or {}
            resize = preprocessing.get("resize") or metadata.get("image_size")

            if not isinstance(resize, (list, tuple)) or len(resize) < 2:
                raise HTTPException(
                    status_code=422,
                    detail="Image resize metadata is missing or invalid."
                )

            height = _coerce_positive_int(resize[0])
            width = _coerce_positive_int(resize[1])
            if not height or not width:
                raise HTTPException(
                    status_code=422,
                    detail=f"Invalid resize dimensions: {resize}"
                )

            color_mode = normalize_text(preprocessing.get("color_mode") or "RGB").upper()
            pil_mode = "L" if color_mode in {"L", "GRAY", "GREYSCALE", "GRAYSCALE"} else "RGB"

            prepared = image.convert(pil_mode).resize(
                (width, height),
                Image.Resampling.BILINEAR,
            )
            array = np.asarray(prepared, dtype=np.float32)

            if array.ndim == 2:
                array = array[..., np.newaxis]

            rescale = normalize_text(preprocessing.get("rescale") or "0_1").lower()
            if rescale in {"0_1", "0-1", "1/255", "divide_255", "true"} or array.max() > 1.0:
                array = array / 255.0

            normalize_cfg = preprocessing.get("normalize")
            if isinstance(normalize_cfg, dict):
                channels = int(array.shape[-1])
                mean = _expand_channel_stats(normalize_cfg.get("mean"), channels, 0.0)
                std = _expand_channel_stats(normalize_cfg.get("std"), channels, 1.0)

                if np.any(std == 0):
                    raise HTTPException(
                        status_code=422,
                        detail="Normalization std cannot contain zero."
                    )

                array = (
                    array - mean.reshape(1, 1, channels)
                ) / std.reshape(1, 1, channels)

            layout = normalize_text(preprocessing.get("layout") or "NCHW").upper()
            if layout in {"NCHW", "CHW"}:
                array = np.transpose(array, (2, 0, 1))
            elif layout not in {"NHWC", "HWC"}:
                raise HTTPException(
                    status_code=422,
                    detail=f"Unsupported image tensor layout: {layout}"
                )

            # Always add a batch dimension.
            array = np.expand_dims(array, axis=0).astype(np.float32)

            print(
                "[IMAGE PREPROCESSING] Tensor prepared. "
                f"shape={array.shape}, dtype={array.dtype}, "
                f"layout={layout}, resize={[height, width]}"
            )
            return array


        def prepare_adaptive_image_input(
            loaded: Dict[str, Any],
            image: Image.Image,
        ) -> Tuple[str, Any, List[str]]:
            model, metadata = _underlying_model_and_metadata(loaded)
            metadata = _resolve_image_metadata(model, metadata)
            loaded["metadata"] = metadata

            preprocessing = (
                metadata.get("preprocessing")
                if isinstance(metadata.get("preprocessing"), dict)
                else {}
            )
            preprocessing_type = normalize_text(
                preprocessing.get("type")
                or metadata.get("preprocessing_type")
                or metadata.get("image_preprocessing")
            ).lower()

            framework = normalize_text(metadata.get("framework")).lower()
            data_type = normalize_text(metadata.get("data_type")).lower()
            model_type = normalize_text(metadata.get("model_type")).lower()

            # Generic tensor image path for MLflow PyFunc/PyTorch/TensorFlow wrappers.
            if (
                preprocessing_type in {"image", "tensor", "image_tensor", "cnn"}
                or data_type in {"image", "images", "vision"}
                or model_type in {"image_classification", "image-classification"}
                or framework in {"pytorch", "torch", "tensorflow", "keras"}
                or "torch" in type(model).__name__.lower()
            ):
                tensor_array = image_to_tensor_array(image, metadata)
                return "image_tensor_metadata", tensor_array, ["image"]

            # Existing tabular-image paths are preserved.
            feature_names = _expected_feature_names(model, metadata)
            feature_count = _expected_feature_count(model, metadata, feature_names)
            feature_set = set(feature_names)

            if (
                preprocessing_type in {"rgb_statistics", "rgb_stats", "color_rgb_statistics"}
                or set(RGB_STATS_14_COLUMNS).issubset(feature_set)
                or feature_count == 14
            ):
                frame = extract_rgb_statistics_14(image)
                if feature_names and set(feature_names) == set(frame.columns):
                    frame = frame[feature_names]
                return "image_rgb_statistics_14", frame, list(frame.columns)

            if (
                preprocessing_type in {"color_statistics", "rgb_hsv_statistics", "color_stats_11"}
                or set(COLOR_STATS_11_COLUMNS).issubset(feature_set)
                or feature_count == 11
            ):
                frame = extract_color_statistics_11(image)
                if feature_names and set(feature_names) == set(frame.columns):
                    frame = frame[feature_names]
                return "image_color_statistics_11", frame, list(frame.columns)

            if (
                preprocessing_type in {"flattened_pixels", "pixels", "mnist"}
                or (
                    feature_count is not None
                    and feature_count > 0
                    and int(round(feature_count ** 0.5)) ** 2 == feature_count
                )
            ):
                count = feature_count or 784
                frame = image_to_flat_pixels(image, count, feature_names)
                return f"image_flattened_pixels_{count}", frame, list(frame.columns)

            raise HTTPException(
                status_code=422,
                detail={
                    "message": "Image preprocessing cannot be inferred safely for this model.",
                    "model_type": type(model).__name__ if model is not None else None,
                    "expected_feature_count": feature_count,
                    "expected_feature_names": feature_names,
                    "metadata_keys": list(metadata.keys()),
                    "metadata": metadata,
                    "hint": (
                        "Store image_size, data_type=image, class_names and preprocessing "
                        "with the MLflow model."
                    ),
                },
            )
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


        def make_predictions(loaded: Dict[str, Any], model_input: Any):
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
                raw_model_fallback_error = None
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



        def decode_image_classification_predictions(
            predictions: Any,
            metadata: Dict[str, Any],
        ) -> Tuple[Any, Optional[Dict[str, Any]]]:
            class_names = metadata.get("class_names") if isinstance(metadata, dict) else None
            if not isinstance(class_names, list) or not class_names:
                return predictions, None

            values = np.asarray(predictions)
            if values.size == 0:
                return predictions, None

            confidence: Optional[float] = None

            if values.ndim >= 2 and values.shape[-1] == len(class_names):
                logits = values.reshape(-1, values.shape[-1])[0].astype(np.float64)
                shifted = logits - np.max(logits)
                probabilities = np.exp(shifted)
                probability_sum = probabilities.sum()
                if probability_sum > 0:
                    probabilities = probabilities / probability_sum
                class_index = int(np.argmax(probabilities))
                confidence = float(probabilities[class_index])
            elif values.ndim == 1 and values.size == len(class_names):
                logits = values.astype(np.float64)
                shifted = logits - np.max(logits)
                probabilities = np.exp(shifted)
                probability_sum = probabilities.sum()
                if probability_sum > 0:
                    probabilities = probabilities / probability_sum
                class_index = int(np.argmax(probabilities))
                confidence = float(probabilities[class_index])
            else:
                try:
                    class_index = int(values.reshape(-1)[0])
                except (TypeError, ValueError, IndexError):
                    return predictions, None

            if class_index < 0 or class_index >= len(class_names):
                return predictions, None

            label = str(class_names[class_index])
            detail: Dict[str, Any] = {
                "class_index": class_index,
                "class_name": label,
            }
            if confidence is not None:
                detail["confidence"] = confidence

            return [label], detail
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
        ):
            try:
                model_name = normalize_text(model_name)
                model_alias = normalize_text(model_alias) or "production"
                if not model_name:
                    raise ValueError("model_name is required.")

                content = await image_file.read()
                if not content:
                    raise ValueError("Uploaded image is empty.")

                image = Image.open(io.BytesIO(content)).convert("RGB")
                loaded, model_uri, version_info = load_model_from_registry(
                    model_name=model_name,
                    model_alias=model_alias,
                )

                prediction_mode, model_input, input_columns = prepare_adaptive_image_input(
                    loaded,
                    image,
                )
                predictions = make_predictions(loaded, model_input)
                predictions, classification = decode_image_classification_predictions(
                    predictions,
                    loaded.get("metadata") or {},
                )

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
                    "original_image_size": list(image.size),
                    "input_columns": input_columns,
                    "predictions": predictions,
                    "classification": classification,
                }

            except HTTPException:
                raise
            except Exception as exc:
                raise HTTPException(
                    status_code=400,
                    detail={
                        "message": "Image prediction failed.",
                        "error": str(exc),
                        "model_name": model_name,
                        "model_alias": model_alias,
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
            loadSafeMainPyTemplate(),
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

private String loadSafeMainPyTemplate() throws Exception {
    String resourcePath = "serving-template/main.py";
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
        if (input == null) {
            System.out.println("[SERVING_IMAGE] Classpath template not found; embedded fallback will be used: " + resourcePath);
            return SAFE_MAIN_PY_TEMPLATE;
        }

        String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        if (template.isBlank()) {
            throw new IllegalStateException("Serving main.py classpath template is empty: " + resourcePath);
        }

        System.out.println("[SERVING_IMAGE] Adaptive serving main.py loaded from classpath: " + resourcePath);
        return template;
    }
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
            "pillow==10.4.0",
            "joblib==1.4.2"
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