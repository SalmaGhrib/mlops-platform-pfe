package com.mlops.platform.dto;

import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MonitoringAlert;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class MonitoringAlertResponse {

    private Long id;
    private Long projectId;
    private String registeredModelName;
    private String modelVersion;
    private AlertType alertType;
    private AlertSeverity severity;
    private Double observedValue;
    private Double thresholdValue;
    private String cause;
    private String action;
    private String message;
    private String createdAt;

    public MonitoringAlertResponse() {
    }

    public MonitoringAlertResponse(MonitoringAlert alert) {
        this.id = alert.getId();
        this.projectId = alert.getProjectId();
        this.registeredModelName = alert.getRegisteredModelName();
        this.modelVersion = alert.getModelVersion();
        this.alertType = alert.getAlertType();
        this.severity = alert.getSeverity();
        this.observedValue = alert.getObservedValue();
        this.thresholdValue = alert.getThresholdValue();
        this.cause = alert.getCause();
        this.action = alert.getAction();
        this.message = alert.getMessage();
        this.createdAt = toUtcIsoString(alert.getCreatedAt());
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

    public Long getProjectId() {
        return projectId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public AlertType getAlertType() {
        return alertType;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public String getCause() {
        return cause;
    }

    public String getAction() {
        return action;
    }

    public String getMessage() {
        return message;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}