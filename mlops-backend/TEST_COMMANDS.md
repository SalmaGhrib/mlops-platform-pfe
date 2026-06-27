# Commandes de Déploiement et Test

## ✅ État Actuel

- Spring Boot est **EN COURS D'EXÉCUTION** sur le port 8081
- Application compilée avec **BUILD SUCCESS**
- PID du processus Maven: 24132

## 1️⃣ Étape 1: Vérifier que Spring Boot Tourne

```powershell
# Vérifier le port 8081
netstat -ano | findstr :8081

# Résultat attendu:
# TCP    0.0.0.0:8081           0.0.0.0:0              LISTENING       24132
# TCP    [::]:8081              [::]:0                 LISTENING       24132
```

## 2️⃣ Étape 2: Démarrer le Port-Forward MLflow

```powershell
# Dans un NOUVEAU terminal, garder ouvert
kubectl port-forward svc/mlflow-service 5000:5000 -n mlops

# Résultat attendu:
# Forwarding from 127.0.0.1:5000 -> 5000
# Handling connection for 5000
```

## 3️⃣ Étape 3: Tester la Connexion à MLflow

```powershell
# Tester que MLflow API répond
$mlflowResponse = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/experiments/get-by-name" `
  -ContentType "application/json" `
  -Body '{"experiment_name":"gold-price-prediction-v2"}'

# Afficher le résultat
Write-Host "Experiment ID: $($mlflowResponse.experiment.experiment_id)"
Write-Host "Experiment Name: $($mlflowResponse.experiment.name)"

# OU tester directement les runs
$runsResponse = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/runs/search" `
  -ContentType "application/json" `
  -Body '{"experiment_ids":["29"],"max_results":100}'

Write-Host "Number of runs: $($runsResponse.runs.Count)"
foreach ($run in $runsResponse.runs) {
    Write-Host "Run ID: $($run.info.run_id), Status: $($run.info.status)"
}
```

## 4️⃣ Étape 4: Tester l'Endpoint de Sync via curl

### Option A: Avec curl (PowerShell)

```powershell
# Remplacer YOUR_JWT_TOKEN par votre vrai token d'authentification
$token = "YOUR_JWT_TOKEN"

# Appeler l'endpoint de sync
$response = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8081/api/experiments/project/10/sync-results" `
  -Headers @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
  }

# Afficher les résultats
$response | ConvertTo-Json | Write-Host

# Ou simplement:
Write-Host "Experiments synchronized: $($response.Count)"
foreach ($exp in $response) {
    Write-Host "- $($exp.experimentName): model_version=$($exp.modelVersion), production=$($exp.production)"
}
```

### Option B: Via Swagger UI (Recommandé pour les tests)

1. Ouvrir http://localhost:8081/swagger-ui.html
2. Chercher: "Experiments"
3. Trouver: **POST /api/experiments/project/{projectId}/sync-results**
4. Cliquer: "Try it out"
5. Remplacer `projectId` par: `10`
6. Cliquer: "Execute"

**Réponse Attendue**:
```json
HTTP 200 OK
[
  {
    "id": 16,
    "experimentName": "linear_regression_run_1",
    "status": "COMPLETED",
    "projectId": 10,
    "projectName": "Gold Price Prediction V2",
    "modelName": "linear_regression",
    "registeredModelName": "gold-price-prediction-v2",
    "mlflowRunId": "92547a5720d74368a4da258bc1c8ea18",
    "modelVersion": "1",
    "production": true,
    "globalScore": 0.18,
    "metrics": {
      "rmse": 3.9680638876824883,
      "mae": 3.3995723528258233,
      "prediction_example": 208.09418058772795,
      "return_code": 0
    }
  }
]
```

## 5️⃣ Étape 5: Vérifier les Données dans MySQL

```sql
-- Vérifier les expériences synchronisées
SELECT 
    id,
    project_id,
    experiment_name,
    registered_model_name,
    model_version,
    is_production,
    global_score,
    status,
    mlflow_run_id
FROM ml_experiments
WHERE project_id = 10
ORDER BY id DESC;

-- Résultat attendu:
-- id | project_id | experiment_name | registered_model_name | model_version | is_production | global_score | status | mlflow_run_id
-- 16 | 10 | linear_regression_run_1 | gold-price-prediction-v2 | 1 | 1 | 0.18 | COMPLETED | 92547a5720d74368a4da258bc1c8ea18

-- Vérifier les métriques
SELECT 
    m.experiment_id,
    m.metric_name,
    m.metric_value,
    m.direction
FROM ml_experiment_metrics m
WHERE m.experiment_id = 16
ORDER BY m.metric_name;

-- Résultat attendu:
-- experiment_id | metric_name | metric_value | direction
-- 16 | mae | 3.3995723528258233 | min
-- 16 | prediction_example | 208.09418058772795 | max
-- 16 | return_code | 0 | max
-- 16 | rmse | 3.9680638876824883 | min
```

## 6️⃣ Étape 6: Vérifier MLflow Registry

```powershell
# Récupérer les versions du modèle enregistré
$registryResponse = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/model-versions/search" `
  -ContentType "application/json" `
  -Body '{"filter":"name='"'"'gold-price-prediction-v2'"'"'","max_results":100}'

# Afficher les versions
foreach ($version in $registryResponse.model_versions) {
    Write-Host "Version: $($version.version)"
    Write-Host "  Status: $($version.status)"
    Write-Host "  Current Stage: $($version.current_stage)"
    Write-Host "  Run ID: $($version.run_id)"
    Write-Host "  Aliases: $($version.aliases -join ', ')"
}

# Vérifier l'alias production
$aliasResponse = Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/registered-models/alias?name=gold-price-prediction-v2&alias=production" `
  -ContentType "application/json"

Write-Host "Production Alias Version: $($aliasResponse.model_version.version)"
```

## 7️⃣ Étape 7: Troubleshooting

### Erreur: "No MLflow runs found"

```powershell
# 1. Vérifier le port-forward
Get-NetTCPConnection -LocalPort 5000 -State Listen

# 2. Tester la connexion directe
$test = Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/version" `
  -ContentType "application/json"

Write-Host "MLflow Version: $($test.version)"

# 3. Chercher l'expérience
$exp = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/experiments/get-by-name" `
  -ContentType "application/json" `
  -Body '{"experiment_name":"gold-price-prediction-v2"}'

Write-Host "Experiment ID: $($exp.experiment.experiment_id)"

# 4. Lister les runs de l'expérience
$runs = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/runs/search" `
  -ContentType "application/json" `
  -Body '{"experiment_ids":["29"],"max_results":100}'

Write-Host "Runs found: $($runs.runs.Count)"
if ($runs.runs.Count -gt 0) {
    $runs.runs | ForEach-Object {
        Write-Host "- Run ID: $($_.info.run_id), Status: $($_.info.status)"
    }
}
```

### Erreur: "model_version is NULL"

```powershell
# Vérifier que le modèle est bien enregistré dans MLflow
$models = Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/registered-models/list" `
  -ContentType "application/json"

Write-Host "Registered Models:"
$models.registered_models | ForEach-Object {
    Write-Host "- $($_.name)"
    Write-Host "  Latest Version: $($_.latest_versions[0].version if $_.latest_versions)"
}

# Vérifier les versions d'un modèle spécifique
$versions = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/model-versions/search" `
  -ContentType "application/json" `
  -Body '{"filter":"name='"'"'gold-price-prediction-v2'"'"'","max_results":100}'

Write-Host "Versions of gold-price-prediction-v2:"
$versions.model_versions | ForEach-Object {
    Write-Host "- Version: $($_.version), Run ID: $($_.run_id)"
}
```

## 8️⃣ Étape 8: Vérifier les Logs Spring Boot

```powershell
# Si Spring Boot tourne toujours, chercher les logs
Get-Content "logs/spring-boot.log" | Select-String "error|Error|ERROR|sync|Sync" | Select-Object -Last 20

# Ou utiliser le terminal actif du Maven
# Les logs s'affichent directement dans la console
```

## 9️⃣ Résumé des Contrôles

### ✅ Avant la Synchronisation

```powershell
# MySQL
SELECT model_version, is_production, global_score FROM ml_experiments WHERE id = 16;
# Résultat: NULL | 0 | NULL
```

### ✅ Après la Synchronisation

```powershell
# MySQL
SELECT model_version, is_production, global_score FROM ml_experiments WHERE id = 16;
# Résultat: 1 | 1 | 0.18
```

## 🔟 Arrêt et Nettoyage

```powershell
# Si vous voulez arrêter Spring Boot
netstat -ano | findstr :8081
taskkill /PID <PID> /F

# Redémarrer
cd "c:\Users\ASUS\OneDrive - ESPRIT\Bureau\mlops-backend\mlops-backend"
.\mvnw.cmd spring-boot:run
```

---

**Note**: Le serveur Spring Boot tourne actuellement avec PID 24132. Tous les fichiers ont été compilés avec succès et les corrections sont actives.
