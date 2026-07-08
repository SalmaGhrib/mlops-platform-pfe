package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MonitoringRetrainingTriggerService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final RetrainingDecisionService retrainingDecisionService;

    @Value("${monitoring.retraining.cooldown-minutes:120}")
    private long cooldownMinutes;

    public MonitoringRetrainingTriggerService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentRepository mlExperimentRepository,
            RetrainingDecisionService retrainingDecisionService
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.retrainingDecisionService = retrainingDecisionService;
    }

    @Transactional
    public boolean triggerRetrainingFromMonitoringAlert(Long projectId, String reason) {
        if (projectId == null) {
            System.out.println("[MONITORING_RETRAINING] Ignored: projectId is null");
            return false;
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);

        if (config == null) {
            System.out.println("[MONITORING_RETRAINING] Ignored: no retraining config for projectId="
                    + projectId);
            return false;
        }

        if (!canRequestMonitoringRetraining(config, projectId)) {
            return false;
        }

        requestNow(config, "MONITORING_ALERT", reason);

        System.out.println("[MONITORING_RETRAINING] Retraining requested from monitoring alert. projectId="
                + projectId
                + ", reason="
                + reason);

        return true;
    }

    @Transactional
    public int evaluateSmartTriggersForAllConfigs() {
        List<RetrainingConfig> configs = retrainingConfigRepository.findEnabledNotRunningConfigs();

        if (configs == null || configs.isEmpty()) {
            return 0;
        }

        int requested = 0;

        for (RetrainingConfig config : configs) {
            if (config == null || config.getId() == null || config.getProjectId() == null) {
                continue;
            }

            RetrainingStrategy strategy = config.getStrategy();
            if (strategy == RetrainingStrategy.SCHEDULED_ONLY) {
                continue;
            }

            if (!canRequestMonitoringRetraining(config, config.getProjectId())) {
                continue;
            }

            RetrainingDecisionResult decision = retrainingDecisionService.evaluateMonitoringSignals(config);

            if (decision != null && decision.isShouldRetrain()) {
                requestNow(
                        config,
                        decision.getTriggerType() != null ? decision.getTriggerType().name() : "SMART_MONITORING",
                        decision.getReason()
                );
                requested++;
            }
        }

        if (requested > 0) {
            System.out.println("[MONITORING_RETRAINING] Smart monitoring requested retraining count=" + requested);
        }

        return requested;
    }

    private boolean canRequestMonitoringRetraining(RetrainingConfig config, Long projectId) {
        if (config == null) {
            return false;
        }

        if (!config.isEnabled()) {
            System.out.println("[MONITORING_RETRAINING] Ignored: retraining disabled for projectId="
                    + projectId);
            return false;
        }

        if (config.isRunning()) {
            System.out.println("[MONITORING_RETRAINING] Ignored: retraining config already running for projectId="
                    + projectId);
            return false;
        }

        Long activeScheduledRetrainingCount =
                mlExperimentRepository.countActiveScheduledRetrainingForProject(projectId);

        boolean activeScheduledRetraining = activeScheduledRetrainingCount != null
                && activeScheduledRetrainingCount > 0;

        if (activeScheduledRetraining) {
            System.out.println("[MONITORING_RETRAINING] Ignored: active scheduled retraining already exists for projectId="
                    + projectId);
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        if (config.getNextRetrainingAt() != null
                && !config.getNextRetrainingAt().isAfter(now)) {
            System.out.println("[MONITORING_RETRAINING] Ignored: retraining already requested for projectId="
                    + projectId
                    + ", nextRetrainingAt="
                    + config.getNextRetrainingAt());
            return false;
        }

        if (config.getLastRetrainingAt() != null
                && cooldownMinutes > 0
                && config.getLastRetrainingAt().isAfter(now.minusMinutes(cooldownMinutes))) {
            System.out.println("[MONITORING_RETRAINING] Ignored: cooldown active for projectId="
                    + projectId
                    + ", lastRetrainingAt="
                    + config.getLastRetrainingAt()
                    + ", cooldownMinutes="
                    + cooldownMinutes);
            return false;
        }

        return true;
    }

    private void requestNow(RetrainingConfig config, String triggerReason, String decisionReason) {
        config.setRunning(false);
        config.setManualTriggerRequested(false);
        config.setLastTriggerReason(triggerReason);
        config.setLastDecisionReason(decisionReason);
        config.setNextRetrainingAt(LocalDateTime.now().minusSeconds(5));

        retrainingConfigRepository.saveAndFlush(config);
    }
}
