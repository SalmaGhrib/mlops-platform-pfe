from __future__ import annotations

import os
from typing import Any, Optional

import mlflow
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


# ==========================================================
# CONFIG
# ==========================================================
MLFLOW_TRACKING_URI = os.getenv(
    "MLFLOW_TRACKING_URI",
    "http://mlflow-service.mlops.svc.cluster.local:5000"
)


DEFAULT_MODEL_NAME = os.getenv(
    "REGISTERED_MODEL_NAME",
    "default-model"
)

DEFAULT_MODEL_ALIAS = os.getenv(
    "MODEL_ALIAS",
    "production"
)

mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)


# ==========================================================
# FASTAPI APP
# ==========================================================
app = FastAPI(
    title="MLOps Generic Model Prediction API",
    version="2.0.0",
    description="API générique pour prédire avec n'importe quel modèle sklearn enregistré dans MLflow."
)


# ==========================================================
# GLOBAL CACHE
# ==========================================================
loaded_models: dict[str, Any] = {}


# ==========================================================
# SCHEMAS
# ==========================================================
class PredictionRequest(BaseModel):
    data: list[dict[str, Any]] = Field(
        ...,
        description="Liste des lignes à prédire sous forme JSON."
    )

    model_name: Optional[str] = Field(
        default=None,
        description="Nom du modèle enregistré dans MLflow."
    )

    alias: Optional[str] = Field(
        default=None,
        description="Alias MLflow du modèle, par exemple production."
    )


class LoadModelRequest(BaseModel):
    model_name: str
    alias: str = "production"


# ==========================================================
# UTILS
# ==========================================================
def build_model_uri(model_name: str, alias: str) -> str:
    return f"models:/{model_name}@{alias}"


def get_cache_key(model_name: str, alias: str) -> str:
    return f"{model_name}@{alias}"


def load_model(model_name: str, alias: str) -> Any:
    model_uri = build_model_uri(model_name, alias)

    try:
        model = mlflow.pyfunc.load_model(model_uri)
        loaded_models[get_cache_key(model_name, alias)] = model
        return model

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Impossible de charger le modèle {model_uri}: {str(e)}"
        )


def get_or_load_model(model_name: str, alias: str) -> Any:
    cache_key = get_cache_key(model_name, alias)

    if cache_key in loaded_models:
        return loaded_models[cache_key]

    return load_model(model_name, alias)


# ==========================================================
# ROUTES
# ==========================================================
@app.get("/")
def root() -> dict[str, Any]:
    return {
        "message": "Generic MLOps Model API is running",
        "docs": "/docs",
        "tracking_uri": MLFLOW_TRACKING_URI,
        "default_model_name": DEFAULT_MODEL_NAME,
        "default_alias": DEFAULT_MODEL_ALIAS,
    }


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "tracking_uri": MLFLOW_TRACKING_URI,
        "loaded_models": list(loaded_models.keys()),
    }


@app.post("/load-model")
def load_model_endpoint(request: LoadModelRequest) -> dict[str, Any]:
    model = load_model(
        model_name=request.model_name,
        alias=request.alias,
    )

    return {
        "message": "Model loaded successfully",
        "model_name": request.model_name,
        "alias": request.alias,
        "model_uri": build_model_uri(request.model_name, request.alias),
        "loaded": model is not None,
    }


@app.post("/predict")
def predict(request: PredictionRequest) -> dict[str, Any]:
    model_name = request.model_name or DEFAULT_MODEL_NAME
    alias = request.alias or DEFAULT_MODEL_ALIAS

    if not model_name or model_name == "default-model":
        raise HTTPException(
            status_code=400,
            detail=(
                "Aucun modèle valide fourni. "
                "Ajoute model_name dans le JSON ou définis REGISTERED_MODEL_NAME dans les variables d'environnement."
            )
        )

    if not request.data:
        raise HTTPException(
            status_code=400,
            detail="Le champ data ne doit pas être vide."
        )

    try:
        model = get_or_load_model(model_name, alias)

        df = pd.DataFrame(request.data)

        predictions = model.predict(df)

        if hasattr(predictions, "tolist"):
            predictions = predictions.tolist()

        return {
            "model_name": model_name,
            "alias": alias,
            "model_uri": build_model_uri(model_name, alias),
            "rows": len(df),
            "predictions": predictions,
        }

    except HTTPException:
        raise

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Erreur pendant la prédiction: {str(e)}"
        )


@app.post("/clear-cache")
def clear_cache() -> dict[str, Any]:
    loaded_models.clear()

    return {
        "message": "Model cache cleared successfully"
    }