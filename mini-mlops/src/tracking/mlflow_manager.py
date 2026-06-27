from __future__ import annotations

from typing import Any

import mlflow
import mlflow.sklearn


def setup_mlflow(config: dict[str, Any]) -> None:
    tracking_uri = config["tracking"]["uri"]
    experiment_name = config["tracking"]["experiment_name"]

    mlflow.set_tracking_uri(tracking_uri)
    mlflow.set_experiment(experiment_name)


def log_run(
    config: dict[str, Any],
    model_name: str,
    model_params: dict[str, Any],
    metrics: dict[str, float],
    trained_model: Any,
) -> dict[str, Any]:
    framework = config["task"]["framework"]
    task_type = config["task"]["type"]
    registered_model_name = config["registry"]["model_name"]

    with mlflow.start_run() as run:
        mlflow.log_param("project_name", config["project_name"])
        mlflow.log_param("framework", framework)
        mlflow.log_param("task_type", task_type)
        mlflow.log_param("model_name", model_name)

        for param_name, param_value in model_params.items():
            mlflow.log_param(param_name, param_value)

        for metric_name, metric_value in metrics.items():
            mlflow.log_metric(metric_name, metric_value)

        if framework == "sklearn":
            mlflow.sklearn.log_model(
                sk_model=trained_model,
                name="model",
                registered_model_name=registered_model_name,
            )
        else:
            raise ValueError(
                f"MLflow logging not yet implemented for framework '{framework}'"
            )

        return {
            "run_id": run.info.run_id,
            "experiment_id": run.info.experiment_id,
            "registered_model_name": registered_model_name,
        }