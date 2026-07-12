package com.mlops.platform.service;

import com.mlops.platform.dto.NotificationResponse;
import com.mlops.platform.entity.*;
import com.mlops.platform.repository.NotificationRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

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

    boolean alreadyExists = notificationRepository.existsByUserAndTypeAndTargetTypeAndTargetId(
            user,
            type,
            targetType,
            targetId
    );

    if (alreadyExists) {
        return null;
    }

    Notification notification = new Notification();
    notification.setUser(user);
    notification.setTitle(title);
    notification.setMessage(message);
    notification.setType(type);
    notification.setTargetType(targetType);
    notification.setTargetId(targetId);
    notification.setRead(false);
    notification.setAdminNotification(false);

    Notification saved = notificationRepository.save(notification);
    NotificationResponse response = new NotificationResponse(saved);

    messagingTemplate.convertAndSend(
            "/topic/users/" + user.getId() + "/notifications",
            response
    );

    return response;
}
    public void notifyAdmins(
        String title,
        String message,
        NotificationType type,
        NotificationTargetType targetType,
        Long targetId
) {
    List<User> users = userRepository.findAll();
    System.out.println("[NOTIFICATION] notifyAdmins called. usersCount=" + users.size());

    for (User user : users) {
        if (!isAdminUser(user)) {
            continue;
        }

        boolean alreadyExists = notificationRepository.existsByUserAndTypeAndTargetTypeAndTargetId(
                user,
                type,
                targetType,
                targetId
        );

        if (alreadyExists) {
            continue;
        }

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(type);
        notification.setTargetType(targetType);
        notification.setTargetId(targetId);
        notification.setRead(false);
        notification.setAdminNotification(true);

        Notification saved = notificationRepository.save(notification);
        NotificationResponse response = new NotificationResponse(saved);

        messagingTemplate.convertAndSend(
                "/topic/users/" + user.getId() + "/notifications",
                response
        );

        messagingTemplate.convertAndSend(
                "/topic/admin/notifications",
                response
        );
    }
}

public void notifyDeploymentOutOfSync(Long deploymentId, User owner, String deploymentName) {
    String title = "Déploiement désynchronisé";
    String message = "Le déploiement \"" + safeValue(deploymentName, "model deployment")
            + "\" est désynchronisé avec l’état réel de Kubernetes.";

    notifyUser(
            owner,
            title,
            message,
            NotificationType.DEPLOYMENT_OUT_OF_SYNC,
            NotificationTargetType.DEPLOYMENT,
            deploymentId
    );

    notifyAdmins(
            "Déploiement désynchronisé",
            message,
            NotificationType.DEPLOYMENT_OUT_OF_SYNC,
            NotificationTargetType.DEPLOYMENT,
            deploymentId
    );
}

public void notifyFailedRunsAlert(Long alertTargetId, double failedRuns) {
    String title = "Alerte monitoring : runs échoués";
    String message = "Une alerte monitoring a été détectée : "
            + (int) failedRuns
            + " run(s) d’entraînement sont en échec.";

    notifyAdmins(
            title,
            message,
            NotificationType.MONITORING_ALERT,
            NotificationTargetType.MONITORING,
            alertTargetId
    );
}

public void notifyFailedDeploymentsAlert(Long alertTargetId, double failedDeployments) {
    String title = "Alerte monitoring : déploiements échoués";
    String message = "Une alerte monitoring a été détectée : "
            + (int) failedDeployments
            + " déploiement(s) de modèle sont en échec.";

    notifyAdmins(
            title,
            message,
            NotificationType.MONITORING_ALERT,
            NotificationTargetType.MONITORING,
            alertTargetId
    );
}

public void notifyUnavailableModelsAlert(Long alertTargetId, double unavailableModels) {
    String title = "Alerte monitoring : modèles indisponibles";
    String message = "Une alerte monitoring a été détectée : "
            + (int) unavailableModels
            + " modèle(s) déployés ont 0 replica disponible dans Kubernetes.";

    notifyAdmins(
            title,
            message,
            NotificationType.MONITORING_ALERT,
            NotificationTargetType.MONITORING,
            alertTargetId
    );
}

public void notifyBackendLatencyAlert(Long alertTargetId, double latencyMs) {
    String title = "Alerte monitoring : latence backend élevée";
    String message = "La latence moyenne du backend est élevée : "
            + Math.round(latencyMs)
            + " ms.";

    notifyAdmins(
            title,
            message,
            NotificationType.MONITORING_ALERT,
            NotificationTargetType.MONITORING,
            alertTargetId
    );
}

    public void notifyRunCompleted(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        User owner = getExperimentOwner(experiment);

        String title = "Run terminé avec succès";
        String message = "Le run \"" + safeExperimentName(experiment) + "\" est terminé avec succès.";

        notifyUser(
                owner,
                title,
                message,
                NotificationType.RUN_COMPLETED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );

        notifyAdmins(
                "Run utilisateur terminé",
                "Le run \"" + safeExperimentName(experiment) + "\" est terminé avec succès.",
                NotificationType.RUN_COMPLETED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );
    }

    public void notifyRunFailed(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        User owner = getExperimentOwner(experiment);

        String title = "Run échoué";
        String message = "Le run \"" + safeExperimentName(experiment) + "\" a échoué.";

        if (experiment.getErrorMessage() != null && !experiment.getErrorMessage().isBlank()) {
            message += " Erreur : " + experiment.getErrorMessage();
        }

        notifyUser(
                owner,
                title,
                message,
                NotificationType.RUN_FAILED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );

        notifyAdmins(
                "Run utilisateur échoué",
                "Le run \"" + safeExperimentName(experiment) + "\" a échoué.",
                NotificationType.RUN_FAILED,
                NotificationTargetType.EXPERIMENT,
                experiment.getId()
        );
    }

    public void notifyDeploymentDeployed(Long deploymentId, User owner, String deploymentName) {
        String title = "Déploiement terminé";
        String message = "Le déploiement \"" + safeValue(deploymentName, "model deployment") + "\" est terminé avec succès.";

        notifyUser(
                owner,
                title,
                message,
                NotificationType.DEPLOYMENT_DEPLOYED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );

        notifyAdmins(
                "Déploiement réussi",
                message,
                NotificationType.DEPLOYMENT_DEPLOYED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );
    }

    public void notifyDeploymentFailed(Long deploymentId, User owner, String deploymentName, String errorMessage) {
        String title = "Déploiement échoué";
        String message = "Le déploiement \"" + safeValue(deploymentName, "model deployment") + "\" a échoué.";

        if (errorMessage != null && !errorMessage.isBlank()) {
            message += " Erreur : " + errorMessage;
        }

        notifyUser(
                owner,
                title,
                message,
                NotificationType.DEPLOYMENT_FAILED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );

        notifyAdmins(
                "Déploiement échoué",
                message,
                NotificationType.DEPLOYMENT_FAILED,
                NotificationTargetType.DEPLOYMENT,
                deploymentId
        );
    }

    public List<NotificationResponse> getMyNotifications(User user) {
        LocalDateTime cutoff = LocalDateTime.now(Clock.systemUTC()).minusHours(24);
        return notificationRepository
                .findTop20ByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(user, cutoff)
                .stream()
                .map(NotificationResponse::new)
                .toList();
    }

    public long countUnread(User user) {
        LocalDateTime cutoff = LocalDateTime.now(Clock.systemUTC()).minusHours(24);
        return notificationRepository
                .countByUserAndReadFalseAndCreatedAtGreaterThanEqual(user, cutoff);
    }

    public void markAsRead(User user, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new RuntimeException("Notification not found"));

        if (notification.getUser() == null
                || user == null
                || notification.getUser().getId() == null
                || !notification.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized notification access");
        }

        notification.setRead(true);
        notificationRepository.save(notification);
    }

    public void markAllAsRead(User user) {
        List<Notification> unreadNotifications =
                notificationRepository.findByUserAndReadFalseOrderByCreatedAtDesc(user);

        for (Notification notification : unreadNotifications) {
            notification.setRead(true);
        }

        notificationRepository.saveAll(unreadNotifications);
    }

    private User getExperimentOwner(MlExperiment experiment) {
        if (experiment == null || experiment.getProject() == null) {
            return null;
        }

        return experiment.getProject().getOwner();
    }

    private String safeExperimentName(MlExperiment experiment) {
        if (experiment == null || experiment.getExperimentName() == null || experiment.getExperimentName().isBlank()) {
            return "training run";
        }

        return experiment.getExperimentName();
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value;
    }

    /*
     * Cette méthode évite de casser ton code si ton entité User stocke le rôle
     * sous forme String, Enum ou via une méthode getRole().
     */
    private boolean isAdminUser(User user) {
    if (user == null) {
        return false;
    }

    try {
        Method getRoleMethod = user.getClass().getMethod("getRole");
        Object roleValue = getRoleMethod.invoke(user);

        if (roleValue == null) {
            return false;
        }

        String roleName = extractRoleName(roleValue);

        return roleName.equals("ADMIN")
                || roleName.equals("ROLE_ADMIN")
                || roleName.equals("SUPER_ADMIN");
    } catch (Exception e) {
        System.err.println("[NOTIFICATION] Unable to resolve user role for admin notification: "
                + e.getMessage());
        return false;
    }
}

private String extractRoleName(Object roleValue) {
    if (roleValue == null) {
        return "";
    }

    /*
     * Cas 1 : role stocké directement comme String ou Enum.
     */
    if (roleValue instanceof String || roleValue.getClass().isEnum()) {
        return normalizeRole(roleValue.toString());
    }

    /*
     * Cas 2 : role est une entité Role avec getName().
     */
    String[] possibleMethods = {
            "getName",
            "getRoleName",
            "getAuthority",
            "getCode",
            "name"
    };

    for (String methodName : possibleMethods) {
        try {
            Method method = roleValue.getClass().getMethod(methodName);
            Object value = method.invoke(roleValue);

            if (value != null) {
                String normalized = normalizeRole(value.toString());

                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        } catch (Exception ignored) {
            /*
             * On essaye la méthode suivante.
             */
        }
    }

    /*
     * Dernier fallback.
     */
    return normalizeRole(roleValue.toString());
}

private String normalizeRole(String role) {
    if (role == null) {
        return "";
    }

    return role
            .trim()
            .replace("ROLE_", "")
            .replace("-", "_")
            .toUpperCase();
}
}