package com.mlops.platform.scheduler;

import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.RetrainingConfigRepository;
import com.mlops.platform.service.AutoRetrainingService;

import jakarta.annotation.PostConstruct;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler de réentraînement.
 *
 * Le bean n'est créé que si :
 *
 * app.retraining.scheduler.enabled=true
 */
@Component
@ConditionalOnProperty(
        name = "app.retraining.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AutoRetrainingScheduler {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final AutoRetrainingService autoRetrainingService;
    private final PlatformTransactionManager transactionManager;

    private final AtomicBoolean schedulerRunning =
            new AtomicBoolean(false);

    public AutoRetrainingScheduler(
            RetrainingConfigRepository retrainingConfigRepository,
            AutoRetrainingService autoRetrainingService,
            PlatformTransactionManager transactionManager
    ) {
        this.retrainingConfigRepository =
                retrainingConfigRepository;

        this.autoRetrainingService =
                autoRetrainingService;

        this.transactionManager =
                transactionManager;
    }

    @PostConstruct
    public void init() {
        System.out.println(
                "[AUTO_RETRAINING] Scheduler loaded."
        );
    }

    @Scheduled(
            initialDelayString =
                    "${app.retraining.scheduler.initial-delay-ms:30000}",
            fixedDelayString =
                    "${app.retraining.scheduler.delay-ms:30000}"
    )
    @SchedulerLock(
            name = "autoRetrainingScheduler",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT2S"
    )
    public void checkScheduledRetraining() {
        if (!schedulerRunning.compareAndSet(false, true)) {
            System.out.println(
                    "[AUTO_RETRAINING] "
                            + "Previous local cycle still running. Skipped."
            );
            return;
        }

        try {
            LocalDateTime now = utcNow();

            long stuckTimeoutMinutes =
                    resolveStuckTimeoutMinutes();

            LocalDateTime stuckCutoff =
                    now.minusMinutes(stuckTimeoutMinutes);

            System.out.println(
                    "[AUTO_RETRAINING] Checking configs at "
                            + now
            );

            int resetCount =
                    executeInNewTransaction(
                            () ->
                                    retrainingConfigRepository
                                            .resetStuckRunningConfigs(
                                                    stuckCutoff,
                                                    now
                                            )
                    );

            if (resetCount > 0) {
                System.out.println(
                        "[AUTO_RETRAINING] Reset stuck configs="
                                + resetCount
                );
            }

            int reconciledCount =
                    autoRetrainingService
                            .reconcileFinishedRunningConfigs();

            if (reconciledCount > 0) {
                System.out.println(
                        "[AUTO_RETRAINING] Reconciled finished configs="
                                + reconciledCount
                );
            }

            int k8sReconciled =
                    autoRetrainingService
                            .reconcileKubernetesJobStatuses(null);

            if (k8sReconciled > 0) {
                System.out.println(
                        "[AUTO_RETRAINING] Reconciled K8s job statuses="
                                + k8sReconciled
                );
            }

        } catch (Exception schedulerError) {
            System.err.println(
                    "[AUTO_RETRAINING] Scheduler cycle failed. type="
                            + schedulerError
                            .getClass()
                            .getSimpleName()
                            + ", reason="
                            + safeMessage(
                                    schedulerError.getMessage()
                            )
            );

        } finally {
            schedulerRunning.set(false);
        }
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

        return result == null ? 0 : result;
    }

    private long resolveStuckTimeoutMinutes() {
        return 30L;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(
                Clock.systemUTC()
        );
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
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