package com.mlops.platform.service;

import com.mlops.platform.dto.NotificationResponse;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.Notification;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.NotificationRepository;
import com.mlops.platform.repository.UserRepository;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    // =========================================================
    // CRÉATION DES NOTIFICATIONS
    // =========================================================

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public NotificationResponse notifyUser(
            User user,
            String title,
            String message,
            NotificationType type,
            NotificationTargetType targetType,
            Long targetId
    ) {
        if (user == null || user.getId() == null) {
            return null;
        }

        User recipient = userRepository
                .findById(user.getId())
                .orElse(null);

        if (recipient == null) {
            return null;
        }

        boolean alreadyExists =
                notificationRepository
                        .existsByUserAndTypeAndTargetTypeAndTargetIdAndTitle(
                                recipient,
                                type,
                                targetType,
                                targetId,
                                title
                        );

        if (alreadyExists) {
            return null;
        }

        Notification notification = new Notification();
        notification.setUser(recipient);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(type);
        notification.setTargetType(targetType);
        notification.setTargetId(targetId);
        notification.setRead(false);
        notification.setAdminNotification(false);

        Notification saved =
                notificationRepository.save(notification);

        NotificationResponse response =
                new NotificationResponse(saved);

        sendUserNotification(
                recipient.getId(),
                response
        );

        return response;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 15
    )
    public void notifyAdmins(
            String title,
            String message,
            NotificationType type,
            NotificationTargetType targetType,
            Long targetId
    ) {
        List<User> users =
                userRepository.findAll();

        System.out.println(
                "[NOTIFICATION] notifyAdmins called. usersCount="
                        + users.size()
        );

        for (User user : users) {
            if (!isAdminUser(user)) {
                continue;
            }

            boolean alreadyExists =
                    notificationRepository
                            .existsByUserAndTypeAndTargetTypeAndTargetIdAndTitle(
                                    user,
                                    type,
                                    targetType,
                                    targetId,
                                    title
                            );

            if (alreadyExists) {
                continue;
            }

            Notification notification =
                    new Notification();

            notification.setUser(user);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setType(type);
            notification.setTargetType(targetType);
            notification.setTargetId(targetId);
            notification.setRead(false);
            notification.setAdminNotification(true);

            Notification saved =
                    notificationRepository.save(notification);

            NotificationResponse response =
                    new NotificationResponse(saved);

            sendUserNotification(
                    user.getId(),
                    response
            );

            try {
                messagingTemplate.convertAndSend(
                        "/topic/admin/notifications",
                        response
                );
            } catch (Exception websocketError) {
                System.err.println(
                        "[NOTIFICATION] Admin WebSocket delivery failed. reason="
                                + safeValue(
                                        websocketError.getMessage(),
                                        "Unknown WebSocket error"
                                )
                );
            }
        }
    }

    private void sendUserNotification(
            Long userId,
            NotificationResponse response
    ) {
        if (userId == null || response == null) {
            return;
        }

        try {
            messagingTemplate.convertAndSend(
                    "/topic/users/"
                            + userId
                            + "/notifications",
                    response
            );
        } catch (Exception websocketError) {
            System.err.println(
                    "[NOTIFICATION] WebSocket delivery failed. userId="
                            + userId
                            + ", reason="
                            + safeValue(
                                    websocketError.getMessage(),
                                    "Unknown WebSocket error"
                            )
            );
        }
    }

    // =========================================================
    // NOTIFICATIONS MÉTIER
    // =========================================================

    public void notifyDeploymentOutOfSync(
            Long deploymentId,
            User owner,
            String deploymentName
    ) {
        String title =
                "Déploiement désynchronisé";

        String message =
                "Le déploiement \""
                        + safeValue(
                                deploymentName,
                                "model deployment"
                        )
                        + "\" est désynchronisé avec l’état réel "
                        + "de Kubernetes.";

        notifyUser(
                owner,
                title,
                message,
                NotificationType.DEPLOYMENT_OUT_OF_SYNC,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );
    }

    public void notifyFailedRunsAlert(
            Long alertTargetId,
            double failedRuns
    ) {
        notifyAdmins(
                "Alerte monitoring : runs échoués",
                "Une alerte monitoring a été détectée : "
                        + (int) failedRuns
                        + " run(s) d’entraînement sont en échec.",
                NotificationType.MONITORING_ALERT,
                NotificationTargetType.MONITORING,
                alertTargetId
        );
    }

    public void notifyFailedDeploymentsAlert(
            Long alertTargetId,
            double failedDeployments
    ) {
        notifyAdmins(
                "Alerte monitoring : déploiements échoués",
                "Une alerte monitoring a été détectée : "
                        + (int) failedDeployments
                        + " déploiement(s) de modèle sont en échec.",
                NotificationType.MONITORING_ALERT,
                NotificationTargetType.MONITORING,
                alertTargetId
        );
    }

    public void notifyUnavailableModelsAlert(
            Long alertTargetId,
            double unavailableModels
    ) {
        notifyAdmins(
                "Alerte monitoring : modèles indisponibles",
                "Une alerte monitoring a été détectée : "
                        + (int) unavailableModels
                        + " modèle(s) déployé(s) ont 0 replica "
                        + "disponible dans Kubernetes.",
                NotificationType.MONITORING_ALERT,
                NotificationTargetType.MONITORING,
                alertTargetId
        );
    }

    public void notifyBackendLatencyAlert(
            Long alertTargetId,
            double latencyMs
    ) {
        notifyAdmins(
                "Alerte monitoring : latence backend élevée",
                "La latence moyenne du backend est élevée : "
                        + Math.round(latencyMs)
                        + " ms.",
                NotificationType.MONITORING_ALERT,
                NotificationTargetType.MONITORING,
                alertTargetId
        );
    }

    public void notifyRunCompleted(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return;
        }

        notifyUser(
                getExperimentOwner(experiment),
                "Run terminé avec succès",
                "Le run \""
                        + safeExperimentName(experiment)
                        + "\" est terminé avec succès.",
                NotificationType.RUN_COMPLETED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );
    }

    public void notifyRunFailed(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return;
        }

        String message =
                "Le run \""
                        + safeExperimentName(experiment)
                        + "\" a échoué.";

        if (experiment.getErrorMessage() != null
                && !experiment.getErrorMessage().isBlank()) {

            message +=
                    " Erreur : "
                            + experiment.getErrorMessage();
        }

        notifyUser(
                getExperimentOwner(experiment),
                "Run échoué",
                message,
                NotificationType.RUN_FAILED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );
    }

    public void notifyDeploymentDeployed(
            Long deploymentId,
            User owner,
            String deploymentName
    ) {
        notifyUser(
                owner,
                "Déploiement terminé",
                "Le déploiement \""
                        + safeValue(
                                deploymentName,
                                "model deployment"
                        )
                        + "\" est terminé avec succès.",
                NotificationType.DEPLOYMENT_DEPLOYED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );
    }

    public void notifyDeploymentFailed(
            Long deploymentId,
            User owner,
            String deploymentName,
            String errorMessage
    ) {
        String message =
                "Le déploiement \""
                        + safeValue(
                                deploymentName,
                                "model deployment"
                        )
                        + "\" a échoué.";

        if (errorMessage != null
                && !errorMessage.isBlank()) {

            message +=
                    " Erreur : "
                            + errorMessage;
        }

        notifyUser(
                owner,
                "Déploiement échoué",
                message,
                NotificationType.DEPLOYMENT_FAILED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );
    }

    // =========================================================
    // LECTURE
    // =========================================================

    @Transactional(
            readOnly = true,
            timeout = 10
    )
    public List<NotificationResponse> getMyNotifications(
            User user
    ) {
        if (user == null || user.getId() == null) {
            return List.of();
        }

        LocalDateTime cutoff =
                LocalDateTime
                        .now(Clock.systemUTC())
                        .minusHours(24);

        return notificationRepository
                .findTop20ByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        user,
                        cutoff
                )
                .stream()
                .map(NotificationResponse::new)
                .toList();
    }

    @Transactional(
            readOnly = true,
            timeout = 10
    )
    public long countUnread(
            User user
    ) {
        if (user == null || user.getId() == null) {
            return 0L;
        }

        LocalDateTime cutoff =
                LocalDateTime
                        .now(Clock.systemUTC())
                        .minusHours(24);

        return notificationRepository
                .countByUserAndReadFalseAndCreatedAtGreaterThanEqual(
                        user,
                        cutoff
                );
    }

    // =========================================================
    // MISE À JOUR
    // =========================================================

    @Transactional(timeout = 10)
    public void markAsRead(
            User user,
            Long notificationId
    ) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException(
                    "Authenticated user is required"
            );
        }

        if (notificationId == null) {
            throw new IllegalArgumentException(
                    "Notification ID is required"
            );
        }

        int updatedRows =
                notificationRepository
                        .markAsReadByNotificationIdAndUserId(
                                notificationId,
                                user.getId()
                        );

        if (updatedRows > 0) {
            return;
        }

        Notification notification =
                notificationRepository
                        .findById(notificationId)
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Notification not found"
                                )
                        );

        if (notification.getUser() == null
                || notification.getUser().getId() == null
                || !notification.getUser()
                        .getId()
                        .equals(user.getId())) {

            throw new SecurityException(
                    "Unauthorized notification access"
            );
        }

        /*
         * Si la notification existe, appartient au bon utilisateur
         * et updatedRows vaut zéro, elle était déjà lue.
         */
    }

    @Transactional(timeout = 10)
    public int markAllAsRead(
            User user
    ) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException(
                    "Authenticated user is required"
            );
        }

        int updatedRows =
                notificationRepository
                        .markAllAsReadByUserId(
                                user.getId()
                        );

        System.out.println(
                "[NOTIFICATION] All notifications marked as read. "
                        + "userId="
                        + user.getId()
                        + ", updatedRows="
                        + updatedRows
        );

        return updatedRows;
    }

    // =========================================================
    // UTILITAIRES
    // =========================================================

    private User getExperimentOwner(
            MlExperiment experiment
    ) {
        if (experiment == null
                || experiment.getProject() == null) {

            return null;
        }

        return experiment
                .getProject()
                .getOwner();
    }

    private String safeExperimentName(
            MlExperiment experiment
    ) {
        if (experiment == null
                || experiment.getExperimentName() == null
                || experiment.getExperimentName().isBlank()) {

            return "training run";
        }

        return experiment.getExperimentName();
    }

    private String safeValue(
            String value,
            String fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value;
    }

    private boolean isAdminUser(
            User user
    ) {
        if (user == null) {
            return false;
        }

        try {
            Method getRoleMethod =
                    user.getClass().getMethod("getRole");

            Object roleValue =
                    getRoleMethod.invoke(user);

            if (roleValue == null) {
                return false;
            }

            String roleName =
                    extractRoleName(roleValue);

            return roleName.equals("ADMIN")
                    || roleName.equals("SUPER_ADMIN");

        } catch (Exception roleError) {
            System.err.println(
                    "[NOTIFICATION] Unable to resolve user role. "
                            + "userId="
                            + user.getId()
                            + ", reason="
                            + roleError.getMessage()
            );

            return false;
        }
    }

    private String extractRoleName(
            Object roleValue
    ) {
        if (roleValue == null) {
            return "";
        }

        if (roleValue instanceof String
                || roleValue.getClass().isEnum()) {

            return normalizeRole(
                    roleValue.toString()
            );
        }

        String[] possibleMethods = {
                "getName",
                "getRoleName",
                "getAuthority",
                "getCode",
                "name"
        };

        for (String methodName : possibleMethods) {
            try {
                Method method =
                        roleValue
                                .getClass()
                                .getMethod(methodName);

                Object value =
                        method.invoke(roleValue);

                if (value == null) {
                    continue;
                }

                String normalized =
                        normalizeRole(value.toString());

                if (!normalized.isBlank()) {
                    return normalized;
                }

            } catch (Exception ignored) {
                // Essai avec la méthode suivante.
            }
        }

        return normalizeRole(
                roleValue.toString()
        );
    }

    private String normalizeRole(
            String role
    ) {
        if (role == null) {
            return "";
        }

        return role
                .trim()
                .toUpperCase()
                .replace("-", "_")
                .replace("ROLE_", "");
    }
}