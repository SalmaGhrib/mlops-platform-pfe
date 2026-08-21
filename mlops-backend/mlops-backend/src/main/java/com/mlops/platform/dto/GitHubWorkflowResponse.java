package com.mlops.platform.dto;

/**
 * Workflow GitHub Actions fourni par la plateforme, déjà adapté au projet
 * (projectId et branche remplacés). L'utilisateur n'a qu'à le coller dans
 * son dépôt à .github/workflows/retraining.yml.
 */
public class GitHubWorkflowResponse {

    private String workflow;

    public GitHubWorkflowResponse() {
    }

    public GitHubWorkflowResponse(String workflow) {
        this.workflow = workflow;
    }

    public String getWorkflow() {
        return workflow;
    }

    public void setWorkflow(String workflow) {
        this.workflow = workflow;
    }
}