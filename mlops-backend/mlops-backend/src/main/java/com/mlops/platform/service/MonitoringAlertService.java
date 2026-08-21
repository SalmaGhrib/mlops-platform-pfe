package com.mlops.platform.service;

import com.mlops.platform.dto.MonitoringAlertResponse;
import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.MonitoringAlert;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MonitoringAlertRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

/**
 * Centralise la création et la lecture des alertes de monitoring applicatif.
 *
 * Chaque alerte est dédupliquée grâce à une clé d'idempotence unique
 * (alertType + projectId + registeredModelName + fenêtre temporelle).
 * Une alerte peut ensuite générer une notification utilisateur (propriétaire
 * du projet) et, pour les alertes critiques, une notification administrateur.
 */
@Service
public class MonitoringAlertService {

    private final MonitoringAlertRepository monitoringAlertRepository;
    private final NotificationService notificationService;

    public MonitoringAlertService(
            MonitoringAlertRepository monitoringAlertRepository,
            NotificationService notificationService
    ) {
        this.monitoringAlertRepository = monitoringAlertRepository;
        this.notificationService = notificationService;
    }

    // =========================================================
    // CRÉATION D'ALERTE (IDEMPOTENTE)
    // =========================================================

    /**
     * Crée une alerte si et seulement si la clé d'idempotence est nouvelle.
     *
     * @return l'alerte persistée, ou null si elle existait déjà.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public MonitoringAlert raiseAlert(
            AlertType alertType,
            AlertSeverity severity,
            Long projectId,
            String registeredModelName,
            String modelVersion,
            Double observedValue,
            Double thresholdValue,
            String cause,
            String action,
            String message,
            String idempotencyKey,
            User owner
    ) {
        if (alertType == null) {
            throw new IllegalArgumentException("alertType is required");
        }

        AlertSeverity resolvedSeverity = severity != null
                ? severity
                : AlertSeverity.INFO;

        String key = normalizeKey(idempotencyKey);
        if (key == null) {
            key = buildIdempotencyKey(
                    alertType,
                    projectId,
                    registeredModelName,
                    LocalDateTime.now(Clock.systemUTC())
            );
        }

        if (monitoringAlertRepository.existsByIdempotencyKey(key)) {
            System.out.println(
                    "[MONITORING_ALERT] Duplicate alert ignored. "
                            + "alertType=" + alertType
                            + ", key=" + key
            );
            return null;
        }

        MonitoringAlert alert = new MonitoringAlert();
        alert.setAlertType(alertType);
        alert.setSeverity(resolvedSeverity);
        alert.setProjectId(projectId);
        alert.setRegisteredModelName(registeredModelName);
        alert.setModelVersion(modelVersion);
        alert.setObservedValue(observedValue);
        alert.setThresholdValue(thresholdValue);
        alert.setCause(normalizeKey(cause));
        alert.setAction(action);
        alert.setMessage(message);
        alert.setIdempotencyKey(key);

        MonitoringAlert saved = monitoringAlertRepository.saveAndFlush(alert);

        notifyAlert(saved, owner);

        return saved;
    }

    /**
     * Génère une notification pour le propriétaire du projet, et pour les
     * administrateurs lorsque l'alerte est critique.
     */
    private void notifyAlert(MonitoringAlert alert, User owner) {
        if (alert == null) {
            return;
        }

        NotificationType notificationType = toNotificationType(alert.getAlertType());
        NotificationTargetType targetType = alert.getProjectId() != null
                ? NotificationTargetType.PROJECT
                : NotificationTargetType.MONITORING;
        Long targetId = alert.getProjectId() != null
                ? alert.getProjectId()
                : alert.getId();

        String title = buildTitle(alert);
        String message = alert.getMessage() != null
                ? alert.getMessage()
                : title;

        try {
            if (owner != null && owner.getId() != null) {
                notificationService.notifyUser(
                        owner,
                        title,
                        message,
                        notificationType,
                        targetType,
                        targetId
                );
            }

            if (alert.getSeverity() == AlertSeverity.CRITICAL) {
                notificationService.notifyAdmins(
                        title,
                        message,
                        notificationType,
                        targetType,
                        targetId
                );
            }
        } catch (Exception notificationError) {
            System.err.println(
                    "[MONITORING_ALERT] Notification delivery failed. alertId="
                            + alert.getId()
                            + ", reason="
                            + safeMessage(notificationError.getMessage())
            );
        }
    }

    // =========================================================
    // LECTURE
    // =========================================================

    @Transactional(readOnly = true, timeout = 10)
    public List<MonitoringAlertResponse> getRecentAlertsByProject(Long projectId) {
        if (projectId == null) {
            return List.of();
        }

        LocalDateTime cutoff = LocalDateTime.now(Clock.systemUTC()).minusDays(30);

        return monitoringAlertRepository
                .findByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        projectId,
                        cutoff
                )
                .stream()
                .limit(50)
                .map(MonitoringAlertResponse::new)
                .toList();
    }

    @Transactional(readOnly = true, timeout = 10)
    public List<MonitoringAlertResponse> getRecentAlerts(int limit) {
        LocalDateTime cutoff = LocalDateTime.now(Clock.systemUTC()).minusDays(7);

        return monitoringAlertRepository
                .findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(cutoff)
                .stream()
                .limit(Math.max(1, Math.min(limit, 100)))
                .map(MonitoringAlertResponse::new)
                .toList();
    }

    @Transactional(readOnly = true, timeout = 10)
    public long countAlertsBySeveritySince(AlertSeverity severity, LocalDateTime since) {
        if (severity == null || since == null) {
            return 0L;
        }

        return monitoringAlertRepository
                .countBySeverityAndCreatedAtGreaterThanEqual(severity, since);
    }

    @Transactional(readOnly = true, timeout = 10)
    public long countAlertsSince(LocalDateTime since) {
        if (since == null) {
            return 0L;
        }

        return monitoringAlertRepository
                .countByCreatedAtGreaterThanEqual(since);
    }

    // =========================================================
    // UTILITAIRES
    // =========================================================

    public static String buildIdempotencyKey(
            AlertType alertType,
            Long projectId,
            String registeredModelName,
            LocalDateTime window
    ) {
        String typePart = alertType != null ? alertType.name() : "ALERT";
        String projectPart = projectId != null ? String.valueOf(projectId) : "GLOBAL";
        String modelPart = registeredModelName != null && !registeredModelName.isBlank()
                ? registeredModelName.trim()
                : "-";

        String datePart = window != null
                ? window.toLocalDate().toString()
                : LocalDate.now().toString();

        return typePart
                + ":"
                + projectPart
                + ":"
                + modelPart
                + ":"
                + datePart;
    }

    public static String buildHourlyIdempotencyKey(
            AlertType alertType,
            Long projectId,
            String registeredModelName,
            LocalDateTime window
    ) {
        LocalDateTime resolved = window != null
                ? window
                : LocalDateTime.now(Clock.systemUTC());
        LocalDateTime hourWindow = resolved
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        return buildIdempotencyKey(alertType, projectId, registeredModelName, hourWindow);
    }

    private NotificationType toNotificationType(AlertType alertType) {
        if (alertType == null) {
            return NotificationType.MONITORING_ALERT;
        }

        return switch (alertType) {
            case DATA_DRIFT -> NotificationType.DATA_DRIFT;
            case PREDICTION_ERROR_RATE -> NotificationType.PREDICTION_ERROR_RATE;
            case PERFORMANCE_DROP -> NotificationType.PERFORMANCE_DROP;
            case GLOBAL_SCORE_TOO_LOW, MODEL_QUALITY_FAILED ->
                    NotificationType.MODEL_QUALITY_FAILED;
            case DEPLOYMENT_UNHEALTHY -> NotificationType.DEPLOYMENT_UNHEALTHY;
            case DEPLOYMENT_FAILED -> NotificationType.DEPLOYMENT_FAILED;
            case RETRAINING_TRIGGERED -> NotificationType.RETRAINING_TRIGGERED;
            case RETRAINING_FAILED -> NotificationType.RETRAINING_FAILED;
            case RETRAINING_COMPLETED -> NotificationType.RETRAINING_COMPLETED;
            case NEW_PRODUCTION_VERSION -> NotificationType.NEW_PRODUCTION_VERSION;
        };
    }

    private String buildTitle(MonitoringAlert alert) {
        if (alert == null || alert.getAlertType() == null) {
            return "Alerte monitoring";
        }

        return switch (alert.getAlertType()) {
            case DATA_DRIFT -> "Alerte data drift";
            case PREDICTION_ERROR_RATE -> "Alerte taux d'erreur de prédiction";
            case PERFORMANCE_DROP -> "Alerte baisse de performance";
            case GLOBAL_SCORE_TOO_LOW, MODEL_QUALITY_FAILED -> "Qualité modèle insuffisante";
            case DEPLOYMENT_UNHEALTHY -> "Déploiement dégradé";
            case DEPLOYMENT_FAILED -> "Déploiement échoué";
            case RETRAINING_TRIGGERED -> "Réentraînement déclenché";
            case RETRAINING_FAILED -> "Réentraînement échoué";
            case RETRAINING_COMPLETED -> "Réentraînement terminé";
            case NEW_PRODUCTION_VERSION -> "Nouvelle version en production";
        };
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }
}