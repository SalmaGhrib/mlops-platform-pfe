package com.mlops.platform.dto;

/**
 * Réponse du webhook de déclenchement GitHub.
 *
 * status peut valoir ACCEPTED, ALREADY_PROCESSED, REJECTED, BLOCKED_NO_SOURCE,
 * RETRAINING_ALREADY_RUNNING selon la décision prise.
 */
public class GitHubTriggerResponse {

    private String status;
    private String code;
    private Long projectId;
    private String repository;
    private String branch;
    private String commitSha;
    private Long experimentId;
    private String message;

    public GitHubTriggerResponse() {
    }

    public static GitHubTriggerResponse accepted(
            Long projectId,
            String repository,
            String branch,
            String commitSha,
            Long experimentId
    ) {
        GitHubTriggerResponse r = new GitHubTriggerResponse();
        r.status = "ACCEPTED";
        r.code = "ACCEPTED";
        r.projectId = projectId;
        r.repository = repository;
        r.branch = branch;
        r.commitSha = commitSha;
        r.experimentId = experimentId;
        r.message = "Retraining accepted.";
        return r;
    }

    public static GitHubTriggerResponse duplicate(Long projectId, String commitSha) {
        GitHubTriggerResponse r = new GitHubTriggerResponse();
        r.status = "ALREADY_PROCESSED";
        r.code = "ALREADY_PROCESSED";
        r.projectId = projectId;
        r.commitSha = commitSha;
        r.message = "Commit already processed. No new retraining created.";
        return r;
    }

    public static GitHubTriggerResponse rejected(
            Long projectId,
            String commitSha,
            String code,
            String message
    ) {
        GitHubTriggerResponse r = new GitHubTriggerResponse();
        r.status = "REJECTED";
        r.code = code;
        r.projectId = projectId;
        r.commitSha = commitSha;
        r.message = message;
        return r;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
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

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}