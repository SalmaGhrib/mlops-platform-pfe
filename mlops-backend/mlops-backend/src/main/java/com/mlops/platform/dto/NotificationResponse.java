package com.mlops.platform.dto;

import com.mlops.platform.entity.Notification;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class NotificationResponse {

    private Long id;
    private String title;
    private String message;
    private NotificationType type;
    private NotificationTargetType targetType;
    private Long targetId;
    private Boolean read;
    private Boolean adminNotification;

    /*
     * Important :
     * Le backend stocke les dates en UTC.
     * On renvoie une String ISO avec timezone explicite pour éviter qu'Angular
     * interprète la date comme une heure locale et affiche toujours +2 h.
     * Exemple retourné : 2026-07-06T13:15:45Z
     */
    private String createdAt;

    public NotificationResponse() {
    }

    public NotificationResponse(Notification notification) {
        this.id = notification.getId();
        this.title = notification.getTitle();
        this.message = notification.getMessage();
        this.type = notification.getType();
        this.targetType = notification.getTargetType();
        this.targetId = notification.getTargetId();
        this.read = notification.getRead();
        this.adminNotification = notification.getAdminNotification();
        this.createdAt = toUtcIsoString(notification.getCreatedAt());
    }

    private String toUtcIsoString(LocalDateTime value) {
        if (value == null) {
            return null;
        }

        return value
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public Boolean getRead() {
        return read;
    }

    public Boolean getAdminNotification() {
        return adminNotification;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
