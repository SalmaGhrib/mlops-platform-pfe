"""
Dockerized image classification training script for the MLOps platform.

Goal:
- Train an image classifier on simulated 28x28 grayscale digits 0..4.
- Save outputs/model.pkl.
- Log a real MLflow pyfunc model and register it.
- Set MLflow alias "production" automatically.

Dataset format:
- pixel_0 ... pixel_783: grayscale values 0..255
- label: target class 0, 1, 2, 3, or 4

Prediction format supported by the deployed FastAPI model server:
1) /predict-image:
   The server converts an uploaded image to a "pixels" list of 784 values.
2) /predict:
   {
     "rows": [
       {"pixels": [0.0, 0.1, ..., 1.0]}
     ]
   }
3) /predict with pixel columns:
   {
     "rows": [
       {"pixel_0": 0, ..., "pixel_783": 255}
     ]
   }
"""

import argparse
import json
import os
import warnings
from pathlib import Path
from typing import Any, List

import joblib
import mlflow
import mlflow.pyfunc
import numpy as np
import pandas as pd
from mlflow.tracking import MlflowClient
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score, classification_report
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import MinMaxScaler


warnings.filterwarnings("ignore")


class ImageDigitsPyFunc(mlflow.pyfunc.PythonModel):
    """
    MLflow pyfunc wrapper that accepts:
    - DataFrame with one column "pixels" containing list[784]
    - DataFrame with columns pixel_0..pixel_783
    - numpy arrays shaped (n, 784)
    """

    def __init__(self, sklearn_pipeline: Pipeline):
        self.sklearn_pipeline = sklearn_pipeline

    def _to_matrix(self, model_input: Any) -> np.ndarray:
        if isinstance(model_input, pd.DataFrame):
            if "pixels" in model_input.columns:
                rows: List[np.ndarray] = []

                for value in model_input["pixels"].tolist():
                    if isinstance(value, str):
                        value = json.loads(value)

                    arr = np.asarray(value, dtype=np.float32).reshape(-1)

                    if arr.size != 784:
                        raise ValueError(f"pixels must contain exactly 784 values. Received {arr.size}.")

                    rows.append(arr)

                X = np.vstack(rows)

            else:
                pixel_cols = [f"pixel_{i}" for i in range(784)]

                missing = [col for col in pixel_cols if col not in model_input.columns]
                if missing:
                    raise ValueError(
                        "Input must contain either a 'pixels' column with 784 values "
                        "or columns pixel_0..pixel_783. Missing example: "
                        + ", ".join(missing[:10])
                    )

                X = model_input[pixel_cols].to_numpy(dtype=np.float32)

        else:
            X = np.asarray(model_input, dtype=np.float32)

            if X.ndim == 1:
                X = X.reshape(1, -1)

        if X.shape[1] != 784:
            raise ValueError(f"Expected 784 image features. Received shape={X.shape}.")

        # Accept both 0..255 and 0..1 input values.
        if np.nanmax(X) <= 1.0:
            X = X * 255.0

        return X

    def predict(self, context, model_input: Any):
        X = self._to_matrix(model_input)
        predictions = self.sklearn_pipeline.predict(X)
        return predictions.astype(int).tolist()


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_path", required=True, help="Path to CSV dataset.")
    parser.add_argument("--output_dir", default="outputs", help="Output directory.")
    parser.add_argument("--model_name", default=None, help="MLflow registered model name.")
    return parser.parse_args()


def resolve_model_name(args_model_name: str | None) -> str:
    candidates = [
        args_model_name,
        os.getenv("MODEL_NAME"),
        os.getenv("REGISTERED_MODEL_NAME"),
        os.getenv("MLFLOW_MODEL_NAME"),
        "docker_image_digits_model",
    ]

    for value in candidates:
        if value and str(value).strip():
            return str(value).strip()

    return "docker_image_digits_model"


def set_mlflow_tracking():
    tracking_uri = (
        os.getenv("MLFLOW_TRACKING_URI")
        or os.getenv("MLFLOW_TRACKING_URL")
        or "http://mlflow-service:5000"
    ).strip()

    mlflow.set_tracking_uri(tracking_uri)
    print(f"MLFLOW_TRACKING_URI={tracking_uri}")

    experiment_name = os.getenv("MLFLOW_EXPERIMENT_NAME")
    if experiment_name and experiment_name.strip():
        mlflow.set_experiment(experiment_name.strip())
        print(f"MLFLOW_EXPERIMENT_NAME={experiment_name.strip()}")

    return tracking_uri


def load_dataset(data_path: str):
    df = pd.read_csv(data_path)

    target_col = "label"
    pixel_cols = [f"pixel_{i}" for i in range(784)]

    missing = [col for col in pixel_cols if col not in df.columns]
    if missing:
        raise ValueError(
            "Dataset must contain columns pixel_0..pixel_783. Missing example: "
            + ", ".join(missing[:10])
        )

    if target_col not in df.columns:
        raise ValueError("Dataset must contain target column 'label'.")

    X = df[pixel_cols].to_numpy(dtype=np.float32)
    y = df[target_col].astype(int).to_numpy()

    print(f"Dataset shape={df.shape}")
    print(f"Number of pixel features={len(pixel_cols)}")
    print(f"Target column={target_col}")
    print(f"Classes={sorted(pd.Series(y).unique().tolist())}")

    return X, y, pixel_cols


def build_pipeline(random_state: int = 42):
    """
    Build a fully serializable sklearn pipeline.

    Important:
    Do not use FunctionTransformer with a local function here.
    joblib/pickle cannot reliably serialize local functions such as
    build_pipeline.<locals>.normalize_pixels, which was causing the
    Kubernetes training job to fail after training.

    MinMaxScaler is a native sklearn transformer and is safely serializable.
    It scales pixel values from the training range to 0..1.
    """
    return Pipeline(
        steps=[
            ("scaler", MinMaxScaler(feature_range=(0, 1))),
            (
                "model",
                RandomForestClassifier(
                    n_estimators=250,
                    max_depth=None,
                    min_samples_leaf=1,
                    random_state=random_state,
                    n_jobs=-1,
                    class_weight="balanced",
                ),
            ),
        ]
    )


def register_pyfunc_model(model_name: str, sklearn_pipeline: Pipeline, X_example: np.ndarray):
    example_pixels = (X_example[0] / 255.0).astype(float).round(6).tolist()
    input_example = pd.DataFrame([{"pixels": example_pixels}])

    pip_requirements = [
        "mlflow==2.17.2",
        "numpy==2.2.6",
        "pandas==2.3.3",
        "pyarrow==17.0.0",
        "scikit-learn==1.5.2",
        "joblib==1.4.2",
        "cloudpickle==3.1.1",
        "pillow==10.4.0",
    ]

    with mlflow.start_run(run_name=f"{model_name}_docker_image_training") as run:
        run_id = run.info.run_id

        pyfunc_model = ImageDigitsPyFunc(sklearn_pipeline)

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=pyfunc_model,
            registered_model_name=model_name,
            input_example=input_example,
            pip_requirements=pip_requirements,
        )

        mlflow.set_tag("model_type", "dockerized_image_classifier")
        mlflow.set_tag("data_type", "IMAGE")
        mlflow.set_tag("supported_input", "predict-image or pixels[784]")
        mlflow.set_tag("target_classes", "0,1,2,3,4")

        client = MlflowClient()
        versions = client.search_model_versions(f"name = '{model_name}'")

        current_versions = [
            version for version in versions
            if getattr(version, "run_id", None) == run_id
        ]

        if not current_versions:
            raise RuntimeError(
                f"Could not find registered model version for model={model_name}, run_id={run_id}"
            )

        model_version = sorted(
            current_versions,
            key=lambda item: int(item.version),
            reverse=True,
        )[0]

        client.set_registered_model_alias(
            name=model_name,
            alias="production",
            version=model_version.version,
        )

        print(f"mlflow_run_id={run_id}")
        print(f"registered_model_name={model_name}")
        print(f"model_version={model_version.version}")
        print(f"production_version={model_version.version}")
        print(f"model_uri=models:/{model_name}/{model_version.version}")
        print(f"model_alias_uri=models:/{model_name}@production")

        return run_id, str(model_version.version)


def main():
    args = parse_args()

    print("Dockerized IMAGE model training started.")
    print(f"data_path={args.data_path}")
    print(f"output_dir={args.output_dir}")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    model_name = resolve_model_name(args.model_name)
    print(f"MODEL_NAME={model_name}")

    set_mlflow_tracking()

    X, y, pixel_cols = load_dataset(args.data_path)

    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=0.20,
        random_state=42,
        stratify=y,
    )

    print(f"Train size={X_train.shape[0]}")
    print(f"Test size={X_test.shape[0]}")

    pipeline = build_pipeline()

    print("Training RandomForest image classifier...")
    pipeline.fit(X_train, y_train)

    y_pred = pipeline.predict(X_test)

    accuracy = accuracy_score(y_test, y_pred)
    f1 = f1_score(y_test, y_pred, average="weighted", zero_division=0)
    precision = precision_score(y_test, y_pred, average="weighted", zero_division=0)
    recall = recall_score(y_test, y_pred, average="weighted", zero_division=0)

    print("Classification report:")
    print(classification_report(y_test, y_pred, zero_division=0))

    # Metrics expected by the platform parser.
    print(f"accuracy={accuracy}")
    print(f"f1_score={f1}")
    print(f"precision={precision}")
    print(f"recall={recall}")

    model_path = output_dir / "model.pkl"
    joblib.dump(pipeline, model_path)
    print(f"Model saved to {model_path}")

    metadata = {
        "model_name": model_name,
        "data_type": "IMAGE",
        "input_format": "pixels list of 784 values or pixel_0..pixel_783 columns",
        "classes": sorted(pd.Series(y).unique().astype(int).tolist()),
        "metrics": {
            "accuracy": float(accuracy),
            "f1_score": float(f1),
            "precision": float(precision),
            "recall": float(recall),
        },
    }

    (output_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    sample_payload = {
        "model_name": model_name,
        "model_alias": "production",
        "rows": [
            {
                "pixels": (X_test[0] / 255.0).astype(float).round(6).tolist()
            }
        ],
    }

    (output_dir / "sample_predict_payload.json").write_text(
        json.dumps(sample_payload, indent=2),
        encoding="utf-8",
    )

    try:
        run_id, version = register_pyfunc_model(model_name, pipeline, X_test)
        print("MLflow registration completed successfully.")
        print(f"run_id={run_id}")
        print(f"version={version}")

    except Exception as exc:
        print(f"MLflow registration failed: {repr(exc)}")
        raise

    print("Dockerized IMAGE model training completed successfully.")


if __name__ == "__main__":
    main()
