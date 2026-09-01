package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingConfigRequest;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingFrequencyType;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class RetrainingConfigService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlProjectRepository mlProjectRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final NotificationService notificationService;
    private final RetrainingSourceResolverService sourceResolver;

    public RetrainingConfigService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlProjectRepository mlProjectRepository,
            MlExperimentRepository mlExperimentRepository,
            NotificationService notificationService,
            RetrainingSourceResolverService sourceResolver
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.notificationService = notificationService;
        this.sourceResolver = sourceResolver;
    }

    @Transactional(timeout = 10)
    public RetrainingConfig saveOrUpdateProjectConfig(
            Long projectId,
            RetrainingConfigRequest request
    ) {
        return saveOrUpdateInternal(projectId, null, request, false);
    }

    @Transactional(timeout = 10)
    public RetrainingConfig saveOrUpdateConfig(
            Long projectId,
            Long sourceExperimentId,
            RetrainingConfigRequest request
    ) {
        return saveOrUpdateInternal(projectId, sourceExperimentId, request, true);
    }

    private RetrainingConfig saveOrUpdateInternal(
            Long projectId,
            Long sourceExperimentId,
            RetrainingConfigRequest request,
            boolean requireSourceExperiment
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (request == null) {
            throw new IllegalArgumentException("Retraining configuration request is required");
        }

        MlProject project = mlProjectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Project not found: " + projectId
                ));

        boolean enabled = request.isEnabled();

        if (requireSourceExperiment && sourceExperimentId == null && enabled) {
            /*
             * Pour un projet déjà en Production, la source sera retrouvée plus bas
             * avec RetrainingSourceResolverService. On ne bloque donc pas la création
             * de la configuration si l'identifiant n'est pas transmis par le frontend.
             */
            System.out.println(
                    "[RETRAINING_CONFIG] sourceExperimentId not provided; "
                            + "Production source will be resolved automatically. projectId="
                            + projectId
            );
        }

        if (sourceExperimentId != null) {
            validateSourceExperiment(projectId, sourceExperimentId);
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseGet(RetrainingConfig::new);

        boolean newConfig = config.getId() == null;

        RetrainingFrequencyType frequencyType = resolveFrequencyType(request);
        Integer frequencyValue = resolveFrequencyValue(request);

        config.setProjectId(projectId);

        if (sourceExperimentId != null) {
            config.setSourceExperimentId(sourceExperimentId);
        }

        config.setEnabled(enabled);
        config.setFrequencyType(frequencyType);
        config.setFrequencyValue(frequencyValue);

        /*
         * En l'absence de stratégie explicite, on applique le mode planifié simple.
         */
        if (request.getStrategy() == null) {
            config.setStrategy(RetrainingStrategy.MONITORING_SMART);
        }

        applyMonitoringSettings(config, request);

        /*
         * Une simple modification ne doit jamais forcer running=false
         * lorsqu'un réentraînement est réellement en cours.
         */
        if (newConfig) {
            config.setRunning(false);
            config.setManualTriggerRequested(false);
        } else if (!enabled) {
            config.setManualTriggerRequested(false);
        }

        config.setLastTriggerReason(enabled ? "CONFIG_UPDATED" : "DISABLED");
        config.setLastDecisionReason(null);

        if (enabled) {
            config.scheduleNextFrom(LocalDateTime.now());
        } else {
            config.setNextRetrainingAt(null);
        }

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);

        /*
         * IMPORTANT :
         * La résolution de la source Production n'est plus effectuée ici.
         * Elle peut appeler plusieurs services et prolonger la transaction HTTP.
         * Le scheduler / RetrainingSourcePersistenceService résoudra la source
         * lorsqu'une version Production sera disponible.
         */
        return saved;
    }

    @Transactional(readOnly = true)
    public RetrainingConfig getConfig(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        return retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);
    }

    @Transactional(timeout = 10)
    public RetrainingConfig enableDefaultWeeklyRetraining(
            Long projectId,
            Long sourceExperimentId
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        RetrainingConfigRequest request = new RetrainingConfigRequest();
        request.setEnabled(true);
        request.setFrequencyType(RetrainingFrequencyType.WEEK);
        request.setFrequencyValue(1);
        request.setStrategy(RetrainingStrategy.MONITORING_SMART);

        return saveOrUpdateConfig(projectId, sourceExperimentId, request);
    }

    @Transactional(timeout = 10)
    public RetrainingConfig disableRetraining(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        MlProject project = mlProjectRepository.findById(projectId).orElse(null);

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseGet(RetrainingConfig::new);

        boolean newConfig = config.getId() == null;

        config.setProjectId(projectId);
        config.setEnabled(false);

        /*
         * Pour une nouvelle configuration uniquement, on initialise running.
         * Pour une configuration existante, on conserve l'état réel du job.
         */
        if (newConfig) {
            config.setRunning(false);
        }

        /*
         * Une configuration désactivée ne doit garder aucune demande manuelle.
         */
        config.setManualTriggerRequested(false);
        config.setLastTriggerReason("DISABLED");
        config.setLastDecisionReason(null);
        config.setNextRetrainingAt(null);

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);


        return saved;
    }

    @Transactional(timeout = 10)
    public RetrainingConfig forceRetrainingNow(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No retraining config found for projectId=" + projectId
                ));

        if (!config.isEnabled()) {
            throw new IllegalStateException(
                    "Retraining is disabled for projectId=" + projectId
            );
        }

        if (config.isRunning()) {
            throw new IllegalStateException(
                    "Retraining is already running for projectId=" + projectId
            );
        }

        /*
         * Le scheduler verra manualTriggerRequested=true et une date déjà échue.
         */
        config.setRunning(false);
        config.setManualTriggerRequested(true);
        config.setLastTriggerReason("MANUAL");
        config.setLastDecisionReason(
                "Réentraînement manuel demandé par l'utilisateur."
        );
        config.setNextRetrainingAt(LocalDateTime.now(Clock.systemUTC()).minusSeconds(5));

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);


        return saved;
    }

    private void applyMonitoringSettings(
            RetrainingConfig config,
            RetrainingConfigRequest request
    ) {
        if (config == null || request == null) {
            return;
        }

        if (request.getStrategy() != null) {
            config.setStrategy(request.getStrategy());
        }

        if (request.getFailureMonitoringEnabled() != null) {
            config.setFailureMonitoringEnabled(
                    request.getFailureMonitoringEnabled()
            );
        }

        if (request.getPerformanceMonitoringEnabled() != null) {
            config.setPerformanceMonitoringEnabled(
                    request.getPerformanceMonitoringEnabled()
            );
        }

        if (request.getDataDriftMonitoringEnabled() != null) {
            config.setDataDriftMonitoringEnabled(
                    request.getDataDriftMonitoringEnabled()
            );
        }

        if (request.getMonitoringLookbackHours() != null) {
            config.setMonitoringLookbackHours(
                    request.getMonitoringLookbackHours()
            );
        }

        if (request.getMinPredictionsForMonitoring() != null) {
            config.setMinPredictionsForMonitoring(
                    request.getMinPredictionsForMonitoring()
            );
        }

        if (request.getMaxPredictionErrorRatePercent() != null) {
            config.setMaxPredictionErrorRatePercent(
                    request.getMaxPredictionErrorRatePercent()
            );
        }

        if (request.getPerformanceDropThresholdPercent() != null) {
            config.setPerformanceDropThresholdPercent(
                    request.getPerformanceDropThresholdPercent()
            );
        }

        if (request.getDataDriftThresholdPercent() != null) {
            config.setDataDriftThresholdPercent(
                    request.getDataDriftThresholdPercent()
            );
        }

        if (request.getGlobalScoreThreshold() != null) {
            config.setGlobalScoreThreshold(
                    request.getGlobalScoreThreshold()
            );
        }

        if (request.getClassificationMinimumGlobalScore() != null) {
            config.setClassificationMinimumGlobalScore(
                    request.getClassificationMinimumGlobalScore()
            );
        }

        if (request.getRegressionMinimumGlobalScore() != null) {
            config.setRegressionMinimumGlobalScore(
                    request.getRegressionMinimumGlobalScore()
            );
        }

        if (request.getDataPolicy() != null) {
            config.setDataPolicy(request.getDataPolicy());
        }

        config.normalizeMonitoringSettings();
    }

    private void validateSourceExperiment(
            Long projectId,
            Long sourceExperimentId
    ) {
        MlExperiment experiment = mlExperimentRepository
                .findById(sourceExperimentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source experiment not found: " + sourceExperimentId
                ));

        if (experiment.getProject() == null
                || experiment.getProject().getId() == null
                || !experiment.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Source experiment does not belong to projectId=" + projectId
            );
        }
    }

    private RetrainingFrequencyType resolveFrequencyType(
            RetrainingConfigRequest request
    ) {
        if (request == null || request.getFrequencyType() == null) {
            return RetrainingFrequencyType.WEEK;
        }

        return request.getFrequencyType();
    }

    private Integer resolveFrequencyValue(
            RetrainingConfigRequest request
    ) {
        if (request == null
                || request.getFrequencyValue() == null
                || request.getFrequencyValue() <= 0) {
            return 1;
        }

        return Math.min(request.getFrequencyValue(), 365);
    }

    private void notifyConfigChanged(
            MlProject project,
            RetrainingConfig config,
            String action
    ) {
        if (project == null || config == null) {
            return;
        }

        try {
            String projectName =
                    project.getName() != null && !project.getName().isBlank()
                            ? project.getName()
                            : "project " + project.getId();

            String message =
                    "Le réentraînement automatique du projet "
                            + projectName
                            + " a été "
                            + action
                            + ".";

            if (config.isEnabled() && config.getNextRetrainingAt() != null) {
                message +=
                        " Prochaine exécution : "
                                + config.getNextRetrainingAt()
                                + ".";
            }

            notificationService.notifyUser(
                    project.getOwner(),
                    "Réentraînement automatique",
                    message,
                    NotificationType.SYSTEM_INFO,
                    NotificationTargetType.PROJECT,
                    project.getId()
            );
        } catch (Exception e) {
            System.out.println(
                    "[AUTO_RETRAINING][NOTIFICATION] "
                            + "Cannot notify config change. projectId="
                            + project.getId()
                            + ", reason="
                            + e.getMessage()
            );
        }
    }
}
