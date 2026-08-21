package com.mlops.platform.service;

import com.mlops.platform.dto.DashboardSummaryResponse;
import com.mlops.platform.dto.ModelRegistryResponse;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agrège la vue d'ensemble temps réel du tableau de bord.
 *
 * Toutes les données sont scopées au propriétaire des projets
 * (aucune fuite inter-utilisateurs). Le statut plateforme est
 * calculé via un contrôle DB simple + latence mesurée.
 */
@Service
public class DashboardService {

    private final MlProjectRepository projectRepository;
    private final MlExperimentRepository experimentRepository;
    private final ModelDeploymentRepository deploymentRepository;
    private final ModelRegistryService modelRegistryService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public DashboardService(
            MlProjectRepository projectRepository,
            MlExperimentRepository experimentRepository,
            ModelDeploymentRepository deploymentRepository,
            ModelRegistryService modelRegistryService,
            UserRepository userRepository,
            JwtService jwtService
    ) {
        this.projectRepository = projectRepository;
        this.experimentRepository = experimentRepository;
        this.deploymentRepository = deploymentRepository;
        this.modelRegistryService = modelRegistryService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public DashboardSummaryResponse getSummary(String authHeader) {
        User user = getUserFromAuthorizationHeader(authHeader);
        LocalDateTime started = LocalDateTime.now();

        List<MlProject> projects = projectRepository.findByOwnerOrderByCreatedAtDesc(user);
        List<MlExperiment> experiments =
                experimentRepository.findByProjectOwnerOrderByCreatedAtDesc(user);

        List<Long> projectIds = projects.stream().map(MlProject::getId).toList();
        List<ModelDeployment> deployments = projectIds.isEmpty()
                ? List.of()
                : deploymentRepository.findByProjectIdInOrderByCreatedAtDesc(projectIds);

        DashboardSummaryResponse response = new DashboardSummaryResponse();
        response.setEvaluatedAt(started);

        fillProjectAndRunStats(response, projects, experiments);
        fillDeploymentStats(response, deployments);
        fillModelStats(response, authHeader, experiments);
        fillRecentRuns(response, experiments);
        fillBestModels(response, authHeader);
        fillPlatformStatus(response, started);

        return response;
    }

    private void fillProjectAndRunStats(
            DashboardSummaryResponse response,
            List<MlProject> projects,
            List<MlExperiment> experiments
    ) {
        long runningProjects = projects.stream()
                .filter(project -> experiments.stream()
                        .anyMatch(e -> project.getId().equals(e.getProject().getId())
                                && e.getStatus() == ExperimentStatus.RUNNING))
                .count();

        long running = experiments.stream()
                .filter(e -> e.getStatus() == ExperimentStatus.RUNNING)
                .count();
        long completed = experiments.stream()
                .filter(e -> e.getStatus() == ExperimentStatus.COMPLETED)
                .count();
        long failed = experiments.stream()
                .filter(e -> e.getStatus() == ExperimentStatus.FAILED)
                .count();
        long cancelled = experiments.stream()
                .filter(e -> e.getStatus() == ExperimentStatus.CANCELLED)
                .count();

        long settled = completed + failed;
        double successRate = settled == 0 ? 0 : Math.round((completed * 1000.0) / settled) / 10.0;

        response.setProjectsTotal(projects.size());
        response.setRunningProjects(runningProjects);
        response.setRunsTotal(experiments.size());
        response.setRunsRunning(running);
        response.setRunsCompleted(completed);
        response.setRunsFailed(failed);
        response.setRunsCancelled(cancelled);
        response.setRunSuccessRate(successRate);
    }

    private void fillDeploymentStats(
            DashboardSummaryResponse response,
            List<ModelDeployment> deployments
    ) {
        long deployed = deployments.stream()
                .filter(d -> d.getDeploymentStatus() == DeploymentStatus.DEPLOYED)
                .count();
        long inProgress = deployments.stream()
                .filter(d -> d.getDeploymentStatus() == DeploymentStatus.PENDING
                        || d.getDeploymentStatus() == DeploymentStatus.DEPLOYING)
                .count();
        long failed = deployments.stream()
                .filter(d -> d.getDeploymentStatus() == DeploymentStatus.FAILED)
                .count();
        long active = deployments.stream()
                .filter(d -> Boolean.TRUE.equals(d.getActive()))
                .count();

        response.setDeploymentsTotal(deployments.size());
        response.setDeploymentsDeployed(deployed);
        response.setDeploymentsInProgress(inProgress);
        response.setDeploymentsFailed(failed);
        response.setActiveDeployments(active);
    }

    private void fillModelStats(
            DashboardSummaryResponse response,
            String authHeader,
            List<MlExperiment> experiments
    ) {
        Set<String> registeredNames = experiments.stream()
                .map(MlExperiment::getRegisteredModelName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());

        long withProduction = experiments.stream()
                .filter(e -> Boolean.TRUE.equals(e.getProduction()))
                .map(MlExperiment::getRegisteredModelName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .count();

        response.setModelsTotal(registeredNames.size());
        response.setModelsWithProduction(withProduction);
    }

    private void fillRecentRuns(
            DashboardSummaryResponse response,
            List<MlExperiment> experiments
    ) {
        List<MlExperiment> recent = experiments.stream()
                .sorted(Comparator
                        .comparing(
                                MlExperiment::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .limit(8)
                .toList();

        List<DashboardSummaryResponse.RecentRun> recentRuns = new ArrayList<>();
        for (MlExperiment experiment : recent) {
            DashboardSummaryResponse.RecentRun run = new DashboardSummaryResponse.RecentRun();
            run.setExperimentId(experiment.getId());
            run.setExperimentName(experiment.getExperimentName());
            run.setProjectName(experiment.getProject() != null
                    ? experiment.getProject().getName()
                    : null);
            run.setStatus(experiment.getStatus() != null
                    ? experiment.getStatus().name()
                    : null);
            run.setModelName(experiment.getModelName());
            run.setPrimaryMetric(experiment.getPrimaryMetric());
            run.setPrimaryMetricValue(experiment.getPrimaryMetricValue());
            run.setRegisteredModelName(experiment.getRegisteredModelName());
            run.setCreatedAt(experiment.getCreatedAt() != null
                    ? experiment.getCreatedAt().toString()
                    : null);
            run.setFinishedAt(experiment.getFinishedAt() != null
                    ? experiment.getFinishedAt().toString()
                    : null);
            recentRuns.add(run);
        }

        response.setRecentRuns(recentRuns);
    }

    private void fillBestModels(DashboardSummaryResponse response, String authHeader) {
        List<ModelRegistryResponse> models;
        try {
            models = modelRegistryService.getMyRegisteredModels(authHeader);
        } catch (Exception ignored) {
            models = List.of();
        }

        List<DashboardSummaryResponse.BestModel> bestModels = models.stream()
                .filter(model -> model.getGlobalScore() != null)
                .sorted(Comparator.comparing(ModelRegistryResponse::getGlobalScore).reversed())
                .limit(5)
                .map(model -> {
                    DashboardSummaryResponse.BestModel best =
                            new DashboardSummaryResponse.BestModel();
                    best.setRegisteredModelName(model.getRegisteredModelName());
                    best.setModelName(model.getModelName());
                    best.setProjectName(model.getProjectName());
                    best.setGlobalScore(model.getGlobalScore());
                    best.setPrimaryMetric(model.getPrimaryMetric());
                    best.setPrimaryMetricValue(model.getPrimaryMetricValue());
                    best.setProductionVersion(model.getProductionVersion());
                    best.setDeploymentStatus(model.getDeploymentStatus());
                    best.setHasProduction(model.getHasProduction());
                    return best;
                })
                .toList();

        response.setBestModels(bestModels);
    }

    private void fillPlatformStatus(
            DashboardSummaryResponse response,
            LocalDateTime started
    ) {
        DashboardSummaryResponse.PlatformStatus status =
                new DashboardSummaryResponse.PlatformStatus();

        boolean dbUp = isDatabaseUp();
        status.setBackend("UP");
        status.setDatabase(dbUp ? "UP" : "DOWN");
        status.setLatencyMs(Duration.between(started, LocalDateTime.now()).toMillis());

        response.setPlatformStatus(status);
    }

    private boolean isDatabaseUp() {
        try {
            projectRepository.count();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private User getUserFromAuthorizationHeader(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new IllegalArgumentException("En-tête Authorization manquant");
        }

        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;

        if (!jwtService.isTokenValid(token)) {
            throw new IllegalArgumentException("Token invalide");
        }

        String email = jwtService.extractEmail(token);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Utilisateur introuvable pour le token fourni"));
    }
}
