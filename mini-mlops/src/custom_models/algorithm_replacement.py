# src/custom_models/algorithm_replacement.py

import ast
import copy
import importlib
import inspect
import itertools
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from src.custom_models.algorithm_registry import ALGORITHM_REGISTRY


@dataclass
class AlgorithmReplacementResult:
    original_algorithm: str
    new_algorithm: str
    old_params: Dict[str, Any]
    preserved_params: Dict[str, Any]
    removed_params: Dict[str, Any]
    added_params: Dict[str, Any]
    final_params: Dict[str, Any]
    tested_param_sets: List[Dict[str, Any]]
    import_statement: str
    model_code: str


def load_model_class(algorithm_name: str):
    if algorithm_name not in ALGORITHM_REGISTRY:
        raise ValueError(f"Unsupported algorithm: {algorithm_name}")

    config = ALGORITHM_REGISTRY[algorithm_name]
    module = importlib.import_module(config["import_path"])
    return getattr(module, config["class_name"])


def get_valid_init_params(model_class) -> set:
    signature = inspect.signature(model_class.__init__)
    return {
        name
        for name in signature.parameters.keys()
        if name != "self"
    }


def safe_literal_eval(node: ast.AST):
    try:
        return ast.literal_eval(node)
    except Exception:
        return None


def extract_model_params_from_call(call_node: ast.Call) -> Dict[str, Any]:
    params = {}

    for kw in call_node.keywords:
        if kw.arg is None:
            continue

        value = safe_literal_eval(kw.value)

        if value is not None:
            params[kw.arg] = value
        else:
            try:
                params[kw.arg] = ast.unparse(kw.value)
            except Exception:
                params[kw.arg] = None

    return params


def merge_params_keep_compatible(
    original_params: Dict[str, Any],
    new_algorithm: str,
) -> Tuple[Dict[str, Any], Dict[str, Any], Dict[str, Any], Dict[str, Any]]:
    """
    Règle professionnelle :
    - paramètres compatibles entre ancien et nouveau modèle : garder valeurs originales utilisateur
    - paramètres incompatibles : supprimer
    - paramètres nouveaux du nouvel algo : ajouter depuis default_params
    """

    model_class = load_model_class(new_algorithm)
    valid_params = get_valid_init_params(model_class)

    default_params = ALGORITHM_REGISTRY[new_algorithm].get("default_params", {})

    preserved_params = {}
    removed_params = {}

    for key, value in original_params.items():
        if key in valid_params:
            preserved_params[key] = value
        else:
            removed_params[key] = value

    added_params = {}

    for key, value in default_params.items():
        if key in valid_params and key not in preserved_params:
            added_params[key] = value

    final_params = {}
    final_params.update(preserved_params)
    final_params.update(added_params)

    return preserved_params, removed_params, added_params, final_params


def build_param_sets_for_testing(
    new_algorithm: str,
    final_params: Dict[str, Any],
) -> List[Dict[str, Any]]:
    """
    On ne modifie pas les paramètres originaux compatibles.
    On teste seulement les nouveaux paramètres indiqués dans extra_param_grid.
    """

    config = ALGORITHM_REGISTRY[new_algorithm]
    extra_grid = config.get("extra_param_grid", {})

    if not extra_grid:
        return [final_params]

    keys = list(extra_grid.keys())
    values_product = list(itertools.product(*[extra_grid[k] for k in keys]))

    param_sets = []

    for values in values_product:
        params = copy.deepcopy(final_params)

        for key, value in zip(keys, values):
            if key in params:
                params[key] = value

        param_sets.append(params)

    return param_sets or [final_params]


def format_python_value(value: Any) -> str:
    if isinstance(value, str):
        if value.startswith(("np.", "pd.", "sklearn.", "xgboost.")):
            return value
        return repr(value)

    return repr(value)


def build_model_code(new_algorithm: str, params: Dict[str, Any]) -> str:
    lines = [f"model = {new_algorithm}("]

    for key, value in params.items():
        lines.append(f"        {key}={format_python_value(value)},")

    lines.append("    )")

    return "\n".join(lines)


def build_import_statement(new_algorithm: str) -> str:
    config = ALGORITHM_REGISTRY[new_algorithm]
    return f"from {config['import_path']} import {config['class_name']}"


class ModelReplacementTransformer(ast.NodeTransformer):
    def __init__(
        self,
        target_algorithm: str,
        original_algorithm: Optional[str] = None,
        target_var_name: Optional[str] = None,
    ):
        self.target_algorithm = target_algorithm
        self.original_algorithm = original_algorithm
        self.target_var_name = target_var_name
        self.result: Optional[AlgorithmReplacementResult] = None

    def visit_ImportFrom(self, node: ast.ImportFrom):
        return node

    def visit_Assign(self, node: ast.Assign):
        self.generic_visit(node)

        if not isinstance(node.value, ast.Call):
            return node

        call = node.value

        if isinstance(call.func, ast.Name):
            original_algorithm = call.func.id
        elif isinstance(call.func, ast.Attribute):
            original_algorithm = call.func.attr
        else:
            return node

        if original_algorithm not in ALGORITHM_REGISTRY:
            return node

        target_names = []

        for target in node.targets:
            if isinstance(target, ast.Name):
                target_names.append(target.id)
            elif isinstance(target, ast.Attribute):
                target_names.append(target.attr)

        # Avant tu remplaçais seulement si la variable s'appelait exactement "model".
        # Maintenant on accepte model, clf, regressor, classifier, pipeline_model, etc.
        if self.target_var_name:
            if self.target_var_name not in target_names:
                return node
        else:
            accepted_names = {
                "model",
                "clf",
                "classifier",
                "regressor",
                "estimator",
                "final_model",
                "best_model",
                "pipeline_model",
            }

            if not any(name in accepted_names for name in target_names):
                # Si aucun nom classique, on autorise quand même si l'algo correspond.
                if self.original_algorithm and original_algorithm != self.original_algorithm:
                    return node

        if self.original_algorithm and original_algorithm != self.original_algorithm:
            return node

        old_params = extract_model_params_from_call(call)

        preserved_params, removed_params, added_params, final_params = merge_params_keep_compatible(
            original_params=old_params,
            new_algorithm=self.target_algorithm,
        )

        tested_param_sets = build_param_sets_for_testing(
            new_algorithm=self.target_algorithm,
            final_params=final_params,
        )

        selected_params = tested_param_sets[0]

        new_keywords = []

        for key, value in selected_params.items():
            if isinstance(value, (str, int, float, bool, type(None))):
                new_keywords.append(
                    ast.keyword(arg=key, value=ast.Constant(value=value))
                )

        new_call = ast.Call(
            func=ast.Name(id=self.target_algorithm, ctx=ast.Load()),
            args=[],
            keywords=new_keywords,
        )

        node.value = new_call

        self.result = AlgorithmReplacementResult(
            original_algorithm=original_algorithm,
            new_algorithm=self.target_algorithm,
            old_params=old_params,
            preserved_params=preserved_params,
            removed_params=removed_params,
            added_params=added_params,
            final_params=selected_params,
            tested_param_sets=tested_param_sets,
            import_statement=build_import_statement(self.target_algorithm),
            model_code=build_model_code(self.target_algorithm, selected_params),
        )

        return node


def ensure_import(source_code: str, import_statement: str) -> str:
    if import_statement in source_code:
        return source_code

    lines = source_code.splitlines()
    insert_index = 0

    for i, line in enumerate(lines):
        if line.startswith("import ") or line.startswith("from "):
            insert_index = i + 1

    lines.insert(insert_index, import_statement)

    return "\n".join(lines) + "\n"


def replace_algorithm_in_source(
    source_code: str,
    target_algorithm: str,
    original_algorithm: Optional[str] = None,
    target_var_name: Optional[str] = None,
) -> Tuple[str, AlgorithmReplacementResult]:
    if target_algorithm not in ALGORITHM_REGISTRY:
        raise ValueError(f"Unsupported target algorithm: {target_algorithm}")

    tree = ast.parse(source_code)

    transformer = ModelReplacementTransformer(
        target_algorithm=target_algorithm,
        original_algorithm=original_algorithm,
        target_var_name=target_var_name,
    )

    new_tree = transformer.visit(tree)

    if transformer.result is None:
        raise RuntimeError(
            "No supported model assignment found. "
            "Expected something like: model = RandomForestClassifier(...), "
            "clf = RandomForestClassifier(...), or regressor = RandomForestRegressor(...)."
        )

    ast.fix_missing_locations(new_tree)

    modified_code = ast.unparse(new_tree)
    modified_code = ensure_import(modified_code, transformer.result.import_statement)

    return modified_code, transformer.result


def print_replacement_report(result: AlgorithmReplacementResult):
    print("========== ALGORITHM REPLACEMENT REPORT ==========")
    print(f"Original algorithm: {result.original_algorithm}")
    print(f"New algorithm: {result.new_algorithm}")

    print("\nOld parameters:")
    for key, value in result.old_params.items():
        print(f"- {key}={value}")

    print("\nPreserved compatible parameters:")
    if result.preserved_params:
        for key, value in result.preserved_params.items():
            print(f"- {key}={value}")
    else:
        print("- None")

    print("\nRemoved incompatible parameters:")
    if result.removed_params:
        for key, value in result.removed_params.items():
            print(f"- {key}={value}")
    else:
        print("- None")

    print("\nAdded new/default parameters:")
    if result.added_params:
        for key, value in result.added_params.items():
            print(f"- {key}={value}")
    else:
        print("- None")

    print("\nFinal parameters used:")
    for key, value in result.final_params.items():
        print(f"- {key}={value}")

    print("\nNumber of tested parameter sets prepared:")
    print(len(result.tested_param_sets))

    print("=================================================")