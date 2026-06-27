package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "model_deployments")
public class ModelDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long experimentId;

    @Column(nullable = false)
    private String registeredModelName;

    @Column(nullable = false)
    private String modelVersion;

    @Column(length = 100)
    private String mlflowRunId;

    @Column(length = 150)
    private String kubeflowRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentStatus deploymentStatus = DeploymentStatus.PENDING;

    @Column(length = 500)
    private String predictionEndpoint;

    @Column(length = 200)
    private String deploymentName;

    @Column(length = 200)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 50)
    private DeploymentVisibility visibility = DeploymentVisibility.PRIVATE;

    @Column(name = "api_enabled", nullable = false)
    private Boolean apiEnabled = false;

    @Column(name = "api_key", length = 255, unique = true)
    private String apiKey;

    @Column(name = "api_key_created_at")
    private LocalDateTime apiKeyCreatedAt;

    @Column(name = "api_key_last_used_at")
    private LocalDateTime apiKeyLastUsedAt;

    @Column(length = 100)
    private String namespaceName;

    @Column(length = 500)
    private String dockerImage;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private Boolean active = false;

    private LocalDateTime deployedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public ModelDeployment() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

        if (this.deploymentStatus == null) {
            this.deploymentStatus = DeploymentStatus.PENDING;
        }

        if (this.active == null) {
            this.active = false;
        }

        if (this.visibility == null) {
            this.visibility = DeploymentVisibility.PRIVATE;
        }

        if (this.apiEnabled == null) {
            this.apiEnabled = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();

        if (this.visibility == null) {
            this.visibility = DeploymentVisibility.PRIVATE;
        }

        if (this.apiEnabled == null) {
            this.apiEnabled = false;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
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

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public void setMlflowRunId(String mlflowRunId) {
        this.mlflowRunId = cleanText(mlflowRunId);
    }

    public String getKubeflowRunId() {
        return kubeflowRunId;
    }

    public void setKubeflowRunId(String kubeflowRunId) {
        this.kubeflowRunId = cleanText(kubeflowRunId);
    }

    public DeploymentStatus getDeploymentStatus() {
        return deploymentStatus;
    }

    public void setDeploymentStatus(DeploymentStatus deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    public String getPredictionEndpoint() {
        return predictionEndpoint;
    }

    public void setPredictionEndpoint(String predictionEndpoint) {
        this.predictionEndpoint = cleanText(predictionEndpoint);
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = cleanText(deploymentName);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = cleanText(serviceName);
    }

    public DeploymentVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(DeploymentVisibility visibility) {
        this.visibility = visibility != null ? visibility : DeploymentVisibility.PRIVATE;
    }

    public void setVisibility(String visibility) {
        this.visibility = normalizeVisibility(visibility);
    }

    public Boolean getApiEnabled() {
        return apiEnabled;
    }

    public void setApiEnabled(Boolean apiEnabled) {
        this.apiEnabled = apiEnabled != null ? apiEnabled : false;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = cleanText(apiKey);
    }

    public LocalDateTime getApiKeyCreatedAt() {
        return apiKeyCreatedAt;
    }

    public void setApiKeyCreatedAt(LocalDateTime apiKeyCreatedAt) {
        this.apiKeyCreatedAt = apiKeyCreatedAt;
    }

    public LocalDateTime getApiKeyLastUsedAt() {
        return apiKeyLastUsedAt;
    }

    public void setApiKeyLastUsedAt(LocalDateTime apiKeyLastUsedAt) {
        this.apiKeyLastUsedAt = apiKeyLastUsedAt;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = cleanText(namespaceName);
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public void setDockerImage(String dockerImage) {
        this.dockerImage = cleanText(dockerImage);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = cleanText(errorMessage);
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active != null ? active : false;
    }

    public LocalDateTime getDeployedAt() {
        return deployedAt;
    }

    public void setDeployedAt(LocalDateTime deployedAt) {
        this.deployedAt = deployedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isApiAccessEnabled() {
        return Boolean.TRUE.equals(this.apiEnabled)
                && this.visibility == DeploymentVisibility.PUBLIC_API
                && this.apiKey != null
                && !this.apiKey.isBlank();
    }

    public void enableApiAccess(String generatedApiKey) {
        this.visibility = DeploymentVisibility.PUBLIC_API;
        this.apiEnabled = true;
        this.apiKey = cleanText(generatedApiKey);
        this.apiKeyCreatedAt = LocalDateTime.now();
    }

    public void disableApiAccess() {
        this.visibility = DeploymentVisibility.PRIVATE;
        this.apiEnabled = false;
        this.apiKey = null;
        this.apiKeyCreatedAt = null;
        this.apiKeyLastUsedAt = null;
    }

    public void markApiKeyUsedNow() {
        this.apiKeyLastUsedAt = LocalDateTime.now();
    }

    private DeploymentVisibility normalizeVisibility(String value) {
        if (value == null || value.isBlank()) {
            return DeploymentVisibility.PRIVATE;
        }

        String clean = value.trim().toUpperCase();

        return switch (clean) {
            case "PUBLIC", "PUBLIC_API", "API", "EXTERNAL" -> DeploymentVisibility.PUBLIC_API;
            case "SHARED", "TEAM" -> DeploymentVisibility.SHARED;
            default -> DeploymentVisibility.PRIVATE;
        };
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}