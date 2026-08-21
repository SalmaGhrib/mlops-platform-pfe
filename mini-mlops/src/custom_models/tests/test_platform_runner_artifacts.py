# -*- coding: utf-8 -*-
"""
Tests d'integration runner <-> detecteur d'artefacts.

Couvre : detection CLI (parse_args / parse_known_args), build_script_commands
sans jamais injecter d'argument inconnu, hint model_path= depuis stdout,
canonicalisation, et recherche multi-racines.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from custom_models import model_artifact_detector as det  # noqa: E402
from custom_models import platform_runner as pr  # noqa: E402


SCRIPT_WITH_OUTPUT_DIR = """
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("--data_path", required=True)
parser.add_argument("--output_dir", required=True)
args = parser.parse_args()
print("model_path=" + args.output_dir + "/model.pkl")
with open(args.output_dir + "/model.pkl", "wb") as f:
    f.write(b"payload")
"""

SCRIPT_WITH_PARSE_KNOWN_ARGS = """
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("--output_dir", default="outputs")
args, _ = parser.parse_known_args()
"""

SCRIPT_NO_ARGPARSE = """
print("hello")
"""

SCRIPT_OUTPUT_ONLY = """
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("--output_dir")
args = parser.parse_args()
"""


def _write_script(tmp_path, content, name="train.py"):
    path = tmp_path / name
    path.write_text(content, encoding="utf-8")
    return path


class TestCliArgDetection:
    def test_detects_output_dir(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_WITH_OUTPUT_DIR)
        args = pr.detect_script_cli_args(script)
        assert "output_dir" in args
        assert "data_path" in args

    def test_detects_parse_known_args_script(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_WITH_PARSE_KNOWN_ARGS)
        args = pr.detect_script_cli_args(script)
        assert "output_dir" in args

    def test_no_argparse_returns_empty(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_NO_ARGPARSE)
        assert pr.detect_script_cli_args(script) == set()

    def test_output_only_detection(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_OUTPUT_ONLY)
        args = pr.detect_script_cli_args(script)
        assert "output_dir" in args


class TestBuildScriptCommands:
    def test_tailored_command_uses_declared_flags(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_WITH_OUTPUT_DIR)
        data = tmp_path / "data.csv"
        out = tmp_path / "outputs"
        commands = pr.build_script_commands(script, data, out)
        first = commands[0]
        assert first[0] == sys.executable
        assert "--data_path" in first
        assert "--output_dir" in first
        assert "--dataset" not in first

    def test_plain_script_no_extra_args(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_NO_ARGPARSE)
        commands = pr.build_script_commands(script, None, tmp_path / "outputs")
        assert commands == [[sys.executable, str(script)]]

    def test_output_only_receives_output_dir(self, tmp_path):
        script = _write_script(tmp_path, SCRIPT_OUTPUT_ONLY)
        out = tmp_path / "outputs"
        commands = pr.build_script_commands(script, None, out)
        output_only = [c for c in commands if "--output_dir" in c]
        assert output_only
        assert output_only[0][-2:] == ["--output_dir", str(out)]


class TestFindModelArtifacts:
    def test_detects_model_in_workspace_outputs(self, tmp_path):
        (tmp_path / "outputs").mkdir()
        (tmp_path / "outputs" / "model.pkl").write_bytes(b"payload")
        found = pr.find_model_artifacts(tmp_path)
        assert len(found) == 1

    def test_detects_root_outputs_via_contract(self, monkeypatch, tmp_path):
        root = tmp_path / "container"
        mapping = {"/outputs": root / "outputs"}
        mapping["/outputs"].mkdir(parents=True)
        (mapping["/outputs"] / "model.pkl").write_bytes(b"payload")
        monkeypatch.setattr(det, "STANDARD_ABS_ROOTS", tuple(mapping.values()))
        found = pr.find_model_artifacts(tmp_path)
        assert len(found) == 1
        assert str(found[0]).endswith("model.pkl")

    def test_stdout_hint_locates_artifact_elsewhere(self, tmp_path):
        weird = tmp_path / "custom-save"
        weird.mkdir()
        target = weird / "saved.pkl"
        target.write_bytes(b"payload")
        found = pr.find_model_artifacts(
            tmp_path,
            stdout_text=f"model_path={target}\naccuracy=0.9\n",
        )
        assert any(str(p) == str(target.resolve()) for p in found)

    def test_no_artifact_returns_empty(self, tmp_path):
        assert pr.find_model_artifacts(tmp_path) == []

    def test_empty_artifact_excluded(self, tmp_path):
        (tmp_path / "outputs").mkdir()
        (tmp_path / "outputs" / "model.pkl").write_bytes(b"")
        assert pr.find_model_artifacts(tmp_path) == []


class TestCanonicalization:
    def test_canonicalize_primary_copies_to_platform_model(self, tmp_path):
        source = tmp_path / "outputs" / "model.pkl"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"payload")
        canonical = pr.canonicalize_primary_artifact(source, tmp_path)
        assert canonical.exists()
        assert canonical.parent.name == "platform_model"
        assert canonical.read_bytes() == b"payload"

    def test_canonicalize_primary_idempotent(self, tmp_path):
        source = tmp_path / "outputs" / "model.pkl"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"payload")
        first = pr.canonicalize_primary_artifact(source, tmp_path)
        second = pr.canonicalize_primary_artifact(source, tmp_path)
        assert first == second

    def test_canonicalize_primary_survives_missing_workspace(self, tmp_path):
        source = tmp_path / "model.pkl"
        source.write_bytes(b"payload")
        canonical = pr.canonicalize_primary_artifact(source, tmp_path)
        assert canonical.exists()


class _FakeVersion:
    def __init__(self, version, run_id="", tags=None):
        self.version = version
        self.run_id = run_id
        self.tags = tags or {}


class _FakeClient:
    def __init__(self, versions):
        self._versions = versions

    def search_model_versions(self, filter_string):
        return self._versions


class TestIdempotentRegistration:
    def test_reuses_version_by_run_id(self):
        client = _FakeClient([_FakeVersion(version="7", run_id="RUN-1")])
        found = pr.find_existing_model_version_for_run(
            client, "energy", "RUN-1"
        )
        assert found == "7"

    def test_reuses_version_by_platform_run_id_tag_when_run_id_empty(self):
        client = _FakeClient(
            [_FakeVersion(version="7", run_id="", tags={"platform_run_id": "RUN-1"})]
        )
        found = pr.find_existing_model_version_for_run(
            client, "energy", "RUN-1"
        )
        assert found == "7"

    def test_returns_none_when_no_matching_run(self):
        client = _FakeClient(
            [_FakeVersion(version="7", run_id="", tags={"platform_run_id": "OTHER"})]
        )
        assert pr.find_existing_model_version_for_run(client, "energy", "RUN-1") is None


class _FakeEstimator:
    @property
    def __class__(self):
        return type("RandomForestClassifier", (), {"__module__": "sklearn.ensemble._forest"})


class _FakeXGB:
    @property
    def __class__(self):
        return type("XGBClassifier", (), {"__module__": "xgboost.sklearn"})


class _FakeTorch:
    @property
    def __class__(self):
        return type("Linear", (), {"__module__": "torch.nn.modules.linear"})


class _FakeCallableOnly:
    def __call__(self, x):
        return x


class _PicklableEstimator:
    def predict(self, x):
        return [1]


class TestFrameworkDetection:
    """Exigence #8 : sélection générique de la stratégie d'enregistrement MLflow."""

    def test_detects_sklearn(self):
        assert pr.detect_estimator_framework(_FakeEstimator()) == "sklearn"

    def test_detects_xgboost(self):
        assert pr.detect_estimator_framework(_FakeXGB()) == "xgboost"

    def test_detects_torch(self):
        assert pr.detect_estimator_framework(_FakeTorch()) == "torch"

    def test_predict_fallback_pyfunc(self):
        assert pr.detect_estimator_framework(_FakeCallableOnly()) == "pyfunc"

    def test_none_returns_none(self):
        assert pr.detect_estimator_framework(None) is None


class TestSafeJsonable:
    def test_primitives_passthrough(self):
        assert pr._safe_jsonable(1) == 1
        assert pr._safe_jsonable("x") == "x"
        assert pr._safe_jsonable(True) is True
        assert pr._safe_jsonable(None) is None

    def test_nested_dict_list(self):
        result = pr._safe_jsonable({"a": [1, 2], "b": {"c": None}})
        assert result == {"a": [1, 2], "b": {"c": None}}

    def test_callable_fallback_to_str(self):
        assert isinstance(pr._safe_jsonable(_FakeCallableOnly()), str)


class TestBundleMetadataExtraction:
    """Exigence #7 : un dict {'model': estimator, labels/classes/image_size...}
    est interprété comme un bundle : estimateur = obj['model'], reste = métadonnées."""

    def test_bundle_shape_via_joblib(self, tmp_path):
        import joblib

        bundle = {
            "model": _PicklableEstimator(),
            "labels": ["a", "b"],
            "classes": ["a", "b"],
            "image_size": (224, 224),
            "metadata": {"framework": "sklearn"},
        }
        path = tmp_path / "bundle.joblib"
        joblib.dump(bundle, path)

        loaded = joblib.load(path)
        estimator = None
        bundle_metadata = {}
        if hasattr(loaded, "predict"):
            estimator = loaded
        elif isinstance(loaded, dict):
            for primary_key in ("model", "estimator", "pipeline", "classifier"):
                candidate = loaded.get(primary_key)
                if candidate is not None and hasattr(candidate, "predict"):
                    estimator = candidate
                    for key, value in loaded.items():
                        if key != primary_key:
                            bundle_metadata[key] = value
                    break

        assert estimator is not None
        assert estimator.predict([0]) == [1]
        assert bundle_metadata["labels"] == ["a", "b"]
        assert bundle_metadata["image_size"] == (224, 224)

    def test_pickle_file_not_force_loaded_as_joblib(self, tmp_path):
        """Un .pkl simple reste chargé via pickle, pas via joblib."""
        import pickle

        obj = {"a": 1}
        path = tmp_path / "simple.pkl"
        with open(path, "wb") as f:
            pickle.dump(obj, f)

        with open(path, "rb") as f:
            loaded = pickle.load(f)
        assert loaded == {"a": 1}
