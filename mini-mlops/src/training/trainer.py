from __future__ import annotations

from typing import Any
import pandas as pd


def train_model(model: Any, X_train: pd.DataFrame, y_train: pd.Series) -> Any:
    if not hasattr(model, "fit"):
        raise ValueError("The provided model does not have a 'fit' method.")

    model.fit(X_train, y_train)
    return model