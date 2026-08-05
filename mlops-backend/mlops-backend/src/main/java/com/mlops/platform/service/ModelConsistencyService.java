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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Réconciliation de cohérence des modèles (MySQL vs MLflow vs Kubernetes).
 *
 * Architecture corrigée (plus de timeout transactionnel) :
 *  - Phase A : lectures courtes (repositories, aucune transaction longue) ;
 *  - Phase B : appels externes (MLflow, Kubernetes) HORS transaction ;
 *  - Phase C : une transaction REQUIRES_NEW courte et bornée PAR MODÈLE
 *    pour écrire uniquement les lignes réellement modifiées.
 *
 * Aucun @Transactional ne couvre plus des appels réseau : chaque modèle
 * est isolé, un échec n'empêche pas les autres.
 */
@Service
public class ModelConsistencyService {

    private final MlExperimentRepository experimentRepository;
    private final ModelDeploymentRepository deploymentRepository;
    private final MlflowTrackingClientService mlflowClient;
    private final ModelDeploymentService deploymentService;
    private final PlatformTransactionManager transactionManager;

    public ModelConsistencyService(
            MlExperimentRepository experimentRepository,
            ModelDeploymentRepository deploymentRepository,
            MlflowTrackingClientService mlflowClient,
            ModelDeploymentService deploymentService,
            PlatformTransactionManager transactionManager
    ) {
        this.experimentRepository = experimentRepository;
        this.deploymentRepository = deploymentRepository;
        this.mlflowClient = mlflowClient;
        this.deploymentService = deploymentService;
        this.transactionManager = transactionManager;
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
        long start = System.currentTimeMillis();
        System.out.println("[CONSISTENCY] ====================================");
        System.out.println("[CONSISTENCY] Starting model consistency reconcile");

        List<ModelConsistencyResponse> responses = reconcileAllModels();

        System.out.println("[CONSISTENCY] Finished. models=" + responses.size()
                + ", durationMs=" + (System.currentTimeMillis() - start));
        System.out.println("[CONSISTENCY] ====================================");
    }

    /**
     * Déclencheur public (endpoint / controllers) du reconcile global.
     */
    public List<ModelConsistencyResponse> reconcileAllModels() {
        Set<String> modelNames = new LinkedHashSet<>();

        for (String name : experimentRepository.findDistinctRegisteredModelNames()) {
            if (hasText(name)) {
                modelNames.add(name.trim());
            }
        }

        for (String name : deploymentRepository.findDistinctRegisteredModelNames()) {
            if (hasText(name)) {
                modelNames.add(name.trim());
            }
        }

        List<ModelConsistencyResponse> responses = new ArrayList<>();
        for (String modelName : modelNames) {
            try {
                responses.add(reconcileModel(modelName));
            } catch (Exception e) {
                System.out.println("[CONSISTENCY] reconcileModel failed. model=" + modelName
                        + ", reason=" + e.getMessage());
            }
        }
        return responses;
    }

    public ModelConsistencyResponse reconcileModel(String registeredModelName) {
        if (!hasText(registeredModelName)) {
            throw new IllegalArgumentException("registeredModelName is required");
        }

        String modelName = registeredModelName.trim();
        long start = System.currentTimeMillis();
        System.out.println("[CONSISTENCY] Reconcile model=" + modelName);

        /*
         * Phase A : lectures courtes (aucune transaction ouverte sur la
         * durée des appels réseau).
         */
        List<MlExperiment> experiments =
                experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);
        List<ModelDeployment> deployments =
                deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);

        /*
         * Phase B : appels MLflow et Kubernetes, HORS transaction.
         */
        String productionVersion = clean(mlflowClient.getProductionVersion(modelName));
        List<MlflowModelVersionDto> registryVersions =
                mlflowClient.getAllModelVersionsWithMetrics(modelName);
        Map<String, String> versionByRunId = buildVersionByRunId(registryVersions);

        Map<Long, Boolean> deploymentReady = new LinkedHashMap<>();
        if (deployments != null) {
            for (ModelDeployment deployment : deployments) {
                if (deployment != null && deployment.getId() != null) {
                    deploymentReady.put(
                            deployment.getId(),
                            deploymentService.isDeploymentReallyReadyInKubernetes(deployment)
                    );
                }
            }
        }

        /*
         * Phase C : écriture courte par modèle dans une transaction
         * REQUIRES_NEW bornée (30 s), séparée des appels réseau.
         */
        ModelConsistencyResponse response =
                applyReconcileInNewTransaction(modelName, productionVersion, versionByRunId, deploymentReady);

        System.out.println("[CONSISTENCY] Reconcile model=" + modelName
                + ", durationMs=" + (System.currentTimeMillis() - start)
                + ", consistent=" + response.isConsistent());
        return response;
    }

    private Map<String, String> buildVersionByRunId(
            List<MlflowModelVersionDto> registryVersions
    ) {
        Map<String, String> versionByRunId = new LinkedHashMap<>();
        if (registryVersions == null) {
            return versionByRunId;
        }

        for (MlflowModelVersionDto version : registryVersions) {
            if (version != null
                    && hasText(version.getRunId())
                    && hasText(version.getVersion())) {
                versionByRunId.put(version.getRunId().trim(), version.getVersion().trim());
            }
        }
        return versionByRunId;
    }

    private ModelConsistencyResponse applyReconcileInNewTransaction(
            String modelName,
            String productionVersion,
            Map<String, String> versionByRunId,
            Map<Long, Boolean> deploymentReady
    ) {
        final ModelConsistencyResponse[] responseHolder = new ModelConsistencyResponse[1];

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);

        template.executeWithoutResult(status -> {
            List<MlExperiment> experiments =
                    experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);

            MlExperiment productionExperiment = null;

            for (MlExperiment experiment : experiments) {
                if (experiment == null) {
                    continue;
                }

                String exactVersion = hasText(experiment.getMlflowRunId())
                        ? versionByRunId.get(experiment.getMlflowRunId().trim())
                        : null;

                if (hasText(exactVersion)) {
                    experiment.setModelVersion(exactVersion);
                }

                boolean exactProductionRun = hasText(productionVersion)
                        && hasText(experiment.getModelVersion())
                        && productionVersion.equals(experiment.getModelVersion())
                        && hasText(experiment.getMlflowRunId())
                        && productionVersion.equals(versionByRunId.get(experiment.getMlflowRunId().trim()));

                if (exactProductionRun && productionExperiment == null) {
                    productionExperiment = experiment;
                }
            }

            for (MlExperiment experiment : experiments) {
                if (experiment == null) {
                    continue;
                }

                experiment.setProductionVersion(productionVersion);
                experiment.setProduction(experiment == productionExperiment);
            }
            experimentRepository.saveAll(experiments);
            experimentRepository.flush();

            List<ModelDeployment> deployments =
                    deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(modelName);

            ModelDeployment activeReady = null;

            for (ModelDeployment deployment : deployments) {
                if (deployment == null) {
                    continue;
                }

                boolean ready = Boolean.TRUE.equals(deploymentReady.get(deployment.getId()));
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

            responseHolder[0] = buildAudit(modelName, productionVersion, experiments, deployments, activeReady);
        });

        return responseHolder[0];
    }

    /**
     * Audit en lecture pure : aucune transaction longue ne couvre les appels
     * Kubernetes. Les repositories ouvrent leurs propres courtes lectures.
     */
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
