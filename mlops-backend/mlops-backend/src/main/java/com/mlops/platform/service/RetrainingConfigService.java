package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingConfigRequest;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingFrequencyType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RetrainingConfigService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlProjectRepository mlProjectRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final NotificationService notificationService;

    public RetrainingConfigService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlProjectRepository mlProjectRepository,
            MlExperimentRepository mlExperimentRepository,
            NotificationService notificationService
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public RetrainingConfig saveOrUpdateProjectConfig(
            Long projectId,
            RetrainingConfigRequest request
    ) {
        return saveOrUpdateInternal(projectId, null, request, false);
    }

    @Transactional
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

        MlProject project = mlProjectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        boolean enabled = request != null && request.isEnabled();

        if (requireSourceExperiment && enabled && sourceExperimentId == null) {
            throw new IllegalArgumentException("sourceExperimentId is required when retraining is enabled from an experiment");
        }

        if (sourceExperimentId != null) {
            validateSourceExperiment(projectId, sourceExperimentId);
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseGet(RetrainingConfig::new);

        RetrainingFrequencyType frequencyType = resolveFrequencyType(request);
        Integer frequencyValue = resolveFrequencyValue(request);

        config.setProjectId(projectId);

        if (sourceExperimentId != null) {
            config.setSourceExperimentId(sourceExperimentId);
        }

        config.setEnabled(enabled);
        config.setFrequencyType(frequencyType);
        config.setFrequencyValue(frequencyValue);
        applyMonitoringSettings(config, request);
        config.setRunning(false);
        config.setManualTriggerRequested(false);
        config.setLastTriggerReason(enabled ? "CONFIG_UPDATED" : "DISABLED");
        config.setLastDecisionReason(null);

        if (enabled) {
            config.scheduleNextFrom(LocalDateTime.now());
        } else {
            config.setNextRetrainingAt(null);
        }

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);

        notifyConfigChanged(project, saved, enabled ? "activé" : "désactivé");

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

    @Transactional
    public RetrainingConfig enableDefaultWeeklyRetraining(
            Long projectId,
            Long sourceExperimentId
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (sourceExperimentId == null) {
            throw new IllegalArgumentException("sourceExperimentId is required");
        }

        RetrainingConfigRequest request = new RetrainingConfigRequest();
        request.setEnabled(true);
        request.setFrequencyType(RetrainingFrequencyType.WEEK);
        request.setFrequencyValue(1);

        return saveOrUpdateConfig(projectId, sourceExperimentId, request);
    }

    @Transactional
    public RetrainingConfig disableRetraining(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        MlProject project = mlProjectRepository.findById(projectId).orElse(null);

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseGet(RetrainingConfig::new);

        config.setProjectId(projectId);
        config.setEnabled(false);
        config.setRunning(false);
        config.setManualTriggerRequested(false);
        config.setLastTriggerReason("DISABLED");
        config.setLastDecisionReason(null);
        config.setNextRetrainingAt(null);

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);

        if (project != null) {
            notifyConfigChanged(project, saved, "désactivé");
        }

        return saved;
    }

    @Transactional
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

        config.setRunning(false);
        config.setManualTriggerRequested(true);
        config.setLastTriggerReason("MANUAL");
        config.setLastDecisionReason("Réentraînement manuel demandé par l'utilisateur.");
        config.setNextRetrainingAt(LocalDateTime.now().minusSeconds(5));

        RetrainingConfig saved = retrainingConfigRepository.saveAndFlush(config);

        MlProject project = mlProjectRepository.findById(projectId).orElse(null);
        if (project != null) {
            notifyConfigChanged(project, saved, "demandé maintenant");
        }

        return saved;
    }


    private void applyMonitoringSettings(RetrainingConfig config, RetrainingConfigRequest request) {
        if (config == null || request == null) {
            return;
        }

        if (request.getStrategy() != null) {
            config.setStrategy(request.getStrategy());
        }

        if (request.getFailureMonitoringEnabled() != null) {
            config.setFailureMonitoringEnabled(request.getFailureMonitoringEnabled());
        }

        if (request.getPerformanceMonitoringEnabled() != null) {
            config.setPerformanceMonitoringEnabled(request.getPerformanceMonitoringEnabled());
        }

        if (request.getDataDriftMonitoringEnabled() != null) {
            config.setDataDriftMonitoringEnabled(request.getDataDriftMonitoringEnabled());
        }

        if (request.getMonitoringLookbackHours() != null) {
            config.setMonitoringLookbackHours(request.getMonitoringLookbackHours());
        }

        if (request.getMinPredictionsForMonitoring() != null) {
            config.setMinPredictionsForMonitoring(request.getMinPredictionsForMonitoring());
        }

        if (request.getMaxPredictionErrorRatePercent() != null) {
            config.setMaxPredictionErrorRatePercent(request.getMaxPredictionErrorRatePercent());
        }

        if (request.getPerformanceDropThresholdPercent() != null) {
            config.setPerformanceDropThresholdPercent(request.getPerformanceDropThresholdPercent());
        }

        if (request.getDataDriftThresholdPercent() != null) {
            config.setDataDriftThresholdPercent(request.getDataDriftThresholdPercent());
        }

        config.normalizeMonitoringSettings();
    }

    private void validateSourceExperiment(Long projectId, Long sourceExperimentId) {
        MlExperiment experiment = mlExperimentRepository.findById(sourceExperimentId)
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

    private RetrainingFrequencyType resolveFrequencyType(RetrainingConfigRequest request) {
        if (request == null || request.getFrequencyType() == null) {
            return RetrainingFrequencyType.WEEK;
        }

        return request.getFrequencyType();
    }

    private Integer resolveFrequencyValue(RetrainingConfigRequest request) {
        if (request == null || request.getFrequencyValue() == null || request.getFrequencyValue() <= 0) {
            return 1;
        }

        return Math.min(request.getFrequencyValue(), 365);
    }

    private void notifyConfigChanged(MlProject project, RetrainingConfig config, String action) {
        if (project == null || config == null) {
            return;
        }

        try {
            String projectName = project.getName() != null && !project.getName().isBlank()
                    ? project.getName()
                    : "project " + project.getId();

            String message = "Le réentraînement automatique du projet "
                    + projectName
                    + " a été "
                    + action
                    + ".";

            if (config.isEnabled() && config.getNextRetrainingAt() != null) {
                message += " Prochaine exécution : " + config.getNextRetrainingAt() + ".";
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
            System.out.println("[AUTO_RETRAINING][NOTIFICATION] Cannot notify config change. projectId="
                    + project.getId()
                    + ", reason="
                    + e.getMessage());
        }
    }
}