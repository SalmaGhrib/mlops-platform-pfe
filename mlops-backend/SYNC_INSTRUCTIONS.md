# Instructions de Test de Synchronisation MLflow → MySQL

## État Actuel ✅

- **Spring Boot Backend**: Démarré sur http://localhost:8081 avec les corrections appliquées
- **Compilation**: Succès (BUILD SUCCESS)
- **Port 8081**: Libre et actif

## Corrections Appliquées

### 1. MlflowTrackingClientService.java
✅ **Amélioration du parsing JSON**:
- Meilleure gestion des cas où les données pourraient être null ou vides
- Support pour les métriques et tags en tant qu'objets (au lieu de tableau)
- Meilleur fallback pour les noms de runs manquants
- Vérification que les runs ne sont pas vides avant de les ajouter à la liste
- Amélioration de la recherche de version du modèle

### 2. Repositories & Services
✅ Vérifiés et confirmés:
- `MlExperimentMetricRepository`: Contient les méthodes requises
- `MlExperimentService.syncSingleExperiment()`: Appelle correctement MLflow
- `ModelPromotionService`: Calcule les scores et promeut les modèles
- `MlflowRegistryClientService.setProductionAlias()`: Gère l'alias production
- `MlExperimentController`: Contient l'endpoint POST /api/experiments/project/{projectId}/sync-results

## Étapes de Test

### Préalable: Vérifier le Port-Forward MLflow

```powershell
# Dans un terminal (garder ouvert)
kubectl port-forward svc/mlflow-service 5000:5000 -n mlops

# Vérifier que ça répond
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/runs/search" `
  -ContentType "application/json" `
  -Body '{"experiment_ids":["29"],"max_results":100}'
```

### Test 1: Vérifier que Spring Boot est accessible

```powershell
# Accès direct au serveur
Invoke-WebRequest -Uri "http://localhost:8081/api/experiments/project/10" `
  -Headers @{"Authorization"="Bearer YOUR_JWT_TOKEN"}
```

### Test 2: Tester la Synchronisation (Swagger UI)

1. Ouvrez http://localhost:8081/swagger-ui.html
2. Cherchez l'endpoint: **POST /api/experiments/project/{projectId}/sync-results**
3. Cliquez sur "Try it out"
4. Remplacez `{projectId}` par `10` (ou votre projectId)
5. Cliquez sur "Execute"

**Réponse Attendue (200 OK)**:
```json
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

### Test 3: Vérifier MySQL

```sql
SELECT 
    e.id,
    e.project_id,
    e.experiment_name,
    e.model_name,
    e.registered_model_name,
    e.model_version,
    e.is_production,
    e.global_score,
    m.metric_name,
    m.metric_value
FROM ml_experiments e
LEFT JOIN ml_experiment_metrics m ON m.experiment_id = e.id
WHERE e.project_id = 10
ORDER BY e.id DESC;
```

**Résultats Attendus**:
- `model_version`: 1 (au lieu de NULL)
- `is_production`: 1 (au lieu de 0)
- `global_score`: ≠ NULL (ex: 0.18)
- `metric_name`: rmse, mae, prediction_example, return_code
- `metric_value`: ≠ NULL

### Test 4: Vérifier les Logs de Spring Boot

Cherchez dans les logs de Spring Boot (console ou fichiers):
- ✅ Messages de type: "Syncing experiment: linear_regression_run_1"
- ✅ Pas d'erreur: "No MLflow runs found"
- ✅ Confirmation: "Successfully synced experiment..."

## Dépannage

### Problème: "No MLflow runs found in experiment"

**Cause Possible 1**: MLflow n'est pas accessible
```powershell
# Vérifier la connexion
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d '{"experiment_ids":["29"],"max_results":100}' `
  http://127.0.0.1:5000/api/2.0/mlflow/runs/search
```

**Cause Possible 2**: Nom d'expérience/run ne correspond pas
- Vérifier dans MLflow UI: http://127.0.0.1:5000/
- Vérifier en MySQL: `SELECT mlflow_experiment_name, experiment_name FROM ml_experiments WHERE project_id = 10;`

**Cause Possible 3**: Pas de run FINISHED dans MLflow
- Vérifier le statut du run dans MLflow UI
- Vérifier que le pipeline Kubeflow s'est terminé

### Problème: model_version reste NULL

**Cause**: Le modèle n'a pas été enregistré dans MLflow Model Registry
- Vérifier dans MLflow UI → Model Registry
- Vérifier que votre pipeline enregistre bien le modèle

## Commandes de Test PowerShell Complètes

```powershell
# 1. Vérifier MLflow API
$response = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:5000/api/2.0/mlflow/runs/search" `
  -ContentType "application/json" `
  -Body '{"experiment_ids":["29"],"max_results":100}'
  
Write-Host "Runs found: $($response.runs.Count)"

# 2. Appeler le endpoint de sync (remplacer le token)
$headers = @{
    "Authorization" = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
    "Content-Type" = "application/json"
}

$syncResult = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8081/api/experiments/project/10/sync-results" `
  -Headers $headers

Write-Host "Sync Results:"
$syncResult | ConvertTo-Json | Write-Host

# 3. Vérifier MySQL
# (Ouvrir MySQL Workbench ou client MySQL et exécuter la requête SQL ci-dessus)
```

## Architecture Corrigée

```
Kubeflow Pipeline (Terminé)
         ↓
    MLflow API (5000)
         ↓
    MlflowTrackingClientService (CORRIGÉ)
         ↓
    MlExperimentService.syncSingleExperiment
         ↓
    MySQL: ml_experiments + ml_experiment_metrics
         ↓
    ModelPromotionService (calcul du globalScore)
         ↓
    MLflow Registry Alias (production)
```

## Fichiers Modifiés

- ✅ `MlflowTrackingClientService.java` - Parsing JSON amélioré
- ✅ Compilation - BUILD SUCCESS
- ✅ Spring Boot - Démarrage OK

## Prochaines Étapes

1. ✅ Maintenir le port-forward MLflow actif
2. ✅ Tester la synchronisation via Swagger
3. ✅ Vérifier les données dans MySQL
4. ✅ Valider les métriques dans ml_experiment_metrics
5. ✅ Vérifier la promotion à production (is_production = 1)

## Support

Si vous rencontrez des problèmes:
1. Vérifier les logs de Spring Boot (port 8081)
2. Vérifier que MLflow répond (port 5000)
3. Vérifier la connexion MySQL
4. Vérifier que les noms d'expérience correspondent entre MLflow et MySQL
