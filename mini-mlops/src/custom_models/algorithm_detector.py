"""
algorithm_detector.py
─────────────────────
Analyse un fichier Python utilisateur et détecte les vrais algorithmes ML
sans modifier le code utilisateur.

Objectifs :
- stabiliser l'affichage Angular ;
- éviter les doublons ;
- détecter les modèles dans Assign, AnnAssign, Pipeline, make_pipeline,
  GridSearchCV et RandomizedSearchCV ;
- détecter les modules supportés / non supportés par le runtime standard ;
- détecter le type de données probable : TABULAR, TEXT ou IMAGE ;
- retourner une structure stable pour le frontend et MLflow.

Important :
- toute logique de remplacement automatique a été supprimée ;
- le détecteur ne propose plus de replacement_options ;
- le champ replaceable reste toujours False pour compatibilité frontend,
  mais aucun remplacement n'est proposé ni exécuté.
"""

from __future__ import annotations

import ast
import json
from pathlib import Path
from typing import Any, Optional, Tuple


from src.custom_models.import_detector import (
    STANDARD_LIBRARY_MODULES,
    normalize_module_name,
)


def _is_standard_library_module(module_name: str) -> bool:
    root = str(module_name or "").strip().split(".")[0]
    return bool(root) and root in STANDARD_LIBRARY_MODULES


SUPPORTED_LIBRARIES: dict[str, str] = {
    "sklearn": "Scikit-learn",
    "xgboost": "XGBoost",
    "lightgbm": "LightGBM",
}

KNOWN_EXTERNAL_LIBRARIES: dict[str, str] = {
    "imblearn": "imbalanced-learn",
    "catboost": "catboost",
    "tensorflow": "tensorflow",
    "torch": "torch",
    "pytorch": "PyTorch",
    "transformers": "Transformers",
    "keras": "Keras",
    "statsmodels": "Statsmodels",
}


ALGORITHM_REGISTRY: dict[str, dict[str, str]] = {
    # Classification - sklearn
    "RandomForestClassifier": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Random Forest Classifier",
        "type": "classifier",
    },
    "GradientBoostingClassifier": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Gradient Boosting Classifier",
        "type": "classifier",
    },
    "ExtraTreesClassifier": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Extra Trees Classifier",
        "type": "classifier",
    },
    "AdaBoostClassifier": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "AdaBoost Classifier",
        "type": "classifier",
    },
    "DecisionTreeClassifier": {
        "lib": "sklearn",
        "module": "sklearn.tree",
        "display": "Decision Tree Classifier",
        "type": "classifier",
    },
    "KNeighborsClassifier": {
        "lib": "sklearn",
        "module": "sklearn.neighbors",
        "display": "K-Nearest Neighbors Classifier",
        "type": "classifier",
    },
    "SVC": {
        "lib": "sklearn",
        "module": "sklearn.svm",
        "display": "SVM / SVC",
        "type": "classifier",
    },
    "LogisticRegression": {
        "lib": "sklearn",
        "module": "sklearn.linear_model",
        "display": "Logistic Regression",
        "type": "classifier",
    },
    "GaussianNB": {
        "lib": "sklearn",
        "module": "sklearn.naive_bayes",
        "display": "Gaussian Naive Bayes",
        "type": "classifier",
    },

    # Regression - sklearn
    "RandomForestRegressor": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Random Forest Regressor",
        "type": "regressor",
    },
    "GradientBoostingRegressor": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Gradient Boosting Regressor",
        "type": "regressor",
    },
    "ExtraTreesRegressor": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "Extra Trees Regressor",
        "type": "regressor",
    },
    "AdaBoostRegressor": {
        "lib": "sklearn",
        "module": "sklearn.ensemble",
        "display": "AdaBoost Regressor",
        "type": "regressor",
    },
    "DecisionTreeRegressor": {
        "lib": "sklearn",
        "module": "sklearn.tree",
        "display": "Decision Tree Regressor",
        "type": "regressor",
    },
    "KNeighborsRegressor": {
        "lib": "sklearn",
        "module": "sklearn.neighbors",
        "display": "K-Nearest Neighbors Regressor",
        "type": "regressor",
    },
    "SVR": {
        "lib": "sklearn",
        "module": "sklearn.svm",
        "display": "SVM / SVR",
        "type": "regressor",
    },
    "LinearRegression": {
        "lib": "sklearn",
        "module": "sklearn.linear_model",
        "display": "Linear Regression",
        "type": "regressor",
    },
    "Ridge": {
        "lib": "sklearn",
        "module": "sklearn.linear_model",
        "display": "Ridge Regression",
        "type": "regressor",
    },
    "Lasso": {
        "lib": "sklearn",
        "module": "sklearn.linear_model",
        "display": "Lasso Regression",
        "type": "regressor",
    },
    "ElasticNet": {
        "lib": "sklearn",
        "module": "sklearn.linear_model",
        "display": "ElasticNet Regression",
        "type": "regressor",
    },

    # XGBoost
    "XGBClassifier": {
        "lib": "xgboost",
        "module": "xgboost",
        "display": "XGBoost Classifier",
        "type": "classifier",
    },
    "XGBRegressor": {
        "lib": "xgboost",
        "module": "xgboost",
        "display": "XGBoost Regressor",
        "type": "regressor",
    },

    # LightGBM
    "LGBMClassifier": {
        "lib": "lightgbm",
        "module": "lightgbm",
        "display": "LightGBM Classifier",
        "type": "classifier",
    },
    "LGBMRegressor": {
        "lib": "lightgbm",
        "module": "lightgbm",
        "display": "LightGBM Regressor",
        "type": "regressor",
    },
}


TECHNICAL_OBJECTS = {
    "Pipeline",
    "make_pipeline",
    "ColumnTransformer",
    "FeatureUnion",
    "OneHotEncoder",
    "StandardScaler",
    "MinMaxScaler",
    "RobustScaler",
    "SimpleImputer",
    "LabelEncoder",
    "OrdinalEncoder",
    "train_test_split",
    "GridSearchCV",
    "RandomizedSearchCV",
    "cross_val_score",
    "accuracy_score",
    "precision_score",
    "recall_score",
    "f1_score",
    "mean_squared_error",
    "mean_absolute_error",
    "r2_score",
    "classification_report",
    "confusion_matrix",
    "DataFrame",
    "Series",
}


TEXT_FEATURE_EXTRACTORS = {
    "TfidfVectorizer",
    "CountVectorizer",
    "HashingVectorizer",
}

IMAGE_HINT_MODULES = {
    "cv2",
    "PIL",
    "PIL.Image",
    "torchvision",
    "torchvision.datasets",
    "torchvision.transforms",
    "tensorflow.keras.preprocessing.image",
}


SUPPORTED_RUNTIME_MODULES: set[str] = {
    "numpy",
    "pandas",
    "sklearn",
    "scikit-learn",
    "scipy",
    "matplotlib",
    "seaborn",
    "mlflow",
    "joblib",
    "cloudpickle",
    "yaml",
    "pyyaml",
    "requests",
    "xgboost",
    "lightgbm",
    "torch",
    "torch.nn",
    "torch.optim",
    "torchvision",
    "torchvision.datasets",
    "torchvision.transforms",
    "opencv-python",
    "pillow",
}

PYTORCH_LAYER_CLASSES = {
    "Linear",
    "Conv1d",
    "Conv2d",
    "Conv3d",
    "BatchNorm1d",
    "BatchNorm2d",
    "BatchNorm3d",
    "Dropout",
    "Dropout2d",
    "MaxPool2d",
    "AvgPool2d",
    "AdaptiveAvgPool2d",
    "Sequential",
    "ReLU",
    "Softmax",
    "Flatten",
}

PYTORCH_LOSS_CLASSES = {
    "CrossEntropyLoss",
    "MSELoss",
    "NLLLoss",
    "BCELoss",
    "BCEWithLogitsLoss",
    "L1Loss",
    "SmoothL1Loss",
}

PYTORCH_OPTIMIZERS = {
    "SGD",
    "Adam",
    "AdamW",
    "RMSprop",
    "Adagrad",
    "Adadelta",
    "Adamax",
}


def detect_algorithms_from_file(file_path: str) -> dict[str, Any]:
    path = Path(file_path)

    if not path.exists():
        return {
            "status": "error",
            "message": f"Fichier introuvable: {file_path}",
            "detected": [],
            "detected_algorithms": [],
            "supported_modules": [],
            "unsupported_modules": [],
            "warnings": [f"Fichier introuvable: {file_path}"],
            "summary": "Aucun fichier analysé.",
        }

    try:
        code = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        code = path.read_text(encoding="latin-1")

    return detect_algorithms(code)


def detect_algorithms(code: str) -> dict[str, Any]:
    try:
        tree = ast.parse(code)
    except SyntaxError as exc:
        return {
            "status": "error",
            "message": f"Erreur de syntaxe Python: {exc}",
            "detected": [],
            "detected_algorithms": [],
            "supported_modules": [],
            "unsupported_modules": [],
            "warnings": [f"Erreur de syntaxe Python: {exc}"],
            "summary": "Analyse impossible à cause d'une erreur de syntaxe.",
        }

    import_map, imported_modules = extract_imports(tree)

    detector = AlgorithmAstDetector(import_map=import_map)
    detector.visit(tree)

    detected = remove_duplicate_detections(detector.detected)
    detected = remove_duplicate_technical_objects(detected)

    module_analysis = build_detected_modules(imported_modules)
    unsupported_module_names = [item["name"] for item in module_analysis if not item["supported"]]
    supported_modules = build_supported_modules(detected)
    unsupported_modules = build_unsupported_modules(import_map)
    warnings = build_warnings(detected, unsupported_module_names)
    runtime_compatible = len(unsupported_module_names) == 0
    data_type, input_type = infer_data_type(code, imported_modules, detected)
    task_type = infer_task_type(detected)

    return {
        "status": "success",
        "runtimeCompatible": runtime_compatible,
        "recommendation": (
            "Runtime standard compatible"
            if runtime_compatible
            else "Utilisez le mode Dockerfile personnalisé ou ajoutez ce module à l’image standard."
        ),
        "detectedModules": module_analysis,
        "unsupportedModules": sorted(set(unsupported_module_names)),
        "detectedAlgorithms": detected,
        "warnings": warnings,
        "detected": detected,
        "detected_algorithms": detected,
        "model_occurrences": detected,
        "modelOccurrences": detected,
        "supported_modules": supported_modules,
        "supportedModules": supported_modules,
        "unsupported_modules": unsupported_modules,
        "unsupportedModulesLegacy": unsupported_modules,
        "data_type": data_type,
        "dataType": data_type,
        "input_type": input_type,
        "inputType": input_type,
        "task_type": task_type,
        "taskType": task_type,
        "algorithm_replacements": [],
        "algorithmReplacementCount": 0,
        "algorithm_replacement_count": 0,
        "has_algorithm_replacement": False,
        "hasAlgorithmReplacement": False,
        "summary": build_summary(detected, supported_modules, unsupported_modules),
    }


def extract_imports(
    tree: ast.AST,
) -> tuple[dict[str, dict[str, Any]], list[str]]:
    """
    Extract only third-party imports for runtime compatibility checks.

    Python standard-library modules such as json, os, pathlib and sys are
    always available in the runtime and must never be reported as missing
    dependencies.
    """
    imports: dict[str, dict[str, Any]] = {}
    imported_modules: set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            module = (node.module or "").strip()
            root = module.split(".")[0] if module else ""

            for alias in node.names:
                local_name = alias.asname or alias.name
                original_name = alias.name

                imports[local_name] = {
                    "local_name": local_name,
                    "original_name": original_name,
                    "module": module,
                    "root_library": root,
                    "full_name": (
                        f"{module}.{original_name}"
                        if module
                        else original_name
                    ),
                }

            if not module or _is_standard_library_module(module):
                continue

            imported_modules.add(module)
            if "." in module:
                imported_modules.add(root)

            for alias in node.names:
                if alias.name == "*":
                    continue

                imported_name = alias.name.strip()
                if not imported_name:
                    continue

                if module in {"torchvision", "torch.nn", "torch.optim"}:
                    imported_modules.add(f"{module}.{imported_name}")
                elif (
                    module.startswith("sklearn")
                    and imported_name[0].islower()
                ):
                    imported_modules.add(f"{module}.{imported_name}")

        elif isinstance(node, ast.Import):
            for alias in node.names:
                original_name = alias.name.strip()
                root = original_name.split(".")[0]
                local_name = alias.asname or root

                imports[local_name] = {
                    "local_name": local_name,
                    "original_name": original_name,
                    "module": original_name,
                    "root_library": root,
                    "full_name": original_name,
                }

                if _is_standard_library_module(original_name):
                    continue

                imported_modules.add(original_name)
                if "." in original_name:
                    imported_modules.add(root)

    return imports, sorted(imported_modules)


class AlgorithmAstDetector(ast.NodeVisitor):
    def __init__(self, import_map: dict[str, dict[str, Any]]):
        self.import_map = import_map
        self.detected: list[dict[str, Any]] = []
        self._assignment_stack: list[Optional[str]] = []
        self._pipeline_depth = 0
        self._search_cv_depth = 0

    @property
    def current_assignment(self) -> Optional[str]:
        for item in reversed(self._assignment_stack):
            if item:
                return item
        return None

    def visit_Assign(self, node: ast.Assign) -> Any:
        var_name = self._extract_target_name(node.targets[0]) if node.targets else None
        self._assignment_stack.append(var_name)
        self.visit(node.value)
        self._assignment_stack.pop()

    def visit_AnnAssign(self, node: ast.AnnAssign) -> Any:
        var_name = self._extract_target_name(node.target)
        self._assignment_stack.append(var_name)
        if node.value is not None:
            self.visit(node.value)
        self._assignment_stack.pop()

    def visit_ClassDef(self, node: ast.ClassDef) -> Any:
        if self._is_pytorch_model_class(node):
            self._detect_pytorch_model_class(node)
        self.generic_visit(node)

    def _is_pytorch_model_class(self, node: ast.ClassDef) -> bool:
        for base in node.bases:
            base_name = get_call_name(base) if isinstance(base, ast.Call) else get_attribute_parent_name(base)
            if not base_name:
                continue
            full_name = resolve_full_call_name(base_name, self.import_map)
            if full_name in {"torch.nn.Module", "nn.Module"} or full_name.endswith(".Module"):
                return True
        return False

    def _detect_pytorch_model_class(self, node: ast.ClassDef) -> None:
        detected_item = {
            "id": build_occurrence_id(node.name, node.name, node),
            "occurrence_id": build_occurrence_id(node.name, node.name, node),
            "occurrenceId": build_occurrence_id(node.name, node.name, node),
            "var_name": node.name,
            "varName": node.name,
            "original_class": node.name,
            "originalClass": node.name,
            "canonical_class": node.name,
            "canonicalClass": node.name,
            "class_name": node.name,
            "className": node.name,
            "full_name": node.name,
            "display": node.name,
            "lib": "torch",
            "library": "torch",
            "module": "torch.nn",
            "type": "custom_pytorch_model",
            "supported": True,
            "replaceable": False,
            "message": "Modèle PyTorch personnalisé détecté. Il sera exécuté tel quel.",
            "line": getattr(node, "lineno", None),
            "column": getattr(node, "col_offset", None),
            "inside_pipeline": self._pipeline_depth > 0,
            "insidePipeline": self._pipeline_depth > 0,
            "inside_search_cv": self._search_cv_depth > 0,
            "insideSearchCv": self._search_cv_depth > 0,
        }
        self.detected.append(detected_item)

    def visit_Call(self, node: ast.Call) -> Any:
        call_name = get_call_name(node)

        if call_name in {"Pipeline", "make_pipeline"}:
            self._pipeline_depth += 1
            self.generic_visit(node)
            self._pipeline_depth -= 1
            return

        if call_name in {"GridSearchCV", "RandomizedSearchCV"}:
            self._search_cv_depth += 1
            self._detect_search_cv_estimator(node)
            self.generic_visit(node)
            self._search_cv_depth -= 1
            return

        self._detect_call(node)
        self.generic_visit(node)

    def _detect_search_cv_estimator(self, node: ast.Call) -> None:
        # GridSearchCV(estimator=RandomForestClassifier(...))
        for keyword in node.keywords:
            if keyword.arg in {"estimator", "model"} and isinstance(keyword.value, ast.Call):
                self._detect_call(keyword.value, inside_search_cv=True)

        # GridSearchCV(RandomForestClassifier(), ...)
        if node.args and isinstance(node.args[0], ast.Call):
            self._detect_call(node.args[0], inside_search_cv=True)

    def _detect_call(self, node: ast.Call, inside_search_cv: bool = False) -> None:
        raw_call_name = get_call_name(node)

        if not raw_call_name:
            return

        component_info = resolve_custom_ml_component(raw_call_name, self.import_map)
        if component_info is not None:
            detected_item = build_component_detection(
                self.current_assignment,
                raw_call_name,
                node,
                component_info,
                inside_search_cv,
                self._pipeline_depth > 0,
            )
            self.detected.append(detected_item)
            return

        canonical_class, info = resolve_algorithm_info(raw_call_name, self.import_map)

        if not canonical_class:
            return

        if canonical_class in TECHNICAL_OBJECTS:
            return

        lib = info.get("lib") or ""
        module = info.get("module") or ""
        model_type = info.get("type") or ""

        supported = canonical_class in ALGORITHM_REGISTRY and lib in SUPPORTED_LIBRARIES

        detected_item = {
            "id": build_occurrence_id(self.current_assignment, canonical_class, node),
            "occurrence_id": build_occurrence_id(self.current_assignment, canonical_class, node),
            "occurrenceId": build_occurrence_id(self.current_assignment, canonical_class, node),
            "var_name": self.current_assignment,
            "varName": self.current_assignment,
            "original_class": canonical_class,
            "originalClass": canonical_class,
            "canonical_class": canonical_class,
            "canonicalClass": canonical_class,
            "class_name": canonical_class,
            "className": canonical_class,
            "full_name": raw_call_name,
            "original_display": info.get("display", canonical_class),
            "originalDisplay": info.get("display", canonical_class),
            "display": info.get("display", canonical_class),
            "lib": lib,
            "library": lib,
            "module": module,
            "type": model_type,
            "model_type": "classification" if model_type == "classifier" else "regression",
            "modelType": "classification" if model_type == "classifier" else "regression",
            "problem_type": "classification" if model_type == "classifier" else "regression",
            "supported": supported,
            "replaceable": False,
            "line": getattr(node, "lineno", None),
            "column": getattr(node, "col_offset", None),
            "inside_pipeline": self._pipeline_depth > 0,
            "insidePipeline": self._pipeline_depth > 0,
            "inside_search_cv": inside_search_cv or self._search_cv_depth > 0,
            "insideSearchCv": inside_search_cv or self._search_cv_depth > 0,
            "message": build_algorithm_message(canonical_class, supported),
        }

        self.detected.append(detected_item)

    def _extract_target_name(self, target: ast.AST) -> Optional[str]:
        if isinstance(target, ast.Name):
            return target.id

        if isinstance(target, ast.Attribute):
            return target.attr

        if isinstance(target, ast.Tuple):
            names = [self._extract_target_name(item) for item in target.elts]
            names = [item for item in names if item]
            return names[0] if names else None

        if isinstance(target, ast.Subscript):
            return "model"

        return None


def canonicalize_name(
    raw_name: Optional[str],
    import_map: dict[str, dict[str, Any]],
) -> tuple[Optional[str], Optional[str], Optional[str]]:
    if not raw_name:
        return None, None, None

    short_name = raw_name.split(".")[-1]

    if short_name in TECHNICAL_OBJECTS:
        return None, None, None

    if short_name in ALGORITHM_REGISTRY:
        info = ALGORITHM_REGISTRY[short_name]
        return short_name, info["lib"], info["module"]

    # Alias importé : from sklearn.ensemble import RandomForestClassifier as RFC
    if short_name in import_map:
        imported = import_map[short_name]
        original_name = imported.get("original_name") or short_name

        if original_name in ALGORITHM_REGISTRY:
            info = ALGORITHM_REGISTRY[original_name]
            return original_name, info["lib"], info["module"]

    # Appels qualifiés : xgb.XGBRegressor, lightgbm.LGBMClassifier, sklearn.ensemble.RandomForestClassifier
    parts = raw_name.split(".")
    if len(parts) >= 2:
        possible_class = parts[-1]
        possible_root = parts[0]

        if possible_class in ALGORITHM_REGISTRY:
            info = ALGORITHM_REGISTRY[possible_class]
            return possible_class, info["lib"], info["module"]

        if possible_root in {"xgb", "xgboost"} and possible_class in {"XGBClassifier", "XGBRegressor"}:
            info = ALGORITHM_REGISTRY[possible_class]
            return possible_class, info["lib"], info["module"]

        if possible_root in {"lgb", "lightgbm"} and possible_class in {"LGBMClassifier", "LGBMRegressor"}:
            info = ALGORITHM_REGISTRY[possible_class]
            return possible_class, info["lib"], info["module"]

    return None, None, None


def resolve_algorithm_info(
    raw_name: Optional[str],
    import_map: dict[str, dict[str, Any]],
) -> tuple[Optional[str], dict[str, str]]:
    canonical_class, lib, module = canonicalize_name(raw_name, import_map)

    if not canonical_class:
        return None, {}

    registry_info = ALGORITHM_REGISTRY.get(canonical_class, {})

    return canonical_class, {
        "lib": lib or registry_info.get("lib", ""),
        "module": module or registry_info.get("module", ""),
        "display": registry_info.get("display", canonical_class),
        "type": registry_info.get("type", ""),
    }


def get_call_name(call_node: ast.Call) -> Optional[str]:
    func = call_node.func

    if isinstance(func, ast.Name):
        return func.id

    if isinstance(func, ast.Attribute):
        parent = get_attribute_parent_name(func.value)
        if parent:
            return f"{parent}.{func.attr}"
        return func.attr

    return None


def get_attribute_parent_name(node: ast.AST) -> Optional[str]:
    if isinstance(node, ast.Name):
        return node.id

    if isinstance(node, ast.Attribute):
        parent = get_attribute_parent_name(node.value)
        if parent:
            return f"{parent}.{node.attr}"
        return node.attr

    return None


def resolve_full_call_name(raw_name: str, import_map: dict[str, dict[str, Any]]) -> str:
    parts = raw_name.split(".")
    if not parts:
        return raw_name

    root = parts[0]
    if root in import_map:
        imported = import_map[root]
        module = imported.get("module") or root
        return ".".join([module] + parts[1:])

    return raw_name


def _is_supported_module(module_name: str) -> bool:
    normalized = normalize_module_name(module_name)
    if normalized in SUPPORTED_RUNTIME_MODULES:
        return True

    return any(
        normalized == supported or normalized.startswith(f"{supported}.")
        for supported in SUPPORTED_RUNTIME_MODULES
    )


def resolve_custom_ml_component(
    raw_name: str,
    import_map: dict[str, dict[str, Any]],
) -> Optional[dict[str, str]]:
    full_name = resolve_full_call_name(raw_name, import_map)
    short_name = full_name.split(".")[-1]

    if full_name.startswith("torch.nn") or full_name.startswith("nn."):
        if short_name in PYTORCH_LAYER_CLASSES:
            return {
                "category": "pytorch_layer",
                "display": f"nn.{short_name}",
                "supported": _is_supported_module(full_name),
                "replaceable": False,
            }
        if short_name in PYTORCH_LOSS_CLASSES:
            return {
                "category": "loss_function",
                "display": f"nn.{short_name}",
                "supported": _is_supported_module(full_name),
                "replaceable": False,
            }
    if full_name.startswith("torch.optim") or full_name.startswith("optim."):
        if short_name in PYTORCH_OPTIMIZERS:
            return {
                "category": "optimizer",
                "display": f"optim.{short_name}",
                "supported": _is_supported_module(full_name),
                "replaceable": False,
            }
    return None


def build_component_detection(
    var_name: Optional[str],
    raw_call_name: str,
    node: ast.Call,
    component_info: dict[str, str],
    inside_search_cv: bool,
    inside_pipeline: bool,
) -> dict[str, Any]:
    name = component_info.get("display") or raw_call_name
    supported = component_info.get("supported", False)

    item = {
        "id": build_occurrence_id(var_name, name, node),
        "occurrence_id": build_occurrence_id(var_name, name, node),
        "occurrenceId": build_occurrence_id(var_name, name, node),
        "var_name": var_name,
        "varName": var_name,
        "original_class": name,
        "originalClass": name,
        "canonical_class": name,
        "canonicalClass": name,
        "class_name": name,
        "className": name,
        "full_name": raw_call_name,
        "display": name,
        "lib": "torch",
        "library": "torch",
        "module": raw_call_name,
        "type": component_info.get("category", "pytorch_component"),
        "supported": supported,
        "replaceable": component_info.get("replaceable", False),
        "message": (
            "Modèle PyTorch personnalisé détecté. Il sera exécuté tel quel."
            if component_info.get("category") == "custom_pytorch_model"
            else None
        ),
        "line": getattr(node, "lineno", None),
        "column": getattr(node, "col_offset", None),
        "inside_pipeline": inside_pipeline,
        "insidePipeline": inside_pipeline,
        "inside_search_cv": inside_search_cv,
        "insideSearchCv": inside_search_cv,
    }
    return item


def build_detected_modules(imported_modules: list[str]) -> list[dict[str, Any]]:
    module_items: list[dict[str, Any]] = []
    seen: set[str] = set()

    for module_name in sorted(imported_modules):
        if module_name in seen:
            continue

        supported = _is_supported_module(module_name)
        module_items.append(
            {
                "name": module_name,
                "supported": supported,
                "source": "standard_runtime" if supported else "custom_image",
            }
        )
        seen.add(module_name)

    return module_items


def build_occurrence_id(
    var_name: Optional[str],
    class_name: str,
    node: ast.AST,
) -> str:
    safe_var = var_name or "model"
    line = getattr(node, "lineno", 0) or 0
    column = getattr(node, "col_offset", 0) or 0
    return f"{safe_var}:{class_name}:{line}:{column}"


def build_algorithm_message(class_name: str, supported: bool) -> str:
    if supported:
        return f"Algorithme supporté détecté : {class_name}"

    return f"Algorithme détecté mais non supporté : {class_name}"


def build_supported_modules(detected: list[dict[str, Any]]) -> list[dict[str, Any]]:
    modules: dict[str, dict[str, Any]] = {}

    for item in detected:
        if not item.get("supported"):
            continue

        lib = item.get("lib") or item.get("library")
        if not lib:
            continue

        modules[lib] = {
            "key": lib,
            "name": SUPPORTED_LIBRARIES.get(lib, lib),
            "module": item.get("module", lib),
            "count": modules.get(lib, {}).get("count", 0) + 1,
        }

    return list(modules.values())


def build_unsupported_modules(import_map: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    unsupported: dict[str, dict[str, Any]] = {}

    for imported in import_map.values():
        root = imported.get("root_library") or ""

        if root in KNOWN_EXTERNAL_LIBRARIES and root not in SUPPORTED_LIBRARIES:
            unsupported[root] = {
                "key": root,
                "name": KNOWN_EXTERNAL_LIBRARIES.get(root, root),
                "module": imported.get("module", root),
                "reason": "Librairie non supportée par le runtime standard actuel.",
            }

    return list(unsupported.values())



def infer_data_type(
    code: str,
    imported_modules: list[str],
    detected: list[dict[str, Any]],
) -> tuple[str, str]:
    """
    Déduit le type de données probable sans influencer l'exécution.
    Retourne :
    - ("TEXT", "text") si un vectorizer NLP est détecté ;
    - ("IMAGE", "image") si des imports image/vision sont détectés ;
    - ("TABULAR", "tabular") par défaut.
    """
    code_text = code or ""
    imported = set(imported_modules or [])

    if any(vectorizer in code_text for vectorizer in TEXT_FEATURE_EXTRACTORS):
        return "TEXT", "text"

    if "sklearn.feature_extraction.text" in imported:
        return "TEXT", "text"

    if any(module in imported for module in IMAGE_HINT_MODULES):
        return "IMAGE", "image"

    if any(str(module).startswith("torchvision") for module in imported):
        return "IMAGE", "image"

    if any(str(module).startswith("PIL") for module in imported):
        return "IMAGE", "image"

    return "TABULAR", "tabular"


def infer_task_type(detected: list[dict[str, Any]]) -> str:
    """
    Déduit classification/régression à partir du premier algorithme détecté.
    Cette information sert uniquement aux métadonnées d'affichage.
    """
    for item in detected or []:
        model_type = str(
            item.get("model_type")
            or item.get("modelType")
            or item.get("problem_type")
            or ""
        ).strip().lower()

        if model_type in {"classification", "regression"}:
            return model_type

        raw_type = str(item.get("type") or "").strip().lower()

        if raw_type == "classifier":
            return "classification"

        if raw_type == "regressor":
            return "regression"

    return "custom"


def build_warnings(
    detected: list[dict[str, Any]],
    unsupported_module_names: list[str],
) -> list[str]:
    warnings: list[str] = []

    if not detected:
        warnings.append("Aucun algorithme ML connu détecté.")

    if unsupported_module_names:
        names = ", ".join(unsupported_module_names)
        warnings.append(
            f"Modules non supportés : {names}. Utilisez le mode Dockerfile personnalisé ou ajoutez ces modules à l’image standard."
        )
    else:
        warnings.append(
            "Le script peut être exécuté tel quel car les modules détectés sont supportés."
        )

    for item in detected:
        if item.get("inside_pipeline"):
            warnings.append(
                f"{item.get('canonical_class')} est dans une Pipeline. "
                "La plateforme détecte uniquement le modèle et conserve le script original."
            )

        if item.get("inside_search_cv"):
            warnings.append(
                f"{item.get('canonical_class')} est dans GridSearchCV/RandomizedSearchCV. "
                "La plateforme détecte l'estimator interne sans modifier le code."
            )

    # Dédupliquer warnings
    unique_warnings: list[str] = []
    seen = set()
    for warning in warnings:
        if warning in seen:
            continue
        seen.add(warning)
        unique_warnings.append(warning)

    return unique_warnings


def build_summary(
    detected: list[dict[str, Any]],
    supported_modules: list[dict[str, Any]],
    unsupported_modules: list[dict[str, Any]],
) -> str:
    total = len(detected)
    supported = len([item for item in detected if item.get("supported")])

    if total == 0:
        return "Aucun algorithme ML connu détecté."

    module_names = ", ".join(module.get("name", module.get("key", "")) for module in supported_modules)

    summary = f"{total} algorithme(s) ML détecté(s), {supported} supporté(s) par le runtime standard."

    if module_names:
        summary += f" Modules supportés : {module_names}."

    if unsupported_modules:
        unsupported_names = ", ".join(module["name"] for module in unsupported_modules)
        summary += f" Modules non supportés : {unsupported_names}."

    return summary


def remove_duplicate_detections(detected: list[dict[str, Any]]) -> list[dict[str, Any]]:
    unique: dict[tuple[Any, Any, Any, Any], dict[str, Any]] = {}

    for item in detected:
        class_name = item.get("canonical_class") or item.get("class_name") or item.get("original_class")
        var_name = item.get("var_name") or "model"
        line = item.get("line") or 0
        column = item.get("column") or 0

        key = (class_name, var_name, line, column)

        existing = unique.get(key)

        if existing is None:
            unique[key] = item
            continue

        # Si un doublon existe, garder celui qui a le plus d'informations.
        existing_score = int(bool(existing.get("var_name"))) + int(bool(existing.get("module")))
        current_score = int(bool(item.get("var_name"))) + int(bool(item.get("module")))

        if current_score > existing_score:
            unique[key] = item

    return list(unique.values())


def remove_duplicate_technical_objects(detected: list[dict[str, Any]]) -> list[dict[str, Any]]:
    clean_items: list[dict[str, Any]] = []

    for item in detected:
        class_name = item.get("canonical_class") or item.get("class_name") or item.get("original_class")

        if not class_name:
            continue

        if class_name in TECHNICAL_OBJECTS:
            continue

        clean_items.append(item)

    return clean_items


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage : python algorithm_detector.py mon_model.py")
        sys.exit(1)

    file_path = sys.argv[1]
    result = detect_algorithms_from_file(file_path)

    print(json.dumps(result, indent=2, ensure_ascii=False))
