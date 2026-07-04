from __future__ import annotations

import argparse
import json
import os
import pickle
import re
import shutil
import subprocess
import sys
import time
import traceback
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
}

METRIC_REGEX = re.compile(
    r"^\s*(?P<key>"
    r"loss|val_loss|accuracy|val_accuracy|acc|f1|f1_score|precision|recall|"
    r"rmse|mse|mae|r2|r2_score|auc|roc_auc"
    r")\s*[:=]\s*(?P<value>[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\s*$",
    re.IGNORECASE,
)


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
    def load_context(self, context):
        try:
            import torch
        except ImportError as exc:
            raise RuntimeError("PyTorch is required to log .pt/.pth models") from exc

        model_path = context.artifacts["model_path"]
        self.model = torch.load(model_path, map_location="cpu")

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
        import torch

        input_data = self._prepare_input(model_input)

        if isinstance(input_data, list):
            input_data = torch.tensor(input_data, dtype=torch.float32)
        elif hasattr(input_data, "shape"):
            input_data = torch.tensor(input_data, dtype=torch.float32)

        with torch.no_grad():
            output = self.model(input_data)

        if isinstance(output, torch.Tensor):
            return output.cpu().numpy()

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


def parse_metrics(stdout: str) -> Dict[str, float]:
    metrics: Dict[str, float] = {}

    for line in (stdout or "").splitlines():
        line = line.strip()

        match = METRIC_REGEX.match(line)
        if not match:
            continue

        key = normalize_metric_name(match.group("key"))
        value = match.group("value")

        try:
            metrics[key] = float(value)
        except ValueError:
            continue

    return metrics


def find_model_artifacts(workspace: Path) -> List[Path]:
    paths: List[Path] = []

    ignored_dirs = {
        ".git",
        "__pycache__",
        "mlruns",
        ".venv",
        "venv",
        "env",
        ".ipynb_checkpoints",
    }

    for extension in SUPPORTED_MODEL_EXTENSIONS:
        for path in workspace.rglob(f"*{extension}"):
            if any(part in ignored_dirs for part in path.parts):
                continue

            if path.is_file():
                paths.append(path)

    return sorted(paths, key=lambda p: (p.stat().st_mtime, p.name), reverse=True)


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


def set_platform_production_alias(
    registered_model_name: str,
    model_version: str,
) -> None:
    """
    Met à jour l'alias MLflow "production" pour le modèle officiel de la plateforme.

    Cette fonction ne doit jamais casser l'exécution du runner : si MLflow ne supporte pas
    les alias ou si l'appel échoue, on garde le modèle enregistré et on affiche seulement
    un warning exploitable dans les logs.
    """
    if not registered_model_name or not str(registered_model_name).strip():
        return

    if not model_version or not str(model_version).strip():
        return

    try:
        client = MlflowClient()
        client.set_registered_model_alias(
            name=str(registered_model_name).strip(),
            alias="production",
            version=str(model_version).strip(),
        )

        print(f"registered_model_name={registered_model_name}")
        print(f"model_version={model_version}")
        print(f"production_version={model_version}")
        print(f"model_uri=models:/{registered_model_name}/{model_version}")
        print(f"model_alias_uri=models:/{registered_model_name}@production")
        print(
            "[PLATFORM_MODEL] Production alias updated: "
            f"{registered_model_name}@production -> v{model_version}"
        )

    except Exception as exc:
        print(
            "[PLATFORM_MODEL] Could not set production alias for "
            f"{registered_model_name} v{model_version}: {exc}"
        )


def register_user_mlflow_model_as_platform_model(
    candidate: UserMlflowModelCandidate,
    platform_registered_model_name: str,
) -> Optional[str]:
    """
    Réenregistre un modèle MLflow créé par le script utilisateur
    sous le nom officiel attendu par la plateforme.
    """
    try:
        source_uri = f"models:/{candidate.name}/{candidate.version}"

        print(
            "[PLATFORM_MODEL] User MLflow model detected: "
            f"{candidate.name} v{candidate.version}, source={candidate.source}"
        )

        print(
            "[PLATFORM_MODEL] Re-registering user model as platform model: "
            f"{platform_registered_model_name}"
        )

        model_version = mlflow.register_model(
            model_uri=source_uri,
            name=platform_registered_model_name,
        )

        platform_model_uri = (
            f"models:/{platform_registered_model_name}/{model_version.version}"
        )

        set_platform_production_alias(
            registered_model_name=platform_registered_model_name,
            model_version=str(model_version.version),
        )

        print(
            "[PLATFORM_MODEL] Platform model registered successfully: "
            f"{platform_registered_model_name} v{model_version.version}"
        )

        mlflow.log_param("platform_model_source_type", "USER_MLFLOW_MODEL")
        mlflow.log_param("user_model_name", candidate.name)
        mlflow.log_param("user_model_version", candidate.version)
        mlflow.log_param("user_model_run_id", candidate.run_id or "")
        mlflow.log_param("platform_registered_model_name", platform_registered_model_name)
        mlflow.log_param("platform_model_uri", platform_model_uri)
        mlflow.log_param("platform_model_version", str(model_version.version))

        return platform_model_uri

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


def create_dataset_aliases(workspace: Path, dataset_path: Optional[Path]) -> None:
    if dataset_path is None:
        return

    dataset_path = dataset_path.resolve()
    workspace = workspace.resolve()

    if not dataset_path.exists():
        print(f"[PLATFORM] Dataset path does not exist: {dataset_path}")
        return

    aliases = [
        "dataset.csv",
        "data.csv",
        "train.csv",
        "input.csv",
    ]

    for alias in aliases:
        alias_path = workspace / alias

        try:
            if alias_path.exists():
                continue

            shutil.copyfile(dataset_path, alias_path)
            print(f"[PLATFORM] Dataset alias created: {alias_path}")
        except Exception as exc:
            print(f"[PLATFORM] Could not create dataset alias {alias_path}: {exc}")


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
) -> subprocess.CompletedProcess:
    workspace = workspace.resolve()
    script_path = workspace / entrypoint

    if not script_path.exists():
        raise FileNotFoundError(f"Training script not found: {script_path}")

    output_dir = prepare_outputs_dir(workspace)

    if dataset_path is not None:
        dataset_path = dataset_path.resolve()

    create_dataset_aliases(workspace, dataset_path)

    env = {
        **os.environ,
        "OUTPUT_DIR": str(output_dir),
        "MLOPS_OUTPUT_DIR": str(output_dir),
        "WORKSPACE_DIR": str(workspace),
        "PLATFORM_OUTPUT_DIR": str(output_dir),
    }

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

        result = subprocess.run(
            command,
            cwd=str(workspace),
            capture_output=True,
            text=True,
            env=env,
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


def try_log_pickle_model(model_path: Path) -> Optional[str]:
    try:
        model = joblib.load(model_path)
        mlflow.sklearn.log_model(model, artifact_path="model")
        return mlflow.get_artifact_uri("model")
    except Exception as first_exc:
        print(f"[PLATFORM_MODEL] sklearn log_model failed for {model_path}: {first_exc}")

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


def try_log_pytorch_model(model_path: Path) -> Optional[str]:
    try:
        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=TorchPyFuncWrapper(),
            artifacts={"model_path": str(model_path)},
        )
        return mlflow.get_artifact_uri("model")
    except Exception as exc:
        print(f"[PLATFORM_MODEL] pytorch pyfunc log_model failed for {model_path}: {exc}")
        return None


def log_model_artifact(model_path: Path) -> Dict[str, Any]:
    extension = model_path.suffix.lower()
    result: Dict[str, Any] = {
        "path": str(model_path),
        "extension": extension,
        "logged_model_uri": None,
        "artifact_only": False,
    }

    if extension in {".pkl", ".joblib", ".pickle"}:
        logged_uri = try_log_pickle_model(model_path)
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

    script_start_time_ms = int(time.time() * 1000)

    result = run_user_script(
        workspace=workspace,
        entrypoint=str(script_path.name),
        dataset_path=dataset_path,
    )

    script_end_time_ms = int(time.time() * 1000)

    metrics = parse_metrics(result.stdout)
    model_artifacts = find_model_artifacts(workspace)

    logged_model_uri: Optional[str] = None
    platform_model_registered = False
    platform_model_version: Optional[str] = None

    recoverable_user_mlflow_failure = (
        result.returncode != 0
        and bool(model_artifacts)
        and is_recoverable_user_mlflow_error(result.stderr, result.stdout)
    )

    error_info = format_error_info(result.stderr) if result.returncode != 0 else None
    recovery_warning = (
        build_recovered_mlflow_warning(result, model_artifacts)
        if recoverable_user_mlflow_failure
        else None
    )

    with mlflow.start_run(run_name=run_name):
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

            artifact_results: List[Dict[str, Any]] = []

            for model_path in model_artifacts:
                artifact_result = log_model_artifact(model_path)
                artifact_results.append(artifact_result)

                if artifact_result.get("logged_model_uri") and not logged_model_uri:
                    logged_model_uri = artifact_result["logged_model_uri"]

            if logged_model_uri:
                try:
                    registered_version = mlflow.register_model(
                        model_uri=logged_model_uri,
                        name=platform_registered_model_name,
                    )

                    mlflow.log_param(
                        "platform_model_source_type",
                        "LOCAL_MODEL_FILE",
                    )
                    platform_model_registered = True
                    platform_model_version = str(registered_version.version)

                    mlflow.log_param(
                        "platform_model_version",
                        platform_model_version,
                    )

                    set_platform_production_alias(
                        registered_model_name=platform_registered_model_name,
                        model_version=platform_model_version,
                    )

                    print(
                        "[PLATFORM_MODEL] Local model registered as platform model: "
                        f"{platform_registered_model_name} v{registered_version.version}"
                    )

                except Exception as exc:
                    print(
                        "[PLATFORM_MODEL] Failed to register local model as platform model: "
                        f"{exc}"
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

            if user_model_candidate:
                logged_model_uri = register_user_mlflow_model_as_platform_model(
                    candidate=user_model_candidate,
                    platform_registered_model_name=platform_registered_model_name,
                )

                if logged_model_uri:
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

            else:
                print("[PLATFORM_MODEL] No model candidate detected.")

                mlflow.log_text(
                    "No model artifact detected and no user-created MLflow model found.",
                    artifact_file="logs/model_artifacts.json",
                )

    recovered_success = (
        recoverable_user_mlflow_failure
        and bool(logged_model_uri)
        and platform_model_registered
    )

    final_success = result.returncode == 0 or recovered_success
    final_status = "SUCCESS" if final_success else "FAILED"
    effective_return_code = 0 if final_success else result.returncode

    if recovered_success:
        print(
            "[PLATFORM_RECOVERY] Recovery completed successfully. "
            "The user script failed only in its own MLflow logic, "
            "but the platform registered the detected model."
        )
        if platform_model_version:
            print(f"registered_model_name={platform_registered_model_name}")
            print(f"model_version={platform_model_version}")
            print(f"production_version={platform_model_version}")
            print(
                "model_uri="
                f"models:/{platform_registered_model_name}/{platform_model_version}"
            )
            print(f"model_alias_uri=models:/{platform_registered_model_name}@production")

    return RunResult(
        status=final_status,
        return_code=effective_return_code,
        stdout=result.stdout,
        stderr=result.stderr,
        metrics=metrics,
        model_paths=[str(path) for path in model_artifacts],
        logged_model_uri=logged_model_uri,
        error=None if final_success else error_info,
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

            if args.experiment_name:
                mlflow.set_experiment(args.experiment_name)

            with mlflow.start_run(run_name=args.run_name):
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