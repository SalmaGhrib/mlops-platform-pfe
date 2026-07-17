from __future__ import annotations

import os
from typing import Any, Dict, List, Optional, Sequence

import cloudpickle
import joblib
import mlflow.pyfunc
import numpy as np
import pandas as pd


class GenericPyFuncWrapper(mlflow.pyfunc.PythonModel):
    """
    Wrapper MLflow générique pour les modèles utilisateur.

    Principes :
    1. Un pipeline autonome est utilisé directement.
       Exemple : Pipeline(TfidfVectorizer, LogisticRegression).
    2. Les artefacts séparés sont appliqués uniquement si le modèle principal
       n'embarque pas déjà son propre prétraitement.
    3. Les entrées NLP sont toujours converties en liste 1D de chaînes.
    4. Les entrées tabulaires conservent l'ordre des colonnes d'entraînement.
    5. La sortie est convertie dans un format sérialisable par MLflow/FastAPI.
    """

    TEXT_COLUMN_CANDIDATES: Sequence[str] = (
        "text",
        "texte",
        "message",
        "content",
        "sentence",
        "review",
        "description",
        "input",
    )

    TARGET_COLUMN_CANDIDATES: Sequence[str] = (
        "target",
        "label",
        "class",
        "y",
        "price",
        "output",
        "result",
    )

    TEXT_TRANSFORMER_TOKENS: Sequence[str] = (
        "tfidf",
        "countvectorizer",
        "vectorizer",
        "tokenizer",
        "hashingvectorizer",
    )

    def load_context(self, context) -> None:
        artifacts = getattr(context, "artifacts", {}) or {}

        self.model_path = artifacts.get("model_path")
        self.scaler_path = artifacts.get("scaler_path")
        self.preprocessor_path = artifacts.get("preprocessor_path")
        self.vectorizer_path = artifacts.get("vectorizer_path")
        self.encoder_path = artifacts.get("encoder_path")

        self.model = self._load_object(self.model_path, required=True)
        self.scaler = self._load_object(self.scaler_path)
        self.preprocessor = self._load_object(self.preprocessor_path)
        self.vectorizer = self._load_object(self.vectorizer_path)
        self.encoder = self._load_object(self.encoder_path)

        self.model_is_pipeline = self._is_pipeline(self.model)
        self.model_contains_text_transformer = self._contains_text_transformer(
            self.model
        )
        self.model_contains_preprocessing = self._contains_preprocessing(
            self.model
        )
        self.text_model = (
            self.model_contains_text_transformer
            or self._contains_text_transformer(self.vectorizer)
            or self._contains_text_transformer(self.preprocessor)
        )

        print(f"[GENERIC_PYFUNC] model_path={self.model_path}")
        print(f"[GENERIC_PYFUNC] model_type={type(self.model).__name__}")
        print(f"[GENERIC_PYFUNC] model_is_pipeline={self.model_is_pipeline}")
        print(
            "[GENERIC_PYFUNC] model_contains_text_transformer="
            f"{self.model_contains_text_transformer}"
        )
        print(
            "[GENERIC_PYFUNC] model_contains_preprocessing="
            f"{self.model_contains_preprocessing}"
        )
        print(f"[GENERIC_PYFUNC] text_model={self.text_model}")

        self._log_optional_artifact("preprocessor", self.preprocessor_path, self.preprocessor)
        self._log_optional_artifact("vectorizer", self.vectorizer_path, self.vectorizer)
        self._log_optional_artifact("scaler", self.scaler_path, self.scaler)
        self._log_optional_artifact("encoder", self.encoder_path, self.encoder)

    def _log_optional_artifact(
        self,
        name: str,
        path: Optional[str],
        value: Any,
    ) -> None:
        if value is not None:
            print(
                f"[GENERIC_PYFUNC] {name}_path={path}, "
                f"type={type(value).__name__}"
            )

    def _load_object(
        self,
        path: Optional[str],
        required: bool = False,
    ) -> Any:
        if not path:
            if required:
                raise FileNotFoundError(
                    "Required model artifact path 'model_path' is missing."
                )
            return None

        if not os.path.isfile(path):
            if required:
                raise FileNotFoundError(f"Artifact not found: {path}")
            print(f"[GENERIC_PYFUNC] optional artifact not found: {path}")
            return None

        errors: List[str] = []

        try:
            return joblib.load(path)
        except Exception as exc:
            errors.append(f"joblib.load: {type(exc).__name__}: {exc}")

        try:
            with open(path, "rb") as file:
                return cloudpickle.load(file)
        except Exception as exc:
            errors.append(f"cloudpickle.load: {type(exc).__name__}: {exc}")

        message = f"Could not load artifact {path}. " + " | ".join(errors)

        if required:
            raise RuntimeError(message)

        print(f"[GENERIC_PYFUNC] {message}")
        return None

    @staticmethod
    def _is_pipeline(obj: Any) -> bool:
        return obj is not None and hasattr(obj, "steps") and hasattr(obj, "predict")

    def _iter_nested_estimators(self, obj: Any):
        if obj is None:
            return

        yield obj

        steps = getattr(obj, "steps", None)
        if steps:
            for _, step in steps:
                yield from self._iter_nested_estimators(step)

        transformers = getattr(obj, "transformers", None)
        if transformers:
            for _, transformer, _ in transformers:
                if transformer not in {"drop", "passthrough"}:
                    yield from self._iter_nested_estimators(transformer)

        transformer_list = getattr(obj, "transformer_list", None)
        if transformer_list:
            for _, transformer in transformer_list:
                yield from self._iter_nested_estimators(transformer)

    def _contains_text_transformer(self, obj: Any) -> bool:
        for estimator in self._iter_nested_estimators(obj):
            class_name = estimator.__class__.__name__.lower()

            if any(token in class_name for token in self.TEXT_TRANSFORMER_TOKENS):
                return True

            try:
                params = estimator.get_params(deep=False)
            except Exception:
                params = {}

            analyzer = str(params.get("analyzer", "")).lower()
            if analyzer in {"word", "char", "char_wb"}:
                return True

        return False

    def _contains_preprocessing(self, obj: Any) -> bool:
        if obj is None:
            return False

        if self._is_pipeline(obj):
            steps = list(getattr(obj, "steps", []))
            return len(steps) > 1

        class_name = obj.__class__.__name__.lower()
        return any(
            token in class_name
            for token in (
                "columntransformer",
                "featureunion",
                "vectorizer",
                "scaler",
                "encoder",
                "preprocessor",
            )
        )

    @staticmethod
    def _is_list_like(value: Any) -> bool:
        return isinstance(value, (list, tuple, np.ndarray)) and not isinstance(
            value, (str, bytes)
        )

    def _find_text_column(self, dataframe: pd.DataFrame) -> Optional[str]:
        normalized = {
            str(column).strip().lower(): str(column)
            for column in dataframe.columns
        }

        for candidate in self.TEXT_COLUMN_CANDIDATES:
            if candidate in normalized:
                return normalized[candidate]

        object_columns = [
            column
            for column in dataframe.columns
            if (
                pd.api.types.is_object_dtype(dataframe[column])
                or pd.api.types.is_string_dtype(dataframe[column])
            )
        ]

        if len(object_columns) == 1:
            return str(object_columns[0])

        if dataframe.shape[1] == 1:
            return str(dataframe.columns[0])

        return None

    def _prepare_text_input(self, model_input: Any) -> List[str]:
        if isinstance(model_input, pd.DataFrame):
            text_column = self._find_text_column(model_input)

            if text_column is None:
                raise ValueError(
                    "NLP model expects one text column. "
                    f"Received columns: {list(model_input.columns)}"
                )

            values = model_input[text_column].tolist()
            texts = [self._normalize_text(value) for value in values]

        elif isinstance(model_input, pd.Series):
            texts = [self._normalize_text(value) for value in model_input.tolist()]

        elif isinstance(model_input, str):
            texts = [self._normalize_text(model_input)]

        elif isinstance(model_input, dict):
            selected = None

            for candidate in self.TEXT_COLUMN_CANDIDATES:
                if candidate in model_input:
                    selected = model_input[candidate]
                    break

            if selected is None and len(model_input) == 1:
                selected = next(iter(model_input.values()))

            if selected is None:
                raise ValueError(
                    "NLP dictionary input must contain a text field. "
                    f"Accepted fields: {list(self.TEXT_COLUMN_CANDIDATES)}"
                )

            if self._is_list_like(selected):
                texts = [self._normalize_text(value) for value in selected]
            else:
                texts = [self._normalize_text(selected)]

        elif isinstance(model_input, (list, tuple)):
            if not model_input:
                texts = []
            elif all(isinstance(item, dict) for item in model_input):
                texts = []

                for row in model_input:
                    value = None

                    for candidate in self.TEXT_COLUMN_CANDIDATES:
                        if candidate in row:
                            value = row[candidate]
                            break

                    if value is None and len(row) == 1:
                        value = next(iter(row.values()))

                    if value is None:
                        raise ValueError(
                            "Each NLP row must contain one text field."
                        )

                    texts.append(self._normalize_text(value))
            else:
                texts = [self._normalize_text(value) for value in model_input]

        elif isinstance(model_input, np.ndarray):
            flattened = model_input.reshape(-1).tolist()
            texts = [self._normalize_text(value) for value in flattened]

        else:
            texts = [self._normalize_text(model_input)]

        if not texts:
            raise ValueError("NLP prediction received no text.")

        if any(not text.strip() for text in texts):
            raise ValueError("NLP prediction received an empty text.")

        print(
            "[GENERIC_PYFUNC] normalized_text_count="
            f"{len(texts)}, first_text={texts[0][:200]!r}"
        )

        return texts

    @staticmethod
    def _normalize_text(value: Any) -> str:
        if value is None:
            return ""

        if isinstance(value, float) and np.isnan(value):
            return ""

        return str(value).strip()

    def _prepare_tabular_or_image_input(self, model_input: Any) -> Any:
        if isinstance(model_input, pd.DataFrame):
            return model_input.copy()

        if isinstance(model_input, pd.Series):
            return model_input.to_frame().T

        if isinstance(model_input, dict):
            return pd.DataFrame([model_input])

        if isinstance(model_input, (list, tuple)):
            if model_input and all(isinstance(item, dict) for item in model_input):
                return pd.DataFrame(model_input)

            if model_input and all(self._is_list_like(item) for item in model_input):
                try:
                    return np.asarray(model_input, dtype=float)
                except (TypeError, ValueError):
                    return np.asarray(model_input)

            return list(model_input)

        if isinstance(model_input, np.ndarray):
            return model_input

        return model_input

    def _prepare_input(self, model_input: Any) -> Any:
        if self.text_model:
            return self._prepare_text_input(model_input)

        return self._prepare_tabular_or_image_input(model_input)

    def _expected_feature_names(self) -> Optional[List[str]]:
        for obj in (self.preprocessor, self.scaler, self.model):
            if obj is None:
                continue

            names = getattr(obj, "feature_names_in_", None)

            if names is not None:
                try:
                    return [str(name) for name in list(names)]
                except Exception:
                    continue

        return None

    def _expected_feature_count(self) -> Optional[int]:
        for obj in (self.preprocessor, self.scaler, self.model):
            if obj is None:
                continue

            count = getattr(obj, "n_features_in_", None)

            if count is not None:
                try:
                    return int(count)
                except (TypeError, ValueError):
                    continue

        return None

    def _align_dataframe_features(self, X: Any) -> Any:
        if not isinstance(X, pd.DataFrame):
            return X

        expected = self._expected_feature_names()

        if not expected:
            return X

        missing = [column for column in expected if column not in X.columns]

        if missing:
            raise ValueError(
                "Missing required feature columns: "
                + ", ".join(missing[:20])
            )

        extra = [column for column in X.columns if column not in expected]

        if extra:
            print(
                "[GENERIC_PYFUNC] ignoring extra columns: "
                + ", ".join(extra[:20])
            )

        return X.loc[:, expected]

    def _align_dataframe_by_feature_count(self, X: Any) -> Any:
        if not isinstance(X, pd.DataFrame):
            return X

        expected_count = self._expected_feature_count()

        if expected_count is None:
            return X

        candidate = X.copy()

        for column in self.TARGET_COLUMN_CANDIDATES:
            if column in candidate.columns and candidate.shape[1] > expected_count:
                candidate = candidate.drop(columns=[column])

        if candidate.shape[1] == expected_count:
            return candidate

        numeric_candidate = candidate.select_dtypes(include=["number", "bool"])

        if numeric_candidate.shape[1] == expected_count:
            print(
                "[GENERIC_PYFUNC] using numeric columns selected by feature count."
            )
            return numeric_candidate

        if numeric_candidate.shape[1] > expected_count:
            print(
                "[GENERIC_PYFUNC] selecting the first "
                f"{expected_count} numeric columns."
            )
            return numeric_candidate.iloc[:, :expected_count]

        return X

    @staticmethod
    def _apply_transformer(transformer: Any, X: Any, name: str) -> Any:
        if transformer is None:
            return X

        if hasattr(transformer, "transform"):
            print(f"[GENERIC_PYFUNC] applying {name}.transform()")
            return transformer.transform(X)

        if callable(transformer):
            print(f"[GENERIC_PYFUNC] applying callable {name}")
            return transformer(X)

        raise TypeError(
            f"Artifact '{name}' has neither transform() nor __call__()."
        )

    def _predict_with_self_contained_model(self, X: Any) -> Any:
        """
        Priorité absolue au modèle autonome.

        Un Pipeline sklearn contenant TF-IDF + classifieur doit recevoir
        directement la liste de textes. Aucun vectorizer séparé ne doit être
        appliqué avant model.predict(), sinon le texte serait vectorisé deux fois.
        """
        if hasattr(self.model, "predict"):
            return self.model.predict(X)

        if callable(self.model):
            return self.model(X)

        raise TypeError("Loaded model must expose predict() or be callable.")

    def _predict_with_external_transformers(self, X: Any) -> Any:
        X_work = X

        X_work = self._align_dataframe_features(X_work)
        X_work = self._align_dataframe_by_feature_count(X_work)

        X_work = self._apply_transformer(
            self.preprocessor,
            X_work,
            "preprocessor",
        )
        X_work = self._apply_transformer(
            self.vectorizer,
            X_work,
            "vectorizer",
        )
        X_work = self._apply_transformer(
            self.scaler,
            X_work,
            "scaler",
        )

        if hasattr(self.model, "predict"):
            return self.model.predict(X_work)

        if callable(self.model):
            return self.model(X_work)

        raise TypeError("Loaded model must expose predict() or be callable.")

    def _decode_predictions(self, predictions: Any) -> Any:
        if self.encoder is None or not hasattr(self.encoder, "inverse_transform"):
            return predictions

        try:
            return self.encoder.inverse_transform(predictions)
        except Exception as exc:
            print(
                "[GENERIC_PYFUNC] encoder inverse_transform skipped: "
                f"{type(exc).__name__}: {exc}"
            )
            return predictions

    @staticmethod
    def _serialize_predictions(predictions: Any) -> Any:
        if isinstance(predictions, pd.DataFrame):
            return predictions

        if isinstance(predictions, pd.Series):
            return predictions.tolist()

        if isinstance(predictions, np.ndarray):
            return predictions.tolist()

        if isinstance(predictions, np.generic):
            return predictions.item()

        if isinstance(predictions, tuple):
            return list(predictions)

        return predictions

    def predict(
        self,
        context,
        model_input,
        params: Optional[Dict[str, Any]] = None,
    ):
        del context, params

        X = self._prepare_input(model_input)

        print(
            "[GENERIC_PYFUNC] prepared_input_type="
            f"{type(X).__name__}"
        )

        # Pipeline autonome : ne jamais appliquer de transformateurs externes.
        if self.model_contains_preprocessing:
            print(
                "[GENERIC_PYFUNC] using self-contained model directly; "
                "external transformers are skipped."
            )
            predictions = self._predict_with_self_contained_model(X)
        else:
            predictions = self._predict_with_external_transformers(X)

        predictions = self._decode_predictions(predictions)
        predictions = self._serialize_predictions(predictions)

        print(
            "[GENERIC_PYFUNC] prediction_type="
            f"{type(predictions).__name__}, prediction={predictions!r}"
        )

        return predictions