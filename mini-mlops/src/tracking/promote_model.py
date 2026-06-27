from __future__ import annotations

from typing import Any

import mlflow
from mlflow.tracking import MlflowClient


def promote_best_model(config: dict[str, Any], current_run_info: dict[str, Any]) -> dict[str, Any]:
    tracking_uri = config["tracking"]["uri"]
    experiment_name = config["tracking"]["experiment_name"]
    registered_model_name = config["registry"]["model_name"]
    alias = config["registry"]["alias"]

    primary_metric = config["metrics"]["primary_metric"]
    mode = config["metrics"]["mode"]

    mlflow.set_tracking_uri(tracking_uri)
    client = MlflowClient()

    experiment = client.get_experiment_by_name(experiment_name)
    if experiment is None:
        raise ValueError(f"Experiment '{experiment_name}' not found")

    runs = client.search_runs(
        experiment_ids=[experiment.experiment_id],
        order_by=[f"metrics.{primary_metric} DESC"] if mode == "max" else [f"metrics.{primary_metric} ASC"],
    )

    if not runs:
        raise ValueError(f"No runs found in experiment '{experiment_name}'")

    best_run = runs[0]
    best_run_id = best_run.info.run_id
    best_metric_value = best_run.data.metrics.get(primary_metric)

    model_versions = client.search_model_versions(f"name = '{registered_model_name}'")

    best_version = None
    for version in model_versions:
        if version.run_id == best_run_id:
            best_version = version
            break

    if best_version is None:
        raise ValueError(
            f"No model version found for registered model '{registered_model_name}' and run_id '{best_run_id}'"
        )

    client.set_registered_model_alias(
        name=registered_model_name,
        alias=alias,
        version=best_version.version,
    )

    return {
        "best_run_id": best_run_id,
        "best_metric_name": primary_metric,
        "best_metric_value": best_metric_value,
        "promoted_version": best_version.version,
        "alias": alias,
        "registered_model_name": registered_model_name,
    }