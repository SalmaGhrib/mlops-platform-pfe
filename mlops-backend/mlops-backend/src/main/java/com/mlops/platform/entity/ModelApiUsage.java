package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/*
 * Trace d'utilisation d'une clé API SMART MODEL GATEWAY.
 *
 * Une ligne est écrite pour CHAQUE requête externe reçue,
 * qu'elle soit acceptée ou refusée (après authentification).
 *
 * Aucune donnée sensible du payload n'est stockée ici.
 */
@Entity
@Table(
        name = "model_api_usage",
        indexes = {
                @Index(name = "idx_model_api_usage_key_time", columnList = "api_key_id, requested_at"),
                @Index(name = "idx_model_api_usage_model_time", columnList = "registered_model_name, requested_at")
        }
)
public class ModelApiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_key_id", nullable = false)
    private Long apiKeyId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "registered_model_name", nullable = false)
    private String registeredModelName;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "status_code", nullable = false)
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "success", nullable = false)
    private Boolean success = true;

    @Column(name = "endpoint_type", length = 30)
    private String endpointType;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    public ModelApiUsage() {
    }

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (success == null) {
            success = true;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(Long apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = cleanText(registeredModelName);
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = cleanText(modelVersion);
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success != null ? success : true;
    }

    public String getEndpointType() {
        return endpointType;
    }

    public void setEndpointType(String endpointType) {
        this.endpointType = cleanText(endpointType);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = cleanText(errorCode);
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
