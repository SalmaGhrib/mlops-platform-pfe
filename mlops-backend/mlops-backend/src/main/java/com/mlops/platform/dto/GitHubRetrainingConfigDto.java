package com.mlops.platform.dto;

import com.mlops.platform.entity.GitHubRetrainingConfig;
import java.time.LocalDateTime;

/**
 * Configuration d'intégration GitHub d'un projet.
 *
 * Ne contient JAMAIS la clé complète : seulement un indicateur
 * (keyConfigured) et les 4 derniers caractères (secretSuffix).
 */
public class GitHubRetrainingConfigDto {

    private Long projectId;
    private boolean enabled;
    private String repository;
    private String branch;
    private boolean keyConfigured;
    private String secretSuffix;
    private String lastCommitSha;
    private LocalDateTime lastTriggerAt;
    private String lastTriggerStatus;
    private String lastTriggerMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public GitHubRetrainingConfigDto() {
    }

    public static GitHubRetrainingConfigDto from(GitHubRetrainingConfig config) {
        GitHubRetrainingConfigDto dto = new GitHubRetrainingConfigDto();
        dto.projectId = config.getProjectId();
        dto.enabled = Boolean.TRUE.equals(config.getEnabled());
        dto.repository = config.getRepository();
        dto.branch = config.getBranch();
        dto.keyConfigured = config.isKeyConfigured();
        dto.secretSuffix = config.getSecretSuffix();
        dto.lastCommitSha = config.getLastCommitSha();
        dto.lastTriggerAt = config.getLastTriggerAt();
        dto.lastTriggerStatus = config.getLastTriggerStatus();
        dto.lastTriggerMessage = config.getLastTriggerMessage();
        dto.createdAt = config.getCreatedAt();
        dto.updatedAt = config.getUpdatedAt();
        return dto;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public boolean isKeyConfigured() {
        return keyConfigured;
    }

    public void setKeyConfigured(boolean keyConfigured) {
        this.keyConfigured = keyConfigured;
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

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}