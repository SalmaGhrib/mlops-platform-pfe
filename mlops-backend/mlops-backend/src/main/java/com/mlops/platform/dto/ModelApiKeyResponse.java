package com.mlops.platform.dto;

import com.mlops.platform.entity.ModelApiKey;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Réponse d'une clé API SMART MODEL GATEWAY.
 *
 * - `apiKey` (clé complète) n'est présent QUE dans la réponse de création.
 * - `maskedKey` est toujours présent : préfixe + derniers caractères.
 */
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
    private Long dailyQuota;
    private Long requestsToday;
    private Long totalRequests;
    private LocalDate usageDate;
    private Long quotaRemaining;

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
        response.setDailyQuota(key.getDailyQuota());
        response.setRequestsToday(key.getRequestsToday());
        response.setTotalRequests(key.getTotalRequests());
        response.setUsageDate(key.getUsageDate());
        return response;
    }

    public static String buildMaskedKey(ModelApiKey key) {
        if (key == null || key.getKeyPrefix() == null || key.getKeyPrefix().isBlank()) {
            return null;
        }
        return key.getKeyPrefix() + "\u2022\u2022\u2022\u2022\u2022\u2022";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getMaskedKey() {
        return maskedKey;
    }

    public void setMaskedKey(String maskedKey) {
        this.maskedKey = maskedKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Long getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(Long dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    public Long getRequestsToday() {
        return requestsToday;
    }

    public void setRequestsToday(Long requestsToday) {
        this.requestsToday = requestsToday;
    }

    public Long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(Long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public Long getQuotaRemaining() {
        return quotaRemaining;
    }

    public void setQuotaRemaining(Long quotaRemaining) {
        this.quotaRemaining = quotaRemaining;
    }
}
