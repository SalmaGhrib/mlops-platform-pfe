package com.mlops.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "monitoring_alerts")
public class MonitoringAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "registered_model_name", length = 256)
    private String registeredModelName;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", length = 40, nullable = false)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20, nullable = false)
    private AlertSeverity severity = AlertSeverity.INFO;

    @Column(name = "observed_value")
    private Double observedValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    /**
     * Cause courte et stable de l'alerte
     * (ex. DATA_DRIFT, GLOBAL_SCORE_TOO_LOW, DEPLOYMENT_UNHEALTHY).
     */
    @Column(name = "cause", length = 120)
    private String cause;

    @Column(name = "action", columnDefinition = "TEXT")
    private String action;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /**
     * Clé d'idempotence unique (ex. type + projectId + fenêtre horaire).
     * Garantit qu'une même alerte n'est jamais créée deux fois.
     */
    @Column(name = "idempotency_key", length = 255, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        this.createdAt = now;

        if (this.severity == null) {
            this.severity = AlertSeverity.INFO;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = registeredModelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public AlertType getAlertType() {
        return alertType;
    }

    public void setAlertType(AlertType alertType) {
        this.alertType = alertType;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public void setObservedValue(Double observedValue) {
        this.observedValue = observedValue;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(Double thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public String getCause() {
        return cause;
    }

    public void setCause(String cause) {
        this.cause = cause;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}