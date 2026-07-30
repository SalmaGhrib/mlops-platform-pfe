package com.mlops.platform.service;

import com.mlops.platform.repository.NotificationRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supprime progressivement les anciennes notifications.
 *
 * Le bean n'existe que si :
 *
 * app.notifications.cleanup.enabled=true
 */
@Service
@ConditionalOnProperty(
        name = "app.notifications.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class NotificationCleanupScheduler {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ROWS_PER_CYCLE = 1000;

    private final NotificationRepository notificationRepository;

    private final AtomicBoolean cleanupRunning =
            new AtomicBoolean(false);

    public NotificationCleanupScheduler(
            NotificationRepository notificationRepository
    ) {
        this.notificationRepository = notificationRepository;
    }

    @Scheduled(
            initialDelayString =
                    "${app.notifications.cleanup.initial-delay-ms:60000}",
            fixedDelayString =
                    "${app.notifications.cleanup.delay-ms:900000}"
    )
    @SchedulerLock(
            name = "notificationCleanupScheduler",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT1S"
    )
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public void deleteOldNotifications() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            System.out.println(
                    "[NOTIFICATION_CLEANUP] "
                            + "Previous local cycle still running. Skipped."
            );
            return;
        }

        try {
            LocalDateTime limit =
                    LocalDateTime.now(Clock.systemUTC())
                            .minusHours(24);

            int totalDeleted = 0;
            int deletedRows;

            do {
                deletedRows =
                        notificationRepository
                                .deleteOldNotificationsBatch(
                                        limit,
                                        BATCH_SIZE
                                );

                totalDeleted += deletedRows;

            } while (
                    deletedRows == BATCH_SIZE
                            && totalDeleted < MAX_ROWS_PER_CYCLE
            );

            System.out.println(
                    "[NOTIFICATION_CLEANUP] Completed. deletedRows="
                            + totalDeleted
                            + ", before="
                            + limit
            );

        } catch (Exception error) {
            System.err.println(
                    "[NOTIFICATION_CLEANUP] Failed. type="
                            + error.getClass().getSimpleName()
                            + ", reason="
                            + safeMessage(error.getMessage())
            );

        } finally {
            cleanupRunning.set(false);
        }
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
}