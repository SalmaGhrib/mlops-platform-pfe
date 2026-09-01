package com.mlops.platform.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ModelApiKeyStatsResponse {

    private Long totalToday;
    private Long successToday;
    private Long failedToday;
    private Double successRate;
    private Double avgLatencyMs;
    private LocalDateTime lastUsedAt;
    private List<ModelApiKeyResponse> keys;

    public ModelApiKeyStatsResponse() {
    }

    public Long getTotalToday() { return totalToday; }
    public void setTotalToday(Long totalToday) { this.totalToday = totalToday; }

    public Long getSuccessToday() { return successToday; }
    public void setSuccessToday(Long successToday) { this.successToday = successToday; }

    public Long getFailedToday() { return failedToday; }
    public void setFailedToday(Long failedToday) { this.failedToday = failedToday; }

    public Double getSuccessRate() { return successRate; }
    public void setSuccessRate(Double successRate) { this.successRate = successRate; }

    public Double getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public List<ModelApiKeyResponse> getKeys() { return keys; }
    public void setKeys(List<ModelApiKeyResponse> keys) { this.keys = keys; }
}
