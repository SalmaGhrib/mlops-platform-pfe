import os
from typing import Any

import cloudpickle
import joblib
import mlflow.pyfunc
import numpy as np
import pandas as pd


class GenericPyFuncWrapper(mlflow.pyfunc.PythonModel):
    """
    Wrapper MLflow robuste.

    Objectif :
    - charger exactement le modèle sauvegardé dans model_path
    - supporter TABULAR / TEXT / IMAGE
    - ne jamais recréer un TfidfVectorizer
    - passer le bon format au modèle NLP sklearn :
      TfidfVectorizer attend une liste/Series de textes, pas un DataFrame arbitraire.
    """

    def load_context(self, context):
        model_path = context.artifacts["model_path"]

        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Model artifact not found: {model_path}")

        self.model_path = model_path
        self.model = self._load_model(model_path)

        if self.model is None:
            raise RuntimeError(f"Could not load model from artifact: {model_path}")

        print(f"[GENERIC_PYFUNC] model loaded from: {model_path}")
        print(f"[GENERIC_PYFUNC] model type: {type(self.model)}")

    def _load_model(self, model_path: str) -> Any:
        """
        Important :
        - joblib d'abord pour les modèles sklearn sauvegardés avec joblib.dump()
        - cloudpickle seulement en fallback
        """
        load_errors = []

        try:
            return joblib.load(model_path)
        except Exception as exc:
            load_errors.append(f"joblib.load failed: {exc}")

        try:
            with open(model_path, "rb") as file:
                return cloudpickle.load(file)
        except Exception as exc:
            load_errors.append(f"cloudpickle.load failed: {exc}")

        raise RuntimeError(" | ".join(load_errors))

    def _is_list_like(self, value: Any) -> bool:
        return (
            isinstance(value, (list, tuple, np.ndarray))
            and not isinstance(value, (str, bytes))
        )

    def _looks_like_text_model(self) -> bool:
        """
        Détecte les pipelines NLP sklearn :
        Pipeline([('tfidf', TfidfVectorizer), ...])
        """
        try:
            if hasattr(self.model, "steps"):
                for step_name, step_obj in self.model.steps:
                    class_name = step_obj.__class__.__name__.lower()
                    if "tfidf" in class_name or "countvectorizer" in class_name:
                        return True
                    if "vectorizer" in step_name.lower() or "tfidf" in step_name.lower():
                        return True
        except Exception:
            pass

        return False

    def _prepare_text_input(self, model_input):
        """
        Convertit l'entrée vers une liste de textes.
        """
        if isinstance(model_input, pd.DataFrame):
            if "text" in model_input.columns:
                return model_input["text"].astype(str).tolist()

            if model_input.shape[1] == 1:
                return model_input.iloc[:, 0].astype(str).tolist()

            raise ValueError(
                "TEXT model received a DataFrame with multiple columns and no 'text' column."
            )

        if isinstance(model_input, pd.Series):
            return model_input.astype(str).tolist()

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
                        raise ValueError(
                            "TEXT model received dict rows without a 'text' field."
                        )
                return texts

        if isinstance(model_input, np.ndarray):
            if model_input.ndim == 1:
                return [str(item) for item in model_input.tolist()]

        if isinstance(model_input, str):
            return [model_input]

        return model_input

    def _prepare_tabular_or_image_input(self, model_input):
        if isinstance(model_input, pd.DataFrame):
            if model_input.shape[1] == 1:
                first_col = model_input.iloc[:, 0]

                if first_col.apply(self._is_list_like).all():
                    try:
                        return np.stack([np.asarray(item) for item in first_col.tolist()])
                    except Exception:
                        return first_col.values

                return model_input

            return model_input

        if isinstance(model_input, list):
            if model_input and all(self._is_list_like(item) for item in model_input):
                try:
                    return np.asarray(model_input, dtype=float)
                except Exception:
                    return np.asarray(model_input)

            if model_input and all(isinstance(item, dict) for item in model_input):
                return pd.DataFrame(model_input)

            return model_input

        if isinstance(model_input, np.ndarray):
            return model_input

        if isinstance(model_input, dict):
            return pd.DataFrame([model_input])

        return model_input

    def _prepare_input(self, model_input):
        if self._looks_like_text_model():
            return self._prepare_text_input(model_input)

        return self._prepare_tabular_or_image_input(model_input)

    def predict(self, context, model_input):
        X = self._prepare_input(model_input)

        print(f"[GENERIC_PYFUNC] prediction input type after prepare: {type(X)}")

        if hasattr(self.model, "predict"):
            return self.model.predict(X)

        if callable(self.model):
            return self.model(X)

        raise ValueError("Loaded model must have predict() or be callable.")