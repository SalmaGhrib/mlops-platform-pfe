package com.mlops.platform.service;

import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MonitoringRetrainingTriggerService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentRepository mlExperimentRepository;

    @Value("${monitoring.retraining.cooldown-minutes:120}")
    private long cooldownMinutes;

    public MonitoringRetrainingTriggerService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentRepository mlExperimentRepository
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentRepository = mlExperimentRepository;
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

        config.setRunning(false);
        config.setNextRetrainingAt(LocalDateTime.of(2000, 1, 1, 0, 0));

        retrainingConfigRepository.saveAndFlush(config);

        System.out.println("[MONITORING_RETRAINING] Retraining requested from monitoring alert. projectId="
                + projectId
                + ", reason="
                + reason);

        return true;
    }
}