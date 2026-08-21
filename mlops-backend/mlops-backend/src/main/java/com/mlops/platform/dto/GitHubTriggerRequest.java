package com.mlops.platform.dto;

/**
 * Payload du webhook GitHub vers la plateforme MLOps.
 *
 * La clé d'authentification est portée par l'en-tête X-RETRAINING-KEY et
 * non par le corps : le projectId envoyé n'est JAMAIS suffisant à lui seul.
 */
public class GitHubTriggerRequest {

    private Long projectId;
    private String triggerType;
    private String repository;
    private String branch;
    private String commitSha;

    public GitHubTriggerRequest() {
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
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
}