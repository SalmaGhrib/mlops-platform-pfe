package com.mlops.platform.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO utilisé pour envoyer au frontend les informations d'une version MLflow.
 */
public class MlflowModelVersionDto {

    private String registeredModelName;
    private String version;
    private String runId;
    private String status;
    private String source;
    private String currentStage;
    private String productionVersion;
    private boolean production;
    private Double globalScore;
    private Map<String, Double> metrics = new HashMap<>();

    public MlflowModelVersionDto() {
    }

    public String getRegisteredModelName() { return registeredModelName; }
    public void setRegisteredModelName(String registeredModelName) { this.registeredModelName = registeredModelName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }

    public String getProductionVersion() { return productionVersion; }
    public void setProductionVersion(String productionVersion) { this.productionVersion = productionVersion; }

    public boolean isProduction() { return production; }
    public void setProduction(boolean production) { this.production = production; }

    public Double getGlobalScore() { return globalScore; }
    public void setGlobalScore(Double globalScore) { this.globalScore = globalScore; }

    public Map<String, Double> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Double> metrics) { this.metrics = metrics != null ? metrics : new HashMap<>(); }
}
