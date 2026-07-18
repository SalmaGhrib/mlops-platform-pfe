package com.mlops.platform.scheduler;

import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.RetrainingConfigRepository;
import com.mlops.platform.service.AutoRetrainingService;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AutoRetrainingScheduler {

    private static final long STUCK_RUNNING_TIMEOUT_MINUTES = 30L;

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final AutoRetrainingService autoRetrainingService;
    private final PlatformTransactionManager transactionManager;

    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public AutoRetrainingScheduler(
            RetrainingConfigRepository retrainingConfigRepository,
            AutoRetrainingService autoRetrainingService,
            PlatformTransactionManager transactionManager
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.autoRetrainingService = autoRetrainingService;
        this.transactionManager = transactionManager;
    }

    @PostConstruct
    public void init() {
        System.out.println("[AUTO_RETRAINING] Scheduler loaded");
    }

    @Scheduled(fixedDelayString = "${app.retraining.scheduler.delay-ms:60000}")
    @SchedulerLock(
            name = "autoRetrainingScheduler",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT2S"
    )
    public void checkScheduledRetraining() {
        if (!schedulerRunning.compareAndSet(false, true)) {
            System.out.println(
                    "[AUTO_RETRAINING] Previous local scheduler cycle still running. Skipped."
            );
            return;
        }

        try {
            LocalDateTime now = utcNow();
            LocalDateTime stuckCutoff =
                    now.minusMinutes(STUCK_RUNNING_TIMEOUT_MINUTES);

            System.out.println(
                    "[AUTO_RETRAINING] Checking configs at " + now
            );

            int resetCount = executeInNewTransaction(() ->
                    retrainingConfigRepository.resetStuckRunningConfigs(
                            stuckCutoff,
                            now
                    )
            );

            if (resetCount > 0) {
                System.out.println(
                        "[AUTO_RETRAINING] Reset stuck running configs="
                                + resetCount
                );
            }

            List<RetrainingConfig> dueConfigs =
                    retrainingConfigRepository.findDueConfigs(now);

            System.out.println(
                    "[AUTO_RETRAINING] Found due configs="
                            + dueConfigs.size()
            );

            for (RetrainingConfig config : dueConfigs) {
                processDueConfig(config);
            }

        } catch (Exception schedulerError) {
            System.err.println(
                    "[AUTO_RETRAINING] Scheduler cycle failed: "
                            + schedulerError.getClass().getSimpleName()
                            + ": "
                            + safeMessage(schedulerError.getMessage())
            );
            schedulerError.printStackTrace();

        } finally {
            schedulerRunning.set(false);
        }
    }

    private void processDueConfig(RetrainingConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }

        Long configId = config.getId();
        Long projectId = config.getProjectId();
        LocalDateTime claimTime = utcNow();

        try {
            int claimed = executeInNewTransaction(() ->
                    retrainingConfigRepository.claimDueConfig(
                            configId,
                            claimTime
                    )
            );

            System.out.println(
                    "[AUTO_RETRAINING] Claim configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", result="
                            + claimed
            );

            if (claimed != 1) {
                return;
            }

            /*
             * IMPORTANT :
             * Le scheduler a déjà fait le claim atomique.
             * Le service appelé ici ne doit jamais refaire claimDueConfig().
             */
            autoRetrainingService.processClaimedConfig(configId);

        } catch (Exception processingError) {
            System.err.println(
                    "[AUTO_RETRAINING] Failed configId="
                            + configId
                            + ", projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(processingError.getMessage())
            );
            processingError.printStackTrace();

            safelyReleaseClaimAfterUnexpectedFailure(configId);
        }
    }

    private void safelyReleaseClaimAfterUnexpectedFailure(Long configId) {
        if (configId == null) {
            return;
        }

        try {
            LocalDateTime now = utcNow();
            LocalDateTime retryAt = now.plusMinutes(5);

            int released = executeInNewTransaction(() ->
                    retrainingConfigRepository.releaseAfterFailure(
                            configId,
                            retryAt,
                            now
                    )
            );

            System.out.println(
                    "[AUTO_RETRAINING] Claim released after unexpected failure. "
                            + "configId="
                            + configId
                            + ", result="
                            + released
                            + ", retryAt="
                            + retryAt
            );

        } catch (Exception releaseError) {
            System.err.println(
                    "[AUTO_RETRAINING] Could not release failed claim. "
                            + "configId="
                            + configId
                            + ", reason="
                            + safeMessage(releaseError.getMessage())
            );
        }
    }

    private int executeInNewTransaction(IntTransactionCallback callback) {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(10);

        Integer result = template.execute(status -> callback.execute());

        return result == null ? 0 : result;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(Clock.systemUTC());
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Erreur inconnue";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }

    @FunctionalInterface
    private interface IntTransactionCallback {
        int execute();
    }
}
