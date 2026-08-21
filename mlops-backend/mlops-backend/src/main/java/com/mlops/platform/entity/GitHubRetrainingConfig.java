package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Intégration GitHub → Réentraînement, liée à UN projet MLOps.
 *
 * Principe de sécurité : la clé de retraining n'est affichée qu'une seule fois,
 * à sa génération. En base on ne stocke jamais la clé en clair, uniquement son
 * hash SHA-256 (secretHash) et ses 4 derniers caractères (secretSuffix) pour
 * l'affichage. La clé est strictement liée au projectId de cette ligne.
 */
@Entity
@Table(
        name = "github_retraining_config",
        indexes = {
                @Index(name = "uk_ghrc_project", columnList = "project_id", unique = true),
                @Index(name = "uk_ghrc_secret_hash", columnList = "secret_hash", unique = true)
        }
)
public class GitHubRetrainingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "repository", length = 255)
    private String repository;

    @Column(name = "branch", length = 255)
    private String branch;

    @Column(name = "secret_hash", length = 64)
    private String secretHash;

    @Column(name = "secret_suffix", length = 8)
    private String secretSuffix;

    @Column(name = "last_commit_sha", length = 80)
    private String lastCommitSha;

    @Column(name = "last_trigger_at")
    private LocalDateTime lastTriggerAt;

    @Column(name = "last_trigger_status", length = 40)
    private String lastTriggerStatus;

    @Column(name = "last_trigger_message", columnDefinition = "TEXT")
    private String lastTriggerMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public GitHubRetrainingConfig() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (enabled == null) {
            enabled = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isKeyConfigured() {
        return secretHash != null && !secretHash.isBlank();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled != null ? enabled : false;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public String getSecretSuffix() {
        return secretSuffix;
    }

    public void setSecretSuffix(String secretSuffix) {
        this.secretSuffix = secretSuffix;
    }

    public String getLastCommitSha() {
        return lastCommitSha;
    }

    public void setLastCommitSha(String lastCommitSha) {
        this.lastCommitSha = lastCommitSha;
    }

    public LocalDateTime getLastTriggerAt() {
        return lastTriggerAt;
    }

    public void setLastTriggerAt(LocalDateTime lastTriggerAt) {
        this.lastTriggerAt = lastTriggerAt;
    }

    public String getLastTriggerStatus() {
        return lastTriggerStatus;
    }

    public void setLastTriggerStatus(String lastTriggerStatus) {
        this.lastTriggerStatus = lastTriggerStatus;
    }

    public String getLastTriggerMessage() {
        return lastTriggerMessage;
    }

    public void setLastTriggerMessage(String lastTriggerMessage) {
        this.lastTriggerMessage = lastTriggerMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}