package com.mlops.platform.service;

import com.mlops.platform.dto.MlflowModelVersionDto;
import com.mlops.platform.dto.ModelConsistencyResponse;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ModelConsistencyService {

    private final MlExperimentRepository experimentRepository;
    private final ModelDeploymentRepository deploymentRepository;
    private final MlflowTrackingClientService mlflowClient;
    private final ModelDeploymentService deploymentService;

    public ModelConsistencyService(
            MlExperimentRepository experimentRepository,
            ModelDeploymentRepository deploymentRepository,
            MlflowTrackingClientService mlflowClient,
            ModelDeploymentService deploymentService
    ) {
        this.experimentRepository = experimentRepository;
        this.deploymentRepository = deploymentRepository;
        this.mlflowClient = mlflowClient;
        this.deploymentService = deploymentService;
    }

    @Scheduled(
            fixedDelayString = "${app.mlops.consistency.delay-ms:120000}",
            initialDelayString = "${app.mlops.consistency.initial-delay-ms:60000}"
    )
    @SchedulerLock(
            name = "modelVersionConsistencyReconciler",
            lockAtMostFor = "20m",
            lockAtLeastFor = "30s"
    )
    public void reconcileAllModelsScheduled() {
        reconcileAllModels();
    }

    @Transactional
    public List<ModelConsistencyResponse> reconcileAllModels() {
        Set<String> modelNames = new LinkedHashSet<>();

        for (MlExperiment experiment : experimentRepository.findAll()) {
            if (hasText(experiment.getRegisteredModelName())) {
                modelNames.add(experiment.getRegisteredModelName().trim());
            }
        }

        for (ModelDeployment deployment : deploymentRepository.findAll()) {
            if (hasText(deployment.getRegisteredModelName())) {
                modelNames.add(deployment.getRegisteredModelName().trim());
            }
        }

        List<ModelConsistencyResponse> responses = new ArrayList<>();
        for (String modelName : modelNames) {
            responses.add(reconcileModel(modelName));
        }
        return responses;
    }

    @Transactional
    public ModelConsistencyResponse reconcileModel(String registeredModelName) {
        if (!hasText(registeredModelName)) {
            throw new IllegalArgumentException("registeredModelName is required");
        }

        String modelName = registeredModelName.trim();
        String productionVersion = clean(mlflowClient.getProductionVersion(modelName));
        List<MlflowModelVersionDto> registryVersions = mlflowClient.getAllModelVersionsWithMetrics(modelName);

        Map<String, String> versionByRunId = new LinkedHashMap<>();
        if (registryVersions != null) {
            for (MlflowModelVersionDto version : registryVersions) {
                if (version != null && hasText(version.getRunId()) && hasText(version.getVersion())) {
                    versionByRunId.put(version.getRunId().trim(), version.getVersion().trim());
                }
            }
        }

        List<MlExperiment> experiments = experimentRepository
                .findByRegisteredModelNameOrderByCreatedAtDesc(modelName);

        MlExperiment productionExperiment = null;
        for (MlExperiment experiment : experiments) {
            if (experiment == null) continue;

            String exactVersion = hasText(experiment.getMlflowRunId())
                    ? versionByRunId.get(experiment.getMlflowRunId().trim())
                    : null;

            if (hasText(exactVersion)) {
                experiment.setModelVersion(exactVersion);
            }

            experiment.setProductionVersion(productionVersion);
            boolean exactProductionRun = hasText(productionVersion)
                    && hasText(experiment.getModelVersion())
                    && productionVersion.equals(experiment.getModelVersion())
                    && hasText(experiment.getMlflowRunId())
                    && productionVersion.equals(versionByRunId.get(experiment.getMlflowRunId().trim()));

            experiment.setProduction(false);
            if (exactProductionRun && productionExperiment == null) {
                productionExperiment = experiment;
            }
        }

        if (productionExperiment != null) {
            productionExperiment.setProduction(true);
        }
        experimentRepository.saveAll(experiments);
        experimentRepository.flush();

        List<ModelDeployment> deployments = deploymentRepository
                .findByRegisteredModelNameOrderByCreatedAtDesc(modelName);

        ModelDeployment activeReady = null;
        for (ModelDeployment deployment : deployments) {
            if (deployment == null) continue;

            boolean ready = deploymentService.isDeploymentReallyReadyInKubernetes(deployment);
            boolean isProductionVersion = hasText(productionVersion)
                    && productionVersion.equals(clean(deployment.getModelVersion()));

            deployment.setActive(false);

            if (ready && isProductionVersion && activeReady == null) {
                deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                deployment.setActive(true);
                deployment.setErrorMessage(null);
                activeReady = deployment;
            } else if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus()) && !ready) {
                deployment.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
                deployment.setErrorMessage("MySQL said DEPLOYED, but Kubernetes Deployment/Service is not ready.");
            }
        }
        deploymentRepository.saveAll(deployments);
        deploymentRepository.flush();

        return buildAudit(modelName, productionVersion, experiments, deployments, activeReady);
    }

    @Transactional(readOnly = true)
    public ModelConsistencyResponse auditModel(String registeredModelName) {
        String modelName = registeredModelName == null ? "" : registeredModelName.trim();
        String productionVersion = clean(mlflowClient.getProductionVersion(modelName));
        List<MlExperiment> experiments = experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);
        List<ModelDeployment> deployments = deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);
        ModelDeployment activeReady = deployments.stream()
                .filter(d -> Boolean.TRUE.equals(d.getActive()))
                .filter(d -> DeploymentStatus.DEPLOYED.equals(d.getDeploymentStatus()))
                .filter(deploymentService::isDeploymentReallyReadyInKubernetes)
                .findFirst()
                .orElse(null);
        return buildAudit(modelName, productionVersion, experiments, deployments, activeReady);
    }

    private ModelConsistencyResponse buildAudit(
            String modelName,
            String productionVersion,
            List<MlExperiment> experiments,
            List<ModelDeployment> deployments,
            ModelDeployment activeReady
    ) {
        ModelConsistencyResponse response = new ModelConsistencyResponse();
        response.setRegisteredModelName(modelName);
        response.setMlflowProductionVersion(productionVersion);

        String dbProductionVersion = experiments.stream()
                .filter(e -> Boolean.TRUE.equals(e.getProduction()))
                .map(MlExperiment::getModelVersion)
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
        response.setDatabaseProductionVersion(dbProductionVersion);

        if (activeReady != null) {
            response.setActiveDeploymentVersion(activeReady.getModelVersion());
            response.setActiveDockerImage(activeReady.getDockerImage());
            response.setKubernetesReady(true);
        }

        List<String> problems = new ArrayList<>();
        long productionRows = experiments.stream().filter(e -> Boolean.TRUE.equals(e.getProduction())).count();
        long activeRows = deployments.stream().filter(d -> Boolean.TRUE.equals(d.getActive())).count();

        if (!hasText(productionVersion)) problems.add("MLflow has no @production alias.");
        if (productionRows != 1) problems.add("MySQL must contain exactly one production experiment, found " + productionRows + ".");
        if (activeRows != 1) problems.add("MySQL must contain exactly one active deployment, found " + activeRows + ".");
        if (activeReady == null) problems.add("No active DEPLOYED deployment is really ready in Kubernetes.");
        if (hasText(productionVersion) && hasText(dbProductionVersion) && !productionVersion.equals(dbProductionVersion)) {
            problems.add("MLflow production version and experiment production version differ.");
        }
        if (hasText(productionVersion) && activeReady != null && !productionVersion.equals(clean(activeReady.getModelVersion()))) {
            problems.add("MLflow production version and active Kubernetes deployment version differ.");
        }

        response.setProblems(problems);
        response.setConsistent(problems.isEmpty());
        return response;
    }

    private String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
