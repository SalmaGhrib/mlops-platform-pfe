from typing import Dict, List, Optional, Tuple

from mlflow.tracking import MlflowClient


MINIMIZE_METRICS = {
    "loss",
    "log_loss",
    "mse",
    "rmse",
    "mae",
    "error",
    "val_loss",
}


def normalize_metric_name(metric_name: Optional[str]) -> Optional[str]:
    if metric_name is None:
        return None

    clean = str(metric_name).strip().lower()
    clean = clean.replace(" ", "_").replace("-", "_")

    aliases = {
        "f1": "f1_score",
        "f1score": "f1_score",
        "accuracy_score": "accuracy",
        "r2_score": "r2",
        "precision_score": "precision",
        "recall_score": "recall",
    }

    return aliases.get(clean, clean)


def infer_direction(metric_name: Optional[str], direction: Optional[str] = None) -> str:
    if direction and str(direction).strip().lower() in {"min", "max"}:
        return str(direction).strip().lower()

    metric = normalize_metric_name(metric_name) or ""

    if metric in MINIMIZE_METRICS or metric.endswith("_loss"):
        return "min"

    return "max"


def get_metric_value(
    client: MlflowClient,
    run_id: str,
    metric_name: str
) -> Optional[float]:
    if not run_id or not metric_name:
        return None

    run = client.get_run(run_id)
    metrics = run.data.metrics or {}

    normalized_target = normalize_metric_name(metric_name)

    # match exact
    if metric_name in metrics:
        return metrics.get(metric_name)

    # match normalized
    for key, value in metrics.items():
        if normalize_metric_name(key) == normalized_target:
            return value

    return None


def clear_old_production_tags(
    client: MlflowClient,
    model_name: str,
    selected_version: str
) -> None:
    versions = client.search_model_versions(f"name='{model_name}'")

    for version in versions:
        version_number = str(version.version)

        if version_number == str(selected_version):
            continue

        try:
            client.set_model_version_tag(
                name=model_name,
                version=version_number,
                key="promotion_status",
                value="archived"
            )
        except Exception as exc:
            print(f"[PROMOTE_MODEL] Failed to archive old version {version_number}: {exc}")


def promote_best_version(
    tracking_uri: str,
    model_name: str,
    alias: str = "production",
    primary_metric: Optional[str] = None,
    direction: str = "max",
):
    """
    Choisit la version à mettre en production.

    - Si primary_metric existe : choisir la meilleure version selon cette métrique.
    - Si primary_metric absent : choisir la dernière version.
    - Direction auto :
      log_loss/loss/rmse/mae/mse => min
      accuracy/f1/precision/recall/r2 => max
    """

    if not tracking_uri:
        raise ValueError("tracking_uri is required.")

    if not model_name or not str(model_name).strip():
        raise ValueError("model_name is required.")

    model_name = str(model_name).strip()
    primary_metric = normalize_metric_name(primary_metric)
    direction = infer_direction(primary_metric, direction)

    client = MlflowClient(tracking_uri=tracking_uri)

    versions = client.search_model_versions(f"name='{model_name}'")

    if not versions:
        raise RuntimeError(f"No versions found for model: {model_name}")

    if not primary_metric:
        latest_version = max(versions, key=lambda v: int(v.version))

        client.set_registered_model_alias(
            name=model_name,
            alias=alias,
            version=latest_version.version,
        )

        clear_old_production_tags(client, model_name, str(latest_version.version))

        client.set_model_version_tag(
            name=model_name,
            version=latest_version.version,
            key="promotion_status",
            value="production",
        )

        return latest_version, {
            "strategy": "latest",
            "selected_version": latest_version.version,
        }

    scored_versions: List[Tuple[object, float]] = []

    for version in versions:
        if not version.run_id:
            continue

        metric_value = get_metric_value(client, version.run_id, primary_metric)

        if metric_value is not None:
            scored_versions.append((version, float(metric_value)))

    if not scored_versions:
        latest_version = max(versions, key=lambda v: int(v.version))

        client.set_registered_model_alias(
            name=model_name,
            alias=alias,
            version=latest_version.version,
        )

        clear_old_production_tags(client, model_name, str(latest_version.version))

        client.set_model_version_tag(
            name=model_name,
            version=latest_version.version,
            key="promotion_status",
            value="production",
        )

        return latest_version, {
            "strategy": "fallback_latest",
            "selected_version": latest_version.version,
            "reason": f"No version has metric {primary_metric}",
        }

    reverse = direction == "max"

    best_version, best_score = sorted(
        scored_versions,
        key=lambda item: item[1],
        reverse=reverse,
    )[0]

    client.set_registered_model_alias(
        name=model_name,
        alias=alias,
        version=best_version.version,
    )

    clear_old_production_tags(client, model_name, str(best_version.version))

    client.set_model_version_tag(
        name=model_name,
        version=best_version.version,
        key="promotion_status",
        value="production",
    )

    client.set_model_version_tag(
        name=model_name,
        version=best_version.version,
        key="promotion_metric",
        value=primary_metric,
    )

    client.set_model_version_tag(
        name=model_name,
        version=best_version.version,
        key="promotion_score",
        value=str(best_score),
    )

    client.set_model_version_tag(
        name=model_name,
        version=best_version.version,
        key="promotion_direction",
        value=direction,
    )

    return best_version, {
        "strategy": "best_metric",
        "selected_version": best_version.version,
        "metric": primary_metric,
        "score": best_score,
        "direction": direction,
    }