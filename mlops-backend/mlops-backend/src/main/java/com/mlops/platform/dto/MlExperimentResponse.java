package com.mlops.platform.dto;

import java.util.Map;

public class MlExperimentResponse {

    private Long id;
    private String experimentName;
    private String status;

    private Long projectId;
    private String projectName;

    private Long datasetFileId;
    private String datasetFileName;

    private Long modelFileId;
    private String modelFileName;

    private String modelType;
    private String modelName;

    private String primaryMetric;
    private String direction;

    private String kubeflowRunId;
    private String kubeflowRunName;
    private String kubeflowStatus;

    private String mlflowExperimentName;
    private String mlflowExperimentId;
    private String mlflowRunId;

    private String registeredModelName;

    private String modelVersion;
    private String latestVersion;
    private String productionVersion;

    private Boolean production;
    private Double globalScore;

    private Double primaryMetricValue;
    private Map<String, Double> metrics;

    private String message;
    private String errorMessage;

    private String createdAt;
    private String updatedAt;

    private String detectedAlgorithms;
    private String usedAlgorithms;
    

    private String runtimeMode;
    private String customImageUri;
    private String dockerfilePath;
    private String requirementsPath;
    private String buildStatus;
    private String buildLogs;
    private String trainingLogs;

    private String failurePhase;
    private String failureReason;
    private String failureDetails;
    private String failureSuggestion;
    private String failureLogExcerpt;
    private String failureSourceFile;

    public MlExperimentResponse() {
    }

    public MlExperimentResponse(
            Long id,
            String experimentName,
            String status,

            Long projectId,
            String projectName,

            Long datasetFileId,
            String datasetFileName,

            Long modelFileId,
            String modelFileName,

            String modelType,
            String modelName,
            String primaryMetric,
            String direction,

            String kubeflowRunId,
            String kubeflowRunName,
            String kubeflowStatus,

            String mlflowExperimentName,
            String mlflowExperimentId,
            String mlflowRunId,

            String registeredModelName,
            String modelVersion,
            String latestVersion,
            String productionVersion,

            Boolean production,
            Double globalScore,

            Double primaryMetricValue,
            Map<String, Double> metrics,

            String message,
            String errorMessage,

            String createdAt,
            String updatedAt
    ) {
        this.id = id;
        this.experimentName = experimentName;
        this.status = status;

        this.projectId = projectId;
        this.projectName = projectName;

        this.datasetFileId = datasetFileId;
        this.datasetFileName = datasetFileName;

        this.modelFileId = modelFileId;
        this.modelFileName = modelFileName;

        this.modelType = modelType;
        this.modelName = modelName;

        this.primaryMetric = primaryMetric;
        this.direction = direction;

        this.kubeflowRunId = kubeflowRunId;
        this.kubeflowRunName = kubeflowRunName;
        this.kubeflowStatus = kubeflowStatus;

        this.mlflowExperimentName = mlflowExperimentName;
        this.mlflowExperimentId = mlflowExperimentId;
        this.mlflowRunId = mlflowRunId;

        this.registeredModelName = registeredModelName;

        this.modelVersion = modelVersion;
        this.latestVersion = latestVersion;
        this.productionVersion = productionVersion;

        this.production = production;
        this.globalScore = globalScore;

        this.primaryMetricValue = primaryMetricValue;
        this.metrics = metrics;

        this.message = message;
        this.errorMessage = errorMessage;

        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getExperimentName() {
        return experimentName;
    }

    public String getStatus() {
        return status;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public Long getDatasetFileId() {
        return datasetFileId;
    }

    public String getDatasetFileName() {
        return datasetFileName;
    }

    public Long getModelFileId() {
        return modelFileId;
    }

    public String getModelFileName() {
        return modelFileName;
    }

    public String getModelType() {
        return modelType;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPrimaryMetric() {
        return primaryMetric;
    }

    public String getDirection() {
        return direction;
    }

    public String getKubeflowRunId() {
        return kubeflowRunId;
    }

    public String getKubeflowRunName() {
        return kubeflowRunName;
    }

    public String getKubeflowStatus() {
        return kubeflowStatus;
    }

    public String getMlflowExperimentName() {
        return mlflowExperimentName;
    }

    public String getMlflowExperimentId() {
        return mlflowExperimentId;
    }

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getProductionVersion() {
        return productionVersion;
    }

    public Boolean getProduction() {
        return production;
    }

    public Boolean getIsProduction() {
        return production;
    }

    public Double getGlobalScore() {
        return globalScore;
    }

    public Double getPrimaryMetricValue() {
        return primaryMetricValue;
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getDetectedAlgorithms() {
        return detectedAlgorithms;
    }

    public String getUsedAlgorithms() {
        return usedAlgorithms;
    }

    

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public String getCustomImageUri() {
        return customImageUri;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public String getRequirementsPath() {
        return requirementsPath;
    }

    public String getBuildStatus() {
        return buildStatus;
    }

    public String getBuildLogs() {
        return buildLogs;
    }

    public String getTrainingLogs() {
        return trainingLogs;
    }

    public String getFailurePhase() {
        return failurePhase;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getFailureDetails() {
        return failureDetails;
    }

    public String getFailureSuggestion() {
        return failureSuggestion;
    }

    public String getFailureLogExcerpt() {
        return failureLogExcerpt;
    }

    public String getFailureSourceFile() {
        return failureSourceFile;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setExperimentName(String experimentName) {
        this.experimentName = experimentName;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public void setDatasetFileId(Long datasetFileId) {
        this.datasetFileId = datasetFileId;
    }

    public void setDatasetFileName(String datasetFileName) {
        this.datasetFileName = datasetFileName;
    }

    public void setModelFileId(Long modelFileId) {
        this.modelFileId = modelFileId;
    }

    public void setModelFileName(String modelFileName) {
        this.modelFileName = modelFileName;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setPrimaryMetric(String primaryMetric) {
        this.primaryMetric = primaryMetric;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public void setKubeflowRunId(String kubeflowRunId) {
        this.kubeflowRunId = kubeflowRunId;
    }

    public void setKubeflowRunName(String kubeflowRunName) {
        this.kubeflowRunName = kubeflowRunName;
    }

    public void setKubeflowStatus(String kubeflowStatus) {
        this.kubeflowStatus = kubeflowStatus;
    }

    public void setMlflowExperimentName(String mlflowExperimentName) {
        this.mlflowExperimentName = mlflowExperimentName;
    }

    public void setMlflowExperimentId(String mlflowExperimentId) {
        this.mlflowExperimentId = mlflowExperimentId;
    }

    public void setMlflowRunId(String mlflowRunId) {
        this.mlflowRunId = mlflowRunId;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = registeredModelName;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public void setProductionVersion(String productionVersion) {
        this.productionVersion = productionVersion;
    }

    public void setProduction(Boolean production) {
        this.production = production;
    }

    public void setIsProduction(Boolean production) {
        this.production = production;
    }

    public void setGlobalScore(Double globalScore) {
        this.globalScore = globalScore;
    }

    public void setPrimaryMetricValue(Double primaryMetricValue) {
        this.primaryMetricValue = primaryMetricValue;
    }

    public void setMetrics(Map<String, Double> metrics) {
        this.metrics = metrics;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setDetectedAlgorithms(String detectedAlgorithms) {
        this.detectedAlgorithms = detectedAlgorithms;
    }

    public void setUsedAlgorithms(String usedAlgorithms) {
        this.usedAlgorithms = usedAlgorithms;
    }

    
    public void setRuntimeMode(String runtimeMode) {
        this.runtimeMode = runtimeMode;
    }

    public void setCustomImageUri(String customImageUri) {
        this.customImageUri = customImageUri;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public void setRequirementsPath(String requirementsPath) {
        this.requirementsPath = requirementsPath;
    }

    public void setBuildStatus(String buildStatus) {
        this.buildStatus = buildStatus;
    }

    public void setBuildLogs(String buildLogs) {
        this.buildLogs = buildLogs;
    }

    public void setTrainingLogs(String trainingLogs) {
        this.trainingLogs = trainingLogs;
    }

    public void setFailurePhase(String failurePhase) {
        this.failurePhase = failurePhase;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public void setFailureDetails(String failureDetails) {
        this.failureDetails = failureDetails;
    }

    public void setFailureSuggestion(String failureSuggestion) {
        this.failureSuggestion = failureSuggestion;
    }

    public void setFailureLogExcerpt(String failureLogExcerpt) {
        this.failureLogExcerpt = failureLogExcerpt;
    }

    public void setFailureSourceFile(String failureSourceFile) {
        this.failureSourceFile = failureSourceFile;
    }
}