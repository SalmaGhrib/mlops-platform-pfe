from pathlib import Path

from kfp import compiler, dsl
from kfp.dsl import ContainerSpec


# Évite latest quand tu veux un comportement stable.
# Change v1/v2/v3 uniquement quand tu rebuild/push l'image runtime.
STANDARD_RUNTIME_IMAGE = "docker.io/salmaghrib2003/mini-mlops-runtime:v1"


@dsl.container_component
def run_custom_model_component(config_yaml: str):
    return ContainerSpec(
        image=STANDARD_RUNTIME_IMAGE,
        command=["python", "-c"],
        args=[
            """
from pathlib import Path
import subprocess
import sys

config_yaml = sys.argv[1]

config_path = Path("/tmp/custom_config.yaml")
config_path.write_text(config_yaml, encoding="utf-8")

print("[KFP CUSTOM PIPELINE] config written to:", config_path)
print("[KFP CUSTOM PIPELINE] launching src.custom_models.custom_main")

subprocess.run(
    [
        sys.executable,
        "-m",
        "src.custom_models.custom_main",
        "--config",
        str(config_path),
    ],
    check=True,
)
            """,
            config_yaml,
        ],
    )


@dsl.pipeline(
    name="custom-model-training-registry-pipeline",
    description=(
        "Train custom uploaded model, support TABULAR/IMAGE/TEXT data types, "
        "track metrics in MLflow, register model, version it, and promote production alias."
    ),
)
def custom_model_pipeline(config_yaml: str):
    task = run_custom_model_component(config_yaml=config_yaml)
    task.set_caching_options(False)


if __name__ == "__main__":
    output_path = Path("kubeflow/compiled/custom_model_pipeline.yaml")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    compiler.Compiler().compile(
        pipeline_func=custom_model_pipeline,
        package_path=str(output_path),
    )

    print(f"Pipeline custom compilée avec succès : {output_path}")