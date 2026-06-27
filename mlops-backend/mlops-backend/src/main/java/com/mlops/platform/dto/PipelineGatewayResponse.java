package com.mlops.platform.dto;

import java.util.HashMap;
import java.util.Map;

public class PipelineGatewayResponse {

    private String status;
    private String kubeflowRunId;
    private String kubeflowRunName;
    private String mlflowExperimentName;
    private String mlflowRunId;
    private String registeredModelName;
    private String modelVersion;
    private Double primaryMetricValue;
    private Map<String, Double> metrics = new HashMap<>();
    private String message;

    public String getStatus() { return status; }
    public String getKubeflowRunId() { return kubeflowRunId; }
    public String getKubeflowRunName() { return kubeflowRunName; }
    public String getMlflowExperimentName() { return mlflowExperimentName; }
    public String getMlflowRunId() { return mlflowRunId; }
    public String getRegisteredModelName() { return registeredModelName; }
    public String getModelVersion() { return modelVersion; }
    public Double getPrimaryMetricValue() { return primaryMetricValue; }
    public Map<String, Double> getMetrics() { return metrics; }
    public String getMessage() { return message; }

    public void setStatus(String status) { this.status = status; }
    public void setKubeflowRunId(String kubeflowRunId) { this.kubeflowRunId = kubeflowRunId; }
    public void setKubeflowRunName(String kubeflowRunName) { this.kubeflowRunName = kubeflowRunName; }
    public void setMlflowExperimentName(String mlflowExperimentName) { this.mlflowExperimentName = mlflowExperimentName; }
    public void setMlflowRunId(String mlflowRunId) { this.mlflowRunId = mlflowRunId; }
    public void setRegisteredModelName(String registeredModelName) { this.registeredModelName = registeredModelName; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public void setPrimaryMetricValue(Double primaryMetricValue) { this.primaryMetricValue = primaryMetricValue; }
    public void setMetrics(Map<String, Double> metrics) { this.metrics = metrics; }
    public void setMessage(String message) { this.message = message; }
}