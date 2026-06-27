# Python API Integration - Script Analysis Endpoint

## Overview

Spring Boot now calls a Python API endpoint to analyze script compatibility with STANDARD_RUNTIME mode **before** launching.

Endpoint: `POST /api/runtime/analyze-script`
Default URL: `http://127.0.0.1:8001/api/runtime/analyze-script`

---

## API Contract

### Request

```json
{
  "script_path": "/path/to/script.py"
}
```

**Parameters:**
- `script_path` (string, required): Absolute or relative path to the Python training script

### Response

```json
{
  "detected_modules": ["torch", "torchvision", "sklearn"],
  "unsupported_modules": [],
  "supported_modules": ["torch", "torchvision", "sklearn"],
  "detected_algorithms": ["nn.Linear", "RandomForestClassifier"],
  "replaceable_algorithms": ["RandomForestClassifier"],
  "runtime_compatible": true,
  "message": "Script is compatible with STANDARD_RUNTIME",
  "recommendation": "Script can run as-is on the standard platform image"
}
```

**Response Fields:**
- `detected_modules` (array): Python modules/packages detected in imports
- `unsupported_modules` (array): Modules NOT available in standard image
- `supported_modules` (array): Modules available in standard image
- `detected_algorithms` (array): ML classes/components detected (e.g., RandomForestClassifier, nn.Linear)
- `replaceable_algorithms` (array): Which detected algorithms could be auto-replaced
- `runtime_compatible` (boolean): **Critical** - whether script can run on standard image
- `message` (string): Human-readable result summary
- `recommendation` (string): Suggestion for user (e.g., "Use CUSTOM_DOCKERFILE" if incompatible)

---

## Implementation Checklist

### Python Service (Pipeline Gateway)

- [ ] Create Flask/FastAPI endpoint at `/api/runtime/analyze-script`
- [ ] Accept POST requests with JSON body containing `script_path`
- [ ] Parse the Python script to extract:
  - All `import` and `from ... import` statements
  - Module names (top-level package only)
  - Class names for ML frameworks (e.g., `nn.Linear`, `RandomForestClassifier`)
- [ ] Check imported modules against **standard image module list**
- [ ] Return JSON response matching the contract above
- [ ] Handle errors gracefully (missing file, parse error, etc.)

### Standard Runtime Image Module List

Modules currently available in `salmaghrib2003/mini-mlops-runtime:latest`:

```python
# Core Python packages
pip
setuptools
wheel
packaging
importlib-metadata

# MLOps
mlflow==2.17.2
cloudpickle
pyyaml
requests

# Data & ML
numpy==1.26.4
pandas
scikit-learn
joblib

# Deep Learning (if available)
torch==2.2.0 (or similar)
torchvision==0.17.0
tensorflow==2.14.0 (if available)

# Others as needed
matplotlib
seaborn
# ... add more as they're available in your image
```

**Update this list** based on what's actually in your standard image.

### Example Implementation (Python)

```python
from flask import Flask, request, jsonify
import ast
import os

app = Flask(__name__)

# Modules available in standard image
SUPPORTED_MODULES = {
    'mlflow', 'numpy', 'pandas', 'sklearn', 'torch', 'torchvision',
    'tensorflow', 'joblib', 'cloudpickle', 'pyyaml', 'requests',
    'matplotlib', 'seaborn', 'pickle', 'json', 'csv', 'os', 'sys',
    'pathlib', 'collections', 'itertools', 'functools', 're', 'io'
}

# Machine learning classes/components to look for
ML_ALGORITHMS = {
    # Sklearn
    'RandomForestClassifier', 'RandomForestRegressor',
    'LogisticRegression', 'LinearRegression', 'SVC', 'KMeans',
    'GradientBoostingClassifier', 'AdaBoostClassifier',
    
    # PyTorch
    'nn.Linear', 'nn.Conv2d', 'nn.LSTM', 'nn.Transformer',
    'Module',
    
    # TensorFlow/Keras
    'Sequential', 'Model', 'Dense', 'Conv2D', 'LSTM',
}

@app.route('/api/runtime/analyze-script', methods=['POST'])
def analyze_script():
    try:
        data = request.get_json()
        script_path = data.get('script_path')
        
        if not script_path:
            return jsonify({
                'runtime_compatible': False,
                'message': 'script_path not provided',
                'detected_modules': [],
                'unsupported_modules': [],
                'detected_algorithms': [],
                'recommendation': 'Provide script_path in request body'
            }), 400
        
        # Read and parse script
        if not os.path.exists(script_path):
            return jsonify({
                'runtime_compatible': False,
                'message': f'Script file not found: {script_path}',
                'detected_modules': [],
                'unsupported_modules': [],
                'detected_algorithms': [],
                'recommendation': 'Verify file path is correct'
            }), 400
        
        with open(script_path, 'r', encoding='utf-8') as f:
            script_content = f.read()
        
        # Parse AST
        try:
            tree = ast.parse(script_content)
        except SyntaxError as e:
            return jsonify({
                'runtime_compatible': False,
                'message': f'Python syntax error: {e.msg} at line {e.lineno}',
                'detected_modules': [],
                'unsupported_modules': [],
                'detected_algorithms': [],
                'recommendation': 'Fix syntax errors in your script'
            }), 400
        
        # Extract imports
        detected_modules = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    module = alias.name.split('.')[0]  # Get top-level module
                    detected_modules.add(module)
            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    module = node.module.split('.')[0]
                    detected_modules.add(module)
        
        detected_modules = sorted(list(detected_modules))
        
        # Find unsupported
        unsupported = [m for m in detected_modules if m not in SUPPORTED_MODULES]
        supported = [m for m in detected_modules if m in SUPPORTED_MODULES]
        
        # Extract algorithm names
        detected_algorithms = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Name):
                for algo in ML_ALGORITHMS:
                    if algo in node.id:
                        detected_algorithms.add(node.id)
            elif isinstance(node, ast.Attribute):
                # For things like nn.Linear, torch.optim.SGD
                full_name = ast.unparse(node)
                for algo in ML_ALGORITHMS:
                    if algo in full_name:
                        detected_algorithms.add(full_name)
        
        detected_algorithms = sorted(list(detected_algorithms))
        
        # Determine compatibility
        runtime_compatible = len(unsupported) == 0
        
        # Build response
        response = {
            'detected_modules': detected_modules,
            'supported_modules': supported,
            'unsupported_modules': unsupported,
            'detected_algorithms': detected_algorithms,
            'replaceable_algorithms': [],  # Always empty for STANDARD_RUNTIME
            'runtime_compatible': runtime_compatible,
            'message': (
                'Script is compatible with STANDARD_RUNTIME' 
                if runtime_compatible
                else f'Script uses unsupported modules: {", ".join(unsupported)}'
            ),
            'recommendation': (
                'Script can run as-is on the standard platform image'
                if runtime_compatible
                else 'Use CUSTOM_DOCKERFILE mode with your own requirements'
            )
        }
        
        return jsonify(response), 200
        
    except Exception as e:
        return jsonify({
            'runtime_compatible': False,
            'message': f'API error: {str(e)}',
            'detected_modules': [],
            'unsupported_modules': [],
            'detected_algorithms': [],
            'recommendation': 'Contact system administrator'
        }), 500

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=8001, debug=True)
```

---

## Integration Steps

1. **Deploy endpoint** at `/api/runtime/analyze-script` in your Pipeline Gateway
2. **Update URL** in Spring Boot properties if different from default:
   ```properties
   app.runtime.analysis-api-url=http://127.0.0.1:8001/api/runtime/analyze-script
   ```
3. **Test endpoint** manually:
   ```bash
   curl -X POST http://127.0.0.1:8001/api/runtime/analyze-script \
     -H "Content-Type: application/json" \
     -d '{"script_path": "/path/to/test.py"}'
   ```
4. **Verify response** matches expected schema
5. **Redeploy Spring Boot** (or restart if running)

---

## Test Cases

### Test 1: Compatible PyTorch Script
**File:** `test_pytorch.py`
```python
import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import datasets, transforms

class SimpleNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.fc = nn.Linear(784, 10)
    
    def forward(self, x):
        return self.fc(x)
```

**Expected Response:**
```json
{
  "runtime_compatible": true,
  "detected_modules": ["torch", "torchvision"],
  "unsupported_modules": [],
  "detected_algorithms": ["SimpleNet", "nn.Linear", "optim"],
  "message": "Script is compatible with STANDARD_RUNTIME"
}
```

### Test 2: Incompatible - Unsupported Module
**File:** `test_transformers.py`
```python
from transformers import BertModel, BertTokenizer
import torch

model = BertModel.from_pretrained('bert-base-uncased')
```

**Expected Response:**
```json
{
  "runtime_compatible": false,
  "detected_modules": ["transformers", "torch"],
  "unsupported_modules": ["transformers"],
  "message": "Script uses unsupported modules: transformers",
  "recommendation": "Use CUSTOM_DOCKERFILE mode with your own requirements"
}
```

### Test 3: Compatible Sklearn
**File:** `test_sklearn.py`
```python
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
import pandas as pd
import numpy as np

clf = RandomForestClassifier(n_estimators=100)
```

**Expected Response:**
```json
{
  "runtime_compatible": true,
  "detected_modules": ["sklearn", "pandas", "numpy"],
  "unsupported_modules": [],
  "detected_algorithms": ["RandomForestClassifier"],
  "message": "Script is compatible with STANDARD_RUNTIME"
}
```

---

## Error Handling

### Missing File
**Request:**
```json
{"script_path": "/nonexistent/file.py"}
```

**Response:** 
```json
{
  "runtime_compatible": false,
  "message": "Script file not found: /nonexistent/file.py",
  "recommendation": "Verify file path is correct"
}
```

### Syntax Error
**Response:**
```json
{
  "runtime_compatible": false,
  "message": "Python syntax error: invalid syntax at line 5",
  "recommendation": "Fix syntax errors in your script"
}
```

### No Body
**Response:** 400 Bad Request
```json
{
  "runtime_compatible": false,
  "message": "script_path not provided"
}
```

---

## Spring Boot Logs

When working correctly, you'll see logs like:

```
[MlExperimentService] Analyzing script compatibility for STANDARD_RUNTIME...
[MlExperimentService] Calling script analysis API: http://127.0.0.1:8001/api/runtime/analyze-script
[MlExperimentService] Script path: /uploads/projects/10/train.py
[MlExperimentService] Script analysis result: runtimeCompatible=true, unsupported=, detected=nn.Linear, RandomForestClassifier
[MlExperimentService] Script compatible with STANDARD_RUNTIME. No automatic replacement applied. Launching with original script.
```

If incompatible:

```
[MlExperimentService] Script analysis result: runtimeCompatible=false, unsupported=transformers, detected=BertModel
[MlExperimentService] Script not compatible with STANDARD_RUNTIME: Unsupported modules: transformers
[MlExperimentService] FAILED
```

---

## Fallback Behavior

If the Python API is unreachable:

```
[MlExperimentService] Script analysis API call failed: Connection refused
→ ScriptAnalysisResult with runtimeCompatible=false
→ Launch blocked with clear error message
→ User sees: "Script analysis failed: Connection refused. Check system configuration."
```

**Recommendation:** Configure monitoring to alert if this endpoint is down.

---

## Future Enhancements

- Add caching of analysis results (script hash → result)
- Add timeout to API calls (currently uses Spring's default)
- Support custom module whitelist per tenant
- Track which modules are used most frequently
- Provide module version compatibility matrix
- Auto-suggest compatible alternatives for unsupported modules
