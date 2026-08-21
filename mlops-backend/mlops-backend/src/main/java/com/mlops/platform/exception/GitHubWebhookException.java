package com.mlops.platform.exception;

import org.springframework.http.HttpStatus;

/**
 * Rejet d'un déclenchement GitHub (clé invalide, repo/branche non
 * configurés, projet non autorisé, intégration désactivée, etc.).
 *
 * Porte le code de statut HTTP de la réponse ainsi qu'un code métier
 * lisible dans GitHubTriggerResponse (ex. INVALID_KEY, INVALID_REPOSITORY,
 * INVALID_BRANCH, PROJECT_MISMATCH, GITHUB_DISABLED).
 */
public class GitHubWebhookException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final Long projectId;
    private final String commitSha;

    public GitHubWebhookException(
            String code,
            HttpStatus httpStatus,
            Long projectId,
            String commitSha,
            String message
    ) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.projectId = projectId;
        this.commitSha = commitSha;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getCommitSha() {
        return commitSha;
    }
}