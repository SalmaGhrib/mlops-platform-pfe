import argparse
from pathlib import Path

import yaml


def is_custom_inline_config(config_path: str) -> bool:
    path = Path(config_path)

    if not path.exists():
        raise FileNotFoundError(f"Fichier config introuvable : {config_path}")

    with open(path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f) or {}

    custom_code = config.get("custom_code", {})
    source_type = custom_code.get("source_type")
    files = custom_code.get("files")

    return source_type == "inline" and isinstance(files, dict) and len(files) > 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Chemin du fichier YAML")
    parser.add_argument(
        "--mode",
        choices=["auto", "classic", "custom"],
        default="auto",
        help="auto = détecte automatiquement, classic = ancien pipeline, custom = modèle custom"
    )

    args = parser.parse_args()

    if args.mode == "classic":
        print("Mode sélectionné : classic")
        from src.core.pipeline_runner import run_pipeline
        run_pipeline(args.config)
        return

    if args.mode == "custom":
        print("Mode sélectionné : custom")
        from src.custom_models.custom_runner import run_custom_model_pipeline
        run_custom_model_pipeline(args.config)
        return

    if is_custom_inline_config(args.config):
        print("Mode détecté automatiquement : custom")
        from src.custom_models.custom_runner import run_custom_model_pipeline
        run_custom_model_pipeline(args.config)
    else:
        print("Mode détecté automatiquement : classic")
        from src.core.pipeline_runner import run_pipeline
        run_pipeline(args.config)


if __name__ == "__main__":
    main()