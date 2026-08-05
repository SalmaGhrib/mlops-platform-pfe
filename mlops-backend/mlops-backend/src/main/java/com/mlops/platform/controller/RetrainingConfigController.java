package com.mlops.platform.controller;

import com.mlops.platform.dto.ManualRetrainingResponse;
import com.mlops.platform.dto.ProjectRetrainingStatusResponse;
import com.mlops.platform.dto.RetrainingConfigRequest;
import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.service.AutoRetrainingService;
import com.mlops.platform.service.RetrainingConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/retraining")
@CrossOrigin(origins = "http://localhost:4200")
public class RetrainingConfigController {

    private final RetrainingConfigService retrainingConfigService;
    private final AutoRetrainingService autoRetrainingService;

    public RetrainingConfigController(
            RetrainingConfigService retrainingConfigService,
            AutoRetrainingService autoRetrainingService
    ) {
        this.retrainingConfigService = retrainingConfigService;
        this.autoRetrainingService = autoRetrainingService;
    }

    /*
     * Endpoint utilisé par Angular dans la page Mes Projets.
     * Il permet de créer/modifier la configuration de réentraînement
     * directement au niveau du projet.
     */
    @PostMapping("/projects/{projectId}/config")
    public ResponseEntity<RetrainingConfig> saveProjectConfig(
            @PathVariable Long projectId,
            @RequestBody RetrainingConfigRequest request
    ) {
        RetrainingConfig config = retrainingConfigService.saveOrUpdateProjectConfig(
                projectId,
                request
        );

        return ResponseEntity.ok(config);
    }

    /*
     * Endpoint utilisé quand on veut associer une expérience source précise.
     */
    @PostMapping("/projects/{projectId}/experiments/{experimentId}/config")
    public ResponseEntity<RetrainingConfig> saveConfig(
            @PathVariable Long projectId,
            @PathVariable Long experimentId,
            @RequestBody RetrainingConfigRequest request
    ) {
        RetrainingConfig config = retrainingConfigService.saveOrUpdateConfig(
                projectId,
                experimentId,
                request
        );

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/experiments/{experimentId}/enable-weekly")
    public ResponseEntity<RetrainingConfig> enableDefaultWeeklyRetraining(
            @PathVariable Long projectId,
            @PathVariable Long experimentId
    ) {
        RetrainingConfig config = retrainingConfigService.enableDefaultWeeklyRetraining(
                projectId,
                experimentId
        );

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/disable")
    public ResponseEntity<RetrainingConfig> disableRetraining(
            @PathVariable Long projectId
    ) {
        RetrainingConfig config = retrainingConfigService.disableRetraining(projectId);

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/force-now")
    public ResponseEntity<ManualRetrainingResponse> forceRetrainingNow(
            @PathVariable Long projectId
    ) {
        /*
         * Répare d'abord les états bloqués : une expérience CREATED/RUNNING
         * trop ancienne sans Job Kubernetes ne doit pas bloquer le bouton.
         */
        autoRetrainingService.recoverStuckRetraining(projectId);

        try {
            MlExperimentResponse experiment =
                    autoRetrainingService.launchManualRetraining(projectId);

            String status = experiment != null
                    ? experiment.getStatus()
                    : "FAILED";

            boolean success = experiment != null
                    && experiment.getId() != null
                    && !"FAILED".equalsIgnoreCase(status);

            String jobName = experiment != null
                    ? experiment.getKubeflowRunName()
                    : null;

            ManualRetrainingResponse body =
                    new ManualRetrainingResponse(
                            success,
                            projectId,
                            experiment != null
                                    ? experiment.getId()
                                    : null,
                            jobName,
                            success
                                    ? status
                                    : "FAILED",
                            "MANUAL",
                            success
                                    ? "Réentraînement lancé avec succès."
                                    : safeMessage(
                                    experiment != null
                                            ? experiment.getErrorMessage()
                                            : "Aucune expérience créée."
                            ),
                            success
                                    ? null
                                    : "RETRAINING_LAUNCH_FAILED"
                    );

            if (!success) {
                return ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(body);
            }

            return ResponseEntity.ok(body);

        } catch (RetrainingConflictException conflict) {
            ManualRetrainingResponse body =
                    new ManualRetrainingResponse(
                            false,
                            projectId,
                            null,
                            null,
                            "RUNNING",
                            "MANUAL",
                            safeMessage(conflict.getMessage()),
                            conflict.getCode()
                    );

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(body);

        } catch (Exception error) {
            ManualRetrainingResponse body =
                    new ManualRetrainingResponse(
                            false,
                            projectId,
                            null,
                            null,
                            "FAILED",
                            "MANUAL",
                            safeMessage(error.getMessage()),
                            "RETRAINING_ERROR"
                    );

            System.err.println(
                    "[RETRAINING_ERROR] force-now failed. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(error.getMessage())
            );

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(body);
        }
    }

    @GetMapping("/projects/{projectId}/status")
    public ResponseEntity<ProjectRetrainingStatusResponse> getProjectStatus(
            @PathVariable Long projectId
    ) {
        autoRetrainingService.recoverStuckRetraining(projectId);

        RetrainingConfig config = retrainingConfigService.getConfig(projectId);

        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        boolean activeExperimentExists = autoRetrainingService.hasActiveExperimentForProject(projectId);
        boolean running = config.isRunning() || activeExperimentExists;
        ProjectRetrainingStatusResponse response = new ProjectRetrainingStatusResponse(
                projectId,
                config.isEnabled(),
                running,
                config.isManualTriggerRequested(),
                activeExperimentExists,
                activeExperimentExists ? "ACTIVE" : (config.isRunning() ? "RUNNING" : (config.isEnabled() ? "IDLE" : "DISABLED")),
                activeExperimentExists ? "Un réentraînement est en cours." : "Aucun réentraînement actif.",
                config.getNextRetrainingAt() != null ? config.getNextRetrainingAt().toString() : null,
                config.getLastRetrainingAt() != null ? config.getLastRetrainingAt().toString() : null,
                config.getLastDecisionReason(),
                config.getLastTriggerReason()
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/projects/{projectId}/config")
    public ResponseEntity<RetrainingConfig> getConfig(
            @PathVariable Long projectId
    ) {
        RetrainingConfig config = retrainingConfigService.getConfig(projectId);

        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(config);
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