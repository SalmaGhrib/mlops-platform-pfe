package com.mlops.platform.service;

import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingState;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.nio.file.Path;

@Service
public class AutoRetrainingService {

    /**
     * Au-delà de cette durée, une expérience de réentraînement sans Job
     * Kubernetes actif est considérée comme bloquée et réparée.
     */
    private static final long RETRAINING_STUCK_TIMEOUT_MINUTES = 120;

    /**
     * Au-delà de ce nombre d'échecs consécutifs, la configuration passe en
     * cooldown au lieu de réessayer immédiatement.
     */
    @Value("${retraining.max-consecutive-failures:3}")
    private int maxConsecutiveFailures;

    /**
     * Durée du cooldown après dépassement du seuil d'échecs consécutifs.
     */
    @Value("${retraining.cooldown-hours:24}")
    private long cooldownHours;

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentService mlExperimentService;
    private final MlExperimentRepository mlExperimentRepository;
    private final MlProjectRepository mlProjectRepository;
    private final NotificationService notificationService;
    private final RetrainingDecisionService retrainingDecisionService;
    private final RetrainingSourceResolverService sourceResolver;
    private final KubernetesJobService kubernetesJobService;
    private final PlatformTransactionManager transactionManager;
    private final GitHubFileFetcherService gitHubFileFetcherService;

    public AutoRetrainingService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentService mlExperimentService,
            MlExperimentRepository mlExperimentRepository,
            MlProjectRepository mlProjectRepository,
            NotificationService notificationService,
            RetrainingDecisionService retrainingDecisionService,
            RetrainingSourceResolverService sourceResolver,
            KubernetesJobService kubernetesJobService,
            PlatformTransactionManager transactionManager,
            GitHubFileFetcherService gitHubFileFetcherService
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
        this.gitHubFileFetcherService = gitHubFileFetcherService;
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

            LocalDateTime now =
                    utcNow();

            boolean sourceReady =
                    sourceResolver.resolveSourceExperimentIdOrNull(
                            projectId,
                            config.getSourceExperimentId()
                    ) != null;

            config.setStrategy(
                    RetrainingStrategy.normalize(
                            config.getStrategy()
                    )
            );

            RetrainingDecision decision =
                    retrainingDecisionService.buildDecision(
                            config,
                            now,
                            sourceReady
                    );

            if (decision == null
                    || !decision.isShouldRetrain()) {

                String reason =
                        decision != null
                                ? safeMessage(
                                        decision.getReason()
                                )
                                : "Aucune décision de monitoring disponible.";

                releaseAfterSkipped(
                        configId,
                        null,
                        reason
                );

                notifyRetrainingInfo(
                        projectId,
                        null,
                        "Réentraînement ignoré",
                        "Le monitoring ne justifie pas un nouveau "
                                + "réentraînement. "
                                + reason
                );

                System.out.println(
                        "[AUTO_RETRAINING] Skipped by decision. "
                                + "configId="
                                + configId
                                + ", projectId="
                                + projectId
                                + ", severity="
                                + decision.getSeverity()
                                + ", security="
                                + decision.getSecurityLevel()
                                + ", reasonCodes="
                                + decision.getReasonCodes()
                                + ", reason="
                                + reason
                );

                return;
            }

            if (decision.getSecurityLevel()
                    == RetrainingSecurityLevel.BLOCK_MODEL) {
                releaseAfterSkipped(
                        configId,
                        null,
                        "[SECURITE] Modèle bloqué : le réentraînement est "
                                + "refusé pour ce cycle. "
                                + safeMessage(decision.getReason())
                );
                notifyRetrainingInfo(
                        projectId,
                        null,
                        "Réentraînement bloqué par sécurité",
                        "Le réentraînement a été bloqué pour ce cycle : "
                                + safeMessage(decision.getReason())
                );
                System.out.println(
                        "[AUTO_RETRAINING] Blocked by security level. "
                                + "configId="
                                + configId
                                + ", projectId="
                                + projectId
                                + ", reasonCodes="
                                + decision.getReasonCodes()
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
             * Résolution de la source Production et mémorisation de la
             * version du modèle source (clé d'idempotence).
             */
            MlExperiment productionSource =
                    sourceResolver.resolveAndSynchronize(
                            projectId,
                            config.getSourceExperimentId()
                    );

            if (productionSource == null
                    || productionSource.getId() == null) {
                throw new IllegalStateException(
                        "No valid Production source could be resolved. "
                                + "projectId="
                                + projectId
                );
            }

            Long productionSourceExperimentId =
                    productionSource.getId();

            String sourceModelVersion =
                    productionSource.getModelVersion() != null
                            ? String.valueOf(
                                    productionSource.getModelVersion()
                            )
                            : null;

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
                            + ", sourceModelVersion="
                            + sourceModelVersion
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

            releaseAfterSuccess(
                    configId,
                    now,
                    null,
                    sourceModelVersion,
                    newExperiment.getId()
            );

            Long newExperimentId =
                    newExperiment.getId();

            notifyRetrainingInfo(
                    projectId,
                    newExperimentId,
                    "Réentraînement démarré",
                    "Le réentraînement automatique a été lancé avec "
                            + "succès. Nouvelle expérience : "
                            + newExperimentId
                            + ".",
                    NotificationType.RETRAINING_TRIGGERED
            );

            System.out.println(
                    "[AUTO_RETRAINING] Retraining launched successfully. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + productionSourceExperimentId
                            + ", sourceModelVersion="
                            + sourceModelVersion
                            + ", newExperimentId="
                            + newExperimentId
            );

        } catch (Exception error) {

            RetrainingConfig latest =
                    retrainingConfigRepository
                            .findById(configId)
                            .orElse(config);

            boolean cooldownReached =
                    latest != null
                            && latest.getConsecutiveFailures() + 1
                            >= maxConsecutiveFailures;

            if (cooldownReached) {
                releaseAfterCooldown(
                        configId,
                        null
                );
            } else {
                releaseAfterFailure(
                        configId,
                        null
                );
            }

            String errorMessage =
                    safeMessage(
                            error.getMessage()
                    );

            notifyRetrainingInfo(
                    projectId,
                    null,
                    "Réentraînement échoué",
                    "Le réentraînement automatique n'a pas pu être "
                            + "lancé. Erreur : "
                            + errorMessage
                            + (cooldownReached
                            ? " Le réentraînement est suspendu "
                            + "temporairement après "
                            + maxConsecutiveFailures
                            + " échecs consécutifs."
                            : ""),
                    NotificationType.RETRAINING_FAILED
            );

            System.err.println(
                    "[AUTO_RETRAINING] Retraining failed. "
                            + "configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", cachedSourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", cooldownReached="
                            + cooldownReached
                            + ", error="
                            + errorMessage
            );

            error.printStackTrace();
        }
    }

    /**
     * @deprecated La planification calendaire est supprimée.
     * Utilisez directement tryValidateAndClaimInNewTransaction + processClaimedConfig.
     */
    @Deprecated
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

                        if (!cfg.isClaimable(now)) {
                                return 0;
                        }

                        Long resolved = null;
                        try {
                                resolved = sourceResolver.resolveSourceExperimentIdOrNull(cfg.getProjectId(), cfg.getSourceExperimentId());
                        } catch (Exception ignore) {
                        }

                        if (resolved == null) {
                                retrainingConfigRepository.updateStateForUnclaimedConfig(
                                                configId,
                                                RetrainingState.IDLE,
                                                null,
                                                "Aucune source Production valide : reporté.",
                                                now
                                );
                                System.out.println(
                                                "[AUTO_RETRAINING] Source invalid: skipped "
                                                                + "instead of blocking. configId="
                                                                + configId
                                );
                                return 0;
                        }

                        return retrainingConfigRepository.claimDueConfig(configId, now);
                });
        }

    /**
     * @deprecated La planification calendaire est supprimée.
     */
    @Deprecated
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

        reconcileKubernetesJobStatuses(projectId);

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

        MlExperiment productionSource =
                sourceResolver.resolveAndSynchronize(
                        projectId,
                        config.getSourceExperimentId()
                );

        String sourceModelVersion =
                productionSource != null
                        && productionSource.getModelVersion() != null
                        ? String.valueOf(
                                productionSource.getModelVersion()
                        )
                        : null;

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

            releaseAfterSuccess(
                    config.getId(),
                    utcNow(),
                    null,
                    sourceModelVersion,
                    response.getId()
            );

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

    public MlExperimentResponse launchGitHubRetraining(
            Long projectId,
            String repository,
            String branch,
            String commitSha,
            String triggerMetadata
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId is required for GitHub retraining."
            );
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);

        if (config == null || !config.isEnabled()) {
            System.out.println(
                    "[GITHUB_RETRAIN_REJECTED] projectId="
                            + projectId
                            + ", repository="
                            + repository
                            + ", branch="
                            + branch
                            + ", commitSha="
                            + shortSha(commitSha)
                            + ", reason=Retraining disabled or config missing."
            );
            throw new IllegalStateException(
                    "Retraining is disabled or missing for projectId="
                            + projectId
            );
        }

        recoverStuckRetraining(projectId);

        reconcileKubernetesJobStatuses(projectId);

        if (hasActiveExperimentForProject(projectId)) {
            System.out.println(
                    "[GITHUB_RETRAIN_REJECTED] projectId="
                            + projectId
                            + ", repository="
                            + repository
                            + ", branch="
                            + branch
                            + ", commitSha="
                            + shortSha(commitSha)
                            + ", reason=Retraining already active."
            );
            throw new RetrainingConflictException(
                    "RETRAINING_ALREADY_RUNNING",
                    "Un réentraînement est déjà en cours pour ce projet."
            );
        }

        /*
         * Source par défaut : CURRENT_PRODUCTION (même résolution que le
         * retraining manuel/planifié). Aucune source valide -> BLOCKED_NO_SOURCE.
         */
        Long sourceExperimentId = sourceResolver
                .resolveSourceExperimentId(
                        projectId,
                        config.getSourceExperimentId()
                );

        if (sourceExperimentId == null) {
            System.out.println(
                    "[GITHUB_RETRAIN_REJECTED] projectId="
                            + projectId
                            + ", repository="
                            + repository
                            + ", branch="
                            + branch
                            + ", commitSha="
                            + shortSha(commitSha)
                            + ", reason=No valid production source."
            );
            throw new IllegalStateException(
                    "No valid production source found for projectId="
                            + projectId
            );
        }

        MlExperiment productionSource =
                sourceResolver.resolveAndSynchronize(
                        projectId,
                        config.getSourceExperimentId()
                );

        String sourceModelVersion =
                productionSource != null
                        && productionSource.getModelVersion() != null
                        ? String.valueOf(
                                productionSource.getModelVersion()
                        )
                        : null;

        /*
         * Claim atomique (même verrou que le retraining manuel) : running=true
         * en base, deux pushs simultanés ne peuvent pas réserver la même config.
         */
        int claimed = executeInNewTransaction(() ->
                retrainingConfigRepository.claimManualRetraining(
                        config.getId(),
                        utcNow()
                )
        );

        if (claimed != 1) {
            System.out.println(
                    "[GITHUB_RETRAIN_REJECTED] projectId="
                            + projectId
                            + ", reason=Config could not be claimed "
                            + "(already running)."
            );
            throw new RetrainingConflictException(
                    "RETRAINING_ALREADY_RUNNING",
                    "Un réentraînement est déjà en cours pour ce projet."
            );
        }

        executeInNewTransaction(() ->
                retrainingConfigRepository.updateLastTriggerReason(
                        config.getId(),
                        "GITHUB_PUSH " + shortSha(commitSha),
                        utcNow()
                )
        );

        System.out.println(
                "[GITHUB_RETRAIN_ACCEPTED] projectId="
                        + projectId
                        + ", repository="
                        + repository
                        + ", branch="
                        + branch
                        + ", commitSha="
                        + shortSha(commitSha)
                        + ", sourceExperimentId="
                        + sourceExperimentId
        );

        try {
            Path commitDir = gitHubFileFetcherService
                    .fetchCommitArchive(repository, commitSha);

            MlExperimentResponse response =
                    mlExperimentService
                            .retrainFromGitHubCommit(
                                    projectId,
                                    sourceExperimentId,
                                    commitDir,
                                    repository,
                                    branch,
                                    commitSha,
                                    RetrainingTriggerType.GITHUB_PUSH.name(),
                                    triggerMetadata
                            );

            if (response == null
                    || response.getId() == null) {
                throw new IllegalStateException(
                        "The GitHub retraining request did not create "
                                + "a new experiment. projectId="
                                + projectId
                );
            }

            String status = response.getStatus();

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

            System.out.println(
                    "[RETRAINING_JOB] GitHub retraining launched. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + sourceExperimentId
                            + ", experimentId="
                            + response.getId()
                            + ", jobName="
                            + response.getKubeflowRunName()
                            + ", status="
                            + status
                            + ", trigger=GITHUB_PUSH"
            );

            releaseAfterSuccess(
                    config.getId(),
                    utcNow(),
                    null,
                    sourceModelVersion,
                    response.getId()
            );

            return response;

        } catch (Exception error) {
            releaseManualAfterFailure(config.getId());

            System.err.println(
                    "[RETRAINING_ERROR] GitHub retraining failed. "
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

    private String shortSha(String commitSha) {
        if (commitSha == null) {
            return "null";
        }
        String trimmed = commitSha.trim();
        return trimmed.length() <= 8
                ? trimmed
                : trimmed.substring(0, 8);
    }

    /**
     * Réconciliation des configurations dont l'expérience de réentraînement
     * est terminée.
     *
     * Après un lancement réussi, l'état reste RUNNING avec running=false :
     * rien ne le ramène à IDLE quand l'entraînement se termine. Ces
     * configurations deviennent alors définitivement non réclamables
     * (manuel ou planifié).
     *
     * On ne réinitialise une configuration que si aucune expérience
     * CREATED/RUNNING 'scheduled_retraining_%' n'existe réellement pour le
     * projet (source de vérité : ml_experiments).
     *
     * @return nombre de configurations réinitialisées
     */
    public int reconcileFinishedRunningConfigs() {
        List<RetrainingConfig> finished =
                retrainingConfigRepository
                        .findFinishedRunningConfigs();

        if (finished == null || finished.isEmpty()) {
            return 0;
        }

        int resetCount = 0;

        for (RetrainingConfig config : finished) {
            if (config == null
                    || config.getId() == null
                    || config.getProjectId() == null) {
                continue;
            }

            Long configId = config.getId();
            Long projectId = config.getProjectId();

            try {
                Long activeCount =
                        mlExperimentRepository
                                .countActiveScheduledRetrainingForProject(
                                        projectId
                                );

                boolean stillActive =
                        activeCount != null
                                && activeCount > 0;

                if (stillActive) {
                    continue;
                }

                int updated =
                        executeInNewTransaction(() -> {
                            LocalDateTime now = utcNow();
                            return retrainingConfigRepository
                                    .resetFinishedConfigToIdle(
                                            configId,
                                            null,
                                            now
                                    );
                        });

                if (updated > 0) {
                    resetCount++;

                    System.out.println(
                            "[RETRAINING_RECONCILIATION] Reset finished config. "
                                    + "configId="
                                    + configId
                                    + ", projectId="
                                    + projectId
                                    + ", state="
                                    + config.getState()
                    );
                }

            } catch (Exception reconcileError) {
                System.err.println(
                        "[RETRAINING_RECONCILIATION] Failed. "
                                + "configId="
                                + configId
                                + ", projectId="
                                + projectId
                                + ", reason="
                                + safeMessage(
                                        reconcileError.getMessage()
                                )
                );
            }
        }

        if (resetCount > 0) {
            System.out.println(
                    "[RETRAINING_RECONCILIATION] Total reset configs="
                            + resetCount
            );
        }

        return resetCount;
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

                mlExperimentService.markExperimentFailedWithDetails(
                        experiment,
                        "STUCK_RECOVERY",
                        "Réentraînement resté bloqué (aucun Job Kubernetes actif après "
                                + RETRAINING_STUCK_TIMEOUT_MINUTES + " minutes)",
                        "projectId=" + projectId
                                + ", jobName=" + firstNotBlank(
                                        experiment.getTrainingJobName(),
                                        experiment.getKubeflowRunName()
                                )
                                + ", ageMinutes=" + ageMinutes,
                        "Vérifiez l'état du cluster Kubernetes et relancez manuellement si nécessaire.",
                        null,
                        "AutoRetrainingService.java"
                );

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
                                + ", newStatus=FAILED"
                                + ", notificationSent=true"
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

    /**
     * Reconcile Kubernetes job status for CUSTOM_DOCKERFILE experiments
     * that are still marked RUNNING in the database.
     *
     * This method proactively checks the actual K8s job status and
     * marks experiments as FAILED if their jobs have completed with failure
     * or are no longer found. This prevents stale RUNNING states from
     * blocking new retraining attempts.
     *
     * @param projectId the project to reconcile (null = all projects)
     * @return number of experiments repaired
     */
    public int reconcileKubernetesJobStatuses(Long projectId) {
        int repaired = 0;

        try {
            List<MlExperiment> runningExperiments;

            if (projectId != null) {
                runningExperiments = mlExperimentRepository
                        .findActiveRetrainingForProject(projectId);
                if (runningExperiments != null) {
                    runningExperiments = runningExperiments.stream()
                            .filter(exp -> "CUSTOM_DOCKERFILE".equalsIgnoreCase(
                                    exp.getRuntimeMode()))
                            .toList();
                }
            } else {
                runningExperiments = mlExperimentRepository
                        .findByStatusAndRuntimeMode(
                                "RUNNING",
                                "CUSTOM_DOCKERFILE"
                        );
            }

            if (runningExperiments == null || runningExperiments.isEmpty()) {
                return 0;
            }

            for (MlExperiment experiment : runningExperiments) {
                try {
                    String jobName = firstNotBlank(
                            experiment.getTrainingJobName(),
                            experiment.getKubeflowRunName()
                    );

                    if (!hasText(jobName)) {
                        continue;
                    }

                    String k8sStatus = kubernetesJobService
                            .getCustomJobStatus(jobName);

                    if ("FAILED".equalsIgnoreCase(k8sStatus)
                            || "NOT_FOUND".equalsIgnoreCase(k8sStatus)) {
                        ExperimentStatus previous = experiment.getStatus();

                        mlExperimentService.markExperimentFailedWithDetails(
                                experiment,
                                "K8S_RECONCILIATION",
                                "Kubernetes job " + k8sStatus,
                                "jobName=" + jobName + ", k8sStatus=" + k8sStatus,
                                "Vérifiez les logs du Job Kubernetes et corrigez la cause première.",
                                null,
                                "KubernetesJobService.java"
                        );

                        repaired++;

                        System.out.println(
                                "[RETRAINING_STATE] projectId="
                                        + (experiment.getProject() != null
                                        ? experiment.getProject().getId()
                                        : "?")
                                        + ", experimentId=" + experiment.getId()
                                        + ", dbStatus=" + previous
                                        + ", k8sJobStatus=" + k8sStatus
                                        + ", jobName=" + jobName
                                        + ", action=RECONCILE_MARK_FAILED"
                                        + ", notificationSent=true"
                        );
                    }
                } catch (Exception ex) {
                    System.err.println(
                            "[RETRAINING_STATE] Failed to reconcile experiment "
                                    + experiment.getId() + ": " + ex.getMessage()
                    );
                }
            }
        } catch (Exception e) {
            System.err.println(
                    "[RETRAINING_STATE] Reconciliation failed. projectId="
                            + projectId + ", reason=" + e.getMessage()
            );
        }

        return repaired;
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
             * S'il est introuvable OU en échec, l'expérience est bloquée
             * et doit être réparée.
             */
            if (!hasText(jobName)) {
                return true;
            }

            try {
                String jobStatus =
                        kubernetesJobService
                                .getCustomJobStatus(jobName);

                return "NOT_FOUND".equalsIgnoreCase(jobStatus)
                        || "FAILED".equalsIgnoreCase(jobStatus);
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

            boolean stateStale =
                    config.getState() == RetrainingState.RUNNING
                            || config.getState() == RetrainingState.TRIGGERED;

            if (config.isRunning()
                    || config.isManualTriggerRequested()
                    || stateStale) {
                config.setRunning(false);
                config.setManualTriggerRequested(false);
                config.setLastTriggerReason("RECOVERED");
                config.setLastDecisionReason(
                        "Aucune expérience active : état réinitialisé."
                );

                if (stateStale) {
                    config.setState(RetrainingState.IDLE);
                }

                retrainingConfigRepository.saveAndFlush(config);

                System.out.println(
                        "[RETRAINING_RECOVERY] Cleared stale flags. "
                                + "projectId="
                                + projectId
                                + ", state="
                                + config.getState()
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

    private void releaseAfterSuccess(
            Long configId,
            LocalDateTime lastRetrainingAt,
            LocalDateTime nextRetrainingAt,
            String sourceModelVersion,
            Long experimentId
    ) {
        LocalDateTime updatedAt =
                utcNow();

        int updated =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .releaseAfterSuccess(
                                        configId,
                                        lastRetrainingAt,
                                        null,
                                        sourceModelVersion,
                                        experimentId,
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after success. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
                        + ", sourceModelVersion="
                        + sourceModelVersion
                        + ", experimentId="
                        + experimentId
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
                                        null,
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after failure. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
        );
    }

    private void releaseAfterCooldown(
            Long configId,
            LocalDateTime nextRetrainingAt
    ) {
        LocalDateTime updatedAt =
                utcNow();

        LocalDateTime cooldownUntil =
                cooldownHours > 0
                        ? updatedAt.plusHours(cooldownHours)
                        : null;

        int updated =
                executeInNewTransaction(() ->
                        retrainingConfigRepository
                                .releaseAfterCooldown(
                                        configId,
                                        nextRetrainingAt,
                                        cooldownUntil,
                                        updatedAt
                                )
                );

        System.out.println(
                "[AUTO_RETRAINING] Released after cooldown threshold. "
                        + "configId="
                        + configId
                        + ", updatedRows="
                        + updated
                        + ", cooldownUntil="
                        + cooldownUntil
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
                                        null,
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
        notifyRetrainingInfo(
                projectId,
                experimentId,
                title,
                message,
                NotificationType.SYSTEM_INFO
        );
    }

    private void notifyRetrainingInfo(
            Long projectId,
            Long experimentId,
            String title,
            String message,
            NotificationType notificationType
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
                    notificationType,
                    targetType,
                    targetId
            );

            System.out.println(
                    "[NOTIFICATION] created type="
                            + notificationType
                            + " title=\""
                            + title
                            + "\" userId="
                            + (project.getOwner() != null
                            ? project.getOwner().getId()
                            : "null")
                            + " projectId="
                            + projectId
                            + " experimentId="
                            + experimentId
            );

        } catch (Exception notificationError) {
            System.err.println(
                    "[NOTIFICATION] FAILED to create. "
                            + "type="
                            + notificationType
                            + " projectId="
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
