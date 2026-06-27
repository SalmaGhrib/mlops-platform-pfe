package com.mlops.platform.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class ModelRegistryResponse {

    private String registeredModelName;
    private String modelName;

    private String latestVersion;
    private String productionVersion;
    private String productionAlias;
    private String mlflowStatus;
    private String status;

    private Long projectId;
    private String projectName;

    /*
     * Ajouté pour que la page Prediction sache si le modèle est :
     * - TABULAR
     * - TEXT
     * - IMAGE
     */
    private String dataType;

    /*
     * Ajouté pour savoir si le projet est :
     * - classification
     * - regression
     */
    private String projectType;

    private Long latestExperimentId;
    private String latestExperimentName;

    private Long productionExperimentId;
    private String productionExperimentName;

    private String primaryMetric;
    private Double primaryMetricValue;
    private String direction;
    private Double globalScore;

    private String modelFileName;
    private String datasetFileName;

    private String mlflowRunId;
    private String kubeflowRunId;
    private String source;

    private Map<String, Double> metrics = new LinkedHashMap<>();

    private String detectedAlgorithms;
    private String usedAlgorithms;

    private Boolean deployed;
    private Long deploymentId;
    private String deploymentStatus;
    private String predictionEndpoint;

    private Boolean hasProduction;

    private String createdAt;
    private String updatedAt;

    public ModelRegistryResponse() {
    }

    /**
     * Ancien constructeur gardé pour ne pas casser le code existant.
     */
    public ModelRegistryResponse(
            String registeredModelName,
            String modelName,
            String productionVersion,
            String productionAlias,
            String mlflowStatus,
            String status,
            Long projectId,
            String projectName,
            Long latestExperimentId,
            String latestExperimentName,
            String primaryMetric,
            Double primaryMetricValue,
            String modelFileName,
            String datasetFileName,
            String mlflowRunId,
            String source,
            String createdAt,
            String updatedAt
    ) {
        this.registeredModelName = registeredModelName;
        this.modelName = modelName;
        this.productionVersion = productionVersion;
        this.productionAlias = productionAlias;
        this.mlflowStatus = mlflowStatus;
        this.status = status;
        this.projectId = projectId;
        this.projectName = projectName;
        this.latestExperimentId = latestExperimentId;
        this.latestExperimentName = latestExperimentName;
        this.primaryMetric = primaryMetric;
        this.primaryMetricValue = primaryMetricValue;
        this.modelFileName = modelFileName;
        this.datasetFileName = datasetFileName;
        this.mlflowRunId = mlflowRunId;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Nouveau constructeur avec dataType et projectType.
     * Tu n’es pas obligée de l’utiliser maintenant, mais il est utile si un service veut créer
     * directement une réponse complète.
     */
    public ModelRegistryResponse(
            String registeredModelName,
            String modelName,
            String productionVersion,
            String productionAlias,
            String mlflowStatus,
            String status,
            Long projectId,
            String projectName,
            String dataType,
            String projectType,
            Long latestExperimentId,
            String latestExperimentName,
            String primaryMetric,
            Double primaryMetricValue,
            String modelFileName,
            String datasetFileName,
            String mlflowRunId,
            String source,
            String createdAt,
            String updatedAt
    ) {
        this.registeredModelName = registeredModelName;
        this.modelName = modelName;
        this.productionVersion = productionVersion;
        this.productionAlias = productionAlias;
        this.mlflowStatus = mlflowStatus;
        this.status = status;
        this.projectId = projectId;
        this.projectName = projectName;
        this.dataType = dataType;
        this.projectType = projectType;
        this.latestExperimentId = latestExperimentId;
        this.latestExperimentName = latestExperimentName;
        this.primaryMetric = primaryMetric;
        this.primaryMetricValue = primaryMetricValue;
        this.modelFileName = modelFileName;
        this.datasetFileName = datasetFileName;
        this.mlflowRunId = mlflowRunId;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = registeredModelName;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public String getProductionVersion() {
        return productionVersion;
    }

    public void setProductionVersion(String productionVersion) {
        this.productionVersion = productionVersion;
    }

    public String getProductionAlias() {
        return productionAlias;
    }

    public void setProductionAlias(String productionAlias) {
        this.productionAlias = productionAlias;
    }

    public String getMlflowStatus() {
        return mlflowStatus;
    }

    public void setMlflowStatus(String mlflowStatus) {
        this.mlflowStatus = mlflowStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getDataType() {
        return dataType;
    }
    public Boolean getDeployed() {
    return deployed;
}

public void setDeployed(Boolean deployed) {
    this.deployed = deployed;
}

public Long getDeploymentId() {
    return deploymentId;
}

public void setDeploymentId(Long deploymentId) {
    this.deploymentId = deploymentId;
}

public String getDeploymentStatus() {
    return deploymentStatus;
}

public void setDeploymentStatus(String deploymentStatus) {
    this.deploymentStatus = deploymentStatus;
}

public String getPredictionEndpoint() {
    return predictionEndpoint;
}

public void setPredictionEndpoint(String predictionEndpoint) {
    this.predictionEndpoint = predictionEndpoint;
}
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public Long getLatestExperimentId() {
        return latestExperimentId;
    }

    public void setLatestExperimentId(Long latestExperimentId) {
        this.latestExperimentId = latestExperimentId;
    }

    public String getLatestExperimentName() {
        return latestExperimentName;
    }

    public void setLatestExperimentName(String latestExperimentName) {
        this.latestExperimentName = latestExperimentName;
    }

    public Long getProductionExperimentId() {
        return productionExperimentId;
    }

    public void setProductionExperimentId(Long productionExperimentId) {
        this.productionExperimentId = productionExperimentId;
    }

    public String getProductionExperimentName() {
        return productionExperimentName;
    }

    public void setProductionExperimentName(String productionExperimentName) {
        this.productionExperimentName = productionExperimentName;
    }

    public String getPrimaryMetric() {
        return primaryMetric;
    }

    public void setPrimaryMetric(String primaryMetric) {
        this.primaryMetric = primaryMetric;
    }

    public Double getPrimaryMetricValue() {
        return primaryMetricValue;
    }

    public void setPrimaryMetricValue(Double primaryMetricValue) {
        this.primaryMetricValue = primaryMetricValue;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Double getGlobalScore() {
        return globalScore;
    }

    public void setGlobalScore(Double globalScore) {
        this.globalScore = globalScore;
    }

    public String getModelFileName() {
        return modelFileName;
    }

    public void setModelFileName(String modelFileName) {
        this.modelFileName = modelFileName;
    }

    public String getDatasetFileName() {
        return datasetFileName;
    }

    public void setDatasetFileName(String datasetFileName) {
        this.datasetFileName = datasetFileName;
    }

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public void setMlflowRunId(String mlflowRunId) {
        this.mlflowRunId = mlflowRunId;
    }

    public String getKubeflowRunId() {
        return kubeflowRunId;
    }

    public void setKubeflowRunId(String kubeflowRunId) {
        this.kubeflowRunId = kubeflowRunId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Double> metrics) {
        this.metrics = metrics != null ? metrics : new LinkedHashMap<>();
    }

    public String getDetectedAlgorithms() {
        return detectedAlgorithms;
    }

    public void setDetectedAlgorithms(String detectedAlgorithms) {
        this.detectedAlgorithms = detectedAlgorithms;
    }

    public String getUsedAlgorithms() {
        return usedAlgorithms;
    }

    public void setUsedAlgorithms(String usedAlgorithms) {
        this.usedAlgorithms = usedAlgorithms;
    }

    public Boolean getHasProduction() {
        return hasProduction;
    }

    public void setHasProduction(Boolean hasProduction) {
        this.hasProduction = hasProduction;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}