# tools/test_algorithm_replacement.py

from pathlib import Path

from src.custom_models.algorithm_replacement import (
    replace_algorithm_in_source,
    print_replacement_report,
)


INPUT_FILE = Path("train_student_performance_regressor.py")
OUTPUT_FILE = Path("modified_train_student_performance_regressor.py")

TARGET_ALGORITHM = "GradientBoostingRegressor"
# Tu peux remplacer par :
# TARGET_ALGORITHM = "XGBRegressor"
# TARGET_ALGORITHM = "ExtraTreesRegressor"
# TARGET_ALGORITHM = "HistGradientBoostingRegressor"


def main():
    if not INPUT_FILE.exists():
        raise FileNotFoundError(f"Input file not found: {INPUT_FILE.resolve()}")

    source_code = INPUT_FILE.read_text(encoding="utf-8")

    modified_code, report = replace_algorithm_in_source(
        source_code=source_code,
        target_algorithm=TARGET_ALGORITHM,
    )

    OUTPUT_FILE.write_text(modified_code, encoding="utf-8")

    print_replacement_report(report)
    print(f"\nModified file generated: {OUTPUT_FILE.resolve()}")


if __name__ == "__main__":
    main()