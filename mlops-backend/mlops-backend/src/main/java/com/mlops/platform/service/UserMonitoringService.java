package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.dto.UserMonitoringDashboardResponse;
import com.mlops.platform.entity.*;
import com.mlops.platform.repository.*;
import org.springframework.stereotype.Service;
import com.mlops.platform.security.JwtService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class UserMonitoringService {

    private final MlProjectRepository mlProjectRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final RetrainingConfigRepository retrainingConfigRepository;
    private final PredictionLogRepository predictionLogRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RetrainingDecisionService retrainingDecisionService;
    private final ModelQualityService modelQualityService;
    private final MonitoringAlertService monitoringAlertService;

    public UserMonitoringService(
            MlProjectRepository mlProjectRepository,
            MlExperimentRepository mlExperimentRepository,
            ModelDeploymentRepository modelDeploymentRepository,
            RetrainingConfigRepository retrainingConfigRepository,
            PredictionLogRepository predictionLogRepository,
            UserRepository userRepository,
            JwtService jwtService,
            RetrainingDecisionService retrainingDecisionService,
            ModelQualityService modelQualityService,
            MonitoringAlertService monitoringAlertService
    ) {
        this.mlProjectRepository = mlProjectRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.predictionLogRepository = predictionLogRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.retrainingDecisionService = retrainingDecisionService;
        this.modelQualityService = modelQualityService;
        this.monitoringAlertService = monitoringAlertService;
    }

    public UserMonitoringDashboardResponse getDashboard(String authHeader, Long requestedProjectId) {
        User user = getUserFromAuthorizationHeader(authHeader);

        List<MlProject> projects = mlProjectRepository.findByOwnerOrderByCreatedAtDesc(user);

        UserMonitoringDashboardResponse response = new UserMonitoringDashboardResponse();
        response.projects = projects.stream()
                .map(this::toProjectOption)
                .toList();

        response.summary = buildSummary(projects);

        MlProject selectedProject = selectProject(projects, requestedProjectId);
        if (selectedProject != null) {
            response.projectDashboard = buildProjectDashboard(selectedProject);
        }

        return response;
    }

    private UserMonitoringDashboardResponse.ProjectOption toProjectOption(MlProject project) {
        UserMonitoringDashboardResponse.ProjectOption option =
                new UserMonitoringDashboardResponse.ProjectOption();

        option.id = project.getId();
        option.name = project.getName();
        option.dataType = safeText(project.getDataType());
        option.projectType = safeText(project.getProjectType());

        return option;
    }

    private UserMonitoringDashboardResponse.Summary buildSummary(List<MlProject> projects) {
        UserMonitoringDashboardResponse.Summary summary =
                new UserMonitoringDashboardResponse.Summary();

        if (projects == null || projects.isEmpty()) {
            return summary;
        }

        List<MlExperiment> allExperiments = new ArrayList<>();
        List<ModelDeployment> allDeployments = new ArrayList<>();
        List<PredictionLog> allPredictionLogs = new ArrayList<>();
        int activeAlerts = 0;

        for (MlProject project : projects) {
            allExperiments.addAll(mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project));
            allDeployments.addAll(modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(project.getId()));

            RetrainingConfig config = retrainingConfigRepository.findByProjectId(project.getId()).orElse(null);
            LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
            int lookbackHours = config != null && config.getMonitoringLookbackHours() != null
                    ? Math.max(1, config.getMonitoringLookbackHours())
                    : 24;

            allPredictionLogs.addAll(
                    predictionLogRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                            project.getId(),
                            now.minusHours(lookbackHours),
                            now
                    )
            );

            activeAlerts += buildProjectAlerts(project).size();
        }

        summary.totalProjects = projects.size();
        summary.totalExperiments = allExperiments.size();

        summary.completedRuns = allExperiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.COMPLETED)
                .count();

        summary.failedRuns = allExperiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.FAILED)
                .count();

        summary.runningRuns = allExperiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.CREATED || exp.getStatus() == ExperimentStatus.RUNNING)
                .count();

        summary.totalDeployments = allDeployments.size();

        summary.successfulDeployments = allDeployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.DEPLOYED)
                .count();

        summary.failedDeployments = allDeployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.FAILED)
                .count();

        summary.deploymentsInProgress = allDeployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.PENDING
                        || dep.getDeploymentStatus() == DeploymentStatus.DEPLOYING)
                .count();

        summary.runSuccessRate = calculateRate(summary.completedRuns, summary.completedRuns + summary.failedRuns);
        summary.deploymentSuccessRate = calculateRate(summary.successfulDeployments, summary.totalDeployments);
        summary.activeAlerts = activeAlerts;
        summary.averagePredictionLatencyMs = roundTwo(
                allPredictionLogs.stream()
                        .map(PredictionLog::getLatencyMs)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0.0)
        );

        return summary;
    }

    private MlProject selectProject(List<MlProject> projects, Long requestedProjectId) {
        if (projects == null || projects.isEmpty()) {
            return null;
        }

        if (requestedProjectId == null) {
            return projects.get(0);
        }

        return projects.stream()
                .filter(project -> Objects.equals(project.getId(), requestedProjectId))
                .findFirst()
                .orElse(projects.get(0));
    }

    private UserMonitoringDashboardResponse.ProjectDashboard buildProjectDashboard(MlProject project) {
        UserMonitoringDashboardResponse.ProjectDashboard dashboard =
                new UserMonitoringDashboardResponse.ProjectDashboard();

        dashboard.projectId = project.getId();
        dashboard.projectName = safeText(project.getName());
        dashboard.description = safeText(project.getDescription());
        dashboard.dataType = safeText(project.getDataType());
        dashboard.projectType = safeText(project.getProjectType());
        dashboard.targetColumn = safeText(project.getTargetColumn());
        dashboard.runtimeMode = safeText(project.getRuntimeMode());
        dashboard.createdAt = project.getCreatedAt();

        dashboard.metricPriority = Stream.of(
                        project.getPrimaryMetric1(),
                        project.getPrimaryMetric2(),
                        project.getPrimaryMetric3()
                )
                .filter(this::hasText)
                .map(this::formatMetricName)
                .toList();

        List<MlExperiment> experiments = mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);
        List<ModelDeployment> deployments = modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(project.getId());

        RetrainingConfig config = retrainingConfigRepository.findByProjectId(project.getId()).orElse(null);
        RetrainingDecisionResult monitoringDecision = config != null
                ? retrainingDecisionService.evaluateMonitoringSignals(config)
                : RetrainingDecisionResult.skip("Aucune configuration de monitoring.");

        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        int lookbackHours = config != null && config.getMonitoringLookbackHours() != null
                ? Math.max(1, config.getMonitoringLookbackHours())
                : 24;

        List<PredictionLog> recentPredictionLogs =
                predictionLogRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                        project.getId(),
                        now.minusHours(lookbackHours),
                        now
                );

        dashboard.experimentsCount = experiments.size();

        dashboard.completedRuns = experiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.COMPLETED)
                .count();

        dashboard.failedRuns = experiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.FAILED)
                .count();

        dashboard.runningRuns = experiments.stream()
                .filter(exp -> exp.getStatus() == ExperimentStatus.CREATED || exp.getStatus() == ExperimentStatus.RUNNING)
                .count();

        dashboard.runSuccessRate = calculateRate(dashboard.completedRuns, dashboard.completedRuns + dashboard.failedRuns);

        long successfulDeployments = deployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.DEPLOYED)
                .count();

        dashboard.deploymentSuccessRate = calculateRate(successfulDeployments, deployments.size());

        dashboard.bestGlobalScore = experiments.stream()
                .map(MlExperiment::getGlobalScore)
                .filter(Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);

        Optional<ModelDeployment> productionDeployment = deployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.DEPLOYED)
                .filter(ModelDeployment::getActive)
                .sorted(Comparator.comparing(ModelDeployment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .findFirst();

        dashboard.currentProductionVersion = productionDeployment.map(ModelDeployment::getModelVersion).orElse(null);
        dashboard.currentProductionModel = productionDeployment.map(ModelDeployment::getRegisteredModelName).orElse(null);

        dashboard.retrainingEnabled = config != null && config.isEnabled();
        dashboard.retrainingStrategy = config != null && config.getStrategy() != null
                ? config.getStrategy().name()
                : "NOT_CONFIGURED";
        dashboard.nextRetrainingAt = config != null ? config.getNextRetrainingAt() : null;
        dashboard.lastRetrainingAt = config != null ? config.getLastRetrainingAt() : null;
        dashboard.lastMonitoringCheckAt = config != null ? config.getLastMonitoringCheckAt() : null;
        dashboard.nextMonitoringCheckAt = config != null ? config.getNextMonitoringCheckAt() : null;
        dashboard.lastDecisionReason = config != null ? safeText(config.getLastDecisionReason()) : null;
        dashboard.failureMonitoringEnabled = config != null && config.isFailureMonitoringEnabled();
        dashboard.performanceMonitoringEnabled = config != null && config.isPerformanceMonitoringEnabled();
        dashboard.dataDriftMonitoringEnabled = config != null && config.isDataDriftMonitoringEnabled();

        dashboard.recentPredictionCount = monitoringDecision.getRecentPredictionCount();
        dashboard.predictionErrorRatePercent = roundTwo(monitoringDecision.getPredictionErrorRatePercent());
        dashboard.dataDriftScorePercent = roundTwo(monitoringDecision.getDataDriftScorePercent());
        dashboard.performanceDropPercent = roundTwo(monitoringDecision.getPerformanceDropPercent());
        dashboard.averagePredictionLatencyMs = roundTwo(
                recentPredictionLogs.stream()
                        .map(PredictionLog::getLatencyMs)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0.0)
        );

        long successfulPredictions = recentPredictionLogs.stream()
                .filter(log -> Boolean.TRUE.equals(log.getSuccess()))
                .count();

        dashboard.predictionSuccessRate = calculateRate(successfulPredictions, recentPredictionLogs.size());

        dashboard.lastPredictionAt = recentPredictionLogs.stream()
                .map(PredictionLog::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        ModelQualityService.QualityEvaluation qualityEvaluation =
                modelQualityService.evaluateCandidate(project);

        if (qualityEvaluation != null) {
            dashboard.globalScoreThreshold = qualityEvaluation.threshold();
            dashboard.currentProductionGlobalScore = qualityEvaluation.globalScore();
            dashboard.qualityStatus = qualityEvaluation.threshold() == null
                    ? "NOT_CONFIGURED"
                    : (qualityEvaluation.conforming() ? "CONFORMING" : "INSUFFICIENT");
            dashboard.qualityReason = qualityEvaluation.reason();
        } else {
            dashboard.globalScoreThreshold = modelQualityService.resolveThreshold(project.getId());
            dashboard.qualityStatus = dashboard.globalScoreThreshold == null
                    ? "NOT_CONFIGURED"
                    : "NO_PRODUCTION_MODEL";
            dashboard.qualityReason = "No production model to evaluate.";
        }

        dashboard.recentAlerts = monitoringAlertService.getRecentAlertsByProject(project.getId());

        dashboard.alerts = buildProjectAlerts(project);
        dashboard.healthScore = calculateProjectHealthScore(dashboard, deployments);
        dashboard.scoreHistory = buildScoreHistory(experiments);
        dashboard.deployments = buildDeployments(deployments);
        dashboard.activities = buildActivities(experiments, deployments);

        return dashboard;
    }

    private List<UserMonitoringDashboardResponse.ScorePoint> buildScoreHistory(List<MlExperiment> experiments) {
        return experiments.stream()
                .filter(exp -> exp.getGlobalScore() != null)
                .sorted(Comparator.comparing(MlExperiment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(12)
                .map(exp -> {
                    UserMonitoringDashboardResponse.ScorePoint point =
                            new UserMonitoringDashboardResponse.ScorePoint();

                    point.experimentId = exp.getId();
                    point.experimentName = safeText(exp.getExperimentName());
                    point.modelVersion = safeText(exp.getModelVersion());
                    point.primaryMetric = formatMetricName(exp.getPrimaryMetric());
                    point.primaryMetricValue = exp.getPrimaryMetricValue();
                    point.globalScore = exp.getGlobalScore();
                    point.status = exp.getStatus() != null ? exp.getStatus().name() : "UNKNOWN";
                    point.production = Boolean.TRUE.equals(exp.getProduction());
                    point.createdAt = exp.getCreatedAt();

                    return point;
                })
                .toList();
    }

    private List<UserMonitoringDashboardResponse.DeploymentItem> buildDeployments(List<ModelDeployment> deployments) {
        return deployments.stream()
                .sorted(Comparator.comparing(ModelDeployment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(8)
                .map(dep -> {
                    UserMonitoringDashboardResponse.DeploymentItem item =
                            new UserMonitoringDashboardResponse.DeploymentItem();

                    item.deploymentId = dep.getId();
                    item.registeredModelName = safeText(dep.getRegisteredModelName());
                    item.modelVersion = safeText(dep.getModelVersion());
                    item.deploymentStatus = dep.getDeploymentStatus() != null
                            ? dep.getDeploymentStatus().name()
                            : "UNKNOWN";
                    item.active = Boolean.TRUE.equals(dep.getActive());
                    item.endpoint = safeText(dep.getPredictionEndpoint());
                    item.deployedAt = dep.getDeployedAt();
                    item.createdAt = dep.getCreatedAt();

                    return item;
                })
                .toList();
    }

    private List<UserMonitoringDashboardResponse.AlertItem> buildProjectAlerts(MlProject project) {
        List<UserMonitoringDashboardResponse.AlertItem> alerts = new ArrayList<>();

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(project.getId());

        RetrainingConfig config = retrainingConfigRepository.findByProjectId(project.getId()).orElse(null);
        RetrainingDecisionResult decision = config != null
                ? retrainingDecisionService.evaluateMonitoringSignals(config)
                : RetrainingDecisionResult.skip("Aucune configuration de monitoring.");

        long failedDeployments = deployments.stream()
                .filter(dep -> dep.getDeploymentStatus() == DeploymentStatus.FAILED)
                .count();

        boolean productionUnavailable = deployments.stream()
                .noneMatch(dep -> dep.getDeploymentStatus() == DeploymentStatus.DEPLOYED && Boolean.TRUE.equals(dep.getActive()));

        if (decision.getRecentFailedRuns() > 0) {
            alerts.add(buildAlert(
                    "critical",
                    "Runs échoués détectés",
                    decision.getRecentFailedRuns() + " run(s) échoué(s) ont été détectés sur la fenêtre de monitoring.",
                    "FAILED_RUNS"
            ));
        }

        if (config != null
                && config.isPerformanceMonitoringEnabled()
                && decision.getRecentPredictionCount() >= safeLong(config.getMinPredictionsForMonitoring(), 30)
                && decision.getPredictionErrorRatePercent() >= safeDouble(config.getMaxPredictionErrorRatePercent(), 20.0)) {
            alerts.add(buildAlert(
                    "warning",
                    "Baisse de performance",
                    "Le taux d’erreur de prédiction est de " + roundTwo(decision.getPredictionErrorRatePercent())
                            + "% (seuil " + roundTwo(safeDouble(config.getMaxPredictionErrorRatePercent(), 20.0)) + "%).",
                    "PREDICTION_ERRORS"
            ));
        }

        if (config != null
                && config.isDataDriftMonitoringEnabled()
                && decision.getRecentPredictionCount() >= safeLong(config.getMinPredictionsForMonitoring(), 30)
                && decision.getDataDriftScorePercent() >= safeDouble(config.getDataDriftThresholdPercent(), 25.0)) {
            alerts.add(buildAlert(
                    "warning",
                    "Data drift détecté",
                    "Le score de drift est de " + roundTwo(decision.getDataDriftScorePercent())
                            + "% (seuil " + roundTwo(safeDouble(config.getDataDriftThresholdPercent(), 25.0)) + "%).",
                    "DATA_DRIFT"
            ));
        }

        if (failedDeployments > 0) {
            alerts.add(buildAlert(
                    "warning",
                    "Déploiements échoués",
                    failedDeployments + " déploiement(s) ont échoué pour ce projet.",
                    "DEPLOYMENT"
            ));
        }

        if (productionUnavailable) {
            alerts.add(buildAlert(
                    "info",
                    "Production indisponible",
                    "Aucun modèle actif en production n’est actuellement disponible.",
                    "PRODUCTION"
            ));
        }

        return alerts;
    }

    private UserMonitoringDashboardResponse.AlertItem buildAlert(
            String severity,
            String title,
            String message,
            String source
    ) {
        UserMonitoringDashboardResponse.AlertItem item =
                new UserMonitoringDashboardResponse.AlertItem();

        item.severity = severity;
        item.title = title;
        item.message = message;
        item.source = source;

        return item;
    }

    private int calculateProjectHealthScore(
            UserMonitoringDashboardResponse.ProjectDashboard dashboard,
            List<ModelDeployment> deployments
    ) {
        int score = 100;

        if (dashboard.failedRuns > 0) {
            score -= 15;
        }

        boolean hasFailedDeployment = deployments.stream()
                .anyMatch(dep -> dep.getDeploymentStatus() == DeploymentStatus.FAILED);

        if (hasFailedDeployment) {
            score -= 15;
        }

        boolean hasProduction = deployments.stream()
                .anyMatch(dep -> dep.getDeploymentStatus() == DeploymentStatus.DEPLOYED && Boolean.TRUE.equals(dep.getActive()));

        if (!hasProduction) {
            score -= 15;
        }

        if (dashboard.predictionErrorRatePercent >= 20.0) {
            score -= 20;
        }

        if (dashboard.dataDriftScorePercent >= 25.0) {
            score -= 15;
        }

        if (!dashboard.alerts.isEmpty()) {
            score -= Math.min(20, dashboard.alerts.size() * 5);
        }

        return Math.max(0, Math.min(100, score));
    }

    private List<UserMonitoringDashboardResponse.ActivityItem> buildActivities(
            List<MlExperiment> experiments,
            List<ModelDeployment> deployments
    ) {
        List<ActivityWrapper> activities = new ArrayList<>();

        for (MlExperiment experiment : experiments.stream().limit(6).toList()) {
            String description = "Run " + safeText(experiment.getExperimentName());

            if (experiment.getGlobalScore() != null) {
                description += " • score global " + roundTwo(experiment.getGlobalScore()) + "%";
            }

            activities.add(new ActivityWrapper(
                    experiment.getCreatedAt(),
                    "Expérience",
                    description,
                    experiment.getStatus() != null ? experiment.getStatus().name() : "UNKNOWN"
            ));
        }

        for (ModelDeployment deployment : deployments.stream().limit(6).toList()) {
            activities.add(new ActivityWrapper(
                    deployment.getCreatedAt(),
                    "Déploiement",
                    "Version " + safeText(deployment.getModelVersion())
                            + " • " + safeText(deployment.getRegisteredModelName()),
                    deployment.getDeploymentStatus() != null
                            ? deployment.getDeploymentStatus().name()
                            : "UNKNOWN"
            ));
        }

        return activities.stream()
                .sorted(Comparator.comparing(ActivityWrapper::date, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(10)
                .map(wrapper -> {
                    UserMonitoringDashboardResponse.ActivityItem item =
                            new UserMonitoringDashboardResponse.ActivityItem();

                    item.date = wrapper.date();
                    item.title = wrapper.title();
                    item.description = wrapper.description();
                    item.status = wrapper.status();

                    return item;
                })
                .toList();
    }

    private User getUserFromAuthorizationHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            throw new RuntimeException("Invalid or expired token");
        }

        String email = jwtService.extractEmail(token);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private double calculateRate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return roundTwo((numerator * 100.0) / denominator);
    }

    private String safeText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String formatMetricName(String metricName) {
        if (!hasText(metricName)) {
            return "-";
        }

        return switch (metricName.trim().toLowerCase()) {
            case "f1_score", "f1" -> "F1 score";
            case "accuracy" -> "Accuracy";
            case "precision" -> "Precision";
            case "recall" -> "Recall";
            case "auc" -> "AUC";
            case "loss" -> "Loss";
            case "rmse" -> "RMSE";
            case "mae" -> "MAE";
            case "mse" -> "MSE";
            case "r2", "r2_score" -> "R²";
            default -> metricName;
        };
    }

    private double roundTwo(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private long safeLong(Number value, long fallback) {
        return value == null ? fallback : value.longValue();
    }

    private double safeDouble(Number value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private record ActivityWrapper(
            LocalDateTime date,
            String title,
            String description,
            String status
    ) {
    }
}