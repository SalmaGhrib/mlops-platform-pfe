package com.mlops.platform.dto;

import com.mlops.platform.entity.Notification;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;

import java.time.LocalDateTime;

public class NotificationResponse {

    private Long id;
    private String title;
    private String message;
    private NotificationType type;
    private NotificationTargetType targetType;
    private Long targetId;
    private Boolean read;
    private Boolean adminNotification;
    private LocalDateTime createdAt;

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
        this.createdAt = notification.getCreatedAt();
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}