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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class AutoRetrainingService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentService mlExperimentService;
    private final MlProjectRepository mlProjectRepository;
    private final NotificationService notificationService;
    private final RetrainingDecisionService retrainingDecisionService;
    private final RetrainingSourceResolverService sourceResolver;
    private final PlatformTransactionManager transactionManager;

    public AutoRetrainingService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentService mlExperimentService,
            MlProjectRepository mlProjectRepository,
            NotificationService notificationService,
            RetrainingDecisionService retrainingDecisionService,
            RetrainingSourceResolverService sourceResolver,
            PlatformTransactionManager transactionManager
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentService = mlExperimentService;
        this.mlProjectRepository = mlProjectRepository;
        this.notificationService = notificationService;
        this.retrainingDecisionService = retrainingDecisionService;
        this.sourceResolver = sourceResolver;
        this.transactionManager = transactionManager;
    }

    /**
     * Point d'entrée utilisé par AutoRetrainingScheduler.
     *
     * La configuration a déjà été réclamée atomiquement par le scheduler :
     * running=true est donc attendu. Cette méthode ne refait jamais le claim.
     */
    public void processClaimedConfig(Long configId) {
        if (configId == null) {
            return;
        }

        RetrainingConfig config =
                retrainingConfigRepository.findById(configId).orElse(null);

        if (config == null) {
            releaseAfterFailure(configId, null);
            System.err.println(
                    "[AUTO_RETRAINING] Claimed config not found. configId="
                            + configId
            );
            return;
        }

        Long projectId = config.getProjectId();

        if (!config.isEnabled()) {
            releaseAfterFailure(configId, null);
            System.out.println(
                    "[AUTO_RETRAINING] Claimed config became disabled. configId="
                            + configId
            );
            return;
        }

        if (!config.isRunning()) {
            System.out.println(
                    "[AUTO_RETRAINING] Claimed config is no longer marked running. "
                            + "configId="
                            + configId
                            + ", projectId="
                            + projectId
            );
            return;
        }

        if (projectId == null) {
            releaseAfterFailure(configId, null);
            System.out.println(
                    "[AUTO_RETRAINING] Ignored claimed config because projectId is null. "
                            + "configId="
                            + configId
            );
            return;
        }

        try {
            System.out.println(
                    "[AUTO_RETRAINING] Processing claimed config. "
                            + "configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", strategy="
                            + config.getStrategy()
            );

            RetrainingDecisionResult decision =
                    buildRetrainingDecision(config);

            if (decision == null || !decision.isShouldRetrain()) {
                LocalDateTime now = utcNow();
                LocalDateTime next =
                        config.calculateNextRetrainingDate(now);

                String reason = decision != null
                        ? safeMessage(decision.getReason())
                        : "Aucune décision de monitoring disponible.";

                releaseAfterSkipped(configId, next, reason);

                notifyRetrainingInfo(
                        projectId,
                        null,
                        "Réentraînement ignoré",
                        "Le monitoring ne justifie pas un nouveau "
                                + "réentraînement. "
                                + reason
                                + " Prochaine vérification : "
                                + next
                                + "."
                );

                System.out.println(
                        "[AUTO_RETRAINING] Skipped by monitoring decision. "
                                + "configId="
                                + configId
                                + ", projectId="
                                + projectId
                                + ", nextRetrainingAt="
                                + next
                                + ", reason="
                                + reason
                );
                return;
            }

            String trigger = decision.getTriggerType() != null
                    ? decision.getTriggerType().name()
                    : "UNKNOWN";

            String decisionReason =
                    safeMessage(decision.getReason());

            /*
             * La source est toujours recalculée à partir de la Production
             * courante. Le sourceExperimentId de la config reste un cache.
             */
            Long productionSourceExperimentId =
                    sourceResolver.resolveSourceExperimentId(projectId);

            System.out.println(
                    "[AUTO_RETRAINING] Starting retraining from CURRENT "
                            + "PRODUCTION version. configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", trigger="
                            + trigger
                            + ", decision="
                            + decisionReason
                            + ", productionSourceExperimentId="
                            + productionSourceExperimentId
            );

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement lancé",
                    "Le réentraînement automatique vient d'être lancé. "
                            + "Déclencheur : "
                            + trigger
                            + ". Raison : "
                            + decisionReason
            );

            MlExperimentResponse newExperiment =
                    mlExperimentService
                            .retrainFromCurrentProductionExperiment(
                                    projectId,
                                    productionSourceExperimentId
                            );

            LocalDateTime now = utcNow();
            LocalDateTime next =
                    config.calculateNextRetrainingDate(now);

            releaseAfterSuccess(configId, now, next);

            Long newExperimentId =
                    newExperiment != null
                            ? newExperiment.getId()
                            : null;

            notifyRetrainingInfo(
                    projectId,
                    newExperimentId,
                    "Réentraînement planifié lancé",
                    "Le réentraînement automatique a été lancé avec "
                            + "succès. Prochaine exécution : "
                            + next
                            + "."
            );

            System.out.println(
                    "[AUTO_RETRAINING] Scheduled retraining launched successfully. "
                            + "projectId="
                            + projectId
                            + ", newExperimentId="
                            + newExperimentId
                            + ", nextRetrainingAt="
                            + next
            );

        } catch (Exception error) {
            RetrainingConfig latest =
                    retrainingConfigRepository
                            .findById(configId)
                            .orElse(config);

            LocalDateTime next =
                    latest != null && latest.isEnabled()
                            ? latest.calculateNextRetrainingDate(utcNow())
                            : null;

            releaseAfterFailure(configId, next);

            String errorMessage =
                    safeMessage(error.getMessage());

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement automatique échoué",
                    "Le réentraînement automatique n'a pas pu être lancé. "
                            + "Erreur : "
                            + errorMessage
            );

            System.err.println(
                    "[AUTO_RETRAINING] Scheduled retraining failed. "
                            + "configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", fallbackSourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", nextRetrainingAt="
                            + next
                            + ", error="
                            + errorMessage
            );
            error.printStackTrace();
        }
    }

    /**
     * Point d'entrée compatible pour un appel qui n'a pas encore fait le claim.
     *
     * Cette méthode effectue exactement un claim, puis délègue au traitement
     * d'une configuration déjà réclamée.
     */
    public void launchScheduledRetraining(Long configId) {
        if (configId == null) {
            return;
        }

        LocalDateTime claimTime = utcNow();

        int claimed = executeInNewTransaction(() ->
                retrainingConfigRepository.claimDueConfig(
                        configId,
                        claimTime
                )
        );

        System.out.println(
                "[AUTO_RETRAINING] Service claim configId="
                        + configId
                        + ", result="
                        + claimed
        );

        if (claimed != 1) {
            System.out.println(
                    "[AUTO_RETRAINING] Config was not claimed. "
                            + "It may be disabled, already running, or not due. "
                            + "configId="
                            + configId
            );
            return;
        }

        processClaimedConfig(configId);
    }

    /**
     * Ancienne signature conservée pour compatibilité.
     *
     * Si running=true, la configuration est considérée comme déjà réclamée.
     * Sinon, le service effectue le claim une seule fois.
     */
    public void launchScheduledRetraining(RetrainingConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }

        if (config.isRunning()) {
            processClaimedConfig(config.getId());
        } else {
            launchScheduledRetraining(config.getId());
        }
    }

    private RetrainingDecisionResult buildRetrainingDecision(
            RetrainingConfig config
    ) {
        if (config.getStrategy() == null
                || config.getStrategy()
                == RetrainingStrategy.SCHEDULED_ONLY) {

            RetrainingDecisionResult decision =
                    new RetrainingDecisionResult();

            decision.setShouldRetrain(true);
            decision.setTriggerType(
                    RetrainingTriggerType.SCHEDULED
            );
            decision.setReason(
                    "Échéance planifiée atteinte."
            );

            return decision;
        }

        /*
         * SMART_MONITORING_ONLY et FULL_SMART passent ici.
         * Le service de décision doit analyser échecs, performance et drift.
         */
        return retrainingDecisionService
                .evaluateBeforeRetraining(config);
    }

    private void releaseAfterSuccess(
            Long configId,
            LocalDateTime lastRetrainingAt,
            LocalDateTime nextRetrainingAt
    ) {
        LocalDateTime updatedAt = utcNow();

        executeInNewTransaction(() ->
                retrainingConfigRepository.releaseAfterSuccess(
                        configId,
                        lastRetrainingAt,
                        nextRetrainingAt,
                        updatedAt
                )
        );
    }

    private void releaseAfterFailure(
            Long configId,
            LocalDateTime nextRetrainingAt
    ) {
        LocalDateTime updatedAt = utcNow();

        executeInNewTransaction(() ->
                retrainingConfigRepository.releaseAfterFailure(
                        configId,
                        nextRetrainingAt,
                        updatedAt
                )
        );
    }

    private void releaseAfterSkipped(
            Long configId,
            LocalDateTime nextRetrainingAt,
            String decisionReason
    ) {
        LocalDateTime updatedAt = utcNow();

        executeInNewTransaction(() ->
                retrainingConfigRepository.releaseAfterSkipped(
                        configId,
                        nextRetrainingAt,
                        safeMessage(decisionReason),
                        updatedAt
                )
        );
    }

    private int executeInNewTransaction(
            IntTransactionCallback callback
    ) {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(10);

        Integer result =
                template.execute(status -> callback.execute());

        return result == null ? 0 : result;
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
            MlProject project =
                    mlProjectRepository
                            .findById(projectId)
                            .orElse(null);

            if (project == null) {
                return;
            }

            Long targetId =
                    experimentId != null
                            ? experimentId
                            : projectId;

            NotificationTargetType targetType =
                    experimentId != null
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
            System.out.println(
                    "[AUTO_RETRAINING][NOTIFICATION] Could not send "
                            + "retraining notification. projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    notificationError.getMessage()
                            )
            );
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(Clock.systemUTC());
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

    @FunctionalInterface
    private interface IntTransactionCallback {
        int execute();
    }
}
