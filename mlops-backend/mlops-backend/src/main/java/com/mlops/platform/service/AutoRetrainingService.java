package com.mlops.platform.service;

import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AutoRetrainingService {

    /**
     * Au-delà de cette durée, une expérience de réentraînement sans Job
     * Kubernetes actif est considérée comme bloquée et réparée.
     */
    private static final long RETRAINING_STUCK_TIMEOUT_MINUTES = 120;

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentService mlExperimentService;
    private final MlExperimentRepository mlExperimentRepository;
    private final MlProjectRepository mlProjectRepository;
    private final NotificationService notificationService;
    private final RetrainingDecisionService retrainingDecisionService;
    private final RetrainingSourceResolverService sourceResolver;
    private final KubernetesJobService kubernetesJobService;
    private final PlatformTransactionManager transactionManager;

    public AutoRetrainingService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentService mlExperimentService,
            MlExperimentRepository mlExperimentRepository,
            MlProjectRepository mlProjectRepository,
            NotificationService notificationService,
            RetrainingDecisionService retrainingDecisionService,
            RetrainingSourceResolverService sourceResolver,
            KubernetesJobService kubernetesJobService,
            PlatformTransactionManager transactionManager
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentService = mlExperimentService;
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.notificationService = notificationService;
        this.retrainingDecisionService = retrainingDecisionService;
        this.sourceResolver = sourceResolver;
        this.kubernetesJobService = kubernetesJobService;
        this.transactionManager = transactionManager;
    }

    public void processClaimedConfig(
            Long configId
    ) {
        if (configId == null) {
            return;
        }

        RetrainingConfig config =
                retrainingConfigRepository
                        .findById(configId)
                        .orElse(null);

        if (config == null) {
            releaseAfterFailure(
                    configId,
                    null
            );

            System.err.println(
                    "[AUTO_RETRAINING] Claimed config not found. "
                            + "configId="
                            + configId
            );

            return;
        }

        Long projectId =
                config.getProjectId();

        if (!config.isEnabled()) {
            releaseAfterFailure(
                    configId,
                    null
            );

            System.out.println(
                    "[AUTO_RETRAINING] Claimed config became disabled. "
                            + "configId="
                            + configId
            );

            return;
        }

        if (!config.isRunning()) {
            System.out.println(
                    "[AUTO_RETRAINING] Claimed config is no longer "
                            + "marked running. configId="
                            + configId
                            + ", projectId="
                            + projectId
            );

            return;
        }

        if (projectId == null) {
            releaseAfterFailure(
                    configId,
                    null
            );

            System.err.println(
                    "[AUTO_RETRAINING] projectId is null. "
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
                            + ", manual="
                            + config.isManualTriggerRequested()
                            + ", nextRetrainingAt="
                            + config.getNextRetrainingAt()
            );

            RetrainingDecisionResult decision =
                    buildRetrainingDecision(
                            config
                    );

            if (decision == null
                    || !decision.isShouldRetrain()) {

                LocalDateTime now =
                        utcNow();

                LocalDateTime next =
                        config.calculateNextRetrainingDate(
                                now
                        );

                String reason =
                        decision != null
                                ? safeMessage(
                                        decision.getReason()
                                )
                                : "Aucune décision de monitoring disponible.";

                releaseAfterSkipped(
                        configId,
                        next,
                        reason
                );

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
                        "[AUTO_RETRAINING] Skipped by decision. "
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

            String trigger =
                    decision.getTriggerType() != null
                            ? decision.getTriggerType().name()
                            : "UNKNOWN";

            String decisionReason =
                    safeMessage(
                            decision.getReason()
                    );

            /*
             * Utilise d'abord sourceExperimentId.
             * Si la source n'est plus valide, le resolver retrouve
             * automatiquement la Production courante.
             */
            Long productionSourceExperimentId =
                    sourceResolver
                            .resolveSourceExperimentId(
                                    projectId,
                                    config.getSourceExperimentId()
                            );

            System.out.println(
                    "[AUTO_RETRAINING] Starting retraining from "
                            + "Production source. "
                            + "configId="
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

            if (newExperiment == null
                    || newExperiment.getId() == null) {
                throw new IllegalStateException(
                        "The retraining request did not create "
                                + "a new experiment. projectId="
                                + projectId
                );
            }

            LocalDateTime now =
                    utcNow();

            LocalDateTime next =
                    config.calculateNextRetrainingDate(
                            now
                    );

            releaseAfterSuccess(
                    configId,
                    now,
                    next
            );

            Long newExperimentId =
                    newExperiment.getId();

            notifyRetrainingInfo(
                    projectId,
                    newExperimentId,
                    "Réentraînement automatique lancé",
                    "Le réentraînement automatique a été lancé avec "
                            + "succès. Nouvelle expérience : "
                            + newExperimentId
                            + ". Prochaine exécution : "
                            + next
                            + "."
            );

            System.out.println(
                    "[AUTO_RETRAINING] Retraining launched successfully. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + productionSourceExperimentId
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
                    latest != null
                            && latest.isEnabled()
                            ? latest.calculateNextRetrainingDate(
                                    utcNow()
                            )
                            : null;

            releaseAfterFailure(
                    configId,
                    next
            );

            String errorMessage =
                    safeMessage(
                            error.getMessage()
                    );

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement automatique échoué",
                    "Le réentraînement automatique n'a pas pu être "
                            + "lancé. Erreur : "
                            + errorMessage
            );

            System.err.println(
                    "[AUTO_RETRAINING] Retraining failed. "
                            + "configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", cachedSourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", nextRetrainingAt="
                            + next
                            + ", error="
                            + errorMessage
            );

            error.printStackTrace();
        }
    }

    public void launchScheduledRetraining(
            Long configId
    ) {
        if (configId == null) {
            return;
        }

        LocalDateTime claimTime =
                utcNow();

        int claimed =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .claimDueConfig(
                                        configId,
                                        claimTime
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Service claim. "
                        + "configId="
                        + configId
                        + ", result="
                        + claimed
        );

        if (claimed != 1) {
            System.out.println(
                    "[AUTO_RETRAINING] Config was not claimed. "
                            + "It may be disabled, already running "
                            + "or not due. configId="
                            + configId
            );

            return;
        }

        processClaimedConfig(
                configId
        );
    }

        /**
         * Tentative atomique : valide rapidement la source et réclame la config.
         * Retourne 1 si claim réussi, 0 sinon.
         */
        public int tryValidateAndClaimInNewTransaction(Long configId) {
                return executeInNewTransaction(() -> {
                        if (configId == null) {
                                return 0;
                        }

                        RetrainingConfig cfg = retrainingConfigRepository.findById(configId).orElse(null);

                        if (cfg == null) {
                                return 0;
                        }

                        if (!cfg.isEnabled() || cfg.isRunning()) {
                                return 0;
                        }

                        LocalDateTime now = utcNow();

                        boolean due = cfg.isManualTriggerRequested()
                                        || (cfg.getNextRetrainingAt() != null && !cfg.getNextRetrainingAt().isAfter(now));

                        if (!due) {
                                return 0;
                        }

                        // Validate that a retraining source exists when not a manual request.
                        if (!cfg.isManualTriggerRequested()) {
                                Long resolved = null;
                                try {
                                        resolved = sourceResolver.resolveSourceExperimentId(cfg.getProjectId(), cfg.getSourceExperimentId());
                                } catch (Exception ignore) {
                                }

                                if (resolved == null) {
                                        // no production source -> skip claiming
                                        return 0;
                                }
                        }

                        // Finally, attempt the DB-level claim (same semantics as claimDueConfig)
                        return retrainingConfigRepository.claimDueConfig(configId, now);
                });
        }

    public void launchScheduledRetraining(
            RetrainingConfig config
    ) {
        if (config == null
                || config.getId() == null) {
            return;
        }

        if (config.isRunning()) {
            processClaimedConfig(
                    config.getId()
            );
        } else {
            launchScheduledRetraining(
                    config.getId()
            );
        }
    }

    public MlExperimentResponse launchManualRetraining(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId is required for manual retraining."
            );
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);

        if (config == null || !config.isEnabled()) {
            System.out.println(
                    "[MANUAL_RETRAIN] Refused. projectId="
                            + projectId
                            + ", reason=Retraining is disabled or config missing."
            );
            throw new IllegalStateException(
                    "Retraining is disabled for projectId="
                            + projectId
            );
        }

        /*
         * Réparation des états bloqués avant toute décision :
         * une expérience CREATED/RUNNING trop ancienne sans Job Kubernetes
         * ne doit pas bloquer un nouveau lancement manuel.
         */
        recoverStuckRetraining(projectId);

        if (hasActiveExperimentForProject(projectId)) {
            System.out.println(
                    "[MANUAL_RETRAIN] Refused. projectId="
                            + projectId
                            + ", reason=Retraining already active."
            );
            throw new RetrainingConflictException(
                    "RETRAINING_ALREADY_RUNNING",
                    "Un réentraînement est déjà en cours pour ce projet."
            );
        }

        Long sourceExperimentId = sourceResolver
                .resolveSourceExperimentId(
                        projectId,
                        config.getSourceExperimentId()
                );

        if (sourceExperimentId == null) {
            System.out.println(
                    "[MANUAL_RETRAIN] Refused. projectId="
                            + projectId
                            + ", reason=No valid production source."
            );
            throw new IllegalStateException(
                    "No valid production source found for projectId="
                            + projectId
            );
        }

        /*
         * Claim atomique : running=true en base.
         * Deux clics simultanés ne peuvent pas réserver la même config.
         */
        int claimed = executeInNewTransaction(() ->
                retrainingConfigRepository.claimManualRetraining(
                        config.getId(),
                        utcNow()
                )
        );

        if (claimed != 1) {
            System.out.println(
                    "[MANUAL_RETRAIN] Refused. projectId="
                            + projectId
                            + ", reason=Config could not be claimed "
                            + "(already running)."
            );
            throw new RetrainingConflictException(
                    "RETRAINING_ALREADY_RUNNING",
                    "Un réentraînement est déjà en cours pour ce projet."
            );
        }

        System.out.println(
                "[MANUAL_RETRAIN] Claimed. projectId="
                        + projectId
                        + ", configId="
                        + config.getId()
                        + ", sourceExperimentId="
                        + sourceExperimentId
                        + ", trigger=MANUAL"
        );

        try {
            MlExperimentResponse response =
                    mlExperimentService
                            .retrainFromCurrentProductionExperiment(
                                    projectId,
                                    sourceExperimentId
                            );

            if (response == null
                    || response.getId() == null) {
                throw new IllegalStateException(
                        "The retraining request did not create "
                                + "a new experiment. projectId="
                                + projectId
                );
            }

            String status = response.getStatus();

            /*
             * Jamais de succès si l'expérience est en échec :
             * le Job Kubernetes n'a pas été créé correctement.
             */
            if ("FAILED".equalsIgnoreCase(status)) {
                throw new IllegalStateException(
                        "Retraining could not be launched. "
                                + "experimentId="
                                + response.getId()
                                + ", reason="
                                + safeMessage(
                                        response.getErrorMessage()
                                )
                );
            }

            String jobName = response.getKubeflowRunName();

            System.out.println(
                    "[RETRAINING_JOB] Manual retraining launched. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + sourceExperimentId
                            + ", experimentId="
                            + response.getId()
                            + ", jobName="
                            + jobName
                            + ", status="
                            + status
                            + ", trigger=MANUAL"
            );

            return response;

        } catch (Exception error) {
            releaseManualAfterFailure(config.getId());

            System.err.println(
                    "[RETRAINING_ERROR] Manual retraining failed. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + sourceExperimentId
                            + ", error="
                            + safeMessage(
                                    error.getMessage()
                            )
            );

            throw error;
        }
    }

    public boolean hasActiveExperimentForProject(Long projectId) {
        return mlExperimentService.hasActiveRetrainingForProject(projectId);
    }

    /**
     * Répare les réentraînements bloqués d'un projet.
     *
     * Une expérience CREATED/RUNNING trop ancienne sans Job Kubernetes
     * actif (ou jamais lancée) est passée en FAILED afin de :
     * - libérer la garde anti-doublon ;
     * - réactiver le bouton « Lancer » ;
     * - enregistrer la raison de la récupération.
     *
     * Un run Kubeflow RUNNING avec un runId valide n'est jamais touché
     * (il peut s'agir d'un entraînement long légitime).
     */
    public void recoverStuckRetraining(Long projectId) {
        if (projectId == null) {
            return;
        }

        try {
            List<MlExperiment> active =
                    mlExperimentRepository
                            .findActiveRetrainingForProject(projectId);

            if (active == null || active.isEmpty()) {
                clearStaleManualFlags(projectId);
                return;
            }

            LocalDateTime now = utcNow();
            boolean repaired = false;

            for (MlExperiment experiment : active) {
                if (experiment.getCreatedAt() == null) {
                    continue;
                }

                long ageMinutes = ChronoUnit.MINUTES.between(
                        experiment.getCreatedAt(),
                        now
                );

                if (ageMinutes < RETRAINING_STUCK_TIMEOUT_MINUTES) {
                    continue;
                }

                if (!shouldRepairStuckExperiment(experiment)) {
                    continue;
                }

                ExperimentStatus previous = experiment.getStatus();
                experiment.setStatus(ExperimentStatus.FAILED);
                experiment.setFinishedAt(now);
                experiment.setErrorMessage(
                        "Récupération automatique : réentraînement resté "
                                + "bloqué (aucun Job Kubernetes actif)."
                );
                experiment.setMessage(
                        "Récupéré automatiquement après détection "
                                + "d'un état bloqué."
                );

                mlExperimentRepository.saveAndFlush(experiment);

                repaired = true;

                System.out.println(
                        "[RETRAINING_RECOVERY] Repaired stuck experiment. "
                                + "projectId="
                                + projectId
                                + ", experimentId="
                                + experiment.getId()
                                + ", jobName="
                                + firstNotBlank(
                                        experiment.getTrainingJobName(),
                                        experiment.getKubeflowRunName()
                                )
                                + ", ageMinutes="
                                + ageMinutes
                                + ", previousStatus="
                                + previous
                                + ", newStatus="
                                + experiment.getStatus()
                );
            }

            if (repaired) {
                List<MlExperiment> remaining =
                        mlExperimentRepository
                                .findActiveRetrainingForProject(projectId);

                if (remaining == null || remaining.isEmpty()) {
                    clearStaleManualFlags(projectId);
                }
            }

        } catch (Exception recoveryError) {
            System.err.println(
                    "[RETRAINING_RECOVERY] Recovery failed. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    recoveryError.getMessage()
                            )
            );
        }
    }

    private boolean shouldRepairStuckExperiment(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return false;
        }

        String runtimeMode = firstNotBlank(
                experiment.getRuntimeMode(),
                "STANDARD_RUNTIME"
        );

        runtimeMode = runtimeMode.trim().toUpperCase();

        String jobName = firstNotBlank(
                experiment.getTrainingJobName(),
                experiment.getKubeflowRunName()
        );

        if ("CUSTOM_DOCKERFILE".equals(runtimeMode)) {
            /*
             * Mode Docker : un Job Kubernetes doit exister.
             * S'il est introuvable, l'expérience est bloquée.
             */
            if (!hasText(jobName)) {
                return true;
            }

            try {
                String jobStatus =
                        kubernetesJobService
                                .getCustomJobStatus(jobName);

                return "NOT_FOUND".equalsIgnoreCase(jobStatus);
            } catch (Exception jobCheckError) {
                /*
                 * Si on ne peut pas interroger Kubernetes, on ne répare pas :
                 * on préfère laisser le traitement réel se terminer.
                 */
                return false;
            }
        }

        /*
         * STANDARD_RUNTIME : un run Kubeflow RUNNING avec un runId est un
         * entraînement légitime potentiellement long, on ne le répare pas.
         */
        boolean neverLaunched = !hasText(experiment.getKubeflowRunId())
                && !hasText(experiment.getKubeflowRunName());

        boolean stillCreated =
                experiment.getStatus() == ExperimentStatus.CREATED;

        return neverLaunched || stillCreated;
    }

    private void clearStaleManualFlags(Long projectId) {
        try {
            RetrainingConfig config = retrainingConfigRepository
                    .findByProjectId(projectId)
                    .orElse(null);

            if (config == null) {
                return;
            }

            if (config.isRunning()
                    || config.isManualTriggerRequested()) {
                config.setRunning(false);
                config.setManualTriggerRequested(false);
                config.setLastTriggerReason("RECOVERED");
                config.setLastDecisionReason(
                        "Aucune expérience active : état réinitialisé."
                );
                retrainingConfigRepository.saveAndFlush(config);

                System.out.println(
                        "[RETRAINING_RECOVERY] Cleared stale flags. "
                                + "projectId="
                                + projectId
                );
            }

        } catch (Exception clearError) {
            System.err.println(
                    "[RETRAINING_RECOVERY] Could not clear flags. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    clearError.getMessage()
                            )
            );
        }
    }

    private void releaseManualAfterFailure(Long configId) {
        if (configId == null) {
            return;
        }

        try {
            executeInNewTransaction(() ->
                    retrainingConfigRepository.releaseAfterFailure(
                            configId,
                            utcNow().plusMinutes(5),
                            utcNow()
                    )
            );
        } catch (Exception releaseError) {
            System.err.println(
                    "[RETRAINING_ERROR] Could not release manual claim. "
                            + "configId="
                            + configId
                            + ", reason="
                            + safeMessage(
                                    releaseError.getMessage()
                            )
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNotBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private RetrainingDecisionResult buildRetrainingDecision(
            RetrainingConfig config
    ) {
        if (config == null) {
            return RetrainingDecisionResult.skip(
                    "Configuration de réentraînement absente."
            );
        }

        RetrainingStrategy strategy =
                config.getStrategy() != null
                        ? config.getStrategy()
                        : RetrainingStrategy.SCHEDULED_ONLY;

        if (config.isManualTriggerRequested()) {
            return RetrainingDecisionResult.run(
                    RetrainingTriggerType.MANUAL,
                    "Réentraînement demandé manuellement."
            );
        }

        boolean scheduleDue =
                config.getNextRetrainingAt() != null
                        && !config.getNextRetrainingAt()
                        .isAfter(
                                utcNow()
                        );

        return switch (strategy) {

            case SCHEDULED_ONLY -> {
                if (!scheduleDue) {
                    yield RetrainingDecisionResult.skip(
                            "Échéance planifiée non atteinte."
                    );
                }

                yield RetrainingDecisionResult.run(
                        RetrainingTriggerType.SCHEDULED,
                        "Échéance planifiée atteinte."
                );
            }

            case SCHEDULED_WITH_MONITORING -> {
                if (!scheduleDue) {
                    yield RetrainingDecisionResult.skip(
                            "Échéance planifiée non atteinte."
                    );
                }

                RetrainingDecisionResult monitoring =
                        retrainingDecisionService
                                .evaluateMonitoringSignals(
                                        config
                                );

                if (monitoring != null && monitoring.isShouldRetrain()) {
                    yield monitoring;
                }

                // If monitoring does not explicitly ask for retraining, skip.
                String reason = monitoring != null
                        ? safeMessage(monitoring.getReason())
                        : "Aucun signal de monitoring disponible.";

                yield RetrainingDecisionResult.skip(
                        "Mode SCHEDULED_WITH_MONITORING : le monitoring n'autorise pas le réentraînement. "
                                + reason
                );
            }

            case SMART_MONITORING_ONLY -> {
                RetrainingDecisionResult monitoring =
                        retrainingDecisionService
                                .evaluateMonitoringSignals(
                                        config
                                );

                if (monitoring == null) {
                    yield RetrainingDecisionResult.skip(
                            "Aucune décision de monitoring disponible."
                    );
                }

                yield monitoring;
            }

            case FULL_SMART -> {
                RetrainingDecisionResult monitoring =
                        retrainingDecisionService
                                .evaluateMonitoringSignals(
                                        config
                                );

                if (monitoring != null
                        && monitoring.isShouldRetrain()) {
                    yield monitoring;
                }

                if (scheduleDue) {
                    RetrainingDecisionResult scheduled =
                            RetrainingDecisionResult.run(
                                    RetrainingTriggerType.SCHEDULED,
                                    "Mode FULL_SMART : échéance "
                                            + "planifiée atteinte. "
                                            + (
                                            monitoring != null
                                                    ? safeMessage(
                                                            monitoring.getReason()
                                                    )
                                                    : "Aucun signal de monitoring "
                                                    + "supplémentaire."
                                    )
                            );

                    copyMonitoringValues(
                            monitoring,
                            scheduled
                    );

                    yield scheduled;
                }

                yield RetrainingDecisionResult.skip(
                        monitoring != null
                                ? safeMessage(
                                        monitoring.getReason()
                                )
                                : "Aucun signal de monitoring et "
                                + "échéance non atteinte."
                );
            }
        };
    }

    private void copyMonitoringValues(
            RetrainingDecisionResult source,
            RetrainingDecisionResult target
    ) {
        if (source == null
                || target == null) {
            return;
        }

        target.setRecentPredictionCount(
                source.getRecentPredictionCount()
        );

        target.setPredictionErrorRatePercent(
                source.getPredictionErrorRatePercent()
        );

        target.setDataDriftScorePercent(
                source.getDataDriftScorePercent()
        );

        target.setRecentFailedRuns(
                source.getRecentFailedRuns()
        );
    }

    private void releaseAfterSuccess(
            Long configId,
            LocalDateTime lastRetrainingAt,
            LocalDateTime nextRetrainingAt
    ) {
        LocalDateTime updatedAt =
                utcNow();

        int updated =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .releaseAfterSuccess(
                                        configId,
                                        lastRetrainingAt,
                                        nextRetrainingAt,
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after success. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
        );
    }

    private void releaseAfterFailure(
            Long configId,
            LocalDateTime nextRetrainingAt
    ) {
        LocalDateTime updatedAt =
                utcNow();

        int updated =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .releaseAfterFailure(
                                        configId,
                                        nextRetrainingAt,
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after failure. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
                        + ", retryAt="
                        + nextRetrainingAt
        );
    }

    private void releaseAfterSkipped(
            Long configId,
            LocalDateTime nextRetrainingAt,
            String decisionReason
    ) {
        LocalDateTime updatedAt =
                utcNow();

        int updated =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .releaseAfterSkipped(
                                        configId,
                                        nextRetrainingAt,
                                        safeMessage(
                                                decisionReason
                                        ),
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after skipped decision. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
        );
    }

    private int executeInNewTransaction(
            IntTransactionCallback callback
    ) {
        TransactionTemplate template =
                new TransactionTemplate(
                        transactionManager
                );

        template.setPropagationBehavior(
                TransactionDefinition
                        .PROPAGATION_REQUIRES_NEW
        );

        template.setIsolationLevel(
                TransactionDefinition
                        .ISOLATION_READ_COMMITTED
        );

        template.setTimeout(10);

        Integer result =
                template.execute(
                        status -> callback.execute()
                );

        return result == null
                ? 0
                : result;
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
                    "[AUTO_RETRAINING][NOTIFICATION] "
                            + "Could not send notification. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    notificationError.getMessage()
                            )
            );
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(
                Clock.systemUTC()
        );
    }

    private String safeMessage(
            String message
    ) {
        if (message == null
                || message.isBlank()) {
            return "Erreur inconnue";
        }

        String clean =
                message
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace("\u0000", "")
                        .trim();

        return clean.length() > 500
                ? clean.substring(
                        0,
                        497
                ) + "..."
                : clean;
    }

    @FunctionalInterface
    private interface IntTransactionCallback {
        int execute();
    }
}
