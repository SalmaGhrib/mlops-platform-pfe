package com.mlops.platform.dto;

import com.mlops.platform.entity.AlertSeverity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AdminMonitoringOverviewResponse {

    public long totalProjects;
    public long monitoredProjects;
    public long totalDeployments;
    public long activeProductionModels;
    public long degradedModels;
    public long modelsBelowQualityThreshold;
    public long projectsWithDataDrift;
    public long projectsWithPredictionErrors;
    public long projectsWithPerformanceDrop;
    public long criticalAlerts24h;
    public long totalAlerts24h;

    public List<MonitoringAlertResponse> recentAlerts = new ArrayList<>();
    public List<ProjectHealthItem> projects = new ArrayList<>();

    public static class ProjectHealthItem {
        public Long projectId;
        public String projectName;
        public String projectType;
        public String registeredModelName;
        public String modelVersion;
        public String qualityStatus;
        public Double globalScore;
        public Double globalScoreThreshold;
        public double predictionErrorRatePercent;
        public double dataDriftScorePercent;
        public double performanceDropPercent;
        public String latestAlertType;
        public AlertSeverity latestAlertSeverity;
        public String latestAlertMessage;
        public LocalDateTime latestAlertAt;
        public boolean retrainingEnabled;
    }
}