package com.mlops.platform.dto;

/**
 * Demande de configuration de l'intégration GitHub d'un projet.
 */
public class GitHubConfigRequest {

    private boolean enabled;
    private String repository;
    private String branch;

    public GitHubConfigRequest() {
    }

    public GitHubConfigRequest(boolean enabled, String repository, String branch) {
        this.enabled = enabled;
        this.repository = repository;
        this.branch = branch;
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
}