# -*- coding: utf-8 -*-
"""
Tests unitaires du detecteur generique d'artefacts de modeles.

Couvre les scenarios demandes :
- /workspace/outputs/model.pkl, /outputs/model.pkl, /app/outputs/model.pkl,
  /opt/ml/model/model.pkl, chemin relatif outputs/model.pkl ;
- .joblib, PyTorch (.pt/.pth), Keras (.keras/.h5), ONNX (.onnx),
  CatBoost (.cbm), XGBoost (.bst), LightGBM (.ubj) ;
- plusieurs alias / hardlinks / contenus identiques vers le meme fichier ;
- absence reelle d'artefact ;
- artefact vide ou corrompu ;
- selection deterministe ;
- canonicalisation (copie + dedup).
"""

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[2]),
)

from custom_models import model_artifact_detector as det  # noqa: E402


@pytest.fixture()
def fake_abs_roots(monkeypatch, tmp_path):
    """
    Simule les racines standards absolues (/outputs, /app/outputs, ...)
    sous un repertoire temporaire afin de tester le pipeline complet.
    """
    root = tmp_path / "container"
    mapping = {
        "/opt/ml/model": root / "opt/ml/model",
        "/outputs": root / "outputs",
        "/output": root / "output",
        "/models": root / "models",
        "/artifacts": root / "artifacts",
        "/model": root / "model",
        "/saved_model": root / "saved_model",
        "/checkpoints": root / "checkpoints",
        "/app/model": root / "app/model",
        "/app/models": root / "app/models",
        "/app/outputs": root / "app/outputs",
        "/app/output": root / "app/output",
        "/app/artifacts": root / "app/artifacts",
        "/app/artifact": root / "app/artifact",
        "/app/saved_model": root / "app/saved_model",
        "/app/checkpoints": root / "app/checkpoints",
    }
    for real in mapping.values():
        real.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(det, "STANDARD_ABS_ROOTS", tuple(mapping.values()))
    return mapping


def _write(path: Path, content: bytes = b"model-payload"):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def _posix(path) -> str:
    return str(path).replace("\\", "/")


def _has_tail(posix: str, tail: str) -> bool:
    return posix == tail or posix.endswith("/" + tail)


class TestContract:
    def test_contract_includes_standard_locations(self):
        roots = [_posix(p) for p in det.build_search_roots(Path("/workspace"))]
        tails = [
            "outputs", "output", "models", "artifacts", "model",
            "saved_model", "checkpoints", "opt/ml/model",
            "app/outputs", "app/output", "app/models", "app/artifacts",
            "app/model", "app/saved_model", "app/checkpoints", "app/artifact",
            "workspace/outputs", "workspace/output", "workspace/artifacts",
            "workspace/model", "workspace/models", "workspace/saved_model",
            "workspace/checkpoints",
        ]
        for tail in tails:
            assert any(_has_tail(p, tail) for p in roots), tail

    def test_contract_dedups_identical_roots(self):
        roots = det.build_search_roots(Path("/workspace"))
        keys = [str(p) for p in roots]
        assert len(keys) == len(set(keys))


class TestDetectionLocations:
    def test_workspace_outputs_model_pkl(self, tmp_path):
        _write(tmp_path / "outputs" / "model.pkl")
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1
        assert found[0].name == "model.pkl"

    def test_relative_outputs_model_pkl(self, tmp_path, monkeypatch):
        monkeypatch.chdir(tmp_path)
        _write(Path("outputs") / "model.pkl")
        found = det.discover_model_artifacts(Path("."))
        assert len(found) == 1
        assert found[0].name == "model.pkl"

    def test_root_outputs_model_pkl(self, fake_abs_roots):
        _write(fake_abs_roots["/outputs"] / "model.pkl")
        found = det.discover_model_artifacts(Path("/workspace"))
        assert any(_has_tail(_posix(p), "outputs/model.pkl") for p in found)

    def test_app_outputs_model_pkl(self, fake_abs_roots):
        _write(fake_abs_roots["/app/outputs"] / "model.pkl")
        found = det.discover_model_artifacts(Path("/workspace"))
        assert any(_has_tail(_posix(p), "app/outputs/model.pkl") for p in found)

    def test_opt_ml_model_pkl(self, fake_abs_roots):
        _write(fake_abs_roots["/opt/ml/model"] / "model.pkl")
        found = det.discover_model_artifacts(Path("/workspace"))
        assert any(_has_tail(_posix(p), "opt/ml/model/model.pkl") for p in found)

    def test_workspace_models_dir(self, tmp_path):
        _write(tmp_path / "models" / "model.pkl")
        found = det.discover_model_artifacts(tmp_path)
        assert any(_has_tail(_posix(p), "models/model.pkl") for p in found)

    def test_workspace_artifacts_dir(self, tmp_path):
        _write(tmp_path / "artifacts" / "model.pkl")
        found = det.discover_model_artifacts(tmp_path)
        assert any(_has_tail(_posix(p), "artifacts/model.pkl") for p in found)

    def test_root_models_dir(self, fake_abs_roots):
        _write(fake_abs_roots["/models"] / "model.pkl")
        found = det.discover_model_artifacts(Path("/workspace"))
        assert any(_has_tail(_posix(p), "models/model.pkl") for p in found)


class TestDetectionFormats:
    @pytest.mark.parametrize(
        "ext",
        [".pkl", ".joblib", ".pickle", ".sav", ".pt", ".pth", ".keras",
         ".h5", ".onnx", ".cbm", ".bst", ".ubj"],
    )
    def test_supported_extension_detected(self, tmp_path, ext):
        _write(tmp_path / "outputs" / ("model" + ext))
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1
        assert found[0].suffix.lower() == ext

    def test_unsupported_extension_ignored(self, tmp_path):
        _write(tmp_path / "outputs" / "model.txt")
        _write(tmp_path / "outputs" / "model.csv")
        assert det.discover_model_artifacts(tmp_path) == []

    def test_recursive_nested_model(self, tmp_path):
        _write(tmp_path / "outputs" / "sub" / "nested" / "model.pkl")
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1


class TestDeduplication:
    def test_hardlink_dedup(self, tmp_path):
        original = _write(tmp_path / "outputs" / "model.pkl")
        alias = tmp_path / "models" / "model.pkl"
        alias.parent.mkdir(parents=True, exist_ok=True)
        os.link(str(original), str(alias))
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1

    def test_symlink_dedup(self, tmp_path):
        if not hasattr(os, "symlink"):
            pytest.skip("symlink not supported")
        original = _write(tmp_path / "outputs" / "model.pkl")
        alias = tmp_path / "artifacts" / "model.pkl"
        alias.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.symlink(str(original), str(alias))
        except (OSError, NotImplementedError):
            pytest.skip("symlink not permitted")
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1

    def test_same_content_hash_dedup(self, tmp_path):
        _write(tmp_path / "outputs" / "model.pkl", b"identical-bytes")
        _write(tmp_path / "models" / "model.pkl", b"identical-bytes")
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 1

    def test_distinct_content_not_deduped(self, tmp_path):
        _write(tmp_path / "outputs" / "model.pkl", b"aaa")
        _write(tmp_path / "models" / "model.pkl", b"bbb")
        found = det.discover_model_artifacts(tmp_path)
        assert len(found) == 2


class TestValidation:
    def test_absent_artifact_returns_empty(self, tmp_path):
        assert det.discover_model_artifacts(tmp_path) == []

    def test_empty_artifact_rejected(self, tmp_path):
        _write(tmp_path / "outputs" / "model.pkl", b"")
        found = det.discover_model_artifacts(tmp_path)
        assert found == []

    def test_validate_empty_file(self, tmp_path):
        path = _write(tmp_path / "model.pkl", b"")
        ok, reason = det.validate_artifact_file(path)
        assert not ok
        assert "empty" in reason

    def test_validate_missing_file(self, tmp_path):
        ok, reason = det.validate_artifact_file(tmp_path / "missing.pkl")
        assert not ok

    def test_validate_ok(self, tmp_path):
        path = _write(tmp_path / "model.pkl", b"payload")
        ok, _ = det.validate_artifact_file(path)
        assert ok

    def test_validate_onnx_too_small(self, tmp_path):
        path = _write(tmp_path / "model.onnx", b"tiny")
        ok, reason = det.validate_artifact_file(path)
        assert not ok
        assert "onnx" in reason


class TestHintAndSelection:
    def test_model_path_hint(self):
        hint = det.model_path_hint_from_stdout(
            "model_path=/outputs/model.pkl\naccuracy=0.9\n"
        )
        assert hint is not None
        assert str(hint).endswith("model.pkl")

    def test_model_path_hint_absent(self):
        assert det.model_path_hint_from_stdout("no hint here") is None

    def test_hint_used_as_search_root(self, tmp_path):
        hint_file = _write(tmp_path / "custom" / "saved.pkl")
        hint = det.model_path_hint_from_stdout(
            f"model_path={hint_file}\n"
        )
        found = det.discover_model_artifacts(tmp_path, stdout_hint=hint)
        assert any(str(p) == str(hint_file) for p in found)

    def test_select_primary_prefers_outputs(self, fake_abs_roots):
        _write(fake_abs_roots["/outputs"] / "model.pkl", b"outputs-content")
        _write(fake_abs_roots["/models"] / "other.pkl", b"models-content")
        found = det.discover_model_artifacts(Path("/workspace"))
        assert len(found) == 2
        selected = det.select_primary_artifact(found)
        assert _has_tail(_posix(selected), "outputs/model.pkl")

    def test_select_primary_prefers_named_model(self, tmp_path):
        _write(tmp_path / "outputs" / "model.pkl")
        _write(tmp_path / "outputs" / "zzz.pkl")
        found = det.discover_model_artifacts(tmp_path)
        selected = det.select_primary_artifact(found)
        assert selected.name == "model.pkl"

    def test_select_primary_none(self):
        assert det.select_primary_artifact([]) is None


class TestCanonicalization:
    def test_canonicalize_copies_to_platform_model_dir(self, tmp_path):
        source = _write(tmp_path / "outputs" / "model.pkl")
        target, digest = det.canonicalize_artifact(source, tmp_path)
        assert target.exists()
        assert target.parent.name == "platform_model"
        assert digest == det.file_sha256(source)

    def test_canonicalize_reuses_identical_copy(self, tmp_path):
        source = _write(tmp_path / "outputs" / "model.pkl", b"payload")
        first, d1 = det.canonicalize_artifact(source, tmp_path)
        second, d2 = det.canonicalize_artifact(source, tmp_path)
        assert d1 == d2
        assert first == second

    def test_canonicalize_already_canonical(self, tmp_path):
        source = _write(tmp_path / "outputs" / "platform_model" / "model.pkl")
        target, _ = det.canonicalize_artifact(source, tmp_path)
        assert target == source
