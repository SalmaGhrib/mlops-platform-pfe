# Diagnostic & Architecture — Plateforme MLOps (2026-08-12)

## 1. Périmètre

Refonte en profondeur de la plateforme vers une architecture générique, flexible,
robuste et extensible. Objectif final opérationnel : **introduire un nouveau
modèle dans la plateforme SANS toucher au code backend** — le runner, l'analyse
des artefacts, l'enregistrement MLflow et le retraining doivent s'adapter seuls.

## 2. État initial (audit DRY-RUN, non modifiant)

### 2.1 Fichiers uploadés (racine de nombreux bugs)

- `uploaded_project_files` ne distinguait pas les rôles : un script Python de
  préparation était stocké `file_type=DATASET` (projet 35 : `prepare_dataset.py`
  en DATASET, projet 4/31/etc. identiques par paquets DATASET+MODEL_FILE).
- `MODEL_FILE` hérité = script d'entraînement uniquement.
- Absence de `file_extension` sur beaucoup d'enregistrements (détection
  d'archive/dossier impossible à partir de la seule base).

### 2.2 Retraining

- Notions temporelles mélangées : `next_retraining_at` servait de rendez-vous
  global (périmés, ex. 2026-08-11 17:18 vs 2026-08-12), `last_monitoring_check_at`
  figé au 2026-08-01/10.
- `state` incohérents (RUNNING/IDLE) faute de état-fin de cycle persisté.
- Pas de politique de source de données (`dataPolicy`) → le retraining reprenait
  toujours le dataset d'origine même si un dataset plus récent était fourni.

### 2.3 Déploiements / MLflow

- `model_deployments` avec statuts dégradés (`OUT_OF_SYNC`) ; versions multiples
  d'un même modèle alignées sur des images k8s distinctes.
- Le runner chargeait les `.pkl` via `pickle` et tentait un pyfunc en premier,
  sans détection de framework standard (sklearn/joblib/bundle).
- Pas de déduplication par hash : mêmes artefacts enregistrés en versions MLflow
  multiples pour une même sortie réelle.

## 3. Corrections apportées

### 3.1 Rôles de fichiers explicites (#1, #5)

`UploadedFileType` enrichi :
`TRAINING_SCRIPT`, `DATASET_FILE`, `DATASET_ARCHIVE`, `DATASET_PREPARATION_SCRIPT`,
`DOCKERFILE`, `REQUIREMENTS`, `AUXILIARY` + legacy (`MODEL_FILE`, `DATASET`,
`IMAGE_FILE`, `TEXT_FILE`, `OTHER`).

- Nouvelle colonne `uploaded_project_files.file_role` (index
  `idx_uploaded_files_role(project_id,file_role)`).
- **Migration `migrations/009-file-roles.sql`** (idempotente MySQL 8.0) : backfill
  des anciens enregistrements à partir de la trace persistée (file_type +
  extension) — aucune décision runtime :
  - `DATASET` + `.py/.py3` → `DATASET_PREPARATION_SCRIPT`
  - `DATASET` + zip/tar/gz/tgz/7z/rar → `DATASET_ARCHIVE`
  - `DATASET` + plat ou NULL → `DATASET_FILE`
  - `MODEL_FILE` + `.py` → `TRAINING_SCRIPT`
- **Résultat migration appliquée en base (MySQL pod)** : 53 fichiers → 27
  `TRAINING_SCRIPT`, 26 `DATASET_FILE`. Projet 35 : 4 `DATASET_FILE` + 4
  `TRAINING_SCRIPT`.

### 3.2 Contrat dataset (#2, #3, #4)

- Upload : nouveau paramètre `dataset_role` sur `POST /api/ml/experiments/upload`
  (`DATASET_FILE`/`DATASET_ARCHIVE`/`DATASET_PREPARATION_SCRIPT`/`AUXILIARY`/
  `DOCKERFILE`/`REQUIREMENTS`).
- Résolution déclarée (prioritaire) sinon dérivation sûre par extension :
  `deriveDatasetRoleFromExtension()` (`py/py3`→prep, archives→ARCHIVE, sinon
  FILE). Le script d'entraînement est toujours persiste en `TRAINING_SCRIPT`.
- Le runner matérialise (ZIP/dossier + scripts `PLATFORM_DATASET_PATH`) et valide
  la structure réelle (tableau plat, images, texte) : marqueurs
  `[DATASET_SOURCE_DETECTED]`, `[DATASET_PREPARATION_STARTED/COMPLETED]`,
  `[DATASET_PATH_RESOLVED]`, `[DATASET_VALIDATED]`, `[DATASET_MATERIALIZED]`.

### 3.3 Artefacts & enregistrement MLflow (#6–#11)

`try_log_pickle_model` réécrit dans `platform_runner.py` :
- `.joblib` → `joblib.load` d'abord ; `.pkl` → `pickle.load` puis fallback joblib.
- **Bundle** : dict contenant un prédicteur + clés compagnons
  (`labels/classes/image_size/preprocessor/feature_extractor/feature_names/
  target/metadata/scaler/encoder/tokenizer/vectorizer/...`) → extraction de
  l'estimateur, conservation du bundle comme `bundle_metadata.json`.
- `detect_estimator_framework()` : xgboost/lightgbm/catboost/keras/tensorflow/
  torch/onnx/sklearn/pyfunc via `__class__.__module__` ; `predict` ou callable →
  pyfunc.
- `mlflow.sklearn` si sklearn (avec `metadata`), sinon pyfunc + artifact bundle.
- Marqueurs `[MODEL_FORMAT_DETECTED]`, `[MODEL_FRAMEWORK_DETECTED]`,
  `[MODEL_OBJECT_LOADED]`, `[MODEL_ESTIMATOR_EXTRACTED]`,
  `[MODEL_REGISTRATION_STARTED/COMPLETED/FAILED]`.
- `ModelArtifactDetector` : déduplication par `realpath + sha256`.

### 3.4 STANDARD vs CUSTOM_DOCKERFILE (#12, #13)

- Whitelist de ports/modèles par mode (inchangée, déjà correcte).
- La politique de dépendances reste séparée : STANDARD = environnement pickle
  embarqué, CUSTOM_DOCKERFILE = image personnalisée avec ses dépendances — le
  runner ne force aucune réinstallation.

### 3.5 Retraining : machine d'état et sources (#14–#21, #24–#26)

- `RetrainingConfig` enrichi :
  - `next_monitoring_check_at` (contrôle périodique monitoring, distinct de
    `next_retraining_at`),
  - `data_policy` (`RetrainingDataPolicy` : `ORIGINAL_DATASET`/
    `LATEST_DATASET`/`MERGED_DATASET`), défaut `ORIGINAL_DATASET`.
  - Helpers de planification (`calculateNextMonitoringCheckDate`,
    `scheduleNextMonitoringCheckFrom`) + lifecycle.
- **Migration `migrations/010-retraining-fields.sql`** (idempotente MySQL 8.0),
  appliquée en base : 11 configs ont reçu une prochaine échéance de monitoring.
- **Nouveau DTO unique de résumé** `RetrainingSummaryDTO` :
  `projectId/enabled/strategy/state/lastRetrainingAt/nextRetrainingAt/
  lastMonitoringCheckAt/nextMonitoringCheckAt/lastTriggerReason/running/
  cooldownUntil/frequencyLabel`.
  - Endpoint `GET /api/retraining/projects/{projectId}/summary`.
  - **Frontend = base** : `my-projects` lit ce DTO (`getRetrainingSummary`) et
    n'affiche plus de libellés/date calculés localement en dur
    (`getRetrainingSectionTitle` par stratégie, `getRetrainingNextDateHint` par
    stratégie + champs persistés).
  - Formulaires : `dataPolicy` ajouté au Request DTO et au formulaire (sélecteur
    « Source des données pour le retraining », #21).

### 3.6 Scheduler distribué (#27–#30)

`AutoRetrainingScheduler` vérifié :
- `@SchedulerLock("autoRetrainingScheduler")` + garde locale
  `schedulerRunning` (pas de double cycle sur le même pod).
- Claim par config en transaction REQUIRES_NEW + isolation READ_COMMITTED
  (`tryValidateAndClaimInNewTransaction`) → **un seul runner** traite chaque
  config (single catch-up), rejet des doublons multi-pods garanti par la ligne
  `state=RUNNING` + `releaseAfterFailure` (+5 min) et `resetStuckRunningConfigs`
  (30 min) pour les crashes.
- Aucun double déclenchement : la planification se base exclusivement sur les
  champs persistés.

## 4. Tests

`mini-mlops/src/custom_models/tests/test_platform_runner_artifacts.py` :
- `TestFrameworkDetection` (sklearn/torch/keras/pyfunc/xgboost…),
- `TestSafeJsonable`,
- `TestBundleMetadataExtraction` (bundle complet → extraction + metadata),
- **28 passed / 0 failed** après correction du pickling joblib (classe au niveau
  module, pas locale).

Backend : `mvnw.cmd compile` = **EXIT 0**.
Frontend : `ng build` = **OK** (seules des warnings NG8102/NG8107 pré-existants
sur `user-monitoring`).

## 5. Scénario de validation (à refaire)

1. Créer/ouvrir un projet, uploader : script d'entraînement, dataset réel
   (CSV/archive/images), éventuellement script de préparation `prepare_dataset.py`.
   Vérifier en base : `file_role` correct pour chacun.
2. Lancer l'entraînement (STANDARD) : observer les marqueurs dataset + modèle.
3. Vérifier MLflow : version enregistrée avec framework détecté + metadata bundle.
4. Configurer le retraining (`FULL_SMART`, `data_policy=LATEST_DATASET`),
   relancer l'upload d'un nouveau dataset → revalidation auto à la prochaine
   échéance de monitoring.
5. Code source inchangé entre les étapes 1→5 : preuve de la généricité.

## 6. Flux cible (par activité)

| Étape | Acteur | Décision | Source de vérité |
|---|---|---|---|
| Upload | UI | rôle du fichier (déclaré/fallback) | `file_role` (base) |
| Préparation dataset | runner | `PLATFORM_DATASET_PATH` | script prep / structure |
| Matérialisation | runner | archive ou dossier | `[DATASET_MATERIALIZED]` |
| Entraînement | runner/script | — | script utilisateur |
| Détection artefact | runner | sha256+realpath | fichiers sortie |
| Enregistrement MLflow | runner | framework détecté | `ModelRegistrationService` |
| État retraining | scheduler | champs persistés | `RetrainingConfig` |
| Résumé UI | frontend | `summary` DTO | base (jamais calcul local) |