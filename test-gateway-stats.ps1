# Test Smart Gateway Stats
# 1. Login to get JWT
# 2. Create API key for wind_speed_model
# 3. Make external prediction
# 4. Check stats

$BASE = "http://128.251.112.197"

# Step 1: Login
Write-Host "=== Step 1: Login ==="
$loginBody = '{"email":"admin@mlops-platform.com","password":"admin123"}'
try {
    $loginResp = Invoke-RestMethod -Uri "$BASE/api/auth/login" -Method POST -Body $loginBody -ContentType "application/json" -ErrorAction Stop
    $token = $loginResp.token
    Write-Host "Login OK, token: $($token.Substring(0, [Math]::Min(20, $token.Length)))..."
} catch {
    Write-Host "Login failed: $_"
    # Try alternative password
    try {
        $loginBody = '{"email":"admin@mlops-platform.com","password":"Admin123!"}'
        $loginResp = Invoke-RestMethod -Uri "$BASE/api/auth/login" -Method POST -Body $loginBody -ContentType "application/json" -ErrorAction Stop
        $token = $loginResp.token
        Write-Host "Login OK (alt), token: $($token.Substring(0, [Math]::Min(20, $token.Length)))..."
    } catch {
        Write-Host "Alt login also failed: $_"
        exit 1
    }
}

$headers = @{ "Authorization" = "Bearer $token" }

# Step 2: Get deployment ID for wind_speed_model
Write-Host "`n=== Step 2: Get model deployments ==="
try {
    $deployments = Invoke-RestMethod -Uri "$BASE/api/model-deployments" -Method GET -Headers $headers -ErrorAction Stop
    $wsDep = $deployments | Where-Object { $_.registeredModelName -eq "wind_speed_model" -and $_.active -eq $true } | Select-Object -First 1
    $depId = $wsDep.id
    Write-Host "wind_speed_model deployment ID: $depId"
} catch {
    Write-Host "Failed to get deployments: $_"
    exit 1
}

# Step 3: Get current stats
Write-Host "`n=== Step 3: Current stats ==="
try {
    $statsBefore = Invoke-RestMethod -Uri "$BASE/api/models/$depId/api-keys/stats" -Method GET -Headers $headers -ErrorAction Stop
    Write-Host "BEFORE - totalToday: $($statsBefore.totalToday), successRate: $($statsBefore.successRate), avgLatencyMs: $($statsBefore.avgLatencyMs)"
} catch {
    Write-Host "Stats failed: $_"
}

# Step 4: Create API key if needed
Write-Host "`n=== Step 4: Check/create API key ==="
$apiKey = $null
try {
    $keys = Invoke-RestMethod -Uri "$BASE/api/models/$depId/api-keys/my" -Method GET -Headers $headers -ErrorAction Stop
    if ($keys) {
        Write-Host "Existing key found: $($keys.keyPrefix)"
        # We need the raw key - try to create a new one
    }
} catch {
    Write-Host "No existing key"
}

# Create a new key
try {
    $createBody = '{"name":"test-stats-key"}'
    $newKey = Invoke-RestMethod -Uri "$BASE/api/models/$depId/api-keys" -Method POST -Body $createBody -ContentType "application/json" -Headers $headers -ErrorAction Stop
    $apiKey = $newKey.apiKey
    Write-Host "New key created: $($apiKey.Substring(0, [Math]::Min(20, $apiKey.Length)))..."
} catch {
    Write-Host "Failed to create key: $_"
}

if (-not $apiKey) {
    Write-Host "No API key available. Cannot test."
    exit 1
}

# Step 5: Make external prediction
Write-Host "`n=== Step 5: Make external prediction ==="
$predictBody = '{"rows":[{"feature1":1.0,"feature2":2.0}]}'
try {
    $predictResp = Invoke-RestMethod -Uri "$BASE/api/external/models/wind_speed_model/predict" -Method POST -Body $predictBody -ContentType "application/json" -Headers @{"X-API-KEY"=$apiKey} -ErrorAction Stop
    Write-Host "Prediction OK: status=$($predictResp.status)"
} catch {
    Write-Host "Prediction failed (may be expected): $_"
}

# Step 6: Check stats after
Write-Host "`n=== Step 6: Stats after prediction ==="
Start-Sleep -Seconds 2
try {
    $statsAfter = Invoke-RestMethod -Uri "$BASE/api/models/$depId/api-keys/stats" -Method GET -Headers $headers -ErrorAction Stop
    Write-Host "AFTER  - totalToday: $($statsAfter.totalToday), successRate: $($statsAfter.successRate), avgLatencyMs: $($statsAfter.avgLatencyMs)"
} catch {
    Write-Host "Stats failed: $_"
}

# Step 7: Make 3 more calls
Write-Host "`n=== Step 7: Make 3 more calls ==="
for ($i = 1; $i -le 3; $i++) {
    try {
        $predictResp = Invoke-RestMethod -Uri "$BASE/api/external/models/wind_speed_model/predict" -Method POST -Body $predictBody -ContentType "application/json" -Headers @{"X-API-KEY"=$apiKey} -ErrorAction Stop
        Write-Host "Call $i OK: status=$($predictResp.status)"
    } catch {
        Write-Host "Call $i failed: $_"
    }
    Start-Sleep -Milliseconds 500
}

# Step 8: Final stats
Write-Host "`n=== Step 8: Final stats ==="
Start-Sleep -Seconds 2
try {
    $statsFinal = Invoke-RestMethod -Uri "$BASE/api/models/$depId/api-keys/stats" -Method GET -Headers $headers -ErrorAction Stop
    Write-Host "FINAL  - totalToday: $($statsFinal.totalToday), successRate: $($statsFinal.successRate), avgLatencyMs: $($statsFinal.avgLatencyMs)"
    Write-Host "        keys: $($statsFinal.keys.Count)"
} catch {
    Write-Host "Stats failed: $_"
}

Write-Host "`n=== Done ==="
