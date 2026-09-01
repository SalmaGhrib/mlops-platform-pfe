package com.mlops.platform.dto;

import com.mlops.platform.entity.ModelApiKey;

import java.time.LocalDateTime;

public class ModelApiKeyResponse {

    private Long id;
    private Long deploymentId;
    private Long ownerId;
    private String name;
    private String keyPrefix;
    private String maskedKey;
    private String apiKey;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private Long totalRequests;
    private Long successfulRequests;
    private Long failedRequests;
    private String status;

    public ModelApiKeyResponse() {
    }

    public static ModelApiKeyResponse fromEntity(ModelApiKey key) {
        ModelApiKeyResponse response = new ModelApiKeyResponse();
        response.setId(key.getId());
        response.setDeploymentId(key.getDeploymentId());
        response.setOwnerId(key.getOwnerId());
        response.setName(key.getName());
        response.setKeyPrefix(key.getKeyPrefix());
        response.setMaskedKey(buildMaskedKey(key));
        response.setEnabled(key.getEnabled());
        response.setCreatedAt(key.getCreatedAt());
        response.setExpiresAt(key.getExpiresAt());
        response.setRevokedAt(key.getRevokedAt());
        response.setLastUsedAt(key.getLastUsedAt());
        response.setTotalRequests(key.getTotalRequests());
        response.setSuccessfulRequests(key.getSuccessfulRequests());
        response.setFailedRequests(key.getFailedRequests());
        response.setStatus(computeStatus(key));
        return response;
    }

    private static String computeStatus(ModelApiKey key) {
        if (key.getRevokedAt() != null) {
            return "REVOKED";
        }
        if (Boolean.FALSE.equals(key.getEnabled())) {
            return "DISABLED";
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isAfter(LocalDateTime.now()) == false
                && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    public static String buildMaskedKey(ModelApiKey key) {
        if (key == null || key.getKeyPrefix() == null || key.getKeyPrefix().isBlank()) {
            return null;
        }
        return key.getKeyPrefix() + "\u2022\u2022\u2022\u2022\u2022\u2022";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDeploymentId() { return deploymentId; }
    public void setDeploymentId(Long deploymentId) { this.deploymentId = deploymentId; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public String getMaskedKey() { return maskedKey; }
    public void setMaskedKey(String maskedKey) { this.maskedKey = maskedKey; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(Long totalRequests) { this.totalRequests = totalRequests; }

    public Long getSuccessfulRequests() { return successfulRequests; }
    public void setSuccessfulRequests(Long successfulRequests) { this.successfulRequests = successfulRequests; }

    public Long getFailedRequests() { return failedRequests; }
    public void setFailedRequests(Long failedRequests) { this.failedRequests = failedRequests; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
