package com.mlops.platform.service;

import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AutoRetrainingService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentService mlExperimentService;
    private final MlProjectRepository mlProjectRepository;
    private final NotificationService notificationService;
    private final RetrainingDecisionService retrainingDecisionService;

    public AutoRetrainingService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentService mlExperimentService,
            MlProjectRepository mlProjectRepository,
            NotificationService notificationService,
            RetrainingDecisionService retrainingDecisionService
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentService = mlExperimentService;
        this.mlProjectRepository = mlProjectRepository;
        this.notificationService = notificationService;
        this.retrainingDecisionService = retrainingDecisionService;
    }

    public void launchScheduledRetraining(Long configId) {
        if (configId == null) {
            return;
        }

        LocalDateTime claimTime = LocalDateTime.now();
        int claimed = retrainingConfigRepository.claimDueConfig(configId, claimTime);

        if (claimed == 0) {
            System.out.println("[AUTO_RETRAINING] Config was not claimed. It may be disabled, already running, or not due. configId="
                    + configId);
            return;
        }

        RetrainingConfig config = retrainingConfigRepository.findById(configId).orElse(null);

        if (config == null) {
            releaseAfterFailure(configId, null);
            System.out.println("[AUTO_RETRAINING] Claimed config not found after claim. configId=" + configId);
            return;
        }

        Long projectId = config.getProjectId();

        if (projectId == null) {
            releaseAfterFailure(configId, null);
            System.out.println("[AUTO_RETRAINING] Ignored claimed config because projectId is null. configId=" + configId);
            return;
        }

        try {
            RetrainingDecisionResult decision;

            if (config.getStrategy() == null
                    || config.getStrategy() == RetrainingStrategy.SCHEDULED_ONLY) {
                // Mode simple : chaque échéance déclenche réellement un run.
                decision = new RetrainingDecisionResult();
                decision.setShouldRetrain(true);
                decision.setTriggerType(RetrainingTriggerType.SCHEDULED);
                decision.setReason("Échéance planifiée atteinte.");
            } else {
                decision = retrainingDecisionService.evaluateBeforeRetraining(config);
            }

            if (decision == null || !decision.isShouldRetrain()) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime next = config.calculateNextRetrainingDate(now);
                String reason = decision != null ? decision.getReason() : "Aucune décision de monitoring disponible.";

                releaseAfterSkipped(configId, next, reason);

                notifyRetrainingInfo(
                        projectId,
                        null,
                        "Réentraînement ignoré",
                        "Le monitoring ne justifie pas un nouveau réentraînement. "
                                + reason
                                + " Prochaine vérification : " + next + "."
                );

                System.out.println("[AUTO_RETRAINING] Skipped by monitoring decision. "
                        + "configId=" + configId
                        + ", projectId=" + projectId
                        + ", nextRetrainingAt=" + next
                        + ", reason=" + reason);
                return;
            }

            String trigger = decision.getTriggerType() != null
                    ? decision.getTriggerType().name()
                    : "UNKNOWN";

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement lancé",
                    "Le réentraînement automatique vient d'être lancé. Déclencheur : "
                            + trigger
                            + ". Raison : "
                            + decision.getReason()
            );

            System.out.println("[AUTO_RETRAINING] Starting retraining from CURRENT PRODUCTION version. "
                    + "configId=" + configId
                    + ", projectId=" + projectId
                    + ", trigger=" + trigger
                    + ", decision=" + decision.getReason()
                    + ", fallbackSourceExperimentId=" + config.getSourceExperimentId());

            MlExperimentResponse newExperiment = mlExperimentService.retrainFromCurrentProductionExperiment(
                    projectId,
                    config.getSourceExperimentId()
            );

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = config.calculateNextRetrainingDate(now);

            releaseAfterSuccess(configId, now, next);

            Long newExperimentId = newExperiment != null ? newExperiment.getId() : null;

            notifyRetrainingInfo(
                    projectId,
                    newExperimentId,
                    "Réentraînement planifié lancé",
                    "Le réentraînement automatique a été lancé avec succès. Prochaine exécution : " + next + "."
            );

            System.out.println("[AUTO_RETRAINING] Scheduled retraining launched successfully. "
                    + "projectId=" + projectId
                    + ", newExperimentId=" + newExperimentId
                    + ", nextRetrainingAt=" + next);

        } catch (Exception e) {
            RetrainingConfig latest = retrainingConfigRepository.findById(configId).orElse(config);

            LocalDateTime next = latest != null && latest.isEnabled()
                    ? latest.calculateNextRetrainingDate(LocalDateTime.now())
                    : null;

            releaseAfterFailure(configId, next);

            String errorMessage = safeMessage(e.getMessage());

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement automatique échoué",
                    "Le réentraînement automatique n'a pas pu être lancé. Erreur : " + errorMessage
            );

            System.err.println("[AUTO_RETRAINING] Scheduled retraining failed. "
                    + "projectId=" + projectId
                    + ", fallbackSourceExperimentId=" + config.getSourceExperimentId()
                    + ", nextRetrainingAt=" + next
                    + ", error=" + errorMessage);
        }
    }

    /*
     * Ancienne signature gardée pour compatibilité avec l'ancien code.
     */
    public void launchScheduledRetraining(RetrainingConfig config) {
        if (config == null) {
            return;
        }

        launchScheduledRetraining(config.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAfterSuccess(
            Long configId,
            LocalDateTime lastRetrainingAt,
            LocalDateTime nextRetrainingAt
    ) {
        retrainingConfigRepository.releaseAfterSuccess(
                configId,
                lastRetrainingAt,
                nextRetrainingAt,
                LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAfterFailure(
            Long configId,
            LocalDateTime nextRetrainingAt
    ) {
        retrainingConfigRepository.releaseAfterFailure(
                configId,
                nextRetrainingAt,
                LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAfterSkipped(
            Long configId,
            LocalDateTime nextRetrainingAt,
            String decisionReason
    ) {
        retrainingConfigRepository.releaseAfterSkipped(
                configId,
                nextRetrainingAt,
                safeMessage(decisionReason),
                LocalDateTime.now()
        );
    }

    private void notifyRetrainingInfo(
            Long projectId,
            Long experimentId,
            String title,
            String message
    ) {
        if (projectId == null) {
            return;
        }

        try {
            MlProject project = mlProjectRepository.findById(projectId).orElse(null);

            if (project == null) {
                return;
            }

            Long targetId = experimentId != null ? experimentId : projectId;

            NotificationTargetType targetType = experimentId != null
                    ? NotificationTargetType.EXPERIMENT
                    : NotificationTargetType.PROJECT;

            notificationService.notifyUser(
                    project.getOwner(),
                    title,
                    message,
                    NotificationType.SYSTEM_INFO,
                    targetType,
                    targetId
            );

        } catch (Exception notificationError) {
            System.out.println("[AUTO_RETRAINING][NOTIFICATION] Could not send retraining notification. projectId="
                    + projectId
                    + ", reason="
                    + safeMessage(notificationError.getMessage()));
        }
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