import os
from typing import Any, Dict, Optional

import cloudpickle
import joblib
import mlflow.pyfunc
import numpy as np
import pandas as pd


class GenericPyFuncWrapper(mlflow.pyfunc.PythonModel):
    """
    Wrapper MLflow intelligent pour les scripts utilisateurs sans code MLflow.

    Il supporte :
    - un modèle sklearn/joblib/pickle simple ;
    - un modèle + scaler séparé : artifacts/model.joblib + artifacts/scaler.joblib ;
    - un preprocessor/vectorizer/encoder séparé si présent ;
    - les entrées TABULAR sous forme dict, list[dict], DataFrame ;
    - les modèles NLP sklearn avec colonne text ;
    - les modèles callable ou avec predict().
    """

    def load_context(self, context):
        self.model_path = context.artifacts["model_path"]
        self.scaler_path = context.artifacts.get("scaler_path")
        self.preprocessor_path = context.artifacts.get("preprocessor_path")
        self.vectorizer_path = context.artifacts.get("vectorizer_path")
        self.encoder_path = context.artifacts.get("encoder_path")

        self.model = self._load_object(self.model_path, required=True)
        self.scaler = self._load_object(self.scaler_path, required=False)
        self.preprocessor = self._load_object(self.preprocessor_path, required=False)
        self.vectorizer = self._load_object(self.vectorizer_path, required=False)
        self.encoder = self._load_object(self.encoder_path, required=False)

        print(f"[GENERIC_PYFUNC] model loaded from: {self.model_path}")
        print(f"[GENERIC_PYFUNC] model type: {type(self.model)}")
        if self.scaler is not None:
            print(f"[GENERIC_PYFUNC] scaler loaded from: {self.scaler_path}")
        if self.preprocessor is not None:
            print(f"[GENERIC_PYFUNC] preprocessor loaded from: {self.preprocessor_path}")
        if self.vectorizer is not None:
            print(f"[GENERIC_PYFUNC] vectorizer loaded from: {self.vectorizer_path}")
        if self.encoder is not None:
            print(f"[GENERIC_PYFUNC] encoder loaded from: {self.encoder_path}")

    def _load_object(self, path: Optional[str], required: bool = False) -> Any:
        if not path:
            if required:
                raise FileNotFoundError("Required artifact path is missing.")
            return None

        if not os.path.exists(path):
            if required:
                raise FileNotFoundError(f"Artifact not found: {path}")
            return None

        errors = []

        try:
            return joblib.load(path)
        except Exception as exc:
            errors.append(f"joblib.load failed: {exc}")

        try:
            with open(path, "rb") as file:
                return cloudpickle.load(file)
        except Exception as exc:
            errors.append(f"cloudpickle.load failed: {exc}")

        if required:
            raise RuntimeError(" | ".join(errors))

        print(f"[GENERIC_PYFUNC] optional artifact could not be loaded: {path} | {errors}")
        return None

    def _is_list_like(self, value: Any) -> bool:
        return isinstance(value, (list, tuple, np.ndarray)) and not isinstance(value, (str, bytes))

    def _looks_like_text_model(self) -> bool:
        candidates = [self.model, self.vectorizer, self.preprocessor]

        for obj in candidates:
            if obj is None:
                continue

            try:
                if hasattr(obj, "steps"):
                    for step_name, step_obj in obj.steps:
                        class_name = step_obj.__class__.__name__.lower()
                        if "tfidf" in class_name or "countvectorizer" in class_name:
                            return True
                        if "vectorizer" in step_name.lower() or "tfidf" in step_name.lower():
                            return True

                class_name = obj.__class__.__name__.lower()
                if "tfidf" in class_name or "countvectorizer" in class_name:
                    return True
            except Exception:
                pass

        return False

    def _prepare_text_input(self, model_input):
        if isinstance(model_input, pd.DataFrame):
            if "text" in model_input.columns:
                return model_input["text"].astype(str).tolist()
            if model_input.shape[1] == 1:
                return model_input.iloc[:, 0].astype(str).tolist()
            raise ValueError("TEXT model received a DataFrame with multiple columns and no 'text' column.")

        if isinstance(model_input, pd.Series):
            return model_input.astype(str).tolist()

        if isinstance(model_input, str):
            return [model_input]

        if isinstance(model_input, list):
            if len(model_input) == 0:
                return []
            if all(isinstance(item, str) for item in model_input):
                return [str(item) for item in model_input]
            if all(isinstance(item, dict) for item in model_input):
                texts = []
                for item in model_input:
                    if "text" in item:
                        texts.append(str(item["text"]))
                    elif len(item) == 1:
                        texts.append(str(next(iter(item.values()))))
                    else:
                        raise ValueError("TEXT model received dict rows without a 'text' field.")
                return texts

        if isinstance(model_input, np.ndarray) and model_input.ndim == 1:
            return [str(item) for item in model_input.tolist()]

        return model_input

    def _prepare_tabular_or_image_input(self, model_input):
        if isinstance(model_input, pd.DataFrame):
            return model_input

        if isinstance(model_input, dict):
            return pd.DataFrame([model_input])

        if isinstance(model_input, list):
            if model_input and all(isinstance(item, dict) for item in model_input):
                return pd.DataFrame(model_input)
            if model_input and all(self._is_list_like(item) for item in model_input):
                try:
                    return np.asarray(model_input, dtype=float)
                except Exception:
                    return np.asarray(model_input)
            return model_input

        if isinstance(model_input, np.ndarray):
            return model_input

        return model_input

    def _prepare_input(self, model_input):
        if self._looks_like_text_model():
            return self._prepare_text_input(model_input)
        return self._prepare_tabular_or_image_input(model_input)

    def _expected_feature_names(self):
        """
        Récupère l'ordre des colonnes attendu par le scaler/preprocessor/modèle.
        Très utile pour les scripts utilisateurs qui sauvegardent un scaler séparé.
        """
        for obj in [self.preprocessor, self.scaler, self.model]:
            if obj is None:
                continue

            names = getattr(obj, "feature_names_in_", None)
            if names is not None:
                try:
                    return [str(name) for name in list(names)]
                except Exception:
                    pass

        return None

    def _align_dataframe_features(self, X):
        """
        Aligne automatiquement un DataFrame sur les colonnes attendues.
        - garde l'ordre d'entraînement ;
        - ignore les colonnes en trop ;
        - signale clairement les colonnes manquantes.
        """
        if not isinstance(X, pd.DataFrame):
            return X

        expected = self._expected_feature_names()

        if not expected:
            return X

        missing = [column for column in expected if column not in X.columns]

        if missing:
            raise ValueError(
                "Missing required feature columns for prediction: "
                + ", ".join(missing[:20])
            )

        extra = [column for column in X.columns if column not in expected]
        if extra:
            print(
                "[GENERIC_PYFUNC] ignoring extra columns not used during training: "
                + ", ".join(extra[:20])
            )

        return X[expected]

    def _apply_transformer(self, transformer, X, name: str):
        if transformer is None:
            return X

        if hasattr(transformer, "transform"):
            print(f"[GENERIC_PYFUNC] applying {name}.transform()")
            return transformer.transform(X)

        if callable(transformer):
            print(f"[GENERIC_PYFUNC] applying callable {name}")
            return transformer(X)

        return X

    def predict(self, context, model_input):
        X = self._prepare_input(model_input)

        print(f"[GENERIC_PYFUNC] prediction input type after prepare: {type(X)}")

        # Important :
        # si un scaler/preprocessor a été entraîné sur un DataFrame,
        # il garde feature_names_in_. On réordonne donc les colonnes avant transform().
        X = self._align_dataframe_features(X)

        # Ordre logique :
        # preprocessor -> vectorizer/scaler -> model
        X = self._apply_transformer(self.preprocessor, X, "preprocessor")
        X = self._apply_transformer(self.vectorizer, X, "vectorizer")
        X = self._apply_transformer(self.scaler, X, "scaler")

        if hasattr(self.model, "predict"):
            predictions = self.model.predict(X)
        elif callable(self.model):
            predictions = self.model(X)
        else:
            raise ValueError("Loaded model must have predict() or be callable.")

        # Si un label encoder est fourni, on peut décoder automatiquement.
        try:
            if self.encoder is not None and hasattr(self.encoder, "inverse_transform"):
                predictions = self.encoder.inverse_transform(predictions)
        except Exception as exc:
            print(f"[GENERIC_PYFUNC] encoder inverse_transform skipped: {exc}")

        return predictions
