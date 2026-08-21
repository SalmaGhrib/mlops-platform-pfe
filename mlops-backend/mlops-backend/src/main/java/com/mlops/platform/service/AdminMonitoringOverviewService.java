package com.mlops.platform.service;

import com.mlops.platform.dto.AdminMonitoringOverviewResponse;
import com.mlops.platform.dto.MonitoringAlertResponse;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Vue globale d'administration du monitoring applicatif (modèle métier).
 *
 * Agrège les indicateurs de santé des modèles à partir de la base MySQL :
 *  - nombre de projets surveillés ;
 *  - modèles déployés / dégradés ;
 *  - modèles sous le seuil minimal de qualité ;
 *  - projets avec drift / erreurs de prédiction / baisse de performance ;
 *  - alertes récentes (24 h).
 */
@Service
public class AdminMonitoringOverviewService {

    private final MlProjectRepository mlProjectRepository;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final MonitoringAlertService monitoringAlertService;
    private final RetrainingDecisionService retrainingDecisionService;
    private final ModelQualityService modelQualityService;

    public AdminMonitoringOverviewService(
            MlProjectRepository mlProjectRepository,
            ModelDeploymentRepository modelDeploymentRepository,
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentRepository mlExperimentRepository,
            MonitoringAlertService monitoringAlertService,
            RetrainingDecisionService retrainingDecisionService,
            ModelQualityService modelQualityService
    ) {
        this.mlProjectRepository = mlProjectRepository;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.monitoringAlertService = monitoringAlertService;
        this.retrainingDecisionService = retrainingDecisionService;
        this.modelQualityService = modelQualityService;
    }

    @Transactional(readOnly = true, timeout = 10)
    public AdminMonitoringOverviewResponse getOverview() {
        AdminMonitoringOverviewResponse overview = new AdminMonitoringOverviewResponse();

        List<MlProject> allProjects = mlProjectRepository.findAll();
        List<ModelDeployment> allDeployments = modelDeploymentRepository.findAll();
        List<RetrainingConfig> enabledConfigs = retrainingConfigRepository.findEnabledConfigs();

        overview.totalProjects = allProjects.size();
        overview.totalDeployments = allDeployments.size();
        overview.monitoredProjects = enabledConfigs.size();

        overview.activeProductionModels = allDeployments.stream()
                .filter(dep -> dep != null && Boolean.TRUE.equals(dep.getActive()))
                .count();

        overview.degradedModels = allDeployments.stream()
                .filter(dep -> dep != null && (
                        com.mlops.platform.entity.DeploymentStatus.FAILED.equals(dep.getDeploymentStatus())
                                || com.mlops.platform.entity.DeploymentStatus.OUT_OF_SYNC.equals(dep.getDeploymentStatus())))
                .count();

        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        LocalDateTime dayAgo = now.minusHours(24);

        overview.criticalAlerts24h = monitoringAlertService.countAlertsBySeveritySince(
                AlertSeverity.CRITICAL,
                dayAgo
        );

        overview.totalAlerts24h = monitoringAlertService.countAlertsSince(dayAgo);

        List<MonitoringAlertResponse> recentAlerts = monitoringAlertService.getRecentAlerts(50);
        overview.recentAlerts = recentAlerts;

        List<AdminMonitoringOverviewResponse.ProjectHealthItem> projectItems = new ArrayList<>();
        long belowThresholdCount = 0;
        long driftProjects = 0;
        long errorProjects = 0;
        long dropProjects = 0;

        for (MlProject project : allProjects) {
            if (project == null || project.getId() == null) {
                continue;
            }

            AdminMonitoringOverviewResponse.ProjectHealthItem item =
                    new AdminMonitoringOverviewResponse.ProjectHealthItem();

            item.projectId = project.getId();
            item.projectName = project.getName();
            item.projectType = project.getProjectType();

            RetrainingConfig config = retrainingConfigRepository
                    .findByProjectId(project.getId())
                    .orElse(null);

            item.retrainingEnabled = config != null && config.isEnabled();

            MlExperiment production = mlExperimentRepository
                    .findCurrentProductionExperimentForProject(project.getId())
                    .orElse(null);

            if (production != null) {
                item.registeredModelName = production.getRegisteredModelName();
                item.modelVersion = production.getModelVersion();
            }

            ModelQualityService.QualityEvaluation quality =
                    modelQualityService.evaluateCandidate(project);

            if (quality != null) {
                item.globalScore = quality.globalScore();
                item.globalScoreThreshold = quality.threshold();
                item.qualityStatus = quality.threshold() == null
                        ? "NOT_CONFIGURED"
                        : (quality.conforming() ? "CONFORMING" : "INSUFFICIENT");
                if (!quality.conforming() && quality.threshold() != null) {
                    belowThresholdCount++;
                }
            }

            if (config != null) {
                RetrainingDecisionResult decision =
                        retrainingDecisionService.evaluateMonitoringSignals(config);

                item.predictionErrorRatePercent = decision.getPredictionErrorRatePercent();
                item.dataDriftScorePercent = decision.getDataDriftScorePercent();
                item.performanceDropPercent = decision.getPerformanceDropPercent();

                if (decision.getDataDriftScorePercent() >= safeDouble(
                        config.getDataDriftThresholdPercent(), 25.0)) {
                    driftProjects++;
                }
                if (decision.getPredictionErrorRatePercent() >= safeDouble(
                        config.getMaxPredictionErrorRatePercent(), 20.0)) {
                    errorProjects++;
                }
                if (decision.getPerformanceDropPercent() >= safeDouble(
                        config.getPerformanceDropThresholdPercent(), 5.0)) {
                    dropProjects++;
                }
            }

            List<MonitoringAlertResponse> projectAlerts =
                    monitoringAlertService.getRecentAlertsByProject(project.getId());

            if (!projectAlerts.isEmpty()) {
                MonitoringAlertResponse latest = projectAlerts.get(0);
                item.latestAlertType = latest.getAlertType() != null
                        ? latest.getAlertType().name()
                        : null;
                item.latestAlertSeverity = latest.getSeverity();
                item.latestAlertMessage = latest.getMessage();
                item.latestAlertAt = parseUtcIso(latest.getCreatedAt());
            }

            projectItems.add(item);
        }

        overview.projects = projectItems.stream()
                .sorted(Comparator.comparing(
                        (AdminMonitoringOverviewResponse.ProjectHealthItem p) -> p.latestAlertAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).reversed())
                .toList();

        overview.modelsBelowQualityThreshold = belowThresholdCount;
        overview.projectsWithDataDrift = driftProjects;
        overview.projectsWithPredictionErrors = errorProjects;
        overview.projectsWithPerformanceDrop = dropProjects;

        return overview;
    }

    private double safeDouble(Double value, double fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private LocalDateTime parseUtcIso(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }

        try {
            String trimmed = iso.trim();
            if (trimmed.endsWith("Z")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return LocalDateTime.parse(trimmed.substring(0, 19));
        } catch (Exception ignored) {
            return null;
        }
    }
}