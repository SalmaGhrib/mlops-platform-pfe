import os
from io import BytesIO
from typing import Any, Dict, List, Optional

import mlflow
import numpy as np
import pandas as pd
from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image
from pydantic import BaseModel


app = FastAPI(
    title="MLOps Generic Model Server",
    description="Generic FastAPI server for serving MLflow production models",
    version="1.1.0"
)

MODEL_URI = os.getenv("MODEL_URI", "").strip()
MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI", "").strip()
MODEL_DATA_TYPE = os.getenv("MODEL_DATA_TYPE", "TABULAR").strip().upper()

model = None


class PredictionRequest(BaseModel):
    rows: Optional[List[Dict[str, Any]]] = None
    data: Optional[Any] = None


class TextPredictionRequest(BaseModel):
    text: str
    columnName: Optional[str] = "text"


@app.on_event("startup")
def load_model():
    global model

    if not MODEL_URI:
        raise RuntimeError("MODEL_URI environment variable is required.")

    if MLFLOW_TRACKING_URI:
        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    print(f"[MODEL_SERVER] MLFLOW_TRACKING_URI={MLFLOW_TRACKING_URI or '-'}")
    print(f"[MODEL_SERVER] MODEL_URI={MODEL_URI}")
    print(f"[MODEL_SERVER] MODEL_DATA_TYPE={MODEL_DATA_TYPE}")

    model = mlflow.pyfunc.load_model(MODEL_URI)

    print(f"[MODEL_SERVER] Model loaded successfully: {MODEL_URI}")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "modelUri": MODEL_URI,
        "mlflowTrackingUri": MLFLOW_TRACKING_URI,
        "dataType": MODEL_DATA_TYPE
    }


@app.get("/model-info")
def model_info():
    return {
        "modelUri": MODEL_URI,
        "mlflowTrackingUri": MLFLOW_TRACKING_URI,
        "dataType": MODEL_DATA_TYPE,
        "loaded": model is not None
    }


@app.post("/predict")
def predict(request: PredictionRequest):
    """
    Endpoint générique pour TABULAR.
    Il peut aussi marcher pour certains modèles TEXT si le modèle attend une colonne "text".
    """
    ensure_model_loaded()

    try:
        input_df = build_dataframe_from_request(request)
        predictions = model.predict(input_df)

        return build_prediction_response(predictions, rows_count=len(input_df), mode="TABULAR")

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Prediction failed: {str(e)}")


@app.post("/predict-text")
def predict_text(request: TextPredictionRequest):
    """
    Endpoint dédié NLP/TEXT.

    Pour les modèles NLP sklearn de type :
    Pipeline([TfidfVectorizer, Classifier])
    l'entrée correcte est souvent une liste de textes :
    ["texte à prédire"]

    Pour les wrappers MLflow ou modèles qui attendent un DataFrame,
    on garde un fallback DataFrame.
    """
    ensure_model_loaded()

    text_value = request.text

    if text_value is None or not str(text_value).strip():
        raise HTTPException(status_code=400, detail="Le champ 'text' est obligatoire.")

    text_value = str(text_value)

    errors = []

    try:
        predictions = model.predict([text_value])
        return build_prediction_response(predictions, rows_count=1, mode="TEXT_LIST")
    except Exception as e:
        errors.append(f"TEXT_LIST failed: {str(e)}")

    try:
        predictions = model.predict(pd.Series([text_value]))
        return build_prediction_response(predictions, rows_count=1, mode="TEXT_SERIES")
    except Exception as e:
        errors.append(f"TEXT_SERIES failed: {str(e)}")

    try:
        column_name = request.columnName or "text"
        input_df = pd.DataFrame([{column_name: text_value}])
        predictions = model.predict(input_df)
        return build_prediction_response(predictions, rows_count=1, mode="TEXT_DATAFRAME")
    except Exception as e:
        errors.append(f"TEXT_DATAFRAME failed: {str(e)}")

    raise HTTPException(
        status_code=500,
        detail="Text prediction failed. " + " | ".join(errors)
    )


@app.post("/predict-image")
async def predict_image(imageFile: UploadFile = File(...)):
    """
    Endpoint IMAGE basique.
    Il lit l'image et la transforme en tableau numpy.
    Attention : le preprocessing exact dépend du modèle entraîné.
    """
    ensure_model_loaded()

    try:
        content = await imageFile.read()

        image = Image.open(BytesIO(content)).convert("RGB")

        # Prétraitement générique simple.
        # Si ton modèle image attend une taille spécifique, on pourra l'adapter plus tard.
        image = image.resize((224, 224))

        image_array = np.array(image).astype(np.float32) / 255.0

        # Format tabulaire générique : une ligne contenant les pixels aplatis.
        flattened = image_array.flatten().reshape(1, -1)

        input_df = pd.DataFrame(flattened)

        predictions = model.predict(input_df)

        return build_prediction_response(predictions, rows_count=1, mode="IMAGE")

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Image prediction failed: {str(e)}")


def ensure_model_loaded():
    if model is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")


def build_dataframe_from_request(request: PredictionRequest) -> pd.DataFrame:
    if request.rows is not None:
        return pd.DataFrame(request.rows)

    if request.data is not None:
        if isinstance(request.data, list):
            return pd.DataFrame(request.data)

        if isinstance(request.data, dict):
            return pd.DataFrame([request.data])

        return pd.DataFrame(request.data)

    raise HTTPException(
        status_code=400,
        detail="Request must contain 'rows' or 'data'."
    )


def build_prediction_response(predictions: Any, rows_count: int, mode: str):
    if hasattr(predictions, "tolist"):
        predictions = predictions.tolist()

    return {
        "status": "success",
        "modelUri": MODEL_URI,
        "dataType": MODEL_DATA_TYPE,
        "predictionMode": mode,
        "rowsCount": rows_count,
        "predictions": predictions
    }