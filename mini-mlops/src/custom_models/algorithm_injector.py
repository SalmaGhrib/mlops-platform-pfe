"""
algorithm_injector.py
──────────────────────
Crée une copie modifiée du fichier .py du développeur.

Rôle :
- remplacer uniquement les algorithmes ML choisis par l'utilisateur ;
- ajouter automatiquement les imports nécessaires ;
- supprimer les imports d'algorithmes devenus inutiles ;
- nettoyer automatiquement les paramètres incompatibles avec le nouvel algorithme ;
- supporter sklearn, xgboost et lightgbm ;
- rester compatible avec plusieurs anciens noms de fonctions utilisés par le runner.

Important :
- le fichier original n'est jamais modifié ;
- le fichier temporaire généré est celui qui sera exécuté par Kubeflow.
"""

import ast
import importlib
import inspect
import json
import os
import tempfile
from pathlib import Path
from typing import Any, Optional


IMPORT_MAP: dict[str, str] = {
    # Scikit-learn — Classification
    "RandomForestClassifier": "from sklearn.ensemble import RandomForestClassifier",
    "GradientBoostingClassifier": "from sklearn.ensemble import GradientBoostingClassifier",
    "AdaBoostClassifier": "from sklearn.ensemble import AdaBoostClassifier",
    "ExtraTreesClassifier": "from sklearn.ensemble import ExtraTreesClassifier",
    "BaggingClassifier": "from sklearn.ensemble import BaggingClassifier",
    "LogisticRegression": "from sklearn.linear_model import LogisticRegression",
    "SVC": "from sklearn.svm import SVC",
    "DecisionTreeClassifier": "from sklearn.tree import DecisionTreeClassifier",
    "KNeighborsClassifier": "from sklearn.neighbors import KNeighborsClassifier",
    "GaussianNB": "from sklearn.naive_bayes import GaussianNB",
    "MultinomialNB": "from sklearn.naive_bayes import MultinomialNB",
    "BernoulliNB": "from sklearn.naive_bayes import BernoulliNB",

    # Scikit-learn — Regression
    "RandomForestRegressor": "from sklearn.ensemble import RandomForestRegressor",
    "GradientBoostingRegressor": "from sklearn.ensemble import GradientBoostingRegressor",
    "AdaBoostRegressor": "from sklearn.ensemble import AdaBoostRegressor",
    "ExtraTreesRegressor": "from sklearn.ensemble import ExtraTreesRegressor",
    "LinearRegression": "from sklearn.linear_model import LinearRegression",
    "Ridge": "from sklearn.linear_model import Ridge",
    "Lasso": "from sklearn.linear_model import Lasso",
    "ElasticNet": "from sklearn.linear_model import ElasticNet",
    "SVR": "from sklearn.svm import SVR",
    "DecisionTreeRegressor": "from sklearn.tree import DecisionTreeRegressor",
    "KNeighborsRegressor": "from sklearn.neighbors import KNeighborsRegressor",

    # XGBoost
    "XGBClassifier": "from xgboost import XGBClassifier",
    "XGBRegressor": "from xgboost import XGBRegressor",

    # LightGBM
    "LGBMClassifier": "from lightgbm import LGBMClassifier",
    "LGBMRegressor": "from lightgbm import LGBMRegressor",
}


CLASS_IMPORT_PATHS: dict[str, str] = {
    # Scikit-learn — Classification
    "RandomForestClassifier": "sklearn.ensemble.RandomForestClassifier",
    "GradientBoostingClassifier": "sklearn.ensemble.GradientBoostingClassifier",
    "AdaBoostClassifier": "sklearn.ensemble.AdaBoostClassifier",
    "ExtraTreesClassifier": "sklearn.ensemble.ExtraTreesClassifier",
    "BaggingClassifier": "sklearn.ensemble.BaggingClassifier",
    "LogisticRegression": "sklearn.linear_model.LogisticRegression",
    "SVC": "sklearn.svm.SVC",
    "DecisionTreeClassifier": "sklearn.tree.DecisionTreeClassifier",
    "KNeighborsClassifier": "sklearn.neighbors.KNeighborsClassifier",
    "GaussianNB": "sklearn.naive_bayes.GaussianNB",
    "MultinomialNB": "sklearn.naive_bayes.MultinomialNB",
    "BernoulliNB": "sklearn.naive_bayes.BernoulliNB",

    # Scikit-learn — Regression
    "RandomForestRegressor": "sklearn.ensemble.RandomForestRegressor",
    "GradientBoostingRegressor": "sklearn.ensemble.GradientBoostingRegressor",
    "AdaBoostRegressor": "sklearn.ensemble.AdaBoostRegressor",
    "ExtraTreesRegressor": "sklearn.ensemble.ExtraTreesRegressor",
    "LinearRegression": "sklearn.linear_model.LinearRegression",
    "Ridge": "sklearn.linear_model.Ridge",
    "Lasso": "sklearn.linear_model.Lasso",
    "ElasticNet": "sklearn.linear_model.ElasticNet",
    "SVR": "sklearn.svm.SVR",
    "DecisionTreeRegressor": "sklearn.tree.DecisionTreeRegressor",
    "KNeighborsRegressor": "sklearn.neighbors.KNeighborsRegressor",

    # XGBoost
    "XGBClassifier": "xgboost.XGBClassifier",
    "XGBRegressor": "xgboost.XGBRegressor",

    # LightGBM
    "LGBMClassifier": "lightgbm.LGBMClassifier",
    "LGBMRegressor": "lightgbm.LGBMRegressor",
}


KNOWN_ALGORITHM_CLASSES = set(IMPORT_MAP.keys())


ALGORITHM_ALIASES: dict[str, str] = {
    # Random Forest
    "Random Forest": "RandomForestClassifier",
    "RandomForest": "RandomForestClassifier",
    "Random Forest Classifier": "RandomForestClassifier",
    "Random Forest Regressor": "RandomForestRegressor",

    # Decision Tree
    "Decision Tree": "DecisionTreeClassifier",
    "DecisionTree": "DecisionTreeClassifier",
    "Decision Tree Classifier": "DecisionTreeClassifier",
    "Decision Tree Regressor": "DecisionTreeRegressor",

    # Gradient Boosting
    "Gradient Boosting": "GradientBoostingClassifier",
    "GradientBoosting": "GradientBoostingClassifier",
    "Gradient Boosting Classifier": "GradientBoostingClassifier",
    "Gradient Boosting Regressor": "GradientBoostingRegressor",

    # Extra Trees
    "Extra Trees": "ExtraTreesClassifier",
    "ExtraTrees": "ExtraTreesClassifier",
    "Extra Trees Classifier": "ExtraTreesClassifier",
    "Extra Trees Regressor": "ExtraTreesRegressor",

    # KNN
    "KNN": "KNeighborsClassifier",
    "KNN Classifier": "KNeighborsClassifier",
    "KNN Regressor": "KNeighborsRegressor",

    # XGBoost
    "XGBoost": "XGBClassifier",
    "XGBoost Classifier": "XGBClassifier",
    "XGBoost Regressor": "XGBRegressor",

    # LightGBM
    "LightGBM": "LGBMClassifier",
    "LightGBM Classifier": "LGBMClassifier",
    "LightGBM Regressor": "LGBMRegressor",
}


def process_file(
    original_code: str,
    original_filename: str,
    replacements: list[dict[str, Any]],
) -> dict[str, Any]:
    result = inject_algorithms(original_code, replacements)

    modified_file_path = save_modified_file(
        result["modified_code"],
        original_filename
    )

    result["modified_file_path"] = modified_file_path
    return result


def inject_algorithms(
    original_code: str,
    replacements: list[dict[str, Any]],
) -> dict[str, Any]:
    if not replacements:
        return {
            "enabled": False,
            "modified_code": original_code,
            "code": original_code,
            "changes_made": [],
            "replacement_reports": [],
            "imports_added": [],
            "imports_removed": [],
            "warnings": ["Aucun remplacement demandé — fichier utilisé tel quel."],
        }

    normalized_replacements, normalization_warnings = normalize_replacements(replacements)

    if not normalized_replacements:
        return {
            "enabled": False,
            "modified_code": original_code,
            "code": original_code,
            "changes_made": [],
            "replacement_reports": [],
            "imports_added": [],
            "imports_removed": [],
            "warnings": normalization_warnings
            + ["Aucun remplacement valide reçu — fichier utilisé tel quel."],
        }

    try:
        tree = ast.parse(original_code)
    except SyntaxError as exc:
        return {
            "enabled": False,
            "modified_code": original_code,
            "code": original_code,
            "changes_made": [],
            "replacement_reports": [],
            "imports_added": [],
            "imports_removed": [],
            "warnings": [f"Erreur de syntaxe Python : {exc}. Injection impossible."],
        }

    transformer = AlgorithmReplacementTransformer(normalized_replacements)
    modified_tree = transformer.visit(tree)
    ast.fix_missing_locations(modified_tree)

    modified_code = ast.unparse(modified_tree)

    imports_added = add_missing_imports(
        modified_code,
        transformer.changes_made
    )

    modified_code_with_imports = insert_imports(
        modified_code,
        imports_added
    )

    cleaned_code, imports_removed = remove_unused_algorithm_imports(
        modified_code_with_imports
    )

    warnings = normalization_warnings + list(transformer.warnings)

    if not transformer.changes_made:
        warnings.append(
            "Aucun remplacement n'a été appliqué. Vérifiez original_class, var_name ou occurrence_id."
        )

    return {
        "enabled": len(transformer.changes_made) > 0,
        "modified_code": cleaned_code,
        "code": cleaned_code,
        "changes_made": transformer.changes_made,
        "replacement_reports": transformer.changes_made,
        "imports_added": imports_added,
        "imports_removed": imports_removed,
        "warnings": warnings,
    }


def normalize_replacements(
    replacements: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[str]]:
    normalized: list[dict[str, Any]] = []
    warnings: list[str] = []

    for replacement in replacements or []:
        new_class = get_first_existing(
            replacement,
            [
                "new_class",
                "newClass",
                "newAlgorithmName",
                "target_algorithm",
                "targetAlgorithm",
                "to",
            ],
        )

        original_class = get_first_existing(
            replacement,
            [
                "original_class",
                "originalClass",
                "oldAlgorithmName",
                "source_algorithm",
                "sourceAlgorithm",
                "from",
            ],
        )

        if new_class is None:
            warnings.append(f"Remplacement ignoré car new_class est absent : {replacement}")
            continue

        new_class = normalize_algorithm_name(str(new_class).strip())
        original_class = normalize_algorithm_name(str(original_class).strip()) if original_class else None

        if new_class == "" or new_class.lower() in {
            "keep",
            "keep_original",
            "original",
            "garder",
            "garder_tel_quel",
        }:
            continue

        if new_class not in IMPORT_MAP:
            warnings.append(
                f"Algorithme cible non supporté : {new_class}. Remplacement ignoré."
            )
            continue

        new_lib = get_first_existing(
            replacement,
            [
                "new_lib",
                "newLib",
                "newModuleName",
                "module",
                "library",
            ],
        )

        if not new_lib:
            new_lib = detect_module_from_algorithm(new_class)

        normalized.append(
            {
                "occurrence_id": get_first_existing(
                    replacement,
                    ["occurrence_id", "occurrenceId", "id"]
                ),
                "var_name": get_first_existing(
                    replacement,
                    ["var_name", "varName", "variableName", "variable"]
                ),
                "original_class": original_class,
                "new_class": new_class,
                "new_lib": str(new_lib).strip() if new_lib else detect_module_from_algorithm(new_class),
            }
        )

    return normalized, warnings


def normalize_algorithm_name(value: Optional[str]) -> str:
    if value is None:
        return ""

    cleaned = str(value).strip()

    if not cleaned:
        return ""

    if cleaned in ALGORITHM_ALIASES:
        return ALGORITHM_ALIASES[cleaned]

    compact = cleaned.lower().replace(" ", "").replace("_", "").replace("-", "")

    if "randomforestregressor" in compact:
        return "RandomForestRegressor"
    if "randomforestclassifier" in compact:
        return "RandomForestClassifier"
    if compact == "randomforest":
        return "RandomForestClassifier"

    if "decisiontreeregressor" in compact:
        return "DecisionTreeRegressor"
    if "decisiontreeclassifier" in compact:
        return "DecisionTreeClassifier"
    if compact == "decisiontree":
        return "DecisionTreeClassifier"

    if "gradientboostingregressor" in compact:
        return "GradientBoostingRegressor"
    if "gradientboostingclassifier" in compact:
        return "GradientBoostingClassifier"
    if compact == "gradientboosting":
        return "GradientBoostingClassifier"

    if "extratreesregressor" in compact:
        return "ExtraTreesRegressor"
    if "extratreesclassifier" in compact:
        return "ExtraTreesClassifier"

    if "kneighborsregressor" in compact or "knnregressor" in compact:
        return "KNeighborsRegressor"
    if "kneighborsclassifier" in compact or "knnclassifier" in compact:
        return "KNeighborsClassifier"

    if "xgbregressor" in compact or "xgboostregressor" in compact:
        return "XGBRegressor"
    if "xgbclassifier" in compact or "xgboostclassifier" in compact:
        return "XGBClassifier"

    if "lgbmregressor" in compact or "lightgbmregressor" in compact:
        return "LGBMRegressor"
    if "lgbmclassifier" in compact or "lightgbmclassifier" in compact:
        return "LGBMClassifier"

    return cleaned


def get_first_existing(
    data: dict[str, Any],
    keys: list[str]
) -> Optional[Any]:
    for key in keys:
        if key in data and data[key] is not None:
            return data[key]
    return None


def detect_module_from_algorithm(class_name: str) -> str:
    class_name = normalize_algorithm_name(class_name)

    if class_name in {
        "RandomForestClassifier",
        "GradientBoostingClassifier",
        "AdaBoostClassifier",
        "ExtraTreesClassifier",
        "BaggingClassifier",
        "RandomForestRegressor",
        "GradientBoostingRegressor",
        "AdaBoostRegressor",
        "ExtraTreesRegressor",
    }:
        return "sklearn.ensemble"

    if class_name in {
        "LogisticRegression",
        "LinearRegression",
        "Ridge",
        "Lasso",
        "ElasticNet",
    }:
        return "sklearn.linear_model"

    if class_name in {"SVC", "SVR"}:
        return "sklearn.svm"

    if class_name in {"DecisionTreeClassifier", "DecisionTreeRegressor"}:
        return "sklearn.tree"

    if class_name in {"KNeighborsClassifier", "KNeighborsRegressor"}:
        return "sklearn.neighbors"

    if class_name in {"GaussianNB", "MultinomialNB", "BernoulliNB"}:
        return "sklearn.naive_bayes"

    if class_name in {"XGBClassifier", "XGBRegressor"}:
        return "xgboost"

    if class_name in {"LGBMClassifier", "LGBMRegressor"}:
        return "lightgbm"

    return ""


def load_class_from_path(class_name: str):
    class_name = normalize_algorithm_name(class_name)
    import_path = CLASS_IMPORT_PATHS.get(class_name)

    if not import_path:
        return None

    module_path, real_class_name = import_path.rsplit(".", 1)

    try:
        module = importlib.import_module(module_path)
        return getattr(module, real_class_name)
    except Exception:
        return None


def get_allowed_init_params(class_name: str) -> set[str]:
    cls = load_class_from_path(class_name)

    if cls is None:
        return set()

    try:
        signature = inspect.signature(cls.__init__)
    except Exception:
        return set()

    allowed_params: set[str] = set()

    for name, parameter in signature.parameters.items():
        if name == "self":
            continue

        if parameter.kind in {
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
            inspect.Parameter.KEYWORD_ONLY,
        }:
            allowed_params.add(name)

        # XGBoost / LightGBM peuvent avoir **kwargs.
        # Dans ce cas, on ne supprime pas agressivement les paramètres.
        if parameter.kind == inspect.Parameter.VAR_KEYWORD:
            return set()

    return allowed_params


def clean_incompatible_keywords(
    new_class: str,
    keywords: list[ast.keyword],
) -> tuple[list[ast.keyword], list[str]]:
    allowed_params = get_allowed_init_params(new_class)

    if not allowed_params:
        return keywords, []

    cleaned_keywords: list[ast.keyword] = []
    removed_keywords: list[str] = []

    for keyword in keywords:
        # **kwargs : impossible de savoir statiquement, on garde.
        if keyword.arg is None:
            cleaned_keywords.append(keyword)
            continue

        if keyword.arg in allowed_params:
            cleaned_keywords.append(keyword)
        else:
            removed_keywords.append(keyword.arg)

    return cleaned_keywords, removed_keywords


class AlgorithmReplacementTransformer(ast.NodeTransformer):
    def __init__(self, replacements: list[dict[str, Any]]):
        self.replacements = replacements
        self.current_assignment: Optional[str] = None
        self.changes_made: list[dict[str, Any]] = []
        self.warnings: list[str] = []

    def visit_Assign(self, node: ast.Assign) -> Any:
        previous_assignment = self.current_assignment
        self.current_assignment = self.extract_assignment_name(node)

        node.value = self.visit(node.value)

        self.current_assignment = previous_assignment
        return node

    def visit_AnnAssign(self, node: ast.AnnAssign) -> Any:
        previous_assignment = self.current_assignment
        self.current_assignment = self.extract_ann_assignment_name(node)

        if node.value:
            node.value = self.visit(node.value)

        self.current_assignment = previous_assignment
        return node

    def visit_Call(self, node: ast.Call) -> Any:
        self.generic_visit(node)

        old_class = get_call_name(node)

        if old_class is None:
            return node

        old_class = normalize_algorithm_name(old_class)

        replacement = self.find_replacement(node, old_class)

        if replacement is None:
            return node

        new_class = normalize_algorithm_name(replacement["new_class"])

        if new_class not in IMPORT_MAP:
            self.warnings.append(
                f"Algorithme cible non supporté : {new_class}. Remplacement ignoré."
            )
            return node

        old_call_name = old_class

        node.func = ast.Name(id=new_class, ctx=ast.Load())

        node.keywords, removed_params = clean_incompatible_keywords(
            new_class,
            node.keywords,
        )

        occurrence_id = build_occurrence_id(
            self.current_assignment,
            old_call_name,
            node
        )

        change = {
            "occurrence_id": occurrence_id,
            "var_name": self.current_assignment,
            "from": old_call_name,
            "to": new_class,
            "original_class": old_call_name,
            "new_class": new_class,
            "new_lib": replacement.get("new_lib") or detect_module_from_algorithm(new_class),
            "line": getattr(node, "lineno", None),
            "column": getattr(node, "col_offset", None),
            "removed_incompatible_params": removed_params,
        }

        self.changes_made.append(change)

        if removed_params:
            self.warnings.append(
                f"Paramètres supprimés car incompatibles avec {new_class}: {removed_params}"
            )

        return node

    def find_replacement(
        self,
        node: ast.Call,
        old_class: str
    ) -> Optional[dict[str, Any]]:
        current_occurrence_id = build_occurrence_id(
            self.current_assignment,
            old_class,
            node
        )

        current_simple_id = f"{self.current_assignment or 'model'}:{old_class}"
        current_short_id = f"model:{old_class}"

        # 1. Match exact occurrence_id.
        for replacement in self.replacements:
            occurrence_id = replacement.get("occurrence_id")

            if occurrence_id and occurrence_id == current_occurrence_id:
                return replacement

        # 2. Match souple occurrence_id partiel.
        # Exemple Angular : model:RandomForestClassifier:0
        # Exemple injector : model:RandomForestClassifier:12:8
        for replacement in self.replacements:
            occurrence_id = replacement.get("occurrence_id")

            if not occurrence_id:
                continue

            occurrence_id = str(occurrence_id)

            if occurrence_id.startswith(current_simple_id):
                return replacement

            if occurrence_id.startswith(current_short_id):
                return replacement

        # 3. Match var_name + original_class.
        for replacement in self.replacements:
            var_name = replacement.get("var_name")
            original_class = replacement.get("original_class")

            if original_class:
                original_class = normalize_algorithm_name(original_class)

            if var_name and self.current_assignment and str(var_name) != self.current_assignment:
                continue

            if original_class and original_class != old_class:
                continue

            return replacement

        # 4. Match seulement original_class si pas de variable fiable.
        for replacement in self.replacements:
            original_class = replacement.get("original_class")

            if not original_class:
                continue

            original_class = normalize_algorithm_name(original_class)

            if original_class == old_class:
                return replacement

        return None

    def extract_assignment_name(self, node: ast.Assign) -> Optional[str]:
        if not node.targets:
            return None

        target = node.targets[0]

        if isinstance(target, ast.Name):
            return target.id

        if isinstance(target, ast.Attribute):
            return target.attr

        if isinstance(target, ast.Subscript):
            return "model"

        return None

    def extract_ann_assignment_name(self, node: ast.AnnAssign) -> Optional[str]:
        target = node.target

        if isinstance(target, ast.Name):
            return target.id

        if isinstance(target, ast.Attribute):
            return target.attr

        return None


def get_call_name(call_node: ast.Call) -> Optional[str]:
    func = call_node.func

    if isinstance(func, ast.Name):
        return func.id

    if isinstance(func, ast.Attribute):
        return func.attr

    return None


def build_occurrence_id(
    var_name: Optional[str],
    class_name: str,
    node: ast.AST
) -> str:
    safe_var = var_name or "model"
    line = getattr(node, "lineno", 0)
    column = getattr(node, "col_offset", 0)
    return f"{safe_var}:{class_name}:{line}:{column}"


def add_missing_imports(
    modified_code: str,
    changes_made: list[dict[str, Any]]
) -> list[str]:
    imports_to_add: list[str] = []

    for change in changes_made:
        new_class = change["to"]
        import_line = IMPORT_MAP.get(new_class)

        if import_line is None:
            continue

        if import_line in modified_code:
            continue

        if import_line not in imports_to_add:
            imports_to_add.append(import_line)

    return imports_to_add


def insert_imports(
    code: str,
    imports_to_add: list[str]
) -> str:
    if not imports_to_add:
        return code

    lines = code.splitlines()
    last_import_index = -1

    for index, line in enumerate(lines):
        stripped = line.strip()

        if stripped.startswith("import ") or stripped.startswith("from "):
            last_import_index = index

    insert_index = last_import_index + 1 if last_import_index >= 0 else 0

    new_lines = lines[:insert_index] + imports_to_add + lines[insert_index:]

    return "\n".join(new_lines) + "\n"


def remove_unused_algorithm_imports(code: str) -> tuple[str, list[str]]:
    used_algorithm_names = collect_used_algorithm_names(code)

    lines = code.splitlines()
    cleaned_lines: list[str] = []
    removed_imports: list[str] = []

    for line in lines:
        stripped = line.strip()

        if not stripped.startswith("from "):
            cleaned_lines.append(line)
            continue

        parsed = parse_from_import_line(stripped)

        if parsed is None:
            cleaned_lines.append(line)
            continue

        module_part, imported_names = parsed

        known_imported_algorithms = [
            name for name in imported_names
            if name in KNOWN_ALGORITHM_CLASSES
        ]

        if not known_imported_algorithms:
            cleaned_lines.append(line)
            continue

        kept_names = []

        for name in imported_names:
            if name not in KNOWN_ALGORITHM_CLASSES:
                kept_names.append(name)
                continue

            if name in used_algorithm_names:
                kept_names.append(name)
            else:
                removed_imports.append(f"from {module_part} import {name}")

        if kept_names:
            cleaned_lines.append(
                f"from {module_part} import {', '.join(kept_names)}"
            )

    return "\n".join(cleaned_lines) + "\n", removed_imports


def collect_used_algorithm_names(code: str) -> set[str]:
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return set()

    used: set[str] = set()

    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            call_name = get_call_name(node)

            if call_name:
                call_name = normalize_algorithm_name(call_name)

            if call_name in KNOWN_ALGORITHM_CLASSES:
                used.add(call_name)

        elif isinstance(node, ast.Name):
            name = normalize_algorithm_name(node.id)

            if name in KNOWN_ALGORITHM_CLASSES:
                used.add(name)

    return used


def parse_from_import_line(
    import_line: str
) -> Optional[tuple[str, list[str]]]:
    if not import_line.startswith("from "):
        return None

    if " import " not in import_line:
        return None

    before_import, after_import = import_line.split(" import ", 1)

    module_part = before_import.replace("from ", "").strip()

    if not module_part:
        return None

    raw_names = after_import.split(",")
    names = []

    for raw_name in raw_names:
        name = raw_name.strip()

        if " as " in name:
            name = name.split(" as ", 1)[0].strip()

        if name:
            names.append(name)

    if not names:
        return None

    return module_part, names


def save_modified_file(
    modified_code: str,
    original_filename: str
) -> str:
    base_name = os.path.splitext(os.path.basename(original_filename))[0]
    temp_dir = tempfile.mkdtemp(prefix="mlops_modified_model_")

    tmp_path = os.path.join(
        temp_dir,
        f"{base_name}_modified.py"
    )

    with open(tmp_path, "w", encoding="utf-8") as file:
        file.write(modified_code)

    return tmp_path


# ---------------------------------------------------------------------
# Fonctions de compatibilité avec d'anciennes versions du runner.
# Garde-les : comme ça custom_runner.py / custom_main.py peuvent appeler
# plusieurs noms sans casser.
# ---------------------------------------------------------------------

def apply_algorithm_replacements_to_code(
    code: str,
    replacements: list[dict[str, Any]],
) -> dict[str, Any]:
    result = inject_algorithms(code, replacements)

    return {
        "enabled": result.get("enabled", False),
        "code": result.get("modified_code", code),
        "modified_code": result.get("modified_code", code),
        "changes_made": result.get("changes_made", []),
        "replacement_reports": result.get("replacement_reports", []),
        "imports_added": result.get("imports_added", []),
        "imports_removed": result.get("imports_removed", []),
        "warnings": result.get("warnings", []),
    }


def apply_algorithm_replacements_to_file(
    file_path: str,
    replacements: list[dict[str, Any]],
    output_path: Optional[str] = None,
) -> dict[str, Any]:
    source_path = Path(file_path)
    source_code = source_path.read_text(encoding="utf-8")

    result = inject_algorithms(source_code, replacements)

    if output_path:
        target_path = Path(output_path)
    else:
        target_path = source_path.with_name(f"{source_path.stem}_modified.py")

    target_path.write_text(result["modified_code"], encoding="utf-8")

    return {
        "enabled": result.get("enabled", False),
        "input_file": str(source_path),
        "output_file": str(target_path),
        "modified_file_path": str(target_path),
        "changes_made": result.get("changes_made", []),
        "replacement_reports": result.get("replacement_reports", []),
        "imports_added": result.get("imports_added", []),
        "imports_removed": result.get("imports_removed", []),
        "warnings": result.get("warnings", []),
    }


def inject_algorithm_replacements(
    original_code: str,
    replacements: list[dict[str, Any]],
) -> dict[str, Any]:
    return inject_algorithms(original_code, replacements)


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 4:
        print("Usage : python algorithm_injector.py <source.py> <output.py> <replacements.json>")
        sys.exit(1)

    source_code = Path(sys.argv[1]).read_text(encoding="utf-8")
    output_file = sys.argv[2]
    replacements = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))

    result = inject_algorithms(source_code, replacements)

    Path(output_file).write_text(result["modified_code"], encoding="utf-8")

    print(json.dumps({
        "output_file": output_file,
        "enabled": result["enabled"],
        "changes_made": result["changes_made"],
        "replacement_reports": result["replacement_reports"],
        "imports_added": result["imports_added"],
        "imports_removed": result["imports_removed"],
        "warnings": result["warnings"],
    }, indent=2, ensure_ascii=False))