# Deployment & Testing Guide - STANDARD_RUNTIME Refactor

## Pre-Deployment Checklist

### Code Review
- [ ] Review all changes in REFACTOR_SUMMARY.md
- [ ] Verify RestTemplate injection is correct
- [ ] Check analyzeScriptCompatibility() error handling
- [ ] Review syncAlgorithmMetadataFromRunTags() modifications
- [ ] Confirm configuration flags added to PipelineClientService

### Dependencies
- [ ] RestTemplate available in Spring context (usually auto-configured)
- [ ] Jackson library for JSON parsing (already present)
- [ ] No missing imports

### Configuration
- [ ] Verify `app.runtime.analysis-api-url` in application.properties
- [ ] Default value correct: `http://127.0.0.1:8001/api/runtime/analyze-script`
- [ ] Can be overridden via environment variables if needed

### Python API
- [ ] Endpoint implemented: `POST /api/runtime/analyze-script`
- [ ] Accepts JSON body with `script_path`
- [ ] Returns response matching contract from PYTHON_API_INTEGRATION.md
- [ ] Running on `127.0.0.1:8001` (or configured URL)
- [ ] Tested with test cases provided

---

## Deployment Steps

### Step 1: Backup Current System

```bash
# Backup database
mysqldump -u root -p mlops_platform > mlops_backup_$(date +%Y%m%d_%H%M%S).sql

# Backup application.properties
cp src/main/resources/application.properties application.properties.backup

# Save current Docker images if using Docker
docker images > docker_images_backup_$(date +%Y%m%d_%H%M%S).txt
```

### Step 2: Deploy Python API Changes

```bash
# 1. Implement /api/runtime/analyze-script endpoint in Pipeline Gateway
# 2. Test endpoint manually (see Testing section below)
# 3. Ensure endpoint is accessible from Spring Boot container

# If running in Docker:
docker logs pipeline-gateway  # Check for errors
curl http://localhost:8001/api/runtime/analyze-script  # Should return 400 (no body) or 200
```

### Step 3: Deploy Spring Boot Changes

```bash
# From mlops-backend directory
cd mlops-backend

# Clean and compile
./mvnw.cmd clean compile -DskipTests

# Package
./mvnw.cmd package -DskipTests

# Or just rebuild without tests if already running:
# ./mvnw.cmd clean package -DskipTests

# Verify .jar created
ls target/mlops-backend-*.jar
```

### Step 4: Update Configuration

```properties
# Verify in application.properties:
app.runtime.analysis-api-url=http://127.0.0.1:8001/api/runtime/analyze-script

# If running in Docker/Kubernetes, might need:
app.runtime.analysis-api-url=http://pipeline-gateway-service:8001/api/runtime/analyze-script
```

### Step 5: Restart Spring Boot Service

```bash
# If running locally:
./mvnw.cmd spring-boot:run

# If running in Docker:
docker build -t mlops-backend:latest .
docker run -p 8081:8081 mlops-backend:latest

# If in Kubernetes:
kubectl rollout restart deployment/mlops-backend -n mlops
kubectl logs -f deployment/mlops-backend -n mlops
```

### Step 6: Verify Startup

```bash
# Check logs for errors
tail -f logs/application.log

# Look for:
# - No RestTemplate initialization errors
# - No "Cannot find bean RestTemplate"
# - Application started message
```

---

## Testing Phase

### Test 1: Unit Level - Python API Endpoint

```bash
# Test endpoint with valid request
curl -X POST http://127.0.0.1:8001/api/runtime/analyze-script \
  -H "Content-Type: application/json" \
  -d '{
    "script_path": "path/to/your/test_script.py"
  }'

# Expected: 200 OK with JSON response
# Response should include:
# - "runtime_compatible": true/false
# - "detected_modules": [...]
# - "unsupported_modules": [...]
```

### Test 2: Integration - Compatible Script (PyTorch)

**Test File:** `uploads/test_pytorch_compatible.py`
```python
import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import datasets, transforms
import numpy as np

class SimpleNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.fc1 = nn.Linear(784, 128)
        self.fc2 = nn.Linear(128, 10)
    
    def forward(self, x):
        x = x.view(-1, 784)
        x = torch.relu(self.fc1(x))
        return self.fc2(x)

model = SimpleNet()
optimizer = optim.SGD(model.parameters(), lr=0.01)
```

**Steps:**
1. Upload model file: `test_pytorch_compatible.py`
2. Upload dataset: (optional)
3. Set runtimeMode: "STANDARD_RUNTIME"
4. Submit experiment

**Expected Outcome:**
- ✅ Status: RUNNING (not FAILED)
- ✅ Message: "Script compatible with STANDARD_RUNTIME..."
- ✅ Log shows: "Script analysis result: runtimeCompatible=true"
- ✅ Experiment launches and runs

### Test 3: Integration - Incompatible Script (Transformers)

**Test File:** `uploads/test_transformers_incompatible.py`
```python
from transformers import BertModel, BertTokenizer
import torch

model = BertModel.from_pretrained('bert-base-uncased')
tokenizer = BertTokenizer.from_pretrained('bert-base-uncased')
```

**Steps:**
1. Upload model file: `test_transformers_incompatible.py`
2. Set runtimeMode: "STANDARD_RUNTIME"
3. Submit experiment

**Expected Outcome:**
- ❌ Status: FAILED (before launching)
- ❌ Message: "Script not compatible with STANDARD_RUNTIME: Unsupported modules: transformers"
- ❌ Log shows: "Script analysis result: runtimeCompatible=false, unsupported=transformers"
- ❌ Experiment blocked (no Pipeline Gateway call)

### Test 4: Integration - Sklearn (Compatible)

**Test File:** `uploads/test_sklearn_compatible.py`
```python
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
import pandas as pd
import numpy as np

X = np.random.rand(100, 10)
y = np.random.randint(0, 2, 100)

clf = RandomForestClassifier(n_estimators=100)
clf.fit(X, y)

# Save model
import joblib
joblib.dump(clf, 'model.pkl')
```

**Steps:**
1. Upload model file: `test_sklearn_compatible.py`
2. Upload dataset: `test_data.csv`
3. Set runtimeMode: "STANDARD_RUNTIME"
4. Submit experiment

**Expected Outcome:**
- ✅ Status: RUNNING → COMPLETED
- ✅ Message: "Script compatible with STANDARD_RUNTIME. No automatic replacement applied."
- ✅ Algorithms detected but NOT replaced (by design)
- ✅ Log shows: "detected=RandomForestClassifier"
- ✅ Algorithms stored but replacement = "[]"

### Test 5: CUSTOM_DOCKERFILE Not Affected

**Test File:** Upload any script + Dockerfile

**Steps:**
1. Upload model file: any script
2. Upload Dockerfile
3. Set runtimeMode: "CUSTOM_DOCKERFILE"
4. Submit experiment

**Expected Outcome:**
- ✅ NO script analysis call (not needed for CUSTOM_DOCKERFILE)
- ✅ Status: CREATED or WAITING_FOR_BUILD_STEP
- ✅ Build process starts normally
- ✅ No changes to existing behavior

### Test 6: Configuration Override

**Via Environment Variable:**
```bash
export APP_RUNTIME_ANALYSIS_API_URL=http://custom-api:9000/analyze
./mvnw.cmd spring-boot:run
```

**Expected:**
- ✅ Logs show using custom URL
- ✅ API calls go to custom URL

### Test 7: API Unreachable

**Simulate failure:**
```bash
# Stop Pipeline Gateway
docker stop pipeline-gateway
# or manually disable /api/runtime/analyze-script endpoint

# Try to launch experiment
```

**Expected Outcome:**
- ❌ Status: FAILED
- ❌ Error Message: "Script analysis failed: Connection refused"
- ❌ Clear guidance to check configuration

### Test 8: Malformed Script

**Test File:** `uploads/test_syntax_error.py`
```python
import torch
def foo(
    # Missing closing parenthesis
    pass
```

**Expected Outcome:**
- ❌ Status: FAILED
- ❌ Message: "Python syntax error: invalid syntax at line 4"
- ❌ Experiment blocked before launch

---

## Monitoring & Logs

### Key Log Patterns

**Successful flow:**
```
[MlExperimentService] Analyzing script compatibility for STANDARD_RUNTIME...
[MlExperimentService] Calling script analysis API: http://127.0.0.1:8001/api/runtime/analyze-script
[MlExperimentService] Script path: /uploads/projects/10/train.py
[MlExperimentService] Script analysis result: runtimeCompatible=true, unsupported=, detected=...
[MlExperimentService] Script compatible with STANDARD_RUNTIME...
[PipelineClientService] Kubeflow pipeline launch requested with algorithm_injection_enabled=false
```

**Failed flow:**
```
[MlExperimentService] Analyzing script compatibility for STANDARD_RUNTIME...
[MlExperimentService] Script analysis result: runtimeCompatible=false, unsupported=transformers, detected=...
[MlExperimentService] FAILED
```

**API error:**
```
[MlExperimentService] Script analysis API call failed: Connection refused
[MlExperimentService] FAILED
```

### Enable Debug Logging

```properties
logging.level.com.mlops.platform.service.MlExperimentService=DEBUG
logging.level.com.mlops.platform.service.PipelineClientService=DEBUG
```

---

## Rollback Plan

If critical issues discovered:

### Immediate Rollback (Within Minutes)

```bash
# Option 1: Revert code to previous version
git checkout HEAD~1 -- src/main/java/com/mlops/platform/service/MlExperimentService.java
git checkout HEAD~1 -- src/main/java/com/mlops/platform/service/PipelineClientService.java

# Recompile and restart
./mvnw.cmd clean package -DskipTests
docker build -t mlops-backend:rollback .
docker run -p 8081:8081 mlops-backend:rollback

# Option 2: Disable analysis API temporarily
# In application.properties:
app.runtime.analysis-api-url=disabled

# Modify MlExperimentService.analyzeScriptCompatibility() to skip call
```

### Graceful Degradation

If analysis API fails:
- Scripts already passing API check: continue to launch
- New scripts: block with clear message
- Existing experiments in database: not affected

### Database Rollback

If needed to revert database:
```bash
mysql -u root -p mlops_platform < mlops_backup_20260601_124453.sql
```

---

## Success Criteria

All tests passing = Green Light

- [ ] Test 1: Python API responds correctly
- [ ] Test 2: PyTorch compatible script launches ✅
- [ ] Test 3: Transformers incompatible script blocked ❌
- [ ] Test 4: Sklearn compatible script launches with correct metadata
- [ ] Test 5: CUSTOM_DOCKERFILE still works unchanged
- [ ] Test 6: Configuration override works
- [ ] Test 7: API unreachable shows clear error
- [ ] Test 8: Syntax errors caught early
- [ ] Compilation succeeds with no new errors
- [ ] Spring Boot starts without errors
- [ ] Logs show expected patterns
- [ ] No regression in existing features
- [ ] Performance acceptable (<100ms per analysis)

---

## Post-Deployment

### Monitor Metrics
- API response time (should be <100ms)
- Number of blocked experiments (should be rare)
- Error rates
- Script analysis success rate

### Gather Feedback
- Check user comments/support tickets
- Monitor for "Script not compatible" errors
- Review error messages for clarity

### Documentation
- Update user guide with new behavior
- Add FAQ about "script compatibility"
- Document how to fix incompatible scripts
- Create troubleshooting guide

---

## Support Contacts

If issues arise during deployment:

1. **Python API Endpoint Issues**
   - Check Python API logs
   - Verify request/response format
   - Test with curl (see Testing section)

2. **Spring Boot Integration Issues**
   - Check Spring Boot logs (tail -f logs/application.log)
   - Verify configuration in application.properties
   - Ensure RestTemplate is initialized

3. **Network/Communication**
   - Verify API URL is accessible from Spring Boot
   - Check firewall rules (Docker/Kubernetes networking)
   - Review DNS resolution if using hostnames

4. **Rollback Questions**
   - See Rollback Plan section above
   - Have backup .sql file ready
   - Keep git history accessible
