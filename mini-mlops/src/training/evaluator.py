from __future__ import annotations

import math
from typing import Any

import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    f1_score,
    precision_score,
    recall_score,
    mean_squared_error,
    mean_absolute_error,
    r2_score,
)


def evaluate_model(
    model: Any,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    config: dict,
) -> dict[str, float]:
    if not hasattr(model, "predict"):
        raise ValueError("The provided model does not have a 'predict' method.")

    y_pred = model.predict(X_test)
    task_type = config["task"]["type"]

    metrics: dict[str, float] = {}

    if task_type == "classification":
        metrics["accuracy"] = float(accuracy_score(y_test, y_pred))
        metrics["f1_weighted"] = float(f1_score(y_test, y_pred, average="weighted"))
        metrics["precision_weighted"] = float(
            precision_score(y_test, y_pred, average="weighted", zero_division=0)
        )
        metrics["recall_weighted"] = float(
            recall_score(y_test, y_pred, average="weighted", zero_division=0)
        )

    elif task_type == "regression":
        metrics["rmse"] = float(math.sqrt(mean_squared_error(y_test, y_pred)))
        metrics["mae"] = float(mean_absolute_error(y_test, y_pred))
        metrics["r2"] = float(r2_score(y_test, y_pred))

    else:
        raise ValueError(f"Unsupported task type: {task_type}")

    return metrics