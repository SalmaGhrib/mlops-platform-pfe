# STANDARD_RUNTIME Execution Flow - Visual Guide

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    USER UPLOADS EXPERIMENT                         │
│          (Model File + Dataset + Runtime Selection)                 │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
        ┌────────────────────┐
        │  Which Runtime?    │
        │  - STANDARD_RUNTIME│
        │  - CUSTOM_DOCKER   │
        └────────┬───────────┘
                 │
        ┌────────┴──────────┐
        │                   │
        ▼                   ▼
   STANDARD           CUSTOM
   RUNTIME          DOCKERFILE
      │                  │
      │            ┌─────▼─────┐
      │            │ Build      │
      │            │ Dockerfile │
      │            │ with       │
      │            │ platform.. │
      │            │ runner.py  │
      │            └─────┬─────┘
      │                  │
      ▼                  ▼
┌──────────────────────────────┐
│  NO SCRIPT ANALYSIS NEEDED   │    ← For CUSTOM_DOCKERFILE
│  Kaniko builds image         │       User controls everything
│  Injects platform_runner.py  │       Can do any algorithm replacement
└──────────┬───────────────────┘
           │
           ▼
    LAUNCH WITH NEW IMAGE


   STANDARD_RUNTIME BRANCH:

      ▼
┌──────────────────────────────────┐
│ ANALYZE SCRIPT COMPATIBILITY     │
│ ┌────────────────────────────────┤
│ │ 1. Extract script_path         │
│ │ 2. Prepare request:             │
│ │    {                            │
│ │     "script_path": "/uploads.." │
│ │    }                            │
│ │ 3. Call Python API:             │
│ │    POST /api/runtime/           │
│ │         analyze-script          │
│ └────────────────────────────────┤
│ 4. Receive response:             │
│    {                             │
│     "runtime_compatible": ?,     │
│     "detected_modules": [...],   │
│     "unsupported_modules":[...], │
│     ...                          │
│    }                             │
└──────────┬──────────────────────┘
           │
           ▼
       ┌────────────────────┐
       │ Compatible?        │
       │ - No unsupported   │
       │ - Syntax OK        │
       └────────┬───────────┘
                │
        ┌───────┴──────────┐
        │                  │
      YES                 NO
        │                  │
        ▼                  ▼

   ┌─────────────┐    ┌──────────────────┐
   │  PROCEED    │    │  BLOCK LAUNCH    │
   │             │    │                  │
   │ 1. Force    │    │ Set Status:      │
   │    algo..   │    │  FAILED          │
   │    =[]      │    │                  │
   │             │    │ Message:         │
   │ 2. Send     │    │ "Script not      │
   │    flags:   │    │  compatible:     │
   │  algo_inj.. │    │  modules X,Y"    │
   │  =false     │    │                  │
   │  use_orig.. │    │ Stop execution   │
   │  =true      │    │ (no Gateway call)│
   │             │    │                  │
   │ 3. Launch   │    └──────┬───────────┘
   │  Pipeline   │           │
   │  Gateway    │           ▼
   │             │    ┌──────────────┐
   │ 4. Script   │    │ User sees:   │
   │    runs     │    │              │
   │    as-is    │    │ X Failed     │
   │    (no      │    │ Error msg ▼  │
   │    changes) │    │ with details  │
   │             │    │              │
   │ 5. Results  │    │ Fix option:   │
   │    stored   │    │ - Use custom  │
   │    in MLflow│    │   dockerfile  │
   │             │    │ - Install     │
   └──────┬──────┘    │   modules     │
          │            └──────────────┘
          ▼
   ┌──────────────────────┐
   │ SYNC METADATA        │
   │ (MLflow Tags)        │
   │                      │
   │ For STANDARD_RUNTIME:│
   │ - Force algo_repl=[]│
   │ - No replacement    │
   │   applied           │
   │                      │
   └──────┬───────────────┘
          │
          ▼
   ┌──────────────────┐
   │ EXPERIMENT       │
   │ COMPLETED        │
   │                  │
   │ Status: SUCCESS  │
   │ (or FAILED if    │
   │  error occurred) │
   └──────────────────┘
```

---

## Request/Response Details

### 1️⃣ Spring Boot → Python API

**When:** Right after user submits, BEFORE Kubeflow launch  
**URL:** `POST /api/runtime/analyze-script`  
**From:** `MlExperimentService.analyzeScriptCompatibility()`

```
POST http://127.0.0.1:8001/api/runtime/analyze-script
Content-Type: application/json

{
  "script_path": "/app/uploads/projects/10/train.py"
}
```

### 2️⃣ Python API → Spring Boot

**Response Time:** Should be <100ms  
**Status Code:** 200 OK  
**Body:**

```json
{
  "runtime_compatible": true,
  "detected_modules": [
    "torch",
    "torch.nn",
    "torchvision.transforms"
  ],
  "unsupported_modules": [],
  "supported_modules": [
    "torch",
    "torchvision",
    "sklearn",
    "pandas",
    "numpy"
  ],
  "detected_algorithms": [
    "torch.nn.Linear",
    "torch.nn.ReLU"
  ],
  "replaceable_algorithms": [],
  "message": "Script is compatible with STANDARD_RUNTIME",
  "recommendation": "You can launch with STANDARD_RUNTIME mode"
}
```

### 3️⃣ Decision in Spring Boot

```java
if (analysisResult.isRuntimeCompatible()) {
    // ✅ COMPATIBLE: Proceed with launch
    
    // Force algorithm replacements to empty
    algorithmReplacements = "[]";
    
    // Send config flags to Gateway
    "algorithm_injection_enabled": "false"
    "use_original_script": "true"
    
    // Launch Kubeflow pipeline
    pipelineClientService.launchCustomModelPipeline(...)
} else {
    // ❌ INCOMPATIBLE: Block launch
    
    experiment.setStatus(ExperimentStatus.FAILED);
    experiment.setErrorMessage(
        "Script not compatible with STANDARD_RUNTIME: " +
        "Unsupported modules: " + 
        String.join(", ", analysisResult.getUnsupportedModules())
    );
    
    // Do NOT call Pipeline Gateway
    // Experiment stays in FAILED state
}
```

---

## Code Execution Timeline

### ⏱️ Timeline for Compatible Script

```
0ms    User clicks "Launch" with STANDARD_RUNTIME
       └─ Request reaches MlExperimentService.uploadAndLaunchExperiment()

2ms    Check runtime mode
       └─ Detect: STANDARD_RUNTIME

5ms    Prepare script analysis
       └─ Extract file path: /uploads/projects/10/train.py
       └─ Create JSON body

10ms   Call Python API
       └─ POST to http://127.0.0.1:8001/api/runtime/analyze-script
       └─ Network transfer + Python AST parsing

50ms   Receive response
       └─ Parse JSON response

55ms   Check compatibility
       └─ runtimeCompatible == true ✅

60ms   Force algorithmReplacements = "[]"
       └─ Update experiment object

65ms   Set config flags
       └─ algorithm_injection_enabled = false
       └─ use_original_script = true

70ms   Call Pipeline Gateway
       └─ Launch Kubeflow pipeline

80ms   Return success response to user
       └─ Status: RUNNING
       └─ Message: "Experiment launched..."

Total: ~80ms to launch (rest depends on Kubeflow)
```

### ⏱️ Timeline for Incompatible Script

```
0ms    User clicks "Launch" with STANDARD_RUNTIME

5ms    Prepare script analysis

10ms   Call Python API

50ms   Receive response

55ms   Check compatibility
       └─ runtimeCompatible == false ❌

60ms   Update experiment status
       └─ Status = FAILED
       └─ ErrorMessage = "Script not compatible: transformers"

70ms   Return error response to user
       └─ Status: FAILED
       └─ Message: "Cannot launch: unsupported modules"
       
❌ NO Pipeline Gateway call (blocked early)

Total: ~70ms to decision + error message
```

---

## Data Flow - Database

### Experiment Record Created/Updated

```sql
UPDATE experiments
SET
  runtime_mode = 'STANDARD_RUNTIME',
  algorithm_replacements = '[]',           ← Always empty for STANDARD_RUNTIME
  algorithm_replacement_count = 0,
  has_algorithm_replacement = false,
  status = 'RUNNING',                      ← After successful analysis
  created_at = NOW(),
  updated_at = NOW()
WHERE id = @experimentId;
```

### MLflow Integration

**For STANDARD_RUNTIME:**
```
MLflow Run Tags:
  mlops.runtime_mode = "STANDARD_RUNTIME"
  mlops.algorithm_replacements = "[]"          ← Forced empty
  mlops.detected_algorithms = "Linear, Conv2d"  ← Detected but not replaced
  mlops.replaceable_algorithms = "[]"           ← Always empty
```

**Difference from CUSTOM_DOCKERFILE:**
```
MLflow Run Tags (CUSTOM_DOCKERFILE):
  mlops.algorithm_replacements = "[Linear→XLinear, Conv2d→XConv2d]"
  mlops.detected_algorithms = "Linear, Conv2d"
  mlops.replaceable_algorithms = "[Linear, Conv2d]"
  mlops.replacement_count = 2
```

---

## Error Scenarios

### Scenario 1: Transformers Import

```
Input Script:
  from transformers import BertModel
  
Step 1: API analysis
  └─ detectedModules: ["transformers"]
  └─ unsupportedModules: ["transformers"]
  └─ runtimeCompatible: false

Step 2: Spring Boot decision
  └─ Not compatible

Step 3: User feedback
  ❌ Status: FAILED
  ❌ Message: "Script not compatible with STANDARD_RUNTIME: 
             Unsupported modules: transformers"
  💡 Suggestion: "This module is not included in the standard runtime. 
                 Use CUSTOM_DOCKERFILE mode to install it."
```

### Scenario 2: PyTorch Compatible

```
Input Script:
  import torch
  from torchvision import transforms
  
Step 1: API analysis
  └─ detectedModules: ["torch", "torchvision"]
  └─ unsupportedModules: [] (empty)
  └─ runtimeCompatible: true

Step 2: Spring Boot decision
  └─ Compatible ✅
  └─ Force algo_repl = "[]"
  └─ Send use_original_script=true

Step 3: Launch
  ✅ Status: RUNNING
  ✅ Message: "Script compatible with STANDARD_RUNTIME. 
             No automatic replacement applied."
```

### Scenario 3: API Connection Error

```
Step 1: Try to call API
  └─ URL: http://127.0.0.1:8001/api/runtime/analyze-script
  └─ ERROR: Connection refused

Step 2: Handle exception
  └─ Log error with details
  └─ Set experiment status = FAILED

Step 3: User feedback
  ❌ Status: FAILED
  ❌ Message: "Script analysis failed: Connection refused
             Please check configuration and try again"
  💡 Troubleshooting: Check if Pipeline Gateway is running
```

---

## Configuration Variations

### Default Configuration

```properties
app.runtime.analysis-api-url=http://127.0.0.1:8001/api/runtime/analyze-script
```

### Docker Configuration

```properties
app.runtime.analysis-api-url=http://pipeline-gateway:8001/api/runtime/analyze-script
```

### Kubernetes Configuration

```properties
app.runtime.analysis-api-url=http://pipeline-gateway-service.mlops.svc.cluster.local:8001/api/runtime/analyze-script
```

### Custom/Override

```bash
export APP_RUNTIME_ANALYSIS_API_URL=http://custom-api:9000/analyze
./mvnw.cmd spring-boot:run
```

---

## Key Differences: STANDARD_RUNTIME vs CUSTOM_DOCKERFILE

| Aspect | STANDARD_RUNTIME | CUSTOM_DOCKERFILE |
|--------|------------------|-------------------|
| **Script Analysis** | ✅ YES (Pre-launch) | ❌ NO |
| **Compatible Check** | ✅ YES (Blocks if not) | ❌ N/A |
| **Algorithm Injection** | ❌ NO (disabled) | ✅ YES (Kaniko) |
| **Replacements** | ❌ "[]" (forced) | ✅ From MLflow |
| **Script Modification** | ❌ Original as-is | ✅ Injects runner |
| **Image Used** | Platform standard | User custom |
| **Build Time** | None | ~2-3 min |
| **Failure Rate** | Depends on modules | Low (user controls) |
| **User Control** | Limited (modules) | Full |

---

## Performance Impact

### Analysis API Call Time

| Scenario | Time | Notes |
|----------|------|-------|
| Simple script (1-10 lines) | 20-30ms | Fast AST parsing |
| Medium script (50-100 lines) | 40-60ms | Normal AST parsing |
| Large script (500+ lines) | 80-150ms | Still reasonable |
| Network latency | +10-30ms | Local network typical |
| **Total per launch** | **<100ms** | Target met ✅ |

### Gateway Performance

**Before:** Received experiment, checked algo_repl at runtime  
**After:** Pre-filtered at Spring Boot, only compatible scripts reach Gateway  
**Result:** Gateway sees fewer incompatible launches → fewer errors

---

## Monitoring Checklist

### What to Watch

```
✅ Analysis API response time
   Goal: <100ms average
   Alert: >500ms

✅ Blocked experiments ratio
   Goal: <5% (rare incompatibilities)
   Alert: >20% (suggests configuration issue)

✅ Spring Boot health
   Goal: All endpoints respond
   Alert: API call failures

✅ Error message clarity
   Goal: Users understand why blocked
   Alert: Vague or missing messages

✅ Compatible scripts success
   Goal: >95% launch success
   Alert: <80% (suggests runtime issues)
```

### Logs to Monitor

```bash
# Follow logs in real-time
tail -f logs/application.log | grep "Script analysis\|FAILED\|RUNNING"

# Count analysis calls
grep "Analyzing script" logs/application.log | wc -l

# Find blocked launches
grep "not compatible with STANDARD_RUNTIME" logs/application.log

# Find errors
grep "Script analysis API call failed" logs/application.log
```
