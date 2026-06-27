import argparse
import json
import traceback

from src.custom_models.custom_runner import run_custom_model_pipeline


def main():
    """
    Point d'entrée du runner custom Kubeflow.

    Il reçoit un fichier YAML via --config,
    puis lance le pipeline custom standard.

    Le YAML peut maintenant contenir :
    - data_type: TABULAR / IMAGE / TEXT
    - task_type: classification / regression / sentiment_analysis / translation / qa / llm_finetuning / custom
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Chemin du YAML custom")
    args = parser.parse_args()

    try:
        result = run_custom_model_pipeline(args.config)

        print("CUSTOM_MODEL_RUNNER_RESULT_START")
        print(json.dumps(result, indent=2, default=str, ensure_ascii=False))
        print("CUSTOM_MODEL_RUNNER_RESULT_END")

    except Exception as exc:
        print("CUSTOM_MODEL_RUNNER_ERROR_START")
        print(str(exc))
        traceback.print_exc()
        print("CUSTOM_MODEL_RUNNER_ERROR_END")
        raise


if __name__ == "__main__":
    main()