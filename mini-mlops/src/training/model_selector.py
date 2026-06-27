def select_best_model(results: list, config: dict):
    primary_metric = config["metrics"]["primary_metric"]
    primary_mode = config["metrics"]["primary_mode"]

    if primary_mode == "min":
        best = min(results, key=lambda x: x["metrics"][primary_metric])
    elif primary_mode == "max":
        best = max(results, key=lambda x: x["metrics"][primary_metric])
    else:
        raise ValueError(f"Mode de sélection invalide : {primary_mode}")

    return best