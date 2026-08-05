package com.mlops.platform.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class UserMonitoringDashboardResponse {

    public List<ProjectOption> projects = new ArrayList<>();
    public Summary summary = new Summary();
    public ProjectDashboard projectDashboard;

    public static class ProjectOption {
        public Long id;
        public String name;
        public String dataType;
        public String projectType;
    }

    public static class Summary {
        public int totalProjects;
        public int totalExperiments;

        public long completedRuns;
        public long failedRuns;
        public long runningRuns;

        public int totalDeployments;
        public long successfulDeployments;
        public long failedDeployments;
        public long deploymentsInProgress;

        public double runSuccessRate;
        public double deploymentSuccessRate;

        public int activeAlerts;
        public double averagePredictionLatencyMs;
    }

    public static class ProjectDashboard {
        public Long projectId;
        public String projectName;
        public String description;
        public String dataType;
        public String projectType;
        public String targetColumn;
        public String runtimeMode;
        public LocalDateTime createdAt;

        public List<String> metricPriority = new ArrayList<>();

        public int experimentsCount;
        public long completedRuns;
        public long failedRuns;
        public long runningRuns;

        public double runSuccessRate;
        public double deploymentSuccessRate;

        public Double bestGlobalScore;
        public String currentProductionVersion;
        public String currentProductionModel;

        public boolean retrainingEnabled;
        public String retrainingStrategy;
        public LocalDateTime nextRetrainingAt;
        public LocalDateTime lastRetrainingAt;
        public LocalDateTime lastMonitoringCheckAt;
        public String lastDecisionReason;
        public boolean failureMonitoringEnabled;
        public boolean performanceMonitoringEnabled;
        public boolean dataDriftMonitoringEnabled;

        public long recentPredictionCount;
        public double predictionErrorRatePercent;
        public double dataDriftScorePercent;
        public double averagePredictionLatencyMs;
        public double predictionSuccessRate;
        public LocalDateTime lastPredictionAt;

        public int healthScore;

        public List<ScorePoint> scoreHistory = new ArrayList<>();
        public List<DeploymentItem> deployments = new ArrayList<>();
        public List<AlertItem> alerts = new ArrayList<>();
        public List<ActivityItem> activities = new ArrayList<>();
    }

    public static class ScorePoint {
        public Long experimentId;
        public String experimentName;
        public String modelVersion;
        public String primaryMetric;
        public Double primaryMetricValue;
        public Double globalScore;
        public String status;
        public boolean production;
        public LocalDateTime createdAt;
    }

    public static class DeploymentItem {
        public Long deploymentId;
        public String registeredModelName;
        public String modelVersion;
        public String deploymentStatus;
        public boolean active;
        public String endpoint;
        public LocalDateTime deployedAt;
        public LocalDateTime createdAt;
    }

    public static class AlertItem {
        public String severity;
        public String title;
        public String message;
        public String source;
    }

    public static class ActivityItem {
        public LocalDateTime date;
        public String title;
        public String description;
        public String status;
    }
}