package com.mlops.platform.dto;

/**
 * Réponse du lancement manuel d'un réentraînement (endpoint force-now).
 *
 * Le succès ne doit être vrai que lorsque l'expérience a réellement été
 * créée et que le Job Kubernetes / pipeline a été confirmé.
 */
public class ManualRetrainingResponse {

    private boolean success;
    private Long projectId;
    private Long experimentId;
    private String jobName;
    private String status;
    private String trigger;
    private String message;
    private String code;

    public ManualRetrainingResponse() {
    }

    public ManualRetrainingResponse(
            boolean success,
            Long projectId,
            Long experimentId,
            String jobName,
            String status,
            String trigger,
            String message,
            String code
    ) {
        this.success = success;
        this.projectId = projectId;
        this.experimentId = experimentId;
        this.jobName = jobName;
        this.status = status;
        this.trigger = trigger;
        this.message = message;
        this.code = code;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
