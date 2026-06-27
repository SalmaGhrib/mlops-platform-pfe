# src/custom_models/algorithm_registry.py

ALGORITHM_REGISTRY = {
    # ============================================================
    # REGRESSION MODELS
    # ============================================================

    "RandomForestRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.ensemble",
        "class_name": "RandomForestRegressor",
        "default_params": {
            "n_estimators": 140,
            "max_depth": 8,
            "min_samples_leaf": 4,
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {},
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "GradientBoostingRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.ensemble",
        "class_name": "GradientBoostingRegressor",
        "default_params": {
            "n_estimators": 200,
            "learning_rate": 0.05,
            "max_depth": 3,
            "min_samples_leaf": 3,
            "random_state": 42,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "HistGradientBoostingRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.ensemble",
        "class_name": "HistGradientBoostingRegressor",
        "default_params": {
            "max_iter": 300,
            "learning_rate": 0.05,
            "max_leaf_nodes": 31,
            "random_state": 42,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "max_leaf_nodes": [15, 31, 63],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "ExtraTreesRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.ensemble",
        "class_name": "ExtraTreesRegressor",
        "default_params": {
            "n_estimators": 300,
            "max_depth": 10,
            "min_samples_leaf": 2,
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {
            "n_estimators": [100, 200, 300],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "AdaBoostRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.ensemble",
        "class_name": "AdaBoostRegressor",
        "default_params": {
            "n_estimators": 120,
            "learning_rate": 0.05,
            "random_state": 42,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "n_estimators": [80, 120, 200],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "DecisionTreeRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.tree",
        "class_name": "DecisionTreeRegressor",
        "default_params": {
            "max_depth": 8,
            "min_samples_leaf": 4,
            "random_state": 42,
        },
        "extra_param_grid": {
            "max_depth": [4, 6, 8, 10],
            "min_samples_leaf": [2, 4, 6],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "KNeighborsRegressor": {
        "problem_type": "regression",
        "import_path": "sklearn.neighbors",
        "class_name": "KNeighborsRegressor",
        "default_params": {
            "n_neighbors": 5,
            "weights": "distance",
        },
        "extra_param_grid": {
            "n_neighbors": [3, 5, 7],
            "weights": ["uniform", "distance"],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "SVR": {
        "problem_type": "regression",
        "import_path": "sklearn.svm",
        "class_name": "SVR",
        "default_params": {
            "kernel": "rbf",
            "C": 1.0,
            "epsilon": 0.1,
        },
        "extra_param_grid": {
            "C": [0.5, 1.0, 2.0],
            "epsilon": [0.05, 0.1, 0.2],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "LinearRegression": {
        "problem_type": "regression",
        "import_path": "sklearn.linear_model",
        "class_name": "LinearRegression",
        "default_params": {},
        "extra_param_grid": {},
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "Ridge": {
        "problem_type": "regression",
        "import_path": "sklearn.linear_model",
        "class_name": "Ridge",
        "default_params": {
            "alpha": 1.0,
            "random_state": 42,
        },
        "extra_param_grid": {
            "alpha": [0.1, 1.0, 10.0],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "Lasso": {
        "problem_type": "regression",
        "import_path": "sklearn.linear_model",
        "class_name": "Lasso",
        "default_params": {
            "alpha": 0.1,
            "random_state": 42,
            "max_iter": 5000,
        },
        "extra_param_grid": {
            "alpha": [0.01, 0.1, 1.0],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "ElasticNet": {
        "problem_type": "regression",
        "import_path": "sklearn.linear_model",
        "class_name": "ElasticNet",
        "default_params": {
            "alpha": 0.1,
            "l1_ratio": 0.5,
            "random_state": 42,
            "max_iter": 5000,
        },
        "extra_param_grid": {
            "alpha": [0.01, 0.1, 1.0],
            "l1_ratio": [0.2, 0.5, 0.8],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "XGBRegressor": {
        "problem_type": "regression",
        "import_path": "xgboost",
        "class_name": "XGBRegressor",
        "default_params": {
            "n_estimators": 300,
            "max_depth": 4,
            "learning_rate": 0.05,
            "subsample": 0.9,
            "colsample_bytree": 0.9,
            "objective": "reg:squarederror",
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "max_depth": [3, 4, 5],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    "LGBMRegressor": {
        "problem_type": "regression",
        "import_path": "lightgbm",
        "class_name": "LGBMRegressor",
        "default_params": {
            "n_estimators": 300,
            "learning_rate": 0.05,
            "max_depth": -1,
            "num_leaves": 31,
            "random_state": 42,
            "n_jobs": -1,
            "verbose": -1,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "num_leaves": [15, 31, 63],
        },
        "metric_direction": {
            "mae": "min",
            "rmse": "min",
            "r2": "max",
            "r2_score": "max",
        },
    },

    # ============================================================
    # CLASSIFICATION MODELS
    # ============================================================

    "RandomForestClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.ensemble",
        "class_name": "RandomForestClassifier",
        "default_params": {
            "n_estimators": 140,
            "max_depth": 8,
            "min_samples_leaf": 4,
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {},
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "GradientBoostingClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.ensemble",
        "class_name": "GradientBoostingClassifier",
        "default_params": {
            "n_estimators": 200,
            "learning_rate": 0.05,
            "max_depth": 3,
            "min_samples_leaf": 3,
            "random_state": 42,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "ExtraTreesClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.ensemble",
        "class_name": "ExtraTreesClassifier",
        "default_params": {
            "n_estimators": 300,
            "max_depth": 10,
            "min_samples_leaf": 2,
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {
            "n_estimators": [100, 200, 300],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "AdaBoostClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.ensemble",
        "class_name": "AdaBoostClassifier",
        "default_params": {
            "n_estimators": 120,
            "learning_rate": 0.05,
            "random_state": 42,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "n_estimators": [80, 120, 200],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "DecisionTreeClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.tree",
        "class_name": "DecisionTreeClassifier",
        "default_params": {
            "max_depth": 8,
            "min_samples_leaf": 4,
            "random_state": 42,
        },
        "extra_param_grid": {
            "max_depth": [4, 6, 8, 10],
            "min_samples_leaf": [2, 4, 6],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "KNeighborsClassifier": {
        "problem_type": "classification",
        "import_path": "sklearn.neighbors",
        "class_name": "KNeighborsClassifier",
        "default_params": {
            "n_neighbors": 5,
            "weights": "distance",
        },
        "extra_param_grid": {
            "n_neighbors": [3, 5, 7],
            "weights": ["uniform", "distance"],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "SVC": {
        "problem_type": "classification",
        "import_path": "sklearn.svm",
        "class_name": "SVC",
        "default_params": {
            "kernel": "rbf",
            "C": 1.0,
            "probability": True,
            "random_state": 42,
        },
        "extra_param_grid": {
            "C": [0.5, 1.0, 2.0],
            "kernel": ["rbf", "linear"],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "LogisticRegression": {
        "problem_type": "classification",
        "import_path": "sklearn.linear_model",
        "class_name": "LogisticRegression",
        "default_params": {
            "max_iter": 1000,
            "random_state": 42,
        },
        "extra_param_grid": {
            "C": [0.5, 1.0, 2.0],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "GaussianNB": {
        "problem_type": "classification",
        "import_path": "sklearn.naive_bayes",
        "class_name": "GaussianNB",
        "default_params": {},
        "extra_param_grid": {},
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "XGBClassifier": {
        "problem_type": "classification",
        "import_path": "xgboost",
        "class_name": "XGBClassifier",
        "default_params": {
            "n_estimators": 300,
            "max_depth": 4,
            "learning_rate": 0.05,
            "subsample": 0.9,
            "colsample_bytree": 0.9,
            "eval_metric": "logloss",
            "random_state": 42,
            "n_jobs": -1,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "max_depth": [3, 4, 5],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },

    "LGBMClassifier": {
        "problem_type": "classification",
        "import_path": "lightgbm",
        "class_name": "LGBMClassifier",
        "default_params": {
            "n_estimators": 300,
            "learning_rate": 0.05,
            "max_depth": -1,
            "num_leaves": 31,
            "random_state": 42,
            "n_jobs": -1,
            "verbose": -1,
        },
        "extra_param_grid": {
            "learning_rate": [0.1, 0.05, 0.03],
            "num_leaves": [15, 31, 63],
        },
        "metric_direction": {
            "accuracy": "max",
            "precision": "max",
            "recall": "max",
            "f1": "max",
            "f1_score": "max",
        },
    },
}


def get_algorithm_config(algorithm_name: str):
    return ALGORITHM_REGISTRY.get(algorithm_name)


def is_supported_algorithm(algorithm_name: str) -> bool:
    return algorithm_name in ALGORITHM_REGISTRY


def get_supported_algorithms_by_problem_type(problem_type: str):
    clean_type = (problem_type or "").strip().lower()

    return {
        name: config
        for name, config in ALGORITHM_REGISTRY.items()
        if config.get("problem_type") == clean_type
    }


def get_import_statement(algorithm_name: str) -> str:
    config = ALGORITHM_REGISTRY[algorithm_name]
    return f"from {config['import_path']} import {config['class_name']}"