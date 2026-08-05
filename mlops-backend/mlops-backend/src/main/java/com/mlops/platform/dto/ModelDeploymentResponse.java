package com.mlops.platform.dto;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ModelDeployment;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class ModelDeploymentResponse {

    private Long id;
    private Long projectId;
    private Long experimentId;

    private String registeredModelName;
    private String modelVersion;

    private String mlflowRunId;
    private String kubeflowRunId;

    private DeploymentStatus deploymentStatus;
    private String predictionEndpoint;

    private String deploymentName;
    private String serviceName;
    private String namespaceName;

    private String dockerImage;
    private String errorMessage;

    private Boolean active;

    /*
     * Dates envoyées au frontend en ISO UTC avec suffixe Z.
     * Cela évite le bug Angular "2 h" causé par les LocalDateTime sans timezone.
     */
    private String deployedAt;
    private String createdAt;
    private String updatedAt;

    /*
     * API access / visibility
     */
    private Boolean apiEnabled;
    private String visibility;
    private String apiKeyCreatedAt;
    private String apiKeyLastUsedAt;

    private String mlflowProductionVersion;
    private String kubernetesActiveVersion;

    public ModelDeploymentResponse() {
    }

    public static ModelDeploymentResponse fromEntity(ModelDeployment deployment) {
        ModelDeploymentResponse response = new ModelDeploymentResponse();

        if (deployment == null) {
            return response;
        }

        response.setId(deployment.getId());
        response.setProjectId(deployment.getProjectId());
        response.setExperimentId(deployment.getExperimentId());

        response.setRegisteredModelName(deployment.getRegisteredModelName());
        response.setModelVersion(deployment.getModelVersion());

        response.setMlflowRunId(deployment.getMlflowRunId());
        response.setKubeflowRunId(deployment.getKubeflowRunId());

        response.setDeploymentStatus(deployment.getDeploymentStatus());
        response.setPredictionEndpoint(deployment.getPredictionEndpoint());

        response.setDeploymentName(deployment.getDeploymentName());
        response.setServiceName(deployment.getServiceName());
        response.setNamespaceName(deployment.getNamespaceName());

        response.setDockerImage(deployment.getDockerImage());
        response.setErrorMessage(deployment.getErrorMessage());

        response.setActive(Boolean.TRUE.equals(deployment.getActive()));

        response.setDeployedAt(toUtcIsoString(deployment.getDeployedAt()));
        response.setCreatedAt(toUtcIsoString(deployment.getCreatedAt()));
        response.setUpdatedAt(toUtcIsoString(deployment.getUpdatedAt()));

        /*
         * Important pour Angular :
         * Ces champs permettent d'afficher correctement PRIVATE / PUBLIC_API / SHARED,
         * la clé API et l'état de l'accès externe.
         */
        response.setApiEnabled(Boolean.TRUE.equals(deployment.getApiEnabled()));

        if (deployment.getVisibility() != null) {
            response.setVisibility(deployment.getVisibility().name());
        } else {
            response.setVisibility("PRIVATE");
        }

        response.setApiKeyCreatedAt(toUtcIsoString(deployment.getApiKeyCreatedAt()));
        response.setApiKeyLastUsedAt(toUtcIsoString(deployment.getApiKeyLastUsedAt()));

        return response;
    }

    private static String toUtcIsoString(LocalDateTime value) {
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

    public Long getExperimentId() {
        return experimentId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public String getKubeflowRunId() {
        return kubeflowRunId;
    }

    public DeploymentStatus getDeploymentStatus() {
        return deploymentStatus;
    }

    public String getPredictionEndpoint() {
        return predictionEndpoint;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Boolean getActive() {
        return active;
    }

    public String getDeployedAt() {
        return deployedAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public Boolean getApiEnabled() {
        return apiEnabled;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getApiKeyCreatedAt() {
        return apiKeyCreatedAt;
    }

    public String getApiKeyLastUsedAt() {
        return apiKeyLastUsedAt;
    }

    public String getMlflowProductionVersion() {
        return mlflowProductionVersion;
    }

    public String getKubernetesActiveVersion() {
        return kubernetesActiveVersion;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = registeredModelName;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public void setMlflowRunId(String mlflowRunId) {
        this.mlflowRunId = mlflowRunId;
    }

    public void setKubeflowRunId(String kubeflowRunId) {
        this.kubeflowRunId = kubeflowRunId;
    }

    public void setDeploymentStatus(DeploymentStatus deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    public void setPredictionEndpoint(String predictionEndpoint) {
        this.predictionEndpoint = predictionEndpoint;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public void setDockerImage(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public void setDeployedAt(String deployedAt) {
        this.deployedAt = deployedAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setApiEnabled(Boolean apiEnabled) {
        this.apiEnabled = apiEnabled;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public void setApiKeyCreatedAt(String apiKeyCreatedAt) {
        this.apiKeyCreatedAt = apiKeyCreatedAt;
    }

    public void setApiKeyLastUsedAt(String apiKeyLastUsedAt) {
        this.apiKeyLastUsedAt = apiKeyLastUsedAt;
    }

    public void setMlflowProductionVersion(String mlflowProductionVersion) {
        this.mlflowProductionVersion = mlflowProductionVersion;
    }

    public void setKubernetesActiveVersion(String kubernetesActiveVersion) {
        this.kubernetesActiveVersion = kubernetesActiveVersion;
    }
}
