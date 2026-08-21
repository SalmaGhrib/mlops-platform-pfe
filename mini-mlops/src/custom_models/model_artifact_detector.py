# -*- coding: utf-8 -*-
"""
Detection generique, robuste et retrocompatible des artefacts de modeles produits
par les scripts d'entrainement Custom Docker de la plateforme MLOps.

CONTRAT DE SORTIE UNIQUE
========================
La plateforme fournit au script utilisateur un repertoire de sortie officiel :

    OUTPUT_DIR=/workspace/outputs      (variable d'environnement + --output_dir)

Le script peut toutefois enregistrer son modele dans n'importe quelle location
standard (retrocompatibilite totale) :

    /outputs
    /app/outputs
    /workspace/outputs
    /opt/ml/model
    ./outputs
    ./models
    ./artifacts
    ./model
    ./saved_model
    ./checkpoints
    /output, /models, /artifacts, /model, /saved_model, /checkpoints
    /app/model, /app/models, /app/artifacts, ...

Formats supportes (detection recursive) :

    .pkl .joblib .pickle .sav        (scikit-learn / pickle / joblib)
    .pt .pth .torchscript            (PyTorch / TorchScript)
    .keras .h5                       (Keras / TensorFlow)
    .onnx                            (ONNX)
    .cbm .bst .ubj                   (CatBoost / XGBoost / LightGBM)

Regles garanties
================
1. Resolution des chemins reels (realpath) et deduplication par identifiant de
   fichier (device+inode) puis par empreinte SHA-256 en secours.
2. Un meme fichier accessible par plusieurs alias/chemins n'est detecte qu'une
   seule fois ([MODEL_ARTIFACT_DUPLICATE_SKIPPED]).
3. L'artefact doit etre non vide et lisible avant enregistrement ; la validite
   reelle (loadable) est confirmee au moment du log/registration.
4. Selection deterministe si plusieurs artefacts distincts sont trouves.
5. Canonicalisation : copie de l'artefact choisi vers
   workspace/outputs/platform_model/ avant l'enregistrement MLflow.
6. Logs structures : [MODEL_ARTIFACT_SEARCH], [MODEL_ARTIFACT_FOUND],
   [MODEL_ARTIFACT_CANONICALIZED], [MODEL_ARTIFACT_DUPLICATE_SKIPPED].

Ce module ne contient AUCUNE dependance lourde (pas de mlflow/joblib) afin
d'etre testable unitairement et reutilisable par le runner et le standardizer.
"""

from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple


SUPPORTED_MODEL_EXTENSIONS: Set[str] = {
    ".pkl",
    ".joblib",
    ".pickle",
    ".sav",
    ".pt",
    ".pth",
    ".torchscript",
    ".keras",
    ".h5",
    ".onnx",
    ".cbm",
    ".bst",
    ".ubj",
}

PREFERRED_MODEL_NAMES: Tuple[str, ...] = (
    "model.pkl",
    "model.joblib",
    "model.pickle",
    "model.sav",
    "model.pt",
    "model.pth",
    "model.keras",
    "model.h5",
    "model.onnx",
    "model.cbm",
    "model.bst",
    "model.ubj",
    "trained_model.pkl",
    "trained_model.joblib",
    "trained_model.pt",
    "trained_model.pth",
    "final_model.pkl",
    "final_model.joblib",
    "best_model.pkl",
    "best_model.joblib",
    "pipeline.pkl",
    "pipeline.joblib",
)

# Extensions qui representent presque toujours un vrai modele predictif.
PRIMARY_CAPABLE_EXTENSIONS: Set[str] = {
    ".pkl",
    ".joblib",
    ".pickle",
    ".sav",
    ".pt",
    ".pth",
    ".torchscript",
    ".keras",
    ".h5",
    ".onnx",
}

IGNORED_DIR_PARTS: Set[str] = {
    "__pycache__",
    ".git",
    ".venv",
    "venv",
    "env",
    ".env",
    "site-packages",
    "node_modules",
    ".ipynb_checkpoints",
    "mlruns",
    "logs",
    "mlartifacts",
    ".cache",
    ".pytest_cache",
}

AUXILIARY_ARTIFACT_KEYWORDS: Tuple[str, ...] = (
    "scaler",
    "standardscaler",
    "minmaxscaler",
    "encoder",
    "label_encoder",
    "onehot",
    "preprocessor",
    "preprocess",
    "vectorizer",
    "tokenizer",
    "classes",
    "metadata",
    "metrics",
    "report",
    "tokenizer",
)

MODEL_PATH_HINT_REGEX = re.compile(
    r"(?im)^\s*(?:model_path|model-file|model_file|model\s*path)\s*=\s*(?P<path>.+?)\s*$"
)

MIN_ARTIFACT_SIZE_BYTES = 1

# Racines standards absolues (retrocompatibilite avec les scripts existants).
# Patchable par les tests via STANDARD_ABS_ROOTS.
STANDARD_ABS_ROOTS: Tuple[Path, ...] = (
    Path("/opt/ml/model"),
    Path("/outputs"),
    Path("/output"),
    Path("/models"),
    Path("/artifacts"),
    Path("/model"),
    Path("/saved_model"),
    Path("/checkpoints"),
    Path("/app/model"),
    Path("/app/models"),
    Path("/app/outputs"),
    Path("/app/output"),
    Path("/app/artifacts"),
    Path("/app/artifact"),
    Path("/app/saved_model"),
    Path("/app/checkpoints"),
)


def _clean_env_value(value: Optional[str]) -> str:
    value = str(value or "").strip()
    if value.lower() in {"", "none", "null", "undefined", "-"}:
        return ""
    return value


def _env_paths(names: Tuple[str, ...]) -> List[Path]:
    paths: List[Path] = []
    for name in names:
        raw = _clean_env_value(os.environ.get(name))
        if raw:
            paths.append(Path(raw))
    return paths


def _realpath(path: Path) -> Path:
    try:
        return Path(os.path.realpath(str(path)))
    except Exception:
        return path.absolute()


def file_sha256(path: Path, chunk_size: int = 1024 * 1024) -> str:
    """Empreinte SHA-256 streamed d'un fichier."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def file_identity(path: Path) -> Optional[Tuple[int, int, int]]:
    """Identifiant (device, inode, size) permettant de dedupliquer les alias."""
    try:
        stat = path.stat()
        return (stat.st_dev, stat.st_ino, stat.st_size)
    except Exception:
        return None


def model_path_hint_from_stdout(stdout: Optional[str]) -> Optional[Path]:
    """
    Exploite l'indice 'model_path=...' imprime par le script utilisateur.
    Retourne le chemin en absolu si un indice est trouve, sinon None.
    """
    if not stdout:
        return None

    for line in stdout.splitlines():
        match = MODEL_PATH_HINT_REGEX.match(line)
        if not match:
            continue

        raw = str(match.group("path")).strip().strip("\"'")
        if not raw:
            continue

        path = _realpath(Path(raw))
        print(
            "[MODEL_ARTIFACT_SEARCH] model_path hint found in stdout: "
            f"{raw} -> {path}"
        )
        return path

    return None


def build_search_roots(workspace: Path) -> List[Path]:
    """
    Racines de recherche du contrat unique + locations standards tolerees.

    L'ordre n'a pas d'importance ici (la selection finale est deterministe),
    mais toutes les locations courantes d'un script Custom Docker sont couvertes.
    """
    workspace = _realpath(workspace)

    roots: List[Path] = list(STANDARD_ABS_ROOTS)

    roots.extend(
        [
            # Locations sous le workspace (contrat OUTPUT_DIR + relatifs).
            workspace / "outputs",
            workspace / "output",
            workspace / "artifacts",
            workspace / "artifact",
            workspace / "model",
            workspace / "models",
            workspace / "saved_model",
            workspace / "checkpoints",
            workspace,
        ]
    )

    # Repertoires configures par la plateforme.
    roots.extend(
        _env_paths(
            (
                "MODEL_OUTPUT_DIR",
                "ML_OUTPUT_DIR",
                "OUTPUT_DIR",
                "MLOPS_OUTPUT_DIR",
                "PLATFORM_OUTPUT_DIR",
                "MLOPS_DEFAULT_OUTPUT_DIR",
            )
        )
    )

    # Locations supplementaires acceptees (liste separee par des virgules).
    accepted = _clean_env_value(os.environ.get("MLOPS_ACCEPTED_MODEL_DIRS"))
    if accepted:
        roots.extend(
            Path(item.strip())
            for item in accepted.split(",")
            if item.strip()
        )

    unique: List[Path] = []
    seen: Set[str] = set()

    for root in roots:
        try:
            resolved = _realpath(root)
        except Exception:
            resolved = root.absolute()

        key = str(resolved)
        if key in seen:
            continue
        seen.add(key)
        unique.append(resolved)

    return unique


def _is_ignored_dir(path: Path) -> bool:
    return any(part in IGNORED_DIR_PARTS for part in path.parts)


def _is_auxiliary_model_artifact(path: Path) -> bool:
    name = path.name.lower()
    stem = path.stem.lower()

    if name in {
        "model.pkl",
        "model.joblib",
        "model.pickle",
        "model.sav",
        "model.pt",
        "model.pth",
        "model.keras",
        "model.h5",
        "model.onnx",
        "model.cbm",
        "model.bst",
        "model.ubj",
    }:
        return False

    return any(keyword in stem for keyword in AUXILIARY_ARTIFACT_KEYWORDS)


def _artifact_priority(path: Path) -> int:
    """
    Score deterministe (independant du temps courant).

    Priorites :
    1. repertoire 'outputs' (contrat) ;
    2. nom prefere (model.pkl, ...) ;
    3. extension primaire vs secondaire ;
    4. non-auxiliaire vs auxiliaire ;
    5. profondeur (chemin court prefere) ;
    6. tri lexical pour la stabilite.
    """
    path = _realpath(path)
    lower_parts = [part.lower() for part in path.parts]
    name = path.name.lower()
    stem = path.stem.lower()

    score = 0

    if "/opt/ml/model" in str(path).replace("\\", "/").lower():
        score += 100_000
    if "outputs" in lower_parts:
        score += 50_000
    elif "artifacts" in lower_parts:
        score += 30_000
    elif "models" in lower_parts:
        score += 20_000
    elif "model" in lower_parts:
        score += 10_000

    if name in PREFERRED_MODEL_NAMES:
        score += 200_000

    if path.suffix.lower() in PRIMARY_CAPABLE_EXTENSIONS:
        score += 5_000

    if is_auxiliary := _is_auxiliary_model_artifact(path):
        score -= 20_000

    score -= len(path.parts) * 100

    return score


def _artifact_sort_key(path: Path):
    return (-_artifact_priority(path), str(_realpath(path)).lower())


def discover_model_artifacts(
    workspace: Path,
    stdout_hint: Optional[Path] = None,
    extra_roots: Optional[List[Path]] = None,
) -> List[Path]:
    """
    Decouvre recursivement les artefacts predictifs produits par le script.

    Deduplication :
    - par chemin reel (realpath) ;
    - par identifiant de fichier (device+inode) pour les alias / hardlinks ;
    - par empreinte SHA-256 en secours (contenu identique via chemins distincts).

    Retourne une liste triee par priorite deterministe, sans doublon.
    """
    workspace = _realpath(workspace)

    roots = build_search_roots(workspace)

    if stdout_hint is not None:
        hint = _realpath(stdout_hint)
        if hint not in roots:
            roots.append(hint)
        if hint.parent not in roots:
            roots.append(hint.parent)

    if extra_roots:
        for extra in extra_roots:
            resolved = _realpath(extra)
            if resolved not in roots:
                roots.append(resolved)

    print(
        "[MODEL_ARTIFACT_SEARCH] "
        f"workspace={workspace}, search_roots={len(roots)}"
    )

    seen_realpaths: Set[str] = set()
    seen_identities: Dict[Tuple[int, int, int], str] = {}
    candidates: List[Path] = []

    def register_candidate(path: Path) -> None:
        real = _realpath(path)
        real_key = str(real)

        if real_key in seen_realpaths:
            print(
                "[MODEL_ARTIFACT_DUPLICATE_SKIPPED] "
                f"same realpath: {path} (already {real})"
            )
            return

        identity = file_identity(real)
        if identity is not None:
            existing = seen_identities.get(identity)
            if existing is not None:
                print(
                    "[MODEL_ARTIFACT_DUPLICATE_SKIPPED] "
                    f"same file identity (dev/ino/size): {path} == {existing}"
                )
                return
            seen_identities[identity] = str(real)

        seen_realpaths.add(real_key)
        candidates.append(real)

    for root in roots:
        try:
            if not root.exists():
                continue

            paths: List[Path]
            if root.is_file():
                paths = [root]
            else:
                paths = list(root.rglob("*"))

            for path in paths:
                try:
                    if not path.is_file():
                        continue

                    if path.suffix.lower() not in SUPPORTED_MODEL_EXTENSIONS:
                        continue

                    if _is_ignored_dir(path):
                        continue

                    try:
                        if path.stat().st_size < MIN_ARTIFACT_SIZE_BYTES:
                            print(
                                "[MODEL_ARTIFACT_DUPLICATE_SKIPPED] empty artifact "
                                f"(size=0): {path}"
                            )
                            continue
                    except Exception:
                        continue

                    register_candidate(path)

                except Exception as exc:
                    print(
                        "[MODEL_ARTIFACT_SEARCH] could not inspect candidate "
                        f"{path}: {exc}"
                    )

        except Exception as exc:
            print(
                "[MODEL_ARTIFACT_SEARCH] could not scan model root "
                f"{root}: {exc}"
            )

    # Secours : deduplication par empreinte SHA-256 parmi les fichiers ayant
    # exactement la meme taille (evite de hasher des dizaines de fichiers).
    size_buckets: Dict[int, List[Path]] = {}
    for candidate in candidates:
        try:
            size = candidate.stat().st_size
        except Exception:
            continue
        size_buckets.setdefault(size, []).append(candidate)

    hash_seen: Set[str] = set()
    content_deduped: List[Path] = []

    for candidate in candidates:
        try:
            size = candidate.stat().st_size
        except Exception:
            content_deduped.append(candidate)
            continue

        bucket = size_buckets.get(size) or []
        if len(bucket) <= 1:
            content_deduped.append(candidate)
            continue

        digest = file_sha256(candidate)
        if digest in hash_seen:
            print(
                "[MODEL_ARTIFACT_DUPLICATE_SKIPPED] "
                f"same sha256={digest}: {candidate}"
            )
            continue
        hash_seen.add(digest)
        content_deduped.append(candidate)

    ordered = sorted(content_deduped, key=_artifact_sort_key)

    print(
        "[MODEL_ARTIFACT_SEARCH] discovered_candidates="
        f"{len(ordered)}"
    )
    for index, path in enumerate(ordered):
        print(
            f"[MODEL_ARTIFACT_FOUND] candidate_index={index + 1}, "
            f"path={path}, suffix={path.suffix.lower()}, "
            f"hash={file_sha256(path)}, bytes={path.stat().st_size}"
        )

    return ordered


def validate_artifact_file(path: Path) -> Tuple[bool, str]:
    """
    Verifie qu'un artefact est non vide, lisible et plausible avant enregistrement.

    Retourne (ok, raison).
    """
    try:
        real = _realpath(path)
    except Exception as exc:
        return False, f"cannot resolve path: {exc}"

    if not real.exists():
        return False, "file does not exist"

    if not real.is_file():
        return False, "not a regular file"

    try:
        size = real.stat().st_size
    except Exception as exc:
        return False, f"cannot stat file: {exc}"

    if size < MIN_ARTIFACT_SIZE_BYTES:
        return False, f"artifact is empty (size={size})"

    try:
        with real.open("rb") as handle:
            header = handle.read(256)
    except Exception as exc:
        return False, f"file is not readable: {exc}"

    suffix = real.suffix.lower()

    if suffix == ".onnx":
        # Le magic header ONNX est un protobuf commence par les octets
        # du MessageDelimited ; on verifie seulement qu'il n'est pas vide.
        if len(header) < 8:
            return False, "onnx artifact too small to be valid"

    if suffix in {".pt", ".pth"} and not header:
        return False, "pytorch artifact is empty"

    return True, "ok"


def select_primary_artifact(
    artifacts: List[Path],
    context: Optional[Dict[str, Any]] = None,
) -> Optional[Path]:
    """
    Selection deterministe de l'artefact principal.

    Regle : si plusieurs artefacts distincts sont trouves, on applique la
    priorite deterministe (repertoire outputs, nom prefere, extension,
    non-auxiliaire, profondeur, lexicographique). On n'enregistre JAMAIS
    plusieurs versions pour le meme run : un seul artefact est choisi.
    """
    context = context or {}

    if not artifacts:
        return None

    if len(artifacts) == 1:
        selected = artifacts[0]
        print(
            "[MODEL_ARTIFACT_FOUND] "
            f"runId={context.get('runId', '')}, "
            f"experimentId={context.get('experimentId', '')}, "
            f"registeredModelName={context.get('registeredModelName', '')}, "
            f"detected_path={selected}, "
            f"canonical_path=, hash={file_sha256(selected)}, "
            f"selection=only_candidate"
        )
        return selected

    ranked = sorted(artifacts, key=_artifact_sort_key)
    selected = ranked[0]

    print(
        "[MODEL_ARTIFACT_FOUND] "
        f"runId={context.get('runId', '')}, "
        f"experimentId={context.get('experimentId', '')}, "
        f"registeredModelName={context.get('registeredModelName', '')}, "
        f"detected_path={selected}, canonical_path=, "
        f"hash={file_sha256(selected)}, selection=deterministic_rank"
    )
    print(
        "[MODEL_ARTIFACT_SEARCH] multiple_distinct_artifacts="
        f"{len(artifacts)}, selected="
        + ", ".join(str(path) for path in artifacts[:10])
    )

    return selected


def canonicalize_artifact(
    path: Path,
    workspace: Path,
    context: Optional[Dict[str, Any]] = None,
) -> Tuple[Path, str]:
    """
    Copie l'artefact choisi vers l'emplacement canonique de la plateforme :
        workspace/outputs/platform_model/<original-name>

    Retourne (canonical_path, sha256). Si un fichier identique (meme empreinte)
    existe deja, on le reutilise sans doublon ([MODEL_ARTIFACT_DUPLICATE_SKIPPED]).
    """
    context = context or {}

    source = _realpath(path)

    if not source.exists() or not source.is_file():
        raise FileNotFoundError(f"Artifact not found for canonicalization: {source}")

    canonical_dir = (_realpath(workspace) / "outputs" / "platform_model").resolve()
    canonical_dir.mkdir(parents=True, exist_ok=True)

    digest = file_sha256(source)
    target = canonical_dir / source.name

    if target.exists():
        try:
            if target.stat().st_size == source.stat().st_size and file_sha256(target) == digest:
                print(
                    "[MODEL_ARTIFACT_DUPLICATE_SKIPPED] "
                    f"canonical copy already exists: {target}"
                )
                print(
                    "[MODEL_ARTIFACT_CANONICALIZED] "
                    f"runId={context.get('runId', '')}, "
                    f"experimentId={context.get('experimentId', '')}, "
                    f"registeredModelName={context.get('registeredModelName', '')}, "
                    f"detected_path={source}, canonical_path={target}, "
                    f"hash={digest}, reused=True"
                )
                return target, digest
        except Exception:
            pass

    if target.resolve() == source.resolve():
        # Le fichier est deja a l'emplacement canonique.
        print(
            "[MODEL_ARTIFACT_CANONICALIZED] "
            f"runId={context.get('runId', '')}, "
            f"experimentId={context.get('experimentId', '')}, "
            f"registeredModelName={context.get('registeredModelName', '')}, "
            f"detected_path={source}, canonical_path={source}, "
            f"hash={digest}, already_canonical=True"
        )
        return source, digest

    try:
        import shutil

        shutil.copy2(str(source), str(target))
    except Exception as exc:
        print(
            "[MODEL_ARTIFACT_SEARCH] canonicalization copy failed "
            f"for {source}: {exc}"
        )
        return source, digest

    print(
        "[MODEL_ARTIFACT_CANONICALIZED] "
        f"runId={context.get('runId', '')}, "
        f"experimentId={context.get('experimentId', '')}, "
        f"registeredModelName={context.get('registeredModelName', '')}, "
        f"detected_path={source}, canonical_path={target}, "
        f"hash={digest}, reused=False"
    )

    return target, digest
