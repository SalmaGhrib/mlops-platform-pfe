# Quick Start - STANDARD_RUNTIME Refactor Summary

## What Changed?

### For STANDARD_RUNTIME Mode:
✅ **Disabled:** Algorithm replacement/injection  
✅ **Enabled:** Script analysis & compatibility checking  
✅ **Behavior:** Execute original script if compatible, block if not  

### For CUSTOM_DOCKERFILE Mode:
✅ **Unchanged:** Still works exactly as before  

---

## 3 Key Changes in Spring Boot

### 1. Script Analysis (BEFORE Launch)

**New:** Calls Python API to check script compatibility
```
POST /api/runtime/analyze-script
← Returns: {runtime_compatible: true/false, ...}
```

If **incompatible** → Block launch, show error  
If **compatible** → Launch with original script

### 2. Force algorithmReplacements = "[]"

**Old:** User could set replacements in STANDARD_RUNTIME  
**New:** Always empty for STANDARD_RUNTIME

```java
algorithmReplacements = "[]";  // Always forced
```

### 3. Configuration Flags

**New:** Sent to Pipeline Gateway
```
algorithm_injection_enabled=false
replacement_enabled=false
use_original_script=true
```

---

## Files Modified

| File | Changes | Impact |
|------|---------|--------|
| `MlExperimentService.java` | Added analysis call, force replacements | Launch logic changed |
| `PipelineClientService.java` | Added config flags | Gateway receives new params |
| `application.properties` | Added API URL config | Config needed |

---

## New Configuration

```properties
# In application.properties:
app.runtime.analysis-api-url=http://127.0.0.1:8001/api/runtime/analyze-script
```

---

## Python API Required

**Endpoint:** `POST /api/runtime/analyze-script`

**Request:**
```json
{"script_path": "/path/to/script.py"}
```

**Response:**
```json
{
  "runtime_compatible": true/false,
  "detected_modules": ["torch", "sklearn"],
  "unsupported_modules": [],
  "message": "..."
}
```

See **PYTHON_API_INTEGRATION.md** for full details + example implementation.

---

## Compilation Status

✅ **No Errors** - Code compiles successfully

```
[INFO] BUILD SUCCESS
[INFO] Total time: 11.897 s
```

---

## Before You Deploy

### Checklist
- [ ] Python API implemented and tested
- [ ] Python API responding correctly at configured URL
- [ ] Spring Boot compiles without errors
- [ ] application.properties has correct API URL
- [ ] Test STANDARD_RUNTIME launch (see DEPLOYMENT_TESTING_GUIDE.md)
- [ ] Test CUSTOM_DOCKERFILE (should be unchanged)
- [ ] Test incompatible script is blocked
- [ ] Test compatible script launches normally

### Quick Test
```bash
# Test Python API
curl -X POST http://127.0.0.1:8001/api/runtime/analyze-script \
  -H "Content-Type: application/json" \
  -d '{"script_path": "path/to/test.py"}'

# Expected: 200 OK + JSON response
```

---

## Expected Behavior - Examples

### ✅ Compatible Script (PyTorch)
```
Input: script with import torch, torchvision
↓
Analysis: All modules supported
↓
Result: LAUNCHES with original script
Message: "Script compatible with STANDARD_RUNTIME. No automatic replacement applied."
```

### ❌ Incompatible Script (Transformers)
```
Input: script with import transformers
↓
Analysis: transformers not in standard image
↓
Result: BLOCKED before launch
Error: "Script not compatible with STANDARD_RUNTIME: Unsupported modules: transformers"
User: See suggestion to use CUSTOM_DOCKERFILE mode
```

### ✅ Sklearn Script
```
Input: script with sklearn, pandas
↓
Analysis: All modules supported
↓
Result: LAUNCHES with original script
Algorithms Detected: RandomForestClassifier (but not replaced - by design)
```

---

## Key Design Points

| Point | Why |
|-------|-----|
| Force `"[]"` replacements | Consistency, simplicity, no surprises |
| Analyze BEFORE launch | Fail fast, save compute, clear feedback |
| No script modification | User controls code, platform orchestrates |
| Keep CUSTOM_DOCKERFILE unchanged | Backward compatibility, user control |
| Clear error messages | Users know exactly what's wrong |

---

## Logs to Watch For

**Successful:**
```
[MlExperimentService] Analyzing script compatibility...
[MlExperimentService] Script analysis result: runtimeCompatible=true...
[MlExperimentService] Script compatible with STANDARD_RUNTIME...
```

**Blocked:**
```
[MlExperimentService] Script analysis result: runtimeCompatible=false...
[MlExperimentService] FAILED
```

**Error:**
```
[MlExperimentService] Script analysis API call failed...
[MlExperimentService] FAILED
```

---

## If Something Goes Wrong

### Python API Unreachable
```
Solution: Check if Python service is running
         Verify URL in application.properties
         Check network connectivity (Docker/Kubernetes networking)
```

### Analysis Returns Wrong Results
```
Solution: Review Python API implementation
         Check script_path resolution
         Verify module detection logic
```

### Script Blocked But Should Work
```
Solution: Verify all modules are in SUPPORTED_MODULES list
         Check Python API response manually
         May need to add modules to standard image
```

### Spring Boot Won't Start
```
Solution: Check compilation errors
         Verify RestTemplate configuration
         Check imports are correct
```

---

## Documentation Files

| File | Purpose |
|------|---------|
| `REFACTOR_SUMMARY.md` | Complete technical changes + design decisions |
| `PYTHON_API_INTEGRATION.md` | Python API spec + example implementation |
| `DEPLOYMENT_TESTING_GUIDE.md` | Step-by-step deployment + test cases |
| `QUICK_START.md` | This file - high-level overview |

---

## Timeline

| Step | Time | Owner |
|------|------|-------|
| Review changes | 15 min | Dev Lead |
| Implement Python API | 1-2 hours | Python Dev |
| Test Python API | 30 min | QA |
| Deploy Spring Boot changes | 15 min | DevOps |
| Run integration tests | 1-2 hours | QA |
| Monitor in production | Ongoing | DevOps/Support |

---

## Next Steps

1. **Read:** REFACTOR_SUMMARY.md (understand what changed)
2. **Implement:** PYTHON_API_INTEGRATION.md (create Python endpoint)
3. **Deploy:** DEPLOYMENT_TESTING_GUIDE.md (step-by-step deployment)
4. **Test:** All 8 test cases in DEPLOYMENT_TESTING_GUIDE.md
5. **Monitor:** Check logs and metrics post-deployment

---

## Questions?

**On Python API?** → See PYTHON_API_INTEGRATION.md  
**On Spring Boot changes?** → See REFACTOR_SUMMARY.md  
**On deployment?** → See DEPLOYMENT_TESTING_GUIDE.md  
**On architecture?** → See design points above  

---

## Support

- Python API not starting? Check Python logs
- Spring Boot won't compile? Check imports
- Tests failing? Review test case expectations
- In production issue? See Rollback Plan in DEPLOYMENT_TESTING_GUIDE.md
