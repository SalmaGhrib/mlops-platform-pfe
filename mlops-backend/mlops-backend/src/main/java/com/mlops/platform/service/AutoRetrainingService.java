package com.mlops.platform.service;

import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AutoRetrainingService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final MlExperimentService mlExperimentService;

    public AutoRetrainingService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentRepository mlExperimentRepository,
            MlExperimentService mlExperimentService
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlExperimentService = mlExperimentService;
    }

    @Transactional
    public void launchScheduledRetraining(RetrainingConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }

        RetrainingConfig freshConfig = retrainingConfigRepository
                .findById(config.getId())
                .orElse(null);

        if (freshConfig == null) {
            return;
        }

        Long projectId = freshConfig.getProjectId();

        if (projectId == null) {
            System.out.println("[AUTO_RETRAINING] Ignored because projectId is null.");
            return;
        }

        if (!freshConfig.isEnabled()) {
            System.out.println("[AUTO_RETRAINING] Ignored because config is disabled. projectId="
                    + projectId);
            return;
        }

        if (freshConfig.isRunning()) {
            System.out.println("[AUTO_RETRAINING] Ignored because retraining config is already running. projectId="
                    + projectId);
            return;
        }

        if (freshConfig.getNextRetrainingAt() == null) {
            System.out.println("[AUTO_RETRAINING] Ignored because nextRetrainingAt is null. projectId="
                    + projectId);
            return;
        }

        if (freshConfig.getNextRetrainingAt().isAfter(LocalDateTime.now())) {
            System.out.println("[AUTO_RETRAINING] Ignored because retraining is not due yet. projectId="
                    + projectId
                    + ", nextRetrainingAt="
                    + freshConfig.getNextRetrainingAt());
            return;
        }

        boolean activeScheduledRetraining =
                mlExperimentRepository.existsActiveScheduledRetrainingForProject(projectId);

        if (activeScheduledRetraining) {
            System.out.println("[AUTO_RETRAINING] Ignored because active scheduled retraining already exists. projectId="
                    + projectId);
            return;
        }

        try {
            freshConfig.setRunning(true);
            retrainingConfigRepository.saveAndFlush(freshConfig);

            System.out.println("[AUTO_RETRAINING] Starting scheduled retraining from CURRENT PRODUCTION version. projectId="
                    + projectId
                    + ", fallbackSourceExperimentId="
                    + freshConfig.getSourceExperimentId());

            MlExperimentResponse newExperiment =
                    mlExperimentService.retrainFromCurrentProductionExperiment(
                            projectId,
                            freshConfig.getSourceExperimentId()
                    );

            LocalDateTime now = LocalDateTime.now();

            freshConfig.setLastRetrainingAt(now);
            freshConfig.setNextRetrainingAt(freshConfig.calculateNextRetrainingDate(now));
            freshConfig.setRunning(false);

            retrainingConfigRepository.saveAndFlush(freshConfig);

            System.out.println("[AUTO_RETRAINING] Scheduled retraining launched successfully. projectId="
                    + projectId
                    + ", newExperimentId="
                    + (newExperiment != null ? newExperiment.getId() : null)
                    + ", nextRetrainingAt="
                    + freshConfig.getNextRetrainingAt());

        } catch (Exception e) {
            freshConfig.setRunning(false);

            if (freshConfig.isEnabled()) {
                freshConfig.setNextRetrainingAt(
                        freshConfig.calculateNextRetrainingDate(LocalDateTime.now())
                );
            } else {
                freshConfig.setNextRetrainingAt(null);
            }

            retrainingConfigRepository.saveAndFlush(freshConfig);

            System.err.println("[AUTO_RETRAINING] Scheduled retraining failed. projectId="
                    + projectId
                    + ", fallbackSourceExperimentId="
                    + freshConfig.getSourceExperimentId()
                    + ", error="
                    + e.getMessage());

            throw e;
        }
    }
}