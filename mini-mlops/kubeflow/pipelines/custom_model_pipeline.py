from pathlib import Path

from kfp import compiler, dsl
from kfp.dsl import ContainerSpec


STANDARD_RUNTIME_IMAGE = (
    "acrmlopssalmav2.azurecr.io/mini-mlops:sync-artifacts-final-v9"
)


COMPONENT_SCRIPT = r"""
import hashlib
import os
import subprocess
import sys
from pathlib import Path

import requests
import yaml


def download_dataset(config: dict) -> None:
    data = config.get("data")

    if not isinstance(data, dict):
        print("[KFP] No valid dataset configuration.")
        return

    source_type = str(data.get("source_type") or "none").strip().lower()

    if source_type == "none":
        print("[KFP] No dataset configured.")
        return

    if source_type == "path":
        dataset_path = Path(str(data.get("path") or "")).expanduser()

        if not dataset_path.is_file():
            raise FileNotFoundError(
                f"Configured dataset path does not exist: {dataset_path}"
            )

        os.environ["DATASET_PATH"] = str(dataset_path.resolve())
        print(f"[KFP] Dataset already available at {dataset_path}")
        return

    if source_type != "url":
        raise ValueError(
            "Unsupported data.source_type. Expected one of: none, path, url. "
            f"Received: {source_type}"
        )

    url = str(data.get("url") or "").strip()
    expected_sha256 = str(data.get("sha256") or "").strip().lower()
    filename = Path(
        str(
            data.get("filename")
            or data.get("dataset_name")
            or "dataset.csv"
        )
    ).name

    if not url:
        raise ValueError("data.source_type='url' but data.url is missing")

    input_dir = Path("/tmp/mlops_input")
    input_dir.mkdir(parents=True, exist_ok=True)
    dataset_path = input_dir / filename

    print("[KFP] Downloading dataset from pipeline-gateway")
    print(f"[KFP] URL={url}")
    print(f"[KFP] destination={dataset_path}")

    digest = hashlib.sha256()

    try:
        with requests.get(url, stream=True, timeout=(10, 300)) as response:
            response.raise_for_status()

            with dataset_path.open("wb") as output:
                for chunk in response.iter_content(chunk_size=1024 * 1024):
                    if not chunk:
                        continue

                    output.write(chunk)
                    digest.update(chunk)

    except requests.RequestException as exc:
        dataset_path.unlink(missing_ok=True)
        raise RuntimeError(f"Dataset download failed: {exc}") from exc

    if not dataset_path.is_file() or dataset_path.stat().st_size <= 0:
        dataset_path.unlink(missing_ok=True)
        raise ValueError("Downloaded dataset is missing or empty")

    actual_sha256 = digest.hexdigest().lower()

    if expected_sha256 and actual_sha256 != expected_sha256:
        dataset_path.unlink(missing_ok=True)
        raise ValueError(
            "Dataset SHA-256 verification failed: "
            f"expected={expected_sha256}, actual={actual_sha256}"
        )

    data.clear()
    data.update(
        {
            "source_type": "path",
            "dataset_name": filename,
            "filename": filename,
            "path": str(dataset_path),
            "sha256": actual_sha256,
            "size_bytes": dataset_path.stat().st_size,
        }
    )

    os.environ["DATASET_PATH"] = str(dataset_path)
    os.environ["DATA_PATH"] = str(dataset_path)
    os.environ["MLOPS_DATASET_PATH"] = str(dataset_path)

    print(f"[KFP] Dataset ready: {dataset_path}")
    print(f"[KFP] SHA-256={actual_sha256}")


def validate_config(config: dict) -> None:
    if not isinstance(config, dict):
        raise ValueError("config_yaml must decode to a mapping")

    required = ("project_name", "model", "run", "tracking", "custom_code")
    missing = [name for name in required if name not in config]

    if missing:
        raise ValueError(
            "Missing required configuration fields: " + ", ".join(missing)
        )


def main() -> None:
    if len(sys.argv) < 2 or not str(sys.argv[1]).strip():
        raise ValueError("config_yaml argument is missing or empty")

    try:
        config = yaml.safe_load(sys.argv[1]) or {}
    except yaml.YAMLError as exc:
        raise ValueError(f"Invalid YAML configuration: {exc}") from exc

    validate_config(config)
    download_dataset(config)

    config_path = Path("/tmp/custom_config.yaml")
    config_path.write_text(
        yaml.safe_dump(
            config,
            sort_keys=False,
            allow_unicode=True,
        ),
        encoding="utf-8",
    )

    command = [
        sys.executable,
        "-m",
        "src.custom_models.custom_main",
        "--config",
        str(config_path),
    ]

    print(f"[KFP] Config written to {config_path}")
    print("[KFP] Launching: " + " ".join(command))

    completed = subprocess.run(command, check=False)

    if completed.returncode != 0:
        raise RuntimeError(
            f"Training runner failed with exit code {completed.returncode}"
        )

    print("[KFP] Training completed successfully")


if __name__ == "__main__":
    main()
"""


@dsl.container_component
def run_custom_model_component(config_yaml: str):
    return ContainerSpec(
        image=STANDARD_RUNTIME_IMAGE,
        command=["python", "-c"],
        args=[COMPONENT_SCRIPT, config_yaml],
    )


@dsl.pipeline(
    name="custom-model-training-registry-pipeline",
    description=(
        "Download the uploaded dataset from the gateway, train the model, "
        "log metrics in MLflow and register the model version."
    ),
)
def custom_model_pipeline(config_yaml: str):
    task = run_custom_model_component(config_yaml=config_yaml)
    task.set_caching_options(False)


def get_output_path() -> Path:
    current_file = Path(__file__).resolve()
    mini_mlops_root = current_file.parents[2]

    return (
        mini_mlops_root
        / "kubeflow"
        / "compiled"
        / "custom_model_pipeline.yaml"
    )


def compile_pipeline() -> Path:
    output_path = get_output_path()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    compiler.Compiler().compile(
        pipeline_func=custom_model_pipeline,
        package_path=str(output_path),
    )

    if not output_path.is_file():
        raise FileNotFoundError(
            f"Compiled pipeline was not generated: {output_path}"
        )

    return output_path


if __name__ == "__main__":
    compiled_path = compile_pipeline()
    print(f"Pipeline compiled successfully: {compiled_path}")
    print(f"Runtime image included: {STANDARD_RUNTIME_IMAGE}")