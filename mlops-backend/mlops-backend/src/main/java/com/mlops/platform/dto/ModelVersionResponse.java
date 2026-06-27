package com.mlops.platform.dto;

public class ModelVersionResponse {

    private Long experimentId;
    private String experimentName;

    private String registeredModelName;
    private String modelName;

    private String version;
    private String alias;
    private String mlflowStatus;
    private String status;

    private Long projectId;
    private String projectName;

    private String primaryMetric;
    private Double primaryMetricValue;

    private String modelFileName;
    private String datasetFileName;

    private String mlflowRunId;
    private String source;

    private String createdAt;
    private String updatedAt;

    public ModelVersionResponse(
            Long experimentId,
            String experimentName,
            String registeredModelName,
            String modelName,
            String version,
            String alias,
            String mlflowStatus,
            String status,
            Long projectId,
            String projectName,
            String primaryMetric,
            Double primaryMetricValue,
            String modelFileName,
            String datasetFileName,
            String mlflowRunId,
            String source,
            String createdAt,
            String updatedAt
    ) {
        this.experimentId = experimentId;
        this.experimentName = experimentName;
        this.registeredModelName = registeredModelName;
        this.modelName = modelName;
        this.version = version;
        this.alias = alias;
        this.mlflowStatus = mlflowStatus;
        this.status = status;
        this.projectId = projectId;
        this.projectName = projectName;
        this.primaryMetric = primaryMetric;
        this.primaryMetricValue = primaryMetricValue;
        this.modelFileName = modelFileName;
        this.datasetFileName = datasetFileName;
        this.mlflowRunId = mlflowRunId;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public String getExperimentName() {
        return experimentName;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public String getModelName() {
        return modelName;
    }

    public String getVersion() {
        return version;
    }

    public String getAlias() {
        return alias;
    }

    public String getMlflowStatus() {
        return mlflowStatus;
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

    public String getPrimaryMetric() {
        return primaryMetric;
    }

    public Double getPrimaryMetricValue() {
        return primaryMetricValue;
    }

    public String getModelFileName() {
        return modelFileName;
    }

    public String getDatasetFileName() {
        return datasetFileName;
    }

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}