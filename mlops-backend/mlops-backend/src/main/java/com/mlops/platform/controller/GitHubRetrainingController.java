package com.mlops.platform.controller;

import com.mlops.platform.dto.GitHubConfigRequest;
import com.mlops.platform.dto.GitHubKeyResponse;
import com.mlops.platform.dto.GitHubRetrainingConfigDto;
import com.mlops.platform.dto.GitHubTriggerRequest;
import com.mlops.platform.dto.GitHubTriggerResponse;
import com.mlops.platform.dto.GitHubWorkflowResponse;
import com.mlops.platform.exception.GitHubWebhookException;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.service.GitHubRetrainingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/retraining")
@CrossOrigin(origins = "http://localhost:4200")
public class GitHubRetrainingController {

    public static final String API_KEY_HEADER = "X-RETRAINING-KEY";

    private final GitHubRetrainingService gitHubRetrainingService;

    public GitHubRetrainingController(
            GitHubRetrainingService gitHubRetrainingService
    ) {
        this.gitHubRetrainingService = gitHubRetrainingService;
    }

    /*
     * Webhook appelé par le workflow GitHub Actions fourni par la plateforme.
     * Authentification par clé de retraining (header X-RETRAINING-KEY), jamais
     * par le projectId du corps.
     */
    @PostMapping("/external/trigger")
    public ResponseEntity<GitHubTriggerResponse> externalTrigger(
            @RequestHeader(name = API_KEY_HEADER, required = false) String retrainingKey,
            @RequestBody GitHubTriggerRequest request
    ) {
        try {
            GitHubTriggerResponse response =
                    gitHubRetrainingService.handleExternalTrigger(
                            retrainingKey,
                            request
                    );

            HttpStatus status = "ALREADY_PROCESSED"
                    .equals(response.getStatus())
                    ? HttpStatus.OK
                    : HttpStatus.ACCEPTED;

            return ResponseEntity.status(status).body(response);

        } catch (GitHubWebhookException webhook) {
            return ResponseEntity
                    .status(webhook.getHttpStatus())
                    .body(GitHubTriggerResponse.rejected(
                            webhook.getProjectId(),
                            webhook.getCommitSha(),
                            webhook.getCode(),
                            safeMessage(webhook.getMessage())
                    ));

        } catch (RetrainingConflictException conflict) {
            GitHubTriggerResponse body = buildConflictBody(request);

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(body);

        } catch (IllegalStateException noSource) {
            boolean noSourceFlag = safeMessage(noSource.getMessage())
                    .toLowerCase()
                    .contains("no valid production source");

            if (noSourceFlag) {
                return ResponseEntity
                        .status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(GitHubTriggerResponse.rejected(
                                request.getProjectId(),
                                request.getCommitSha(),
                                "BLOCKED_NO_SOURCE",
                                "Réentraînement impossible : aucun modèle "
                                        + "Production valide n'a été trouvé."
                        ));
            }

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(GitHubTriggerResponse.rejected(
                            request.getProjectId(),
                            request.getCommitSha(),
                            "RETRAINING_ERROR",
                            safeMessage(noSource.getMessage())
                    ));

        } catch (Exception error) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(GitHubTriggerResponse.rejected(
                            request.getProjectId(),
                            request.getCommitSha(),
                            "RETRAINING_ERROR",
                            safeMessage(error.getMessage())
                    ));
        }
    }

    /*
     * Lecture de la configuration GitHub d'un projet.
     */
    @GetMapping("/github/projects/{projectId}/config")
    public ResponseEntity<GitHubRetrainingConfigDto> getGitHubConfig(
            @PathVariable Long projectId
    ) {
        return ResponseEntity.ok(
                gitHubRetrainingService.getConfig(projectId)
        );
    }

    /*
     * Création / mise à jour de la configuration GitHub d'un projet
     * (repository + branche + activation).
     */
    @PutMapping("/github/projects/{projectId}/config")
    public ResponseEntity<GitHubRetrainingConfigDto> saveGitHubConfig(
            @PathVariable Long projectId,
            @RequestBody GitHubConfigRequest request
    ) {
        return ResponseEntity.ok(
                gitHubRetrainingService.upsertConfig(projectId, request)
        );
    }

    /*
     * Génération d'une nouvelle clé de retraining. Renvoyée en clair UNE SEULE
     * fois. Toute génération annule les clés précédentes du projet.
     */
    @PostMapping("/github/projects/{projectId}/key")
    public ResponseEntity<GitHubKeyResponse> generateKey(
            @PathVariable Long projectId
    ) {
        return ResponseEntity.ok(
                gitHubRetrainingService.generateKey(projectId)
        );
    }

    /*
     * Révocation immédiate de la clé de retraining du projet.
     * Tout webhook utilisant cette clé sera ensuite refusé.
     */
    @DeleteMapping("/github/projects/{projectId}/key")
    public ResponseEntity<GitHubRetrainingConfigDto> revokeKey(
            @PathVariable Long projectId
    ) {
        return ResponseEntity.ok(
                gitHubRetrainingService.revokeKey(projectId)
        );
    }

    /*
     * Workflow GitHub Actions prêt à l'emploi, adapté au projet.
     */
    @GetMapping("/github/projects/{projectId}/workflow")
    public ResponseEntity<GitHubWorkflowResponse> getGitHubWorkflow(
            @PathVariable Long projectId
    ) {
        return ResponseEntity.ok(
                gitHubRetrainingService.getWorkflow(projectId)
        );
    }

    private GitHubTriggerResponse buildConflictBody(
            GitHubTriggerRequest request
    ) {
        return GitHubTriggerResponse.rejected(
                request.getProjectId(),
                request.getCommitSha(),
                "RETRAINING_ALREADY_RUNNING",
                "Un réentraînement est déjà en cours pour ce projet."
        );
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Erreur inconnue";
        }

        String clean = message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();

        return clean.length() > 500
                ? clean.substring(0, 497) + "..."
                : clean;
    }
}