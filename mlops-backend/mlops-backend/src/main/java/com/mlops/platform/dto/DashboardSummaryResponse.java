package com.mlops.platform.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vue d'ensemble temps réel du tableau de bord MLOps Pilot.
 * Agrégée par utilisateur : projets, runs, modèles, déploiements,
 * derniers runs, meilleurs modèles et statut de la plateforme.
 */
public class DashboardSummaryResponse {

    private long projectsTotal;
    private long runningProjects;
    private long runsTotal;
    private long runsRunning;
    private long runsCompleted;
    private long runsFailed;
    private long runsCancelled;
    private double runSuccessRate;

    private long modelsTotal;
    private long modelsWithProduction;

    private long deploymentsTotal;
    private long deploymentsDeployed;
    private long deploymentsInProgress;
    private long deploymentsFailed;
    private long activeDeployments;

    private List<RecentRun> recentRuns;
    private List<BestModel> bestModels;
    private PlatformStatus platformStatus;
    private LocalDateTime evaluatedAt;

    public long getProjectsTotal() {
        return projectsTotal;
    }

    public void setProjectsTotal(long projectsTotal) {
        this.projectsTotal = projectsTotal;
    }

    public long getRunningProjects() {
        return runningProjects;
    }

    public void setRunningProjects(long runningProjects) {
        this.runningProjects = runningProjects;
    }

    public long getRunsTotal() {
        return runsTotal;
    }

    public void setRunsTotal(long runsTotal) {
        this.runsTotal = runsTotal;
    }

    public long getRunsRunning() {
        return runsRunning;
    }

    public void setRunsRunning(long runsRunning) {
        this.runsRunning = runsRunning;
    }

    public long getRunsCompleted() {
        return runsCompleted;
    }

    public void setRunsCompleted(long runsCompleted) {
        this.runsCompleted = runsCompleted;
    }

    public long getRunsFailed() {
        return runsFailed;
    }

    public void setRunsFailed(long runsFailed) {
        this.runsFailed = runsFailed;
    }

    public long getRunsCancelled() {
        return runsCancelled;
    }

    public void setRunsCancelled(long runsCancelled) {
        this.runsCancelled = runsCancelled;
    }

    public double getRunSuccessRate() {
        return runSuccessRate;
    }

    public void setRunSuccessRate(double runSuccessRate) {
        this.runSuccessRate = runSuccessRate;
    }

    public long getModelsTotal() {
        return modelsTotal;
    }

    public void setModelsTotal(long modelsTotal) {
        this.modelsTotal = modelsTotal;
    }

    public long getModelsWithProduction() {
        return modelsWithProduction;
    }

    public void setModelsWithProduction(long modelsWithProduction) {
        this.modelsWithProduction = modelsWithProduction;
    }

    public long getDeploymentsTotal() {
        return deploymentsTotal;
    }

    public void setDeploymentsTotal(long deploymentsTotal) {
        this.deploymentsTotal = deploymentsTotal;
    }

    public long getDeploymentsDeployed() {
        return deploymentsDeployed;
    }

    public void setDeploymentsDeployed(long deploymentsDeployed) {
        this.deploymentsDeployed = deploymentsDeployed;
    }

    public long getDeploymentsInProgress() {
        return deploymentsInProgress;
    }

    public void setDeploymentsInProgress(long deploymentsInProgress) {
        this.deploymentsInProgress = deploymentsInProgress;
    }

    public long getDeploymentsFailed() {
        return deploymentsFailed;
    }

    public void setDeploymentsFailed(long deploymentsFailed) {
        this.deploymentsFailed = deploymentsFailed;
    }

    public long getActiveDeployments() {
        return activeDeployments;
    }

    public void setActiveDeployments(long activeDeployments) {
        this.activeDeployments = activeDeployments;
    }

    public List<RecentRun> getRecentRuns() {
        return recentRuns;
    }

    public void setRecentRuns(List<RecentRun> recentRuns) {
        this.recentRuns = recentRuns;
    }

    public List<BestModel> getBestModels() {
        return bestModels;
    }

    public void setBestModels(List<BestModel> bestModels) {
        this.bestModels = bestModels;
    }

    public PlatformStatus getPlatformStatus() {
        return platformStatus;
    }

    public void setPlatformStatus(PlatformStatus platformStatus) {
        this.platformStatus = platformStatus;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public static class RecentRun {
        private Long experimentId;
        private String experimentName;
        private String projectName;
        private String status;
        private String modelName;
        private String primaryMetric;
        private Double primaryMetricValue;
        private String registeredModelName;
        private String createdAt;
        private String finishedAt;

        public Long getExperimentId() {
            return experimentId;
        }

        public void setExperimentId(Long experimentId) {
            this.experimentId = experimentId;
        }

        public String getExperimentName() {
            return experimentName;
        }

        public void setExperimentName(String experimentName) {
            this.experimentName = experimentName;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
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

        public String getRegisteredModelName() {
            return registeredModelName;
        }

        public void setRegisteredModelName(String registeredModelName) {
            this.registeredModelName = registeredModelName;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(String finishedAt) {
            this.finishedAt = finishedAt;
        }
    }

    public static class BestModel {
        private String registeredModelName;
        private String modelName;
        private String projectName;
        private Double globalScore;
        private String primaryMetric;
        private Double primaryMetricValue;
        private String productionVersion;
        private String deploymentStatus;
        private Boolean hasProduction;

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

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public Double getGlobalScore() {
            return globalScore;
        }

        public void setGlobalScore(Double globalScore) {
            this.globalScore = globalScore;
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

        public String getProductionVersion() {
            return productionVersion;
        }

        public void setProductionVersion(String productionVersion) {
            this.productionVersion = productionVersion;
        }

        public String getDeploymentStatus() {
            return deploymentStatus;
        }

        public void setDeploymentStatus(String deploymentStatus) {
            this.deploymentStatus = deploymentStatus;
        }

        public Boolean getHasProduction() {
            return hasProduction;
        }

        public void setHasProduction(Boolean hasProduction) {
            this.hasProduction = hasProduction;
        }
    }

    public static class PlatformStatus {
        private String backend;
        private String database;
        private long latencyMs;

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
        }
    }
}
