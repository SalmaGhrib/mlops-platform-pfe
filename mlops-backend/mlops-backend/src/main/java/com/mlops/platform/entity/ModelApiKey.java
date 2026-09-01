package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/*
 * Cle API SMART MODEL GATEWAY.
 *
 * Principe de securite :
 * - le secret complet n'est affiche qu'une seule fois, a la creation
 * - en base, on ne stocke que le prefixe (pour la recherche) + un hash du secret
 * - jamais de cle en clair en base
 */
@Entity
@Table(
        name = "model_api_keys",
        indexes = {
                @Index(name = "idx_model_api_keys_deployment", columnList = "deployment_id"),
                @Index(name = "idx_model_api_keys_prefix", columnList = "key_prefix"),
                @Index(name = "idx_model_api_keys_owner", columnList = "owner_id")
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

    @Column(name = "total_requests", nullable = false)
    private Long totalRequests = 0L;

    @Column(name = "successful_requests", nullable = false)
    private Long successfulRequests = 0L;

    @Column(name = "failed_requests", nullable = false)
    private Long failedRequests = 0L;

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
        if (totalRequests == null) {
            totalRequests = 0L;
        }
    }

    public boolean isActiveKey(LocalDateTime now) {
        return Boolean.TRUE.equals(enabled)
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public Long getId() { return id; }

    public Long getDeploymentId() { return deploymentId; }
    public void setDeploymentId(Long deploymentId) { this.deploymentId = deploymentId; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = cleanText(name); }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = cleanText(keyPrefix); }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = cleanText(keyHash); }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled != null ? enabled : false; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(Long totalRequests) { this.totalRequests = totalRequests != null ? totalRequests : 0L; }

    public Long getSuccessfulRequests() { return successfulRequests; }
    public void setSuccessfulRequests(Long successfulRequests) { this.successfulRequests = successfulRequests != null ? successfulRequests : 0L; }

    public Long getFailedRequests() { return failedRequests; }
    public void setFailedRequests(Long failedRequests) { this.failedRequests = failedRequests != null ? failedRequests : 0L; }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
