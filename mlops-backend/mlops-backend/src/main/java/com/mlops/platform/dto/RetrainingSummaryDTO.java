package com.mlops.platform.dto;

/**
 * Résumé unique de l'état de retraining renvoyé au frontend.
 * La carte projet consomme UNIQUEMENT ce DTO (jamais du texte générique
 * reconstruit côté Angular) afin que la stratégie affichée soit exactement
 * celle persistée en base.
 */
public class RetrainingSummaryDTO {

    private Long projectId;
    private boolean enabled;
    private String strategy = "MONITORING_SMART";
    private String state = "IDLE";
    private String lastRetrainingAt;
    private String lastMonitoringCheckAt;
    private String nextMonitoringCheckAt;
    private String lastTriggerReason;
    private boolean running;
    private String cooldownUntil;

    public RetrainingSummaryDTO() {
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

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getLastRetrainingAt() {
        return lastRetrainingAt;
    }

    public void setLastRetrainingAt(String lastRetrainingAt) {
        this.lastRetrainingAt = lastRetrainingAt;
    }

    public String getLastMonitoringCheckAt() {
        return lastMonitoringCheckAt;
    }

    public void setLastMonitoringCheckAt(String lastMonitoringCheckAt) {
        this.lastMonitoringCheckAt = lastMonitoringCheckAt;
    }

    public String getNextMonitoringCheckAt() {
        return nextMonitoringCheckAt;
    }

    public void setNextMonitoringCheckAt(String nextMonitoringCheckAt) {
        this.nextMonitoringCheckAt = nextMonitoringCheckAt;
    }

    public String getLastTriggerReason() {
        return lastTriggerReason;
    }

    public void setLastTriggerReason(String lastTriggerReason) {
        this.lastTriggerReason = lastTriggerReason;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(String cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }
}