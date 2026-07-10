package com.mlops.platform.scheduler;

import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.RetrainingConfigRepository;
import com.mlops.platform.service.AutoRetrainingService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AutoRetrainingScheduler {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final AutoRetrainingService autoRetrainingService;

    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public AutoRetrainingScheduler(
            RetrainingConfigRepository retrainingConfigRepository,
            AutoRetrainingService autoRetrainingService
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.autoRetrainingService = autoRetrainingService;
    }

    @PostConstruct
    public void init() {
        System.out.println("[AUTO_RETRAINING] Scheduler loaded");
    }

    @Scheduled(fixedDelayString = "${app.retraining.scheduler.delay-ms:60000}")
    @SchedulerLock(name = "autoRetrainingScheduler", lockAtMostFor = "60m", lockAtLeastFor = "30s")
    public void checkScheduledRetraining() {
        if (!schedulerRunning.compareAndSet(false, true)) {
            System.out.println("[AUTO_RETRAINING] Previous scheduler cycle still running. Skipped.");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();

            System.out.println("[AUTO_RETRAINING] Checking configs at " + now);

            List<RetrainingConfig> configs =
                    retrainingConfigRepository
                            .findByEnabledTrueAndRunningFalseAndNextRetrainingAtIsNotNullAndNextRetrainingAtLessThanEqual(now);

            System.out.println("[AUTO_RETRAINING] Found configs = " + configs.size());

            for (RetrainingConfig config : configs) {
                if (config == null || config.getId() == null) {
                    continue;
                }

                RetrainingConfig freshConfig = retrainingConfigRepository
                        .findById(config.getId())
                        .orElse(null);

                if (freshConfig == null) {
                    continue;
                }

                if (!freshConfig.isEnabled()) {
                    System.out.println("[AUTO_RETRAINING] Skipped disabled config. projectId="
                            + freshConfig.getProjectId());
                    continue;
                }

                if (freshConfig.isRunning()) {
                    System.out.println("[AUTO_RETRAINING] Skipped running config. projectId="
                            + freshConfig.getProjectId());
                    continue;
                }

                if (freshConfig.getNextRetrainingAt() == null) {
                    System.out.println("[AUTO_RETRAINING] Skipped config without nextRetrainingAt. projectId="
                            + freshConfig.getProjectId());
                    continue;
                }

                if (freshConfig.getNextRetrainingAt().isAfter(now)) {
                    System.out.println("[AUTO_RETRAINING] Skipped not due config. projectId="
                            + freshConfig.getProjectId()
                            + ", nextRetrainingAt="
                            + freshConfig.getNextRetrainingAt());
                    continue;
                }

                try {
                    System.out.println("[AUTO_RETRAINING] Launching retraining. projectId="
                            + freshConfig.getProjectId()
                            + ", sourceExperimentId="
                            + freshConfig.getSourceExperimentId()
                            + ", nextRetrainingAt="
                            + freshConfig.getNextRetrainingAt());

                    autoRetrainingService.launchScheduledRetraining(freshConfig);

                } catch (Exception e) {
                    System.err.println("[AUTO_RETRAINING] Failed to launch retraining for projectId="
                            + freshConfig.getProjectId()
                            + ", error="
                            + e.getMessage());
                }
            }

        } finally {
            schedulerRunning.set(false);
        }
    }
}