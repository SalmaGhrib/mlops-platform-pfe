package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Clé API SMART MODEL GATEWAY.
 *
 * Principe de sécurité :
 * - le secret complet n'est affiché qu'une seule fois, à la création
 * - en base, on ne stocke que le préfixe (pour la recherche) + un hash du secret
 * - jamais de clé en clair en base
 */
@Entity
@Table(
        name = "model_api_keys",
        indexes = {
                @Index(name = "idx_model_api_keys_deployment", columnList = "deployment_id"),
                @Index(name = "idx_model_api_keys_prefix", columnList = "key_prefix")
        }
)
public class ModelApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 60, unique = true)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "daily_quota")
    private Long dailyQuota;

    @Column(name = "requests_today", nullable = false)
    private Long requestsToday = 0L;

    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "total_requests", nullable = false)
    private Long totalRequests = 0L;

    public ModelApiKey() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (requestsToday == null) {
            requestsToday = 0L;
        }
        if (totalRequests == null) {
            totalRequests = 0L;
        }
    }

    public boolean isActiveKey(LocalDateTime now) {
        return Boolean.TRUE.equals(enabled)
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public Long getId() {
        return id;
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
        this.name = cleanText(name);
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = cleanText(keyPrefix);
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = cleanText(keyHash);
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled != null ? enabled : false;
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
        this.requestsToday = requestsToday != null ? requestsToday : 0L;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public Long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(Long totalRequests) {
        this.totalRequests = totalRequests != null ? totalRequests : 0L;
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
