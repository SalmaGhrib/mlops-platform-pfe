from __future__ import annotations

import ast
import sys
from typing import Any, Dict, List, Set

SUPPORTED_MODULE_ROOTS: Set[str] = {
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

CANONICAL_MODULE_NAMES: Dict[str, str] = {
    "sklearn": "scikit-learn",
    "cv2": "opencv-python",
    "PIL": "pillow",
    "yaml": "pyyaml",
    "pyyaml": "pyyaml",
    "torch": "torch",
    "torchvision": "torchvision",
    "tensorflow": "tensorflow",
    "keras": "tensorflow",
    "xgboost": "xgboost",
    "lightgbm": "lightgbm",
}

STANDARD_LIBRARY_MODULES: Set[str] = set(getattr(sys, "stdlib_module_names", set()))
STANDARD_LIBRARY_MODULES.update(
    {
        "os",
        "sys",
        "pathlib",
        "json",
        "re",
        "typing",
        "argparse",
        "logging",
        "math",
        "time",
        "datetime",
        "collections",
        "itertools",
        "functools",
        "inspect",
        "subprocess",
        "tempfile",
        "threading",
        "multiprocessing",
        "concurrent",
        "enum",
        "types",
        "traceback",
        "dataclasses",
        "pickle",
        "shutil",
        "gzip",
        "csv",
        "io",
        "pprint",
    }
)

METRIC_MODULES: Set[str] = {
    "mlflow",
    "sklearn",
    "xgboost",
    "lightgbm",
    "torch",
    "torchvision",
}

KNOWN_METRIC_FUNCTIONS: Set[str] = {
    "accuracy",
    "accuracy_score",
    "precision",
    "precision_score",
    "recall",
    "recall_score",
    "f1",
    "f1_score",
    "f1_weighted",
    "precision_weighted",
    "recall_weighted",
    "roc_auc",
    "roc_auc_score",
    "auc",
    "mean_squared_error",
    "mean_absolute_error",
    "mean_absolute_percentage_error",
    "mse",
    "rmse",
    "mae",
    "r2",
    "r2_score",
    "log_loss",
    "cross_entropy",
}


def normalize_module_name(module_name: str) -> str:
    module_name = str(module_name or "").strip()

    if not module_name:
        return module_name

    return CANONICAL_MODULE_NAMES.get(module_name, module_name)


def extract_imported_modules(code: str) -> List[str]:
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return []

    imported_modules: Set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                module_name = alias.name.strip()
                root = module_name.split(".")[0]
                if not root or root in STANDARD_LIBRARY_MODULES:
                    continue
                imported_modules.add(module_name)
                imported_modules.add(root)

        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if module.startswith("."):
                continue
            root = module.split(".")[0].strip()
            if not root or root in STANDARD_LIBRARY_MODULES:
                continue

            imported_modules.add(module)
            imported_modules.add(root)

            for alias in node.names:
                if alias.name == "*":
                    continue

                imported_name = alias.name.strip()
                if not imported_name:
                    continue

                if module in {"torchvision", "torch.nn", "torch.optim"}:
                    imported_modules.add(f"{module}.{imported_name}")
                elif module.startswith("sklearn") and imported_name[0].islower():
                    imported_modules.add(f"{module}.{imported_name}")
                elif module == "yaml" and imported_name == "safe_load":
                    imported_modules.add(module)

    return sorted(imported_modules)


def extract_metric_references(code: str) -> List[str]:
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return []

    metrics: Set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            module = node.module or ""
            for alias in node.names:
                if alias.name == "*":
                    continue

                imported_name = alias.asname or alias.name
                if imported_name in KNOWN_METRIC_FUNCTIONS:
                    metrics.add(alias.name)
                elif module.startswith("sklearn.metrics") and alias.name:
                    metrics.add(alias.name)
                elif module == "mlflow" and alias.name in {"log_metric", "log_metrics"}:
                    metrics.add(alias.name)

        elif isinstance(node, ast.Import):
            for alias in node.names:
                module_name = alias.name.strip()
                if module_name.startswith("sklearn.metrics"):
                    metrics.add(module_name)
                elif module_name == "mlflow":
                    metrics.add(module_name)

    return sorted(metrics)


def _is_supported_module(module_name: str) -> bool:
    module_name = normalize_module_name(module_name)
    if module_name in SUPPORTED_MODULE_ROOTS:
        return True

    for supported_root in SUPPORTED_MODULE_ROOTS:
        if module_name.startswith(f"{supported_root}."):
            return True

    return False


def analyze_script_imports(code: str) -> Dict[str, Any]:
    try:
        imported_modules = extract_imported_modules(code)
    except SyntaxError as exc:
        return {
            "supported": False,
            "detectedImports": [],
            "supportedImports": [],
            "unsupportedImports": [],
            "recommendation": "Erreur de syntaxe dans le script Python.",
            "error": f"SyntaxError: {exc}",
        }

    detected_imports: List[str] = []
    supported_imports: List[str] = []
    unsupported_imports: List[str] = []

    for module_name in imported_modules:
        normalized_module = normalize_module_name(module_name)
        detected_imports.append(module_name)

        if _is_supported_module(normalized_module):
            supported_imports.append(module_name)
        else:
            unsupported_imports.append(normalized_module)

    detected_metrics = extract_metric_references(code)

    supported = len(unsupported_imports) == 0
    recommendation = (
        "Runtime standard compatible"
        if supported
        else "Utilisez le mode Dockerfile personnalisé ou ajoutez ce module à l’image standard."
    )

    return {
        "supported": supported,
        "detectedImports": detected_imports,
        "supportedImports": supported_imports,
        "unsupportedImports": sorted(set(unsupported_imports)),
        "detectedMetrics": detected_metrics,
        "recommendation": recommendation,
    }
