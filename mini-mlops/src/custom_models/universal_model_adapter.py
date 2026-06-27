import ast
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import joblib
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


DATASET_ALIASES = [
    "dataset.csv",
    "train.csv",
    "data.csv",
    "input.csv",
    "uploaded_dataset.csv",
]

DEFAULT_USER_FUNCTIONS = [
    "main",
    "train",
    "run",
    "train_model",
    "build_model",
    "create_model",
    "get_model",
]

MODEL_VARIABLE_NAMES = [
    "model",
    "MODEL",
    "clf",
    "classifier",
    "regressor",
    "estimator",
    "pipe",
    "pipeline",
    "best_model",
    "final_model",
    "trained_model",
]

METRIC_KEYS = {
    "accuracy": "accuracy",
    "accuracy_score": "accuracy",
    "precision": "precision",
    "precision_weighted": "precision_weighted",
    "recall": "recall",
    "recall_weighted": "recall_weighted",
    "f1": "f1",
    "f1_score": "f1_score",
    "f1_weighted": "f1_weighted",
    "rmse": "rmse",
    "mae": "mae",
    "r2": "r2",
    "r2_score": "r2",
    "r2score": "r2",
    "precision_du_modele": "precision",
    "precision_du_model": "precision",
    "precision_du_modeles": "precision",
    "precision_du_modèle": "precision",
    "precision_du_monde": "precision",
    "précision": "precision",
    "précision_du_modèle": "precision",
    "précision_du_modele": "precision",
}

PERCENTAGE_KEYS = {
    "accuracy",
    "precision",
    "recall",
    "f1",
    "f1_score",
    "precision_weighted",
    "recall_weighted",
}

ARGPARSE_ERROR_PATTERNS = [
    r"unrecognized arguments",
    r"error: unknown option",
    r"error: unrecognized arguments",
    r"unrecognized arguments:.*",
]


def normalize_metric_name(raw_name: str) -> str:
    normalized = raw_name.strip().lower()
    normalized = (
        normalized
        .replace("é", "e")
        .replace("è", "e")
        .replace("ê", "e")
        .replace("à", "a")
        .replace("ù", "u")
        .replace("ç", "c")
    )
    normalized = re.sub(r"[^a-z0-9_ ]", "", normalized)
    normalized = normalized.replace(" ", "_")
    return METRIC_KEYS.get(normalized, normalized)


def create_standard_workspace_structure(workspace: Path) -> None:
    for folder in ["user_code", "data", "outputs", "metadata"]:
        (workspace / folder).mkdir(parents=True, exist_ok=True)


def create_dataset_aliases(workspace: Path, dataset_path: Optional[Path]) -> List[str]:
    aliases: List[str] = []

    if dataset_path is None or not dataset_path.exists():
        return aliases

    workspace.mkdir(parents=True, exist_ok=True)
    data_dir = workspace / "data"
    data_dir.mkdir(parents=True, exist_ok=True)

    for alias in DATASET_ALIASES:
        alias_root = workspace / alias
        alias_data = data_dir / alias

        try:
            if alias_root != dataset_path:
                shutil.copy2(dataset_path, alias_root)
                print(f"[UNIVERSAL ADAPTER] dataset alias created: {alias_root}")
            else:
                print(f"[UNIVERSAL ADAPTER] dataset alias already exists: {alias_root}")
        except Exception as exc:
            print(f"[UNIVERSAL ADAPTER] failed to create alias {alias_root}: {exc}")

        try:
            if alias_data != dataset_path:
                shutil.copy2(dataset_path, alias_data)
                print(f"[UNIVERSAL ADAPTER] dataset alias created: {alias_data}")
            else:
                print(f"[UNIVERSAL ADAPTER] dataset alias already exists: {alias_data}")
        except Exception as exc:
            print(f"[UNIVERSAL ADAPTER] failed to create alias {alias_data}: {exc}")

        aliases.append(str(alias_root))
        aliases.append(str(alias_data))

    return aliases


class change_cwd:
    def __init__(self, path: Path):
        self.path = path
        self.previous = Path.cwd()

    def __enter__(self):
        os.chdir(self.path)

    def __exit__(self, exc_type, exc_val, exc_tb):
        os.chdir(self.previous)


def import_user_module(workspace: Path, entrypoint: str) -> Tuple[Optional[Any], List[str]]:
    script_path = workspace / entrypoint
    warnings: List[str] = []

    if not script_path.exists():
        warnings.append(f"Entrypoint not found for import: {script_path}")
        return None, warnings

    module_name = f"user_custom_model_{int(time.time() * 1000)}"
    spec = importlib.util.spec_from_file_location(module_name, str(script_path))

    if spec is None or spec.loader is None:
        warnings.append(f"Cannot import user file: {script_path}")
        return None, warnings

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module

    script_dir = script_path.parent
    script_dir.mkdir(parents=True, exist_ok=True)

    for alias in DATASET_ALIASES:
        src = workspace / alias
        dst = script_dir / alias

        if src.exists() and dst != src:
            try:
                shutil.copy2(src, dst)
                print(f"[UNIVERSAL ADAPTER] dataset alias copied for import: {dst}")
            except Exception as exc:
                warnings.append(f"Could not copy dataset alias {src} to {dst}: {exc}")

    added_sys_path = False
    workspace_str = str(workspace)

    if workspace_str not in sys.path:
        sys.path.insert(0, workspace_str)
        added_sys_path = True

    try:
        with change_cwd(workspace):
            spec.loader.exec_module(module)
    except Exception as exc:
        warnings.append(f"Import failed for {entrypoint}: {exc}")
        return None, warnings
    finally:
        if added_sys_path and workspace_str in sys.path:
            sys.path.remove(workspace_str)

    return module, warnings


def is_argparse_error(stderr: str) -> bool:
    stderr_lower = stderr.lower() if stderr else ""
    return any(re.search(pattern, stderr_lower) for pattern in ARGPARSE_ERROR_PATTERNS)


def prepare_outputs_dir(workspace: Path) -> Path:
    outputs_dir = workspace / "outputs"

    if outputs_dir.exists():
        shutil.rmtree(outputs_dir)

    outputs_dir.mkdir(parents=True, exist_ok=True)
    return outputs_dir


def find_model_artifact(workspace: Path, outputs_dir: Path) -> Optional[Path]:
    """
    Détection robuste du modèle utilisateur.

    Priorité stricte :
    1. outputs/model.pkl
    2. outputs/model.joblib
    3. outputs/*.pkl / *.joblib
    4. workspace root model.pkl / model.joblib
    5. recherche récursive contrôlée
    """
    supported_extensions = [
        ".pkl",
        ".joblib",
        ".pickle",
        ".pt",
        ".pth",
        ".h5",
        ".keras",
        ".onnx",
    ]

    preferred_names = [
        "model.pkl",
        "model.joblib",
        "trained_model.pkl",
        "trained_model.joblib",
        "final_model.pkl",
        "final_model.joblib",
        "pipeline.pkl",
        "pipeline.joblib",
    ]

    print("[FIND_MODEL] Starting robust model artifact detection")
    print(f"[FIND_MODEL] workspace={workspace}")
    print(f"[FIND_MODEL] outputs_dir={outputs_dir}")

    for name in preferred_names:
        candidate = outputs_dir / name

        if candidate.exists() and candidate.is_file():
            print(f"[FIND_MODEL] selected preferred outputs artifact: {candidate}")
            return candidate

    candidates: List[Path] = []

    for ext in supported_extensions:
        candidates.extend([path for path in outputs_dir.glob(f"*{ext}") if path.is_file()])

    if candidates:
        candidates = sorted(candidates, key=lambda path: path.stat().st_mtime, reverse=True)
        print(f"[FIND_MODEL] candidates in outputs_dir: {[str(path) for path in candidates]}")
        print(f"[FIND_MODEL] selected latest outputs artifact: {candidates[0]}")
        return candidates[0]

    for name in preferred_names:
        candidate = workspace / name

        if candidate.exists() and candidate.is_file():
            print(f"[FIND_MODEL] selected preferred root artifact: {candidate}")
            return candidate

    root_candidates: List[Path] = []

    for ext in supported_extensions:
        root_candidates.extend([path for path in workspace.glob(f"*{ext}") if path.is_file()])

    if root_candidates:
        root_candidates = sorted(root_candidates, key=lambda path: path.stat().st_mtime, reverse=True)
        print(f"[FIND_MODEL] candidates in workspace root: {[str(path) for path in root_candidates]}")
        print(f"[FIND_MODEL] selected latest root artifact: {root_candidates[0]}")
        return root_candidates[0]

    recursive_candidates: List[Path] = []

    ignored_parts = {
        "metadata",
        "__pycache__",
        ".git",
        ".venv",
        "venv",
        "env",
        "site-packages",
        "logs",
    }

    for ext in supported_extensions:
        for path in workspace.rglob(f"*{ext}"):
            if not path.is_file():
                continue

            if set(path.parts).intersection(ignored_parts):
                continue

            recursive_candidates.append(path)

    if recursive_candidates:
        recursive_candidates = sorted(
            recursive_candidates,
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        print(f"[FIND_MODEL] recursive candidates: {[str(path) for path in recursive_candidates]}")
        print(f"[FIND_MODEL] selected recursive artifact: {recursive_candidates[0]}")
        return recursive_candidates[0]

    print("[FIND_MODEL] No model artifact found")
    return None


def _looks_like_text_pipeline(model: Any) -> bool:
    """
    Détecte un pipeline NLP sklearn de type TfidfVectorizer / CountVectorizer.
    """
    try:
        if hasattr(model, "steps"):
            for step_name, step_obj in model.steps:
                step_name_lower = str(step_name).lower()
                class_name_lower = step_obj.__class__.__name__.lower()

                if "tfidf" in step_name_lower or "vectorizer" in step_name_lower:
                    return True

                if "tfidf" in class_name_lower or "countvectorizer" in class_name_lower:
                    return True
    except Exception:
        pass

    return False


def validate_model_artifact(model_path: Path) -> Tuple[bool, List[str]]:
    """
    Validation robuste du modèle avant MLflow.

    Objectif :
    - empêcher l'enregistrement d'un modèle cassé
    - détecter un TfidfVectorizer non fitted
    - vérifier qu'un modèle NLP peut vraiment prédire sur un texte simple
    """
    validation_messages: List[str] = []

    if model_path is None:
        return False, ["model_path is None"]

    if not model_path.exists():
        return False, [f"model_path does not exist: {model_path}"]

    suffix = model_path.suffix.lower()

    if suffix in {".pkl", ".joblib", ".pickle"}:
        try:
            model = joblib.load(model_path)
            validation_messages.append(f"joblib.load OK: {model_path}")
            validation_messages.append(f"model_type={type(model)}")
        except Exception as exc:
            return False, [f"joblib.load failed for {model_path}: {exc}"]

        if not hasattr(model, "predict") and not callable(model):
            return False, [
                "Loaded object is not predictive. "
                f"It has no predict() and is not callable. type={type(model)}"
            ]

        if _looks_like_text_pipeline(model):
            try:
                prediction = model.predict(["le service est excellent et rapide"])
                validation_messages.append(f"text_sample_prediction={prediction}")
                return True, validation_messages
            except Exception as exc:
                return False, [
                    f"Text pipeline validation failed for {model_path}: {exc}",
                    "This usually means TfidfVectorizer/CountVectorizer is not fitted.",
                ]

        validation_messages.append("Pickle/joblib model artifact validation OK.")
        return True, validation_messages

    validation_messages.append(
        f"Non-pickle model artifact detected: {model_path}. "
        "Basic existence validation OK, prediction validation skipped."
    )
    return True, validation_messages


def parse_metrics_from_text(text: str) -> Dict[str, float]:
    metrics: Dict[str, float] = {}

    if not text:
        return metrics

    lines = text.splitlines()

    for line in lines:
        if not line:
            continue

        matches = re.findall(r"([A-Za-z0-9_À-ÿ ]+?)\s*[:=]\s*([-+]?\d+(?:\.\d+)?%?)", line)

        if not matches:
            matches = re.findall(r"^([A-Za-z0-9_À-ÿ ]+?)\s+([-+]?\d+(?:\.\d+)?%?)$", line)

        for key, raw_value in matches:
            name = normalize_metric_name(key)

            if not name:
                continue

            try:
                if raw_value.endswith("%"):
                    value = float(raw_value[:-1]) / 100.0
                else:
                    value = float(raw_value)

                metrics[name] = value
            except ValueError:
                continue

    return metrics


def load_metrics_from_file(outputs_dir: Path) -> Dict[str, float]:
    metrics_json = outputs_dir / "metrics.json"

    if metrics_json.exists():
        try:
            data = json.loads(metrics_json.read_text(encoding="utf-8"))
            return {
                key: float(value)
                for key, value in data.items()
                if isinstance(value, (int, float, str))
            }
        except Exception as exc:
            print(f"[UNIVERSAL ADAPTER] failed to load metrics.json: {exc}")

    metrics_txt = outputs_dir / "metrics.txt"

    if metrics_txt.exists():
        try:
            content = metrics_txt.read_text(encoding="utf-8")
            return parse_metrics_from_text(content)
        except Exception as exc:
            print(f"[UNIVERSAL ADAPTER] failed to load metrics.txt: {exc}")

    return {}


def is_fitted_model(model: Any) -> bool:
    fitted_indicators = {
        "n_features_in_",
        "coef_",
        "feature_importances_",
        "classes_",
        "estimators_",
        "best_estimator_",
        "support_",
        "n_iter_",
    }

    return any(hasattr(model, attr) for attr in fitted_indicators)


def build_preprocessor(X: pd.DataFrame) -> ColumnTransformer:
    numeric_features = X.select_dtypes(include=["number", "bool"]).columns.tolist()
    categorical_features = X.select_dtypes(exclude=["number", "bool"]).columns.tolist()

    transformers: List[Tuple[str, Any, List[str]]] = []

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


def should_wrap_model(model: Any) -> bool:
    if isinstance(model, Pipeline):
        return False

    if hasattr(model, "steps") or hasattr(model, "transformers"):
        return False

    return True


def build_fallback_training_model(model: Any, X: pd.DataFrame) -> Any:
    if should_wrap_model(model):
        print("[UNIVERSAL ADAPTER] Plain estimator detected. Adding platform preprocessor.")
        preprocessor = build_preprocessor(X)
        return Pipeline([("preprocessor", preprocessor), ("model", model)])

    print("[UNIVERSAL ADAPTER] User model looks like a pipeline/preprocessed model. Using it as-is.")
    return model


def infer_target_column(config: Dict[str, Any], df: pd.DataFrame) -> str:
    target_column = (
        config.get("data", {}).get("target_column")
        or config.get("dataset", {}).get("target_column")
        or config.get("target_column")
        or config.get("model", {}).get("target_column")
    )

    if target_column and str(target_column).strip():
        target_column = str(target_column).strip()

        if target_column not in df.columns:
            raise ValueError(
                f"Target column '{target_column}' not found. Available columns: {list(df.columns)}"
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
            print(f"[UNIVERSAL ADAPTER] target_column not provided. Using '{candidate}'.")
            return candidate

    fallback = str(df.columns[-1])
    print(f"[UNIVERSAL ADAPTER] target_column not provided. Using last column '{fallback}'.")
    return fallback


def infer_task_type(config: Dict[str, Any]) -> str:
    task_type = (
        config.get("task", {}).get("type")
        or config.get("project_type")
        or config.get("model", {}).get("project_type")
        or config.get("model", {}).get("task_type")
    )

    if task_type and str(task_type).strip():
        normalized = str(task_type).strip().lower()

        if normalized not in {"regression", "classification"}:
            raise ValueError("project_type/task.type must be 'regression' or 'classification'.")

        return normalized

    primary_metric = (
        config.get("metrics", {}).get("primary_metric")
        or config.get("primary_metric")
        or config.get("model", {}).get("primary_metric")
    )

    metric = str(primary_metric or "").strip().lower()

    if metric in {"rmse", "mse", "mae", "r2", "r2_score"}:
        print("[UNIVERSAL ADAPTER] Inferred regression from primary metric.")
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
        print("[UNIVERSAL ADAPTER] Inferred classification from primary metric.")
        return "classification"

    print("[UNIVERSAL ADAPTER] project_type not provided. Defaulting to classification.")
    return "classification"


def compute_standard_metrics(task_type: str, y_test: Any, y_pred: Any) -> Dict[str, float]:
    from sklearn.metrics import (
        accuracy_score,
        f1_score,
        mean_absolute_error,
        mean_squared_error,
        precision_score,
        r2_score,
        recall_score,
    )

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
) -> Tuple[pd.DataFrame, pd.DataFrame, pd.Series, pd.Series]:
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


def build_model_from_module(workspace: Path, entrypoint: str) -> Tuple[Optional[Any], str, List[str]]:
    script_path = workspace / entrypoint

    if not script_path.exists():
        return None, "ENTRYPOINT_MISSING", [f"Entrypoint not found: {script_path}"]

    module, import_warnings = import_user_module(workspace, entrypoint)
    warnings = import_warnings

    if module is None:
        warnings.append("Module import failed, skipping FUNCTION_BASED/MODEL_VARIABLE detection.")
        return None, "MODULE_IMPORT_FAILED", warnings

    for function_name in DEFAULT_USER_FUNCTIONS:
        if hasattr(module, function_name):
            candidate = getattr(module, function_name)

            if callable(candidate):
                try:
                    model = candidate()

                    if model is not None and hasattr(model, "fit"):
                        return model, "FUNCTION_BASED", warnings
                except Exception as exc:
                    warnings.append(f"{function_name}() called but raised: {exc}")

    for variable_name in MODEL_VARIABLE_NAMES:
        if hasattr(module, variable_name):
            model = getattr(module, variable_name)

            if model is not None and hasattr(model, "fit"):
                return model, "MODEL_VARIABLE", warnings

    warnings.append("No supported model factory or model variable found in the uploaded file.")
    return None, "NO_MODEL_FOUND", warnings


def save_model_artifact(model: Any, outputs_dir: Path) -> Path:
    outputs_dir.mkdir(parents=True, exist_ok=True)

    model_path = outputs_dir / "model.pkl"
    joblib.dump(model, model_path)

    return model_path


def train_model_on_dataset(
    model: Any,
    dataset_path: Path,
    outputs_dir: Path,
    config: Dict[str, Any],
) -> Dict[str, float]:
    df = pd.read_csv(dataset_path)

    if df.empty:
        raise ValueError("Dataset is empty.")

    target_column = infer_target_column(config, df)
    task_type = infer_task_type(config)

    X = df.drop(columns=[target_column]).copy()
    y = df[target_column].copy()

    if not isinstance(X, pd.DataFrame):
        X = pd.DataFrame(X)

    model_to_train = model

    if not is_fitted_model(model):
        model_to_train = build_fallback_training_model(model, X)
    else:
        print("[UNIVERSAL ADAPTER] Model appears already fitted. Using it without re-fitting.")

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
                print("[UNIVERSAL ADAPTER] Stratify disabled because a class has <2 samples.")
        except Exception as exc:
            print(f"[UNIVERSAL ADAPTER] Stratify disabled: {exc}")
            stratify = None

    X_train, X_test, y_train, y_test = dataframe_safe_train_test_split(
        X=X,
        y=y,
        test_size=test_size,
        random_state=random_state,
        shuffle=shuffle,
        stratify=stratify,
    )

    model_to_train.fit(X_train, y_train)

    y_pred = model_to_train.predict(X_test)
    metrics = compute_standard_metrics(task_type, y_test, y_pred)

    save_model_artifact(model_to_train, outputs_dir)
    save_metrics_files(outputs_dir, metrics)

    return metrics


def save_metrics_files(outputs_dir: Path, metrics: Dict[str, float]) -> None:
    outputs_dir.mkdir(parents=True, exist_ok=True)

    metrics_json_path = outputs_dir / "metrics.json"
    metrics_txt_path = outputs_dir / "metrics.txt"

    with metrics_json_path.open("w", encoding="utf-8") as file:
        json.dump(metrics, file, indent=2)

    with metrics_txt_path.open("w", encoding="utf-8") as file:
        for metric_name, metric_value in metrics.items():
            file.write(f"{metric_name}={metric_value}\n")


def run_script(
    workspace: Path,
    entrypoint: str,
    dataset_path: Optional[Path],
    outputs_dir: Path,
    with_args: bool,
) -> Tuple[subprocess.CompletedProcess, str, List[str]]:
    command = [sys.executable, entrypoint]

    if with_args:
        command.extend(["--output_dir", str(outputs_dir)])

        if dataset_path is not None:
            command.extend(["--data_path", str(dataset_path)])

    env = {**os.environ, "OUTPUT_DIR": str(outputs_dir)}

    if dataset_path is not None:
        env["DATA_PATH"] = str(dataset_path)

    with change_cwd(workspace):
        result = subprocess.run(
            command,
            cwd=str(workspace),
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )

    return result, "SCRIPT_WITH_ARGS" if with_args else "SCRIPT_DIRECT", command


def run_user_model_with_universal_adapter(
    workspace: Path,
    entrypoint: str,
    dataset_path: Optional[Path],
    config: Dict[str, Any],
    algorithm_metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    create_standard_workspace_structure(workspace)
    aliases = create_dataset_aliases(workspace, dataset_path)
    outputs_dir = prepare_outputs_dir(workspace)

    warnings: List[str] = []
    metrics: Dict[str, float] = {}
    execution_mode = "SCRIPT_WITH_ARGS"
    stdout = ""
    stderr = ""
    return_code = 0
    model_path: Optional[Path] = None
    status = "failed"

    print(f"[UNIVERSAL ADAPTER] workspace={workspace}")
    print(f"[UNIVERSAL ADAPTER] dataset_path={dataset_path}")
    print(f"[UNIVERSAL ADAPTER] dataset aliases={aliases}")
    print(f"[UNIVERSAL ADAPTER] entrypoint={entrypoint}")

    try:
        for with_args in [True, False]:
            attempt_mode = "SCRIPT_WITH_ARGS" if with_args else "SCRIPT_DIRECT"
            print(f"[UNIVERSAL ADAPTER] trying {attempt_mode}")

            result, mode, command = run_script(
                workspace=workspace,
                entrypoint=entrypoint,
                dataset_path=dataset_path,
                outputs_dir=outputs_dir,
                with_args=with_args,
            )

            stdout = result.stdout or ""
            stderr = result.stderr or ""
            return_code = result.returncode
            execution_mode = mode

            print(f"[UNIVERSAL ADAPTER] command={command}")
            print(f"[UNIVERSAL ADAPTER] return_code={return_code}")
            print(f"[UNIVERSAL ADAPTER] stdout={stdout}")
            print(f"[UNIVERSAL ADAPTER] stderr={stderr}")

            candidate_model_path = find_model_artifact(workspace, outputs_dir)

            if candidate_model_path is not None and candidate_model_path.exists():
                print(f"[UNIVERSAL ADAPTER] model artifact found={candidate_model_path}")

                is_valid_model, validation_messages = validate_model_artifact(candidate_model_path)

                for message in validation_messages:
                    print(f"[UNIVERSAL ADAPTER][MODEL_VALIDATION] {message}")
                    warnings.append(message)

                if is_valid_model:
                    model_path = candidate_model_path
                    warnings.append(f"User script created valid model artifact in {model_path}.")
                    status = "success"
                    break

                status = "failed"
                model_path = None
                stderr = (stderr or "") + "\n" + "\n".join(validation_messages)
                warnings.append("Model artifact validation failed.")
                print("[UNIVERSAL ADAPTER] model artifact validation failed.")

            if result.returncode != 0 and with_args and is_argparse_error(stderr):
                warnings.append("SCRIPT_WITH_ARGS failed due to argument parsing. Falling back to SCRIPT_DIRECT.")
                continue

            if result.returncode == 0:
                print("[UNIVERSAL ADAPTER] script ran successfully but did not create a valid model artifact.")

            if with_args:
                continue

        if model_path is None:
            print("[UNIVERSAL ADAPTER] No valid model artifact found after running the script.")
            warnings.append(
                "No valid model artifact found after executing the user script. "
                "The runtime will not force a fake model_path."
            )

    except Exception as exc:
        status = "failed"
        model_path = None
        warnings.append(f"Universal adapter failed: {exc}")
        stderr = (stderr or "") + f"\nUniversal adapter failed: {exc}"
        print(f"[UNIVERSAL ADAPTER] exception: {exc}")

    if model_path is None or not model_path.exists():
        status = "failed"
        model_path = None
        print("[UNIVERSAL ADAPTER] no valid model artifact located - model_path set to None")
    else:
        status = "success"

    stdout_metrics = parse_metrics_from_text(stdout)
    metrics.update(stdout_metrics)
    metrics.update(load_metrics_from_file(outputs_dir))

    algorithm_metadata = algorithm_metadata or {}

    detected_algorithms = algorithm_metadata.get("detected_algorithms", [])
    used_algorithms = algorithm_metadata.get("used_algorithms", [])
    algorithm_replacements = algorithm_metadata.get("algorithm_replacements", [])
    has_algorithm_replacement = bool(algorithm_metadata.get("has_algorithm_replacement"))
    algorithm_replacement_count = int(algorithm_metadata.get("algorithm_replacement_count", 0))

    return {
        "status": status,
        "execution_mode": execution_mode,
        "model_path": str(model_path) if model_path is not None else None,
        "metrics": metrics,
        "dataset_aliases": aliases,
        "detected_algorithms": detected_algorithms,
        "used_algorithms": used_algorithms,
        "algorithm_replacements": algorithm_replacements,
        "has_algorithm_replacement": has_algorithm_replacement,
        "algorithm_replacement_count": algorithm_replacement_count,
        "warnings": warnings,
        "stdout": stdout,
        "stderr": stderr,
        "return_code": float(return_code),
    }


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

    print("========== UNIVERSAL ADAPTER STANDARD FALLBACK TRAINING ==========")

    df = pd.read_csv(dataset_path)

    if df.empty:
        raise ValueError("Dataset is empty.")

    target_column = infer_target_column(config, df)
    task_type = infer_task_type(config)

    X = df.drop(columns=[target_column]).copy()
    y = df[target_column].copy()

    if not isinstance(X, pd.DataFrame):
        X = pd.DataFrame(X)

    model, model_mode, model_warnings = build_model_from_module(workspace, entrypoint)

    if model is None:
        raise RuntimeError(
            f"[UNIVERSAL ADAPTER] Fallback training could not load model from module: {model_warnings}"
        )

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
        except Exception:
            stratify = None

    X_train, X_test, y_train, y_test = dataframe_safe_train_test_split(
        X=X,
        y=y,
        test_size=test_size,
        random_state=random_state,
        shuffle=shuffle,
        stratify=stratify,
    )

    training_model.fit(X_train, y_train)

    y_pred = training_model.predict(X_test)
    metrics = compute_standard_metrics(task_type, y_test, y_pred)

    save_model_artifact(training_model, outputs_dir)
    save_metrics_files(outputs_dir, metrics)

    return metrics