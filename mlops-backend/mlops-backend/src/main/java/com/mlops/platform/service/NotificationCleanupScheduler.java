package com.mlops.platform.service;

import com.mlops.platform.repository.NotificationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class NotificationCleanupScheduler {

    private final NotificationRepository notificationRepository;

    public NotificationCleanupScheduler(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /*
     * Supprime automatiquement les notifications âgées de plus de 24h.
     * Exécution toutes les heures.
     */
    @Scheduled(
        fixedDelayString = "${app.notifications.cleanup.delay-ms:900000}",
        initialDelayString = "${app.notifications.cleanup.initial-delay-ms:15000}"
)
    @SchedulerLock(
        name = "notificationCleanupScheduler",
        lockAtMostFor = "10m",
        lockAtLeastFor = "1m"
)
    @Transactional
    public void deleteOldNotifications() {
        LocalDateTime limit = LocalDateTime.now(Clock.systemUTC()).minusHours(24);

        try {
            notificationRepository.deleteByCreatedAtBefore(limit);

            System.out.println("[NOTIFICATION_CLEANUP] Old notifications deleted before " + limit);
        } catch (Exception e) {
            System.out.println("[NOTIFICATION_CLEANUP] Failed to delete old notifications: " + e.getMessage());
        }
    }
}