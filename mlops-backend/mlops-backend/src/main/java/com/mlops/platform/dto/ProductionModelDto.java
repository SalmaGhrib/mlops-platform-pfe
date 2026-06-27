package com.mlops.platform.dto;

public class ProductionModelDto {

    private Long experimentId;
    private Long projectId;
    private String projectName;

    private String dataType;
    private String projectType;

    private String registeredModelName;
    private String productionVersion;
    private Double scoreGlobal;
    private String primaryMetric;
    private Double primaryMetricValue;

    public ProductionModelDto() {
    }

    public ProductionModelDto(
            Long experimentId,
            Long projectId,
            String projectName,
            String dataType,
            String projectType,
            String registeredModelName,
            String productionVersion,
            Double scoreGlobal,
            String primaryMetric,
            Double primaryMetricValue
    ) {
        this.experimentId = experimentId;
        this.projectId = projectId;
        this.projectName = projectName;
        this.dataType = dataType;
        this.projectType = projectType;
        this.registeredModelName = registeredModelName;
        this.productionVersion = productionVersion;
        this.scoreGlobal = scoreGlobal;
        this.primaryMetric = primaryMetric;
        this.primaryMetricValue = primaryMetricValue;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
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

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = registeredModelName;
    }

    public String getProductionVersion() {
        return productionVersion;
    }

    public void setProductionVersion(String productionVersion) {
        this.productionVersion = productionVersion;
    }

    public Double getScoreGlobal() {
        return scoreGlobal;
    }

    public void setScoreGlobal(Double scoreGlobal) {
        this.scoreGlobal = scoreGlobal;
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
}