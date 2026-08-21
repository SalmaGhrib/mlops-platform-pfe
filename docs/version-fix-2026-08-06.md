# Correction : gestion des versions MLflow (1 run = 1 version)

Date : 2026-08-06
Statut : déployé et vérifié en production (images `version-fix-20260806-01`).

## 1. Symptômes observés

- 1 entraînement réussi produisait **plusieurs versions MLflow** (cas mesuré :
  le run `c6afd244b7804648b878b6a959873487` du modèle
  `energy_consumption_prediction22_model` a créé **21 versions fantômes** v3→v23).
- Le **premier** run d'un projet commençait parfois à **v2** au lieu de v1
  (une version fantôme créée avant le vrai run consommait le n°1).
- Le Model Registry, l'historique d'entraînement, la page des modèles déployés,
  la page de prédiction et les notifications affichaient des versions incohérentes.
- Un déploiement Kubernetes + une ligne `model_deployments` + une notification
  étaient créés pour **chaque version fantôme** (~1 toutes les 8-10 min).

## 2. Cause racine

Le script `standardize_model_artifact.py` appelait
`mlflow.log_model(..., registered_model_name=...)` à **chaque exécution**.
Ce script est relancé en boucle par 2 schedulers (~60 s) :

- `CustomDockerRunWatcher` (watcher des runs Docker)
- `AutoProductionDeploymentReconcilerService` (reconciler de production)

MLflow incrémente le numéro de version à chaque `log_model` → 1 version par cycle.

### Pourquoi les garde-fous ne s'activaient pas

1. `CustomDockerRunWatcher.isStandardizationAlreadyFinal()` et
   `AutoProductionDeploymentReconcilerService.isAlreadyPrepared()` détectaient la
   standardisation via des **marqueurs textuels dans `MlExperiment.message`**
   (`newversion=`, `[MODEL_ARTIFACT_STANDARDIZED_VERSION=...]`).
2. `MlflowTrackingClientService.syncSingleModelVersionToDatabase()` écrasait
   `message` avec « Synchronisation MLflow Registry terminée pour un run réussi. »
   → le marqueur disparaissait → le garde-fou redevenait `false` → re-standardisation.
3. `message` est tronqué à 3000 caractères.
4. `has_mlmodel_file()` ne cherchait le fichier `MLmodel` qu'à la racine de
   l'artefact ; un `MLmodel` imbriqué (`standardized_model/MLmodel`) n'était pas
   détecté → re-standardisation du même artefact.

## 3. Règle appliquée

- 1 run réussi = **exactement 1 version MLflow**.
- 1er run d'un modèle = **v1**, le suivant = **v2**, etc.
- **Aucun** scheduler / health-check / sync / reconciliation ne doit créer une
  version, un déploiement ou une notification **sans nouvel artefact**.
- Logique **idempotente et transactionnelle** : vérification d'existence dans
  MLflow AVANT tout `log_model`, verrous par expérience contre l'exécution
  concurrente, contraintes UNIQUE en base pour absorber les courses.

## 4. Fichiers corrigés (backend)

| Fichier | Méthodes clés | Correction |
|---|---|---|
| `mlops-backend/scripts/standardize_model_artifact.py` | `has_mlmodel_file`, `get_latest_version_for_run`, `main` | Détection récursive du `MLmodel` (ignore `__pycache__`, `.git`, `.venv`, `venv`, `env`, `site-packages`, `logs`) ; avant tout `log_model`, vérifie si le run possède déjà une version → réutilisation idempotente (retour `idempotent_reuse`, aucune nouvelle version). |
| `mlops-backend/mlops-backend/src/main/java/com/mlops/platform/service/ModelArtifactStandardizerService.java` | `standardizeIfNeeded`, `standardizeSafely` | Verrou `ConcurrentHashMap` par id d'expérience (2 schedulers simultanés) ; vérifie `findModelVersionByRunId` dans MLflow avant de lancer le script ; réutilise la version existante, ne crée rien. |
| `.../service/AutoProductionDeploymentReconcilerService.java` | `isAlreadyPrepared` | Ne dépend plus des marqueurs textuels : interroge le Registry MLflow (`findModelVersionByRunId`) + `versionsAligned`. |
| `.../service/CustomDockerRunWatcher.java` | `isStandardizationAlreadyFinal` | Vérifie d'abord le Registry MLflow ; fallback sur les anciens marqueurs. |
| `.../service/MlflowTrackingClientService.java` | `syncSingleModelVersionToDatabase`, `appendSafeMessage`, `limitMessage` | N'écrase plus `message` : ajoute sans détruire les marqueurs, borne à 3000 caractères. |
| `.../service/ModelDeploymentService.java` | `createDeploymentRecord` | Rattrape `DataIntegrityViolationException` (course sur la clé UNIQUE) → renvoie la ligne existante au lieu de planter/créer un doublon. |

### Copie du script Python

Le script corrigé existe en 3 copies identiques (hash
`507974F197AFDC5FB10EDE3B6ECBC2B72B20DE5F5C8C862CE24B185E84442E2A`) :
`mlops-backend/scripts/`, `mlops-backend/mlops-backend/scripts/`, `mini-mlops/scripts/`.
Le `Dockerfile` backend copie `mlops-backend/scripts/` → `/app/scripts/`.

## 5. Migration SQL appliquée

Fichier : `migrations/version-uniqueness-fix.sql` (appliquée, `MIGRATION_OK`).

- Normalisation `''` → `NULL` sur `ml_experiments(model_version, production_version,
  latest_version, registered_model_name)` et
  `model_deployments(model_version, registered_model_name)`.
- Dédoublonnage `ml_experiments` (garde la ligne production, sinon la plus petite id)
  et `model_deployments` (garde la ligne active, sinon la plus petite id).
- Ajout des contraintes UNIQUE :
  - `ml_experiments.uk_experiment_model_version (registered_model_name, model_version)`
  - `model_deployments.uk_deployment_model_version (registered_model_name, model_version)`
- Vérifié : `dup_check = 0`, `ml_experiments = 98`, `model_deployments = 74`,
  `notifications = 63`, modèles actifs = 6.
- Backup avant migration : pod MySQL `/tmp/backup_before_version_fix.sql`
  (1,4 Mo) et copie locale `backup_before_version_fix.sql`.

## 6. Frontend corrigé

Chaîne canonique de résolution de version appliquée partout :

- version production : `productionVersion → mlflowProductionVersion → modelVersion`
- version du run/modèle : `latestVersion → modelVersion → mlflowVersion`

| Fichier | Correction |
|---|---|
| `front-end/free-angular-tailwind-dashboard/src/app/pages/model-registry/model-registry.component.ts` | `getProductionVersion()` et `getModelVersion()` canoniques ; `mergeModelWithDeployment` priorise `mlflowProductionVersion`. |
| `.../model-registry/model-registry.component.html` | Cartes/badges via les helpers canoniques. |
| `.../accessible-models/accessible-models.component.ts` + `.html` | `getModelVersion()` canonique. |
| `.../core/services/prediction.service.ts` | `normalizeModel` (déjà aligné). |
| `.../upload-training/upload-training.component.ts` | `getModelVersion`/`getProductionVersion` (déjà alignés). |

## 7. Tests automatisés ajoutés

`mvnw -q test` : **31/32 verts** (l'unique échec, `MlopsBackendApplicationTests
.contextLoads`, est pré-existant : il exige MySQL `mysql-service.mlops.svc.cluster.local`,
injoignable hors cluster).

- `ModelArtifactStandardizerServiceTest` (6 tests) : réutilisation idempotente,
  pas d'appel au script si la version existe, version manquante renseignée,
  skip sans runId / sans registeredModelName, échec propre si MLflow KO + script
  absent, stabilité sur 2 cycles scheduler consécutifs.
- `ModelDeploymentCreateRecordTest` (3 tests) : réutilisation de la ligne existante,
  rattrapage de la course UNIQUE (`DataIntegrityViolationException`), rejet des
  arguments null.
- `MlflowTrackingClientAppendMessageTest` (3 tests) : le message de sync n'écrase
  plus les marqueurs de standardisation, idempotence, troncature à 3000.

## 8. Déploiement

Images poussées sur `acrmlopssalmav2.azurecr.io` :

- `mlops-backend:version-fix-20260806-01` (JAR + script Python corrigés)
- `mlops-frontend:version-fix-20260806-01` (bundle `main-JGFK6VRV.js`)

Manifests : `k8s/backend/backend-deployment.yaml`, `k8s/frontend/frontend-deployment.yaml`.
Rollout backend + frontend terminés, pod backend démarré sans erreur.

### Vérification de stabilité

Avant fix : `energy_consumption_prediction22_model` = 20 versions, croissantes.
Après déploiement : **23 versions stables** sur 2,5 min (2-3 cycles reconciler).
Les versions v21→v23 encore apparues juste après le roll-out provenaient de
l'ancien pod encore actif pendant le basculement.

## 9. Commandes de diagnostic K8s

```powershell
# Pods et santé
kubectl get pods -n mlops
kubectl rollout status deployment/mlops-backend -n mlops --timeout=300s
kubectl rollout status deployment/mlops-frontend -n mlops --timeout=300s

# Logs backend (standardiseur, reconciler, sync)
kubectl logs deployment/mlops-backend -n mlops --tail=200
kubectl logs deployment/mlops-backend -n mlops --since=10m | Select-String "MODEL_STANDARDIZER|reconcil|Synchronisation"

# Nombre de versions MLflow par modèle (stabilité)
kubectl exec -n mlops mlflow-6b89f5977d-jjpqs -- python /tmp/version_count.py

# Déploiements K8s d'un modèle
kubectl get deploy,svc -n mlops | Select-String "energy-consumption"

# Base : doublons / contraintes / états
kubectl exec -n mlops mysql-775f6f8c7b-7rfks -- bash -c "mysql -uroot -proot mlops_platform --table < /tmp/verify_fix.sql"
```

## 10. Procédure de nettoyage des versions fantômes (à exécuter manuellement)

> Décision : **rien n'a été supprimé** en production. Procédure documentée, à
> exécuter par l'utilisateur avec une fenêtre de maintenance.

État actuel du seul modèle pollué :

| Modèle | Versions MLflow | Référencées en base | Action |
|---|---|---|---|
| `energy_consumption_prediction22_model` | v1..v23 | v2 (expérience), v3..v23 (déploiements), **active = v23** | Supprimer MLflow v1 + v2 + v3..v22 ; garder v23 (production) |
| `couleurrr_model` (v1..v47) | toutes référencées | cohérent | rien |
| `steggg_model`, `diag_model`, `projet_tri_dechets_model`, `fistt_model` | toutes référencées | cohérents | rien |

### Étapes

1. **Backup MySQL**
   ```sql
   mysqldump -uroot -proot mlops_platform > /tmp/backup_avant_nettoyage.sql
   ```

2. **Supprimer les lignes de déploiement fantômes** (garder l'active v23)
   ```sql
   DELETE FROM model_deployments
   WHERE registered_model_name = 'energy_consumption_prediction22_model'
     AND model_version <> '23';
   ```

3. **Supprimer les versions MLflow fantômes** (API MLflow, garder v23)
   ```bash
   kubectl exec -n mlops mlflow-6b89f5977d-jjpqs -- python -c "
   import json, urllib.request, urllib.parse
   BASE='http://localhost:5000/api/2.0/mlflow'
   def post(path, body):
       req=urllib.request.Request(BASE+path, json.dumps(body).encode(), {'Content-Type':'application/json'})
       with urllib.request.urlopen(req, timeout=30) as r: return json.load(r)
   for v in range(1, 23):
       post('/model-versions/delete', {'name':'energy_consumption_prediction22_model','version':str(v)})
   print('ghost versions deleted')
   "
   ```

4. **Supprimer les ressources Kubernetes obsolètes** (garder v23)
   ```bash
   kubectl delete deployment energy-consumption-prediction22-model-v1-deployment -n mlops
   kubectl delete deployment energy-consumption-prediction22-model-v3-deployment -n mlops
   kubectl delete deployment energy-consumption-prediction22-model-v8-deployment -n mlops
   kubectl delete deployment energy-consumption-prediction22-model-v11-deployment -n mlops
   kubectl delete deployment energy-consumption-prediction22-model-v13-deployment -n mlops
   kubectl delete deployment energy-consumption-prediction22-model-v14-deployment -n mlops
   kubectl delete service energy-consumption-prediction22-model-v1-service -n mlops
   kubectl delete service energy-consumption-prediction22-model-v3-service -n mlops
   kubectl delete service energy-consumption-prediction22-model-v8-service -n mlops
   kubectl delete service energy-consumption-prediction22-model-v11-service -n mlops
   kubectl delete service energy-consumption-prediction22-model-v13-service -n mlops
   kubectl delete service energy-consumption-prediction22-model-v14-service -n mlops
   ```

5. **Vérifier** : le prochain entraînement de ce modèle créera exactement 1 version
   (v24), le déploiement actif restera v23, et aucun nouveau déploiement fantôme.

## 11. Vérification finale multi-modèles (2026-08-07)

Vérification factuelle effectuée ~11 h après le déploiement du fix (pod backend
`mlops-backend` démarré le 2026-08-06 19:28 UTC).

### 11.1 Preuve temporelle MLflow : AUCUNE nouvelle version depuis le fix

Dernière version créée par modèle (timestamp MLflow) vs cutoff = déploiement du
fix (2026-08-06 19:28 UTC) :

| Modèle | Versions MLflow | Dernière version créée | Versions créées après le fix |
|---|---|---|---|
| `couleurrr_model` | 47 | 2026-07-26 15:27 | **0** |
| `steggg_model` | 29 | 2026-07-28 14:26 | **0** |
| `fistt_model` | 3 | 2026-08-01 19:38 | **0** |
| `diag_model` | 2 | 2026-08-01 19:47 | **0** |
| `projet_tri_dechets_model` | 5 | 2026-08-01 19:51 | **0** |
| `energy_consumption_prediction22_model` | 23 | 2026-08-06 19:20 (v23) | **0** |

→ Le backend corrigé n'a créé **aucune** version MLflow en ~11 h (tous schedulers
confondus). Le comptage energy est resté stable à 23 (t1 → t2).

### 11.2 Déploiements créés par le backend corrigé

`model_deployments` créées après 19:28 UTC le 06/08 : **1 seule ligne** :

- `id=847`, `energy_consumption_prediction22_model` **v1**, créé le
  2026-08-07 **02:15** via l'API REST `POST /api/model-deployments/create`
  (`createDeploymentRecord` n'est appelé que par le contrôleur REST ; le
  reconciler passe par `autoDeployExperimentIfNeeded` qui déploie la
  `modelVersion` de l'expérience, ici 23).
- La ligne référence le run `d2519e39933a...` (celui de la **v1 historique**,
  sans aucune nouvelle version MLflow ni nouvel run).
- Le reconciler a réutilisé le deployment existant de la v23 (`deploymentId`
  identique) : il n'est pas à l'origine du 847.
- Conclusion : le 847 est une action d'appel REST isolée (déploiement manuel de
  l'ancienne v1), **pas** une régression du fix. Le déploiement K8s v1 qui en
  découle est sain mais obsolète (ajouté à la procédure de nettoyage, §10).

### 11.3 Audit base (MySQL)

| Vérification | Résultat |
|---|---|
| Contraintes UNIQUE | `uk_experiment_model_version` + `uk_deployment_model_version` présentes |
| Doublons `(model, version)` en base | **0** (`dup_check = 0`) |
| `ml_experiments` | 98 lignes, 87 paires distinctes |
| `model_deployments` | 76 lignes, **76/76 paires distinctes** |
| `production_flags` par modèle | **exactement 1** sur les 6 modèles |
| Expériences COMPLETED sans version | **0** |
| Déploiement actif par modèle | **exactement 1** (`active_count=1`, energy actif = v23) |
| Alias MLflow `@production` | aucun (standardizer déployé avec `--set_production_alias false`) |
| Reconciler logs | `Skipping standardization` + réutilisation de `deploymentId` identiques |

---

# Correction : contrat générique de détection des artefacts Custom Docker

Date : 2026-08-07
Statut : déployé et vérifié en production (images `artifact-fix-20260807-02`).

## 12. Symptôme

Un script Custom Docker a entraîné un modèle correctement mais l'a enregistré à la
racine du conteneur (`/outputs/model.pkl`), en dehors de `/workspace`. Le runner
cherchait principalement sous `/workspace`, `/app/outputs` et `/opt/ml/model` →
**aucun artefact trouvé → run marqué FAILED** alors que l'entraînement avait réussi.

## 13. Cause racine

- Détection d'artefacts **non générique** : racines de recherche figées et limitées,
  formats partiellement couverts, aucune déduplication ni validation.
- Aucun mécanisme de **hint** (`model_path=` imprimé par le script).
- Aucune **canonicalisation** : l'artefact était loggé depuis son emplacement
  d'origine (éventuellement éphémère) et le run échouait si ce chemin n'était pas connu.

## 14. Contrat de sortie unique (implémenté)

La plateforme fournit `OUTPUT_DIR=/workspace/outputs` (env + `--output_dir`), mais
**tolère** tout emplacement standard produit par le script :

| Emplacement | Couvert |
|---|---|
| `/workspace/outputs`, `/workspace/models`, `/workspace/artifacts`, `/workspace/model`, `/workspace/saved_model`, `/workspace/checkpoints`, `/workspace` (racine) | ✅ |
| `/outputs`, `/output`, `/models`, `/artifacts`, `/model`, `/saved_model`, `/checkpoints` (racine conteneur) | ✅ |
| `/app/outputs`, `/app/model`, `/app/models`, ... | ✅ |
| `/opt/ml/model` (compatibilité AKS/SageMaker) | ✅ |
| Env `MODEL_OUTPUT_DIR`, `ML_OUTPUT_DIR`, `OUTPUT_DIR`, `MLOPS_OUTPUT_DIR`, `PLATFORM_OUTPUT_DIR`, `MLOPS_DEFAULT_OUTPUT_DIR`, `MLOPS_ACCEPTED_MODEL_DIRS` (CSV) | ✅ |
| Hint `model_path=<chemin>` imprimé dans stdout | ✅ |

Formats : `.pkl .joblib .pickle .sav` (sklearn/pickle/joblib), `.pt .pth .torchscript`
(PyTorch), `.keras .h5` (Keras/TF), `.onnx` (ONNX), `.cbm .bst .ubj`
(CatBoost/XGBoost/LightGBM).

## 15. Mécanismes

1. **Détection** (`model_artifact_detector.py`, pur stdlib) : multi-racines +
   récursive, dédup par `realpath` → identité (dev/inode/size) → SHA-256, rejet des
   fichiers vides, sélection déterministe (priorité `/opt/ml/model` > `outputs` >
   `artifacts` > `models` > nom `model.*` > extension).
2. **Canonicalisation** : l'artefact retenu est copié vers
   `<workspace>/outputs/platform_model/` (réutilisée si hash identique) avant
   log/registration MLflow → chemin stable, indépendant de l'emplacement éphémère.
3. **CLI tolérante** (`build_script_commands`) : détection `ast` des arguments
   argparse déclarés ; la plateforme ne passe `--output_dir`/`--data_path` que s'ils
   existent → plus jamais d'échec « unrecognized arguments » ; `OUTPUT_DIR` toujours
   exporté en env ; `model_path=` extrait de stdout comme hint de recherche.
4. **1 run + 1 artefact = 1 version** : `register_platform_model_once` rejette une
   deuxième version pour un run déjà enregistré, désormais en comparant **aussi** le
   tag `platform_run_id` (certains serveurs MLflow renvoient `run_id` vide dans
   `search_model_versions`).
5. **Masquage FAILED** : un run `FAILED`/`CANCELLED` ne conserve aucune métrique
   métier ni score global (`markExperimentFailedWithDetails`, `CustomDockerRunWatcher.markFailed`)
   et `mapToResponse` les renvoie `null` ; l'UI affiche « Aucune métrique (run en
   échec) » + le message d'erreur au lieu des métriques/score.
6. **Logs structurés** : `[MODEL_ARTIFACT_SEARCH]`, `[MODEL_ARTIFACT_FOUND]`,
   `[MODEL_ARTIFACT_DUPLICATE_SKIPPED]`, `[MODEL_ARTIFACT_CANONICALIZED]` avec
   runId, chemins, hash.

## 16. Fichiers modifiés

- `mini-mlops/src/custom_models/model_artifact_detector.py` (**nouveau**) : détecteur générique.
- `mini-mlops/src/custom_models/platform_runner.py` : intégration détecteur + hint + canonicalisation + CLI tolérante + idempotence par tag.
- `mini-mlops/src/custom_models/custom_runner.py` : détection déléguée au détecteur.
- `mlops-backend/.../KubernetesBuildService.java` : injection de `model_artifact_detector.py` dans le build context configmap + init container kaniko.
- `mlops-backend/scripts/standardize_model_artifact.py` : extensions `.cbm/.bst/.ubj/.torchscript` + noms préférés alignés.
- `mlops-backend/.../MlExperimentService.java` + `CustomDockerRunWatcher.java` : purge des métriques/score sur FAILED ; `mapToResponse` masque les métriques.
- `front-end/.../upload-training.component.{html,ts}` + `model-registry.component.{html,ts}` : affichage conditionnel.
- `mini-mlops/src/custom_models/tests/test_model_artifact_detector.py` (**nouveau**) + `test_platform_runner_artifacts.py` (**nouveau**) : **60 tests** (57 unit + 3 idempotence).

## 17. Vérification en production (AKS, namespace `mlops`)

Job `artifact-contract-test-02` : script écrivant **`/outputs/model.pkl`** (racine
conteneur, scénario exact du bug).

```
[MODEL_ARTIFACT_SEARCH] model_path hint found in stdout: /outputs/model.pkl
[MODEL_ARTIFACT_FOUND] path=/outputs/model.pkl, suffix=.pkl, hash=11a85317..., bytes=879
[MODEL_ARTIFACT_CANONICALIZED] detected_path=/outputs/model.pkl, canonical_path=/workspace/outputs/platform_model/model.pkl
[PLATFORM_MODEL] Local model registered as platform model: artifact-contract-test-model v2
  "status": "SUCCESS"
```

- Run MLflow : `FINISHED`, `validation_status=PASSED`, `platform_registration_eligible=true`, `accuracy=0.95` loggée.
- Registry : **exactement 1 version par run** (`artifact-contract-test-model` v1→run `e23a8e74...`, v2→run `ad657092...`), chaque version liée par tag `platform_run_id`.
- Images : `acrmlopssalmav2.azurecr.io/mlops-backend:artifact-fix-20260807-02`, `mlops-frontend:artifact-fix-20260807-01` (backend seulement rebuilé en `-02` pour l'idempotence par tag).
