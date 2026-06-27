import tempfile
from pathlib import Path
from typing import Any, Dict, Optional


def create_inline_workspace(config: Dict[str, Any]) -> Path:
    """
    Recrée le projet modèle dans un dossier temporaire du pod Kubeflow.

    Exemple :
    custom_code:
      source_type: inline
      entrypoint: train.py
      files:
        train.py: |
          import os
          ...
    """

    workspace = Path(tempfile.mkdtemp(prefix="inline_custom_model_"))

    files = config["custom_code"]["files"]

    for filename, content in files.items():
        file_path = workspace / filename
        file_path.parent.mkdir(parents=True, exist_ok=True)
        file_path.write_text(content, encoding="utf-8")

    return workspace


def create_inline_dataset(config: Dict[str, Any], workspace: Path) -> Optional[Path]:
    """
    Recrée le dataset uploadé si présent.

    Pour cette V1 :
    - pas de dataset : data.source_type = none
    - dataset uploadé : data.source_type = inline
    """

    data_cfg = config.get("data", {})
    source_type = data_cfg.get("source_type", "none")

    if source_type in ["none", None, ""]:
        return None

    if source_type != "inline":
        raise ValueError(
            "For V1 custom dataset, data.source_type must be inline or none."
        )

    filename = data_cfg.get("filename") or "dataset.csv"
    content = data_cfg.get("content")

    if content is None:
        raise ValueError("data.content is required when data.source_type=inline")

    dataset_path = workspace / filename
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text(content, encoding="utf-8")

    return dataset_path