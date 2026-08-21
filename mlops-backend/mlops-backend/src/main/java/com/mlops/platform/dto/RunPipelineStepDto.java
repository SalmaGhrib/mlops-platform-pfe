package com.mlops.platform.dto;

/**
 * Étape de pipeline exposée au frontend.
 * Le frontend traduit stepCode en label via ses propres définitions de pipeline.
 */
public class RunPipelineStepDto {

    private String stepCode;
    private String label;
    private String executionMode;
    private String status;
    private Integer progress;
    private Integer orderIndex;
    private String startedAt;
    private String completedAt;
    private String message;
    private String errorCode;
    private String errorDetails;
    private String jobName;
    private String podName;
    private String externalId;
    private String updatedAt;
    private boolean hasLogs;

    public String getStepCode() { return stepCode; }
    public void setStepCode(String stepCode) { this.stepCode = stepCode; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorDetails() { return errorDetails; }
    public void setErrorDetails(String errorDetails) { this.errorDetails = errorDetails; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public boolean isHasLogs() { return hasLogs; }
    public void setHasLogs(boolean hasLogs) { this.hasLogs = hasLogs; }
}
