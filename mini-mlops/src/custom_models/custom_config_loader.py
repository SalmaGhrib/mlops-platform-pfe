import re
from pathlib import Path
from typing import Any, Dict

import yaml


def safe_name(value: str) -> str:
    value = str(value).strip().lower()
    value = re.sub(r"[^a-zA-Z0-9_.-]+", "-", value)
    value = re.sub(r"-+", "-", value)
    return value.strip("-")


def load_custom_config(config_path: str) -> Dict[str, Any]:
    path = Path(config_path)

    if not path.exists():
        raise FileNotFoundError(f"Custom config file not found: {config_path}")

    with open(path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f) or {}

    return normalize_custom_config(config)


def normalize_custom_config(config: Dict[str, Any]) -> Dict[str, Any]:
    """
    Configuration spécifique aux modèles custom.

    Règle :
    - project_name = Experiment MLflow
    - run.name = nom du run MLflow
    - project_name + model.name = Registered Model
    """

    config.setdefault("project_name", "default-custom-project")

    config.setdefault("model", {})
    config["model"].setdefault("name", "custom-model")
    config["model"].setdefault("primary_metric", None)
    config["model"].setdefault("direction", "max")
    config["model"].setdefault("params", {})

    project_name = safe_name(config["project_name"])
    model_name = safe_name(config["model"]["name"])

    config["project_name"] = project_name
    config["model"]["name"] = model_name

    config.setdefault("run", {})
    config["run"].setdefault("name", f"{model_name}-run")
    config["run"]["name"] = safe_name(config["run"]["name"])

    config.setdefault("tracking", {})
    config["tracking"].setdefault(
        "uri",
        "http://mlflow-service.mlops.svc.cluster.local:5000"
    )
    config["tracking"].setdefault("experiment_name", project_name)

    config.setdefault("custom_code", {})
    config["custom_code"].setdefault("source_type", "inline")
    config["custom_code"].setdefault("entrypoint", "train.py")
    config["custom_code"].setdefault("files", {})

    if config["custom_code"]["source_type"] != "inline":
        raise ValueError(
            "For V1 custom models, custom_code.source_type must be inline."
        )

    if not config["custom_code"]["files"]:
        raise ValueError(
            "custom_code.files is required for inline custom model."
        )

    entrypoint = config["custom_code"]["entrypoint"]

    if entrypoint not in config["custom_code"]["files"]:
        raise ValueError(
            f"Entrypoint {entrypoint} not found in custom_code.files"
        )

    config.setdefault("data", {})
    config["data"].setdefault("source_type", "none")
    config["data"].setdefault("dataset_name", "internal-data")
    config["data"].setdefault("filename", None)
    config["data"].setdefault("content", None)

    config.setdefault("registry", {})
    config["registry"].setdefault(
        "model_name",
        f"{project_name}-{model_name}"
    )
    config["registry"]["model_name"] = safe_name(config["registry"]["model_name"])
    config["registry"].setdefault("alias", "production")

    config.setdefault("validation", {})
    config["validation"].setdefault("enabled", False)
    config["validation"].setdefault("strict", False)
    config["validation"].setdefault("input_example", None)

    return config