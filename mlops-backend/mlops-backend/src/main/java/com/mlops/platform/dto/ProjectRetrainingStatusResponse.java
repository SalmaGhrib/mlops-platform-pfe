package com.mlops.platform.dto;

public class ProjectRetrainingStatusResponse {

    private Long projectId;
    private boolean enabled;
    private boolean running;
    private boolean manualTriggerRequested;
    private boolean activeExperimentExists;
    private String status;
    private String message;
    private String nextRetrainingAt;
    private String lastRetrainingAt;
    private String lastDecisionReason;
    private String lastTriggerReason;

    public ProjectRetrainingStatusResponse() {
    }

    public ProjectRetrainingStatusResponse(
            Long projectId,
            boolean enabled,
            boolean running,
            boolean manualTriggerRequested,
            boolean activeExperimentExists,
            String status,
            String message,
            String nextRetrainingAt,
            String lastRetrainingAt,
            String lastDecisionReason,
            String lastTriggerReason
    ) {
        this.projectId = projectId;
        this.enabled = enabled;
        this.running = running;
        this.manualTriggerRequested = manualTriggerRequested;
        this.activeExperimentExists = activeExperimentExists;
        this.status = status;
        this.message = message;
        this.nextRetrainingAt = nextRetrainingAt;
        this.lastRetrainingAt = lastRetrainingAt;
        this.lastDecisionReason = lastDecisionReason;
        this.lastTriggerReason = lastTriggerReason;
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

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isManualTriggerRequested() {
        return manualTriggerRequested;
    }

    public void setManualTriggerRequested(boolean manualTriggerRequested) {
        this.manualTriggerRequested = manualTriggerRequested;
    }

    public boolean isActiveExperimentExists() {
        return activeExperimentExists;
    }

    public void setActiveExperimentExists(boolean activeExperimentExists) {
        this.activeExperimentExists = activeExperimentExists;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getNextRetrainingAt() {
        return nextRetrainingAt;
    }

    public void setNextRetrainingAt(String nextRetrainingAt) {
        this.nextRetrainingAt = nextRetrainingAt;
    }

    public String getLastRetrainingAt() {
        return lastRetrainingAt;
    }

    public void setLastRetrainingAt(String lastRetrainingAt) {
        this.lastRetrainingAt = lastRetrainingAt;
    }

    public String getLastDecisionReason() {
        return lastDecisionReason;
    }

    public void setLastDecisionReason(String lastDecisionReason) {
        this.lastDecisionReason = lastDecisionReason;
    }

    public String getLastTriggerReason() {
        return lastTriggerReason;
    }

    public void setLastTriggerReason(String lastTriggerReason) {
        this.lastTriggerReason = lastTriggerReason;
    }
}
