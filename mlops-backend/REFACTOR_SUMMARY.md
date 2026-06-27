# MLOps Runtime Standard Refactor - Summary of Changes

## Objective
Simplify **STANDARD_RUNTIME** mode by completely disabling algorithm injection/replacement in Spring Boot. Keep only script analysis and compatibility checking. Execute original scripts as-is if compatible.

---

## Files Modified

### 1. MlExperimentService.java

#### A. Added Imports (Line ~20)
```java
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
```

#### B. Added RestTemplate Dependency Injection (Line ~50)
```java
private final RestTemplate restTemplate;

@Value("${app.runtime.analysis-api-url:http://127.0.0.1:8001/api/runtime/analyze-script}")
private String runtimeAnalysisApiUrl;
```

Updated constructor to inject RestTemplate:
```java
public MlExperimentService(
    // ... existing parameters ...
    RestTemplate restTemplate
) {
    // ... existing assignments ...
    this.restTemplate = restTemplate;
}
```

#### C. Modified STANDARD_RUNTIME Section (Line ~310)

**Before:**
```java
// MODE 1 : STANDARD_RUNTIME
// Ancien comportement conservé.
experiment.setStatus(ExperimentStatus.RUNNING);
experiment.setBuildStatus(null);
experiment.setBuildLogs(null);
experiment.setMessage("Experiment saved in MySQL. Kubeflow pipeline launch requested.");

MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

try {
    PipelineGatewayResponse gatewayResponse = pipelineClientService.launchCustomModelPipeline(
        // ... with algorithmReplacements passed as-is
    );
```

**After:**
```java
/*
 * MODE 1 : STANDARD_RUNTIME
 * New simplified behavior:
 * - Disable ALL algorithm replacement
 * - Call script analysis API to check compatibility
 * - Block launch if script uses unsupported modules
 * - Execute original script as-is if compatible
 */
experiment.setStatus(ExperimentStatus.RUNNING);
experiment.setBuildStatus(null);
experiment.setBuildLogs(null);

// For STANDARD_RUNTIME: ALWAYS disable algorithm replacement
algorithmReplacements = "[]";
experiment.setAlgorithmReplacements("[]");
experiment.setAlgorithmReplacementCount(0);
experiment.setHasAlgorithmReplacement(false);

// Call Python API to analyze script compatibility
System.out.println("[MlExperimentService] Analyzing script compatibility for STANDARD_RUNTIME...");
ScriptAnalysisResult analysisResult = analyzeScriptCompatibility(savedModelFile);

// Store detected modules in experiment
if (analysisResult.getDetectedAlgorithms() != null && !analysisResult.getDetectedAlgorithms().isEmpty()) {
    experiment.setDetectedAlgorithms(analysisResult.getDetectedAlgorithms());
}

// Check runtime compatibility
if (!analysisResult.isRuntimeCompatible()) {
    String errorMsg = "Script not compatible with STANDARD_RUNTIME: " +
            (analysisResult.getUnsupportedModules() != null && !analysisResult.getUnsupportedModules().isEmpty()
                    ? "Unsupported modules: " + analysisResult.getUnsupportedModules()
                    : analysisResult.getMessage());

    System.err.println("[MlExperimentService] " + errorMsg);

    experiment.setStatus(ExperimentStatus.FAILED);
    experiment.setErrorMessage(errorMsg);
    experiment.setMessage("Script incompatible with STANDARD_RUNTIME. Consider using CUSTOM_DOCKERFILE mode instead.");

    MlExperiment failedExperiment = mlExperimentRepository.saveAndFlush(experiment);
    return mapToResponse(failedExperiment);
}

// Script is compatible - proceed with launch
experiment.setMessage("Script compatible with STANDARD_RUNTIME. No automatic replacement applied. " +
        "Launching with original script.");

MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

try {
    PipelineGatewayResponse gatewayResponse = pipelineClientService.launchCustomModelPipeline(
        // ... with algorithmReplacements = "[]" (forced)
    );
```

#### D. Added New Methods at End of File

**Method 1: analyzeScriptCompatibility()**
```java
private ScriptAnalysisResult analyzeScriptCompatibility(UploadedProjectFile modelFile)
```
- Calls Python API at `app.runtime.analysis-api-url`
- POST request with script path
- Returns ScriptAnalysisResult with:
  - detectedModules
  - unsupportedModules
  - supportedModules
  - detectedAlgorithms
  - replaceableAlgorithms
  - runtimeCompatible (boolean)
  - message
  - recommendation

**Method 2: extractJsonArray()**
```java
private String extractJsonArray(JsonNode node, String fieldName)
```
- Utility to extract JSON arrays and convert to comma-separated strings

**Method 3: ScriptAnalysisResult Inner Class**
```java
public static class ScriptAnalysisResult
```
- Holds the response from the analysis API
- Fields: detectedModules, unsupportedModules, supportedModules, detectedAlgorithms, replaceableAlgorithms, runtimeCompatible, message, recommendation

#### E. Modified syncAlgorithmMetadataFromRunTags() (Line ~2230)

**Before:**
```java
// Applied all algorithm replacements from MLflow tags
if (algorithmReplacements != null && !algorithmReplacements.isBlank()) {
    experiment.setAlgorithmReplacements(algorithmReplacements);
}
```

**After:**
```java
// For STANDARD_RUNTIME: always keep replacements as empty
if (isStandardRuntime) {
    experiment.setAlgorithmReplacements("[]");
    experiment.setAlgorithmReplacementCount(0);
    experiment.setHasAlgorithmReplacement(false);
} else {
    // For CUSTOM_DOCKERFILE: apply replacements from MLflow tags
    if (algorithmReplacements != null && !algorithmReplacements.isBlank()) {
        experiment.setAlgorithmReplacements(algorithmReplacements);
    }
    // ... rest of logic
}
```

---

### 2. PipelineClientService.java

#### Added Configuration Flags (Line ~160)

In `launchCustomModelPipeline()` method, added:

```java
// STANDARD_RUNTIME configuration flags
body.add("algorithm_injection_enabled", "false");
body.add("algorithmInjectionEnabled", "false");
body.add("replacement_enabled", "false");
body.add("replacementEnabled", "false");
body.add("use_original_script", "true");
body.add("useOriginalScript", "true");
```

These flags inform the Pipeline Gateway that:
- Algorithm injection is disabled
- Replacement is disabled
- Original script should be used as-is

---

### 3. application.properties

#### Added Configuration Entry (Line ~47)

```properties
# API for script compatibility analysis
# Called before launching STANDARD_RUNTIME experiments
app.runtime.analysis-api-url=http://127.0.0.1:8001/api/runtime/analyze-script
```

Default: `http://127.0.0.1:8001/api/runtime/analyze-script`

Can be overridden via environment variable or properties file.

---

## Execution Flow - STANDARD_RUNTIME

```
1. User uploads: model.py, [dataset.csv (optional)]
   - NO Dockerfile
   - NO requirements.txt

2. uploadAndLaunchExperiment() called with:
   - runtimeMode = "STANDARD_RUNTIME"
   - algorithmReplacements = [user value or empty]

3. resolveRuntimeMode() → "STANDARD_RUNTIME"

4. For STANDARD_RUNTIME:
   a. Force algorithmReplacements = "[]"
   b. Create MlExperiment with:
      - runtimeMode = "STANDARD_RUNTIME"
      - algorithmReplacements = "[]"
      - algorithmReplacementCount = 0
      - hasAlgorithmReplacement = false
   
   c. Call analyzeScriptCompatibility(modelFile)
      → POST to /api/runtime/analyze-script
      ← Returns: {
          detectedModules: [list],
          unsupportedModules: [list],
          runtimeCompatible: true/false,
          detectedAlgorithms: [list],
          ...
        }
   
   d. If runtimeCompatible == false:
      - Set experiment status = FAILED
      - Set errorMessage = "Script not compatible..."
      - Save and return (BLOCK LAUNCH)
      
   e. If runtimeCompatible == true:
      - Save experiment with status = RUNNING
      - Call pipelineClientService.launchCustomModelPipeline()
      - Pipeline Gateway receives:
        * algorithmReplacements = "[]"
        * algorithm_injection_enabled = "false"
        * replacement_enabled = "false"
        * use_original_script = "true"
        * runtime_mode = "STANDARD_RUNTIME"

5. Pipeline Gateway executes original script (no modification)

6. MLflow collects results + tags

7. syncProjectResults() calls syncExperimentWithMlflow()
   → syncAlgorithmMetadataFromRunTags()
   
   For STANDARD_RUNTIME: Force:
   - algorithmReplacements = "[]"
   - algorithmReplacementCount = 0
   - hasAlgorithmReplacement = false
   
   (ignores any MLflow tags about replacements)
```

---

## Expected Behavior Examples

### Example 1: PyTorch Script (Compatible)

**Input:**
```python
import torch
import torch.nn as nn
from torchvision import datasets, transforms
```

**Analysis Result:**
```json
{
  "detected_modules": ["torch", "torchvision"],
  "unsupported_modules": [],
  "runtime_compatible": true,
  "detected_algorithms": ["nn.Linear", "nn.Conv2d"],
  "message": "All modules supported"
}
```

**Outcome:**
✅ Launch with original script
- No replacement
- Message: "Script compatible with STANDARD_RUNTIME. No automatic replacement applied."
- Status: RUNNING → COMPLETED
- Detection: Algorithms detected but NOT replaceable

### Example 2: Unsupported Module

**Input:**
```python
import transformers
from transformers import BertModel
```

**Analysis Result:**
```json
{
  "detected_modules": ["transformers"],
  "unsupported_modules": ["transformers"],
  "runtime_compatible": false,
  "message": "Module transformers not in STANDARD_RUNTIME image"
}
```

**Outcome:**
❌ Block launch
- Status: FAILED
- Error Message: "Script not compatible with STANDARD_RUNTIME: Unsupported modules: transformers"
- User suggestion: "Use CUSTOM_DOCKERFILE mode instead"

### Example 3: Sklearn Script (Compatible)

**Input:**
```python
from sklearn.ensemble import RandomForestClassifier
import pandas as pd
```

**Analysis Result:**
```json
{
  "detected_modules": ["sklearn", "pandas"],
  "unsupported_modules": [],
  "runtime_compatible": true,
  "detected_algorithms": ["RandomForestClassifier"],
  "replaceable_algorithms": ["RandomForestClassifier"],
  "message": "All modules supported"
}
```

**Outcome:**
✅ Launch with original script
- No replacement (replacement is disabled in STANDARD_RUNTIME)
- Message: "Script compatible with STANDARD_RUNTIME. No automatic replacement applied."
- Algorithms detected and replaceable, but NOT replaced (by design)

---

## Key Design Decisions

1. **Forced "[]" for algorithmReplacements**
   - User cannot enable replacement in STANDARD_RUNTIME
   - Ensures consistency and simplicity

2. **Script Analysis BEFORE Launch**
   - Fail fast if incompatible
   - User gets clear feedback immediately
   - Prevents wasted compute on bad scripts

3. **No Script Modification**
   - Original script executed as-is
   - User controls their code completely
   - Platform acts as orchestrator only

4. **Separation from CUSTOM_DOCKERFILE**
   - CUSTOM_DOCKERFILE keeps existing behavior
   - User has full control over environment
   - No automatic replacement even if detected

5. **Clear Messaging**
   - "Compatible" vs "Failed" distinction
   - No empty replacement = not an error
   - Detection ≠ Replacement

---

## Testing Checklist

- [ ] Test with PyTorch script → Launch succeeds, compatible message
- [ ] Test with unsupported module (transformers) → Launch fails, clear error
- [ ] Test with Sklearn → Launch succeeds, algorithms detected but no replacement
- [ ] Verify CUSTOM_DOCKERFILE not affected
- [ ] Verify MLflow sync doesn't restore replacements for STANDARD_RUNTIME
- [ ] Verify logs show "[MlExperimentService] Analyzing script compatibility..."
- [ ] Verify logs show "algorithm_injection_enabled = false"
- [ ] Test API endpoint exists: POST /api/runtime/analyze-script

---

## Rollback Plan

If issues arise:

1. Remove configuration flags from PipelineClientService
2. Revert STANDARD_RUNTIME section to force `algorithmReplacements` from user input
3. Remove `analyzeScriptCompatibility()` call
4. Remove RestTemplate injection
5. Revert `syncAlgorithmMetadataFromRunTags()` to original logic

---

## Notes

- RestTemplate must be configured in Spring context (usually via @Bean in Application config)
- Python API must return JSON with fields documented in `ScriptAnalysisResult`
- Log messages use `[MlExperimentService]` prefix for easy filtering
- Default API URL can be customized via `app.runtime.analysis-api-url` property
