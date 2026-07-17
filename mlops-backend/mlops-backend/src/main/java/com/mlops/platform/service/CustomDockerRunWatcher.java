package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CustomDockerRunWatcher {

    private final MlExperimentRepository mlExperimentRepository;
    private final MlExperimentMetricRepository metricRepository;
    private final KubernetesBuildService kubernetesBuildService;
    private final KubernetesJobService kubernetesJobService;
    private final MlExperimentService mlExperimentService;
    private final ModelArtifactResolver modelArtifactResolver;
    private final ModelArtifactStandardizerService modelArtifactStandardizerService;
    private final TrainingOutputAnalyzerService trainingOutputAnalyzerService;

    @Value("${app.custom-docker.watcher.enabled:true}")
    private boolean watcherEnabled;

    @Value("${mlflow.tracking.uri:http://mlflow-service.mlops.svc.cluster.local:5000}")
    private String localMlflowTrackingUri;

    public CustomDockerRunWatcher(
            MlExperimentRepository mlExperimentRepository,
            MlExperimentMetricRepository metricRepository,
            KubernetesBuildService kubernetesBuildService,
            KubernetesJobService kubernetesJobService,
            MlExperimentService mlExperimentService,
            ModelArtifactResolver modelArtifactResolver,
            ModelArtifactStandardizerService modelArtifactStandardizerService,
            TrainingOutputAnalyzerService trainingOutputAnalyzerService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.metricRepository = metricRepository;
        this.kubernetesBuildService = kubernetesBuildService;
        this.kubernetesJobService = kubernetesJobService;
        this.mlExperimentService = mlExperimentService;
        this.modelArtifactResolver = modelArtifactResolver;
        this.modelArtifactStandardizerService = modelArtifactStandardizerService;
        this.trainingOutputAnalyzerService = trainingOutputAnalyzerService;
    }

    @Scheduled(
            fixedDelayString = "${app.custom-docker.watcher.delay-ms:10000}",
            initialDelayString = "${app.custom-docker.watcher.initial-delay-ms:10000}"
    )
    @SchedulerLock(
            name = "customDockerRunWatcher",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT2S"
    )
    public void watchCustomDockerRuns() {
        if (!watcherEnabled) {
            return;
        }

        List<MlExperiment> experiments =
                mlExperimentRepository.findActionableCustomDockerExperiments(
                        "CUSTOM_DOCKERFILE",
                        List.of(ExperimentStatus.CREATED, ExperimentStatus.RUNNING)
                );

        if (experiments == null || experiments.isEmpty()) {
            return;
        }

        System.out.println("[CUSTOM-DOCKER] Found " + experiments.size()
                + " actionable CUSTOM_DOCKERFILE experiments.");

        for (MlExperiment experiment : experiments) {
            if (experiment == null || experiment.getId() == null) {
                continue;
            }

            try {
                // Relecture courte : évite de traiter un objet devenu terminal entre la requête
                // initiale et son traitement dans la boucle.
                MlExperiment freshExperiment = mlExperimentRepository
                        .findByIdWithFiles(experiment.getId())
                        .orElse(null);

                if (freshExperiment == null || isTerminalStatus(freshExperiment.getStatus())) {
                    continue;
                }

                processExperiment(freshExperiment);
            } catch (Exception e) {
                System.out.println("[CUSTOM-DOCKER] Error while processing experiment id="
                        + safeExperimentId(experiment)
                        + " : "
                        + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void processExperiment(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        if (!isCustomDockerExperiment(experiment)) {
            return;
        }

        if (isTerminalStatus(experiment.getStatus())) {
            return;
        }

        if (!hasText(experiment.getCustomImageUri()) && !hasText(experiment.getDockerfilePath())) {
            markFailed(
                    experiment,
                    "VALIDATION",
                    "Dockerfile or custom image is required for CUSTOM_DOCKERFILE."
            );
            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " failed validation: missing Dockerfile and custom image.");
            return;
        }

        String buildStatus = normalize(experiment.getBuildStatus());

        if (hasText(experiment.getCustomImageUri())
                && !hasText(experiment.getBuildJobName())
                && !hasText(experiment.getTrainingJobName())
                && isWaitingOrEmptyBuildStatus(buildStatus)) {
            launchTrainingJob(experiment);
            return;
        }

        if (isWaitingOrEmptyBuildStatus(buildStatus)) {
            launchBuildJob(experiment);
            return;
        }

        if (isBuildRunningStatus(buildStatus)) {
            syncBuildJob(experiment);
            return;
        }

        if (isImageBuiltStatus(buildStatus)) {
            launchTrainingJob(experiment);
            return;
        }

        if (isTrainingRunningStatus(buildStatus)) {
            syncTrainingJob(experiment);
            return;
        }

        if ("CUSTOM_IMAGE_JOB_COMPLETED".equals(buildStatus)
                || "TRAINING_COMPLETED".equals(buildStatus)) {
            experiment.setStatus(ExperimentStatus.COMPLETED);
            experiment.setMessage("Custom Docker training already completed.");
            mlExperimentRepository.saveAndFlush(experiment);

            tryStandardizeModelArtifactIfPossible(experiment, "already-completed-build-status");
            return;
        }

        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setMessage("Custom Docker watcher: unknown buildStatus=" + buildStatus);
        mlExperimentRepository.saveAndFlush(experiment);
    }

    private void launchBuildJob(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        MlExperiment freshExperiment = mlExperimentRepository
                .findByIdWithFiles(experiment.getId())
                .orElse(null);

        if (freshExperiment == null || isTerminalStatus(freshExperiment.getStatus())) {
            return;
        }

        // Idempotence : si un autre passage a déjà enregistré le Job Kaniko,
        // on ne crée jamais un second Job pour la même expérience.
        if (hasText(freshExperiment.getBuildJobName())) {
            freshExperiment.setBuildStatus("IMAGE_BUILD_RUNNING");
            freshExperiment.setStatus(ExperimentStatus.RUNNING);
            freshExperiment.setMessage("Image build job already exists. Watching existing build job: "
                    + freshExperiment.getBuildJobName());
            mlExperimentRepository.saveAndFlush(freshExperiment);
            return;
        }

        experiment = freshExperiment;
        MlProject project = experiment.getProject();

        if (project == null || project.getId() == null) {
            markFailed(experiment, "BUILD", "Project is null. Cannot launch custom Docker build.");
            return;
        }

        if (hasText(experiment.getCustomImageUri())) {
            experiment.setBuildStatus("IMAGE_BUILD_COMPLETED");
            experiment.setStatus(ExperimentStatus.RUNNING);
            experiment.setBuildLogs("Custom image URI already provided. Build step skipped.");
            experiment.setMessage("Custom image already available. Training job will be launched.");
            mlExperimentRepository.saveAndFlush(experiment);
            return;
        }

        if (!hasText(experiment.getDockerfilePath())) {
            markFailed(
                    experiment,
                    "VALIDATION",
                    "Dockerfile path is required to build custom Docker image."
            );
            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " cannot launch build: dockerfile_path is missing.");
            return;
        }

        try {
            KubernetesBuildService.BuildJobResult result =
                    kubernetesBuildService.launchImageBuildJob(project, experiment);

            experiment.setBuildJobName(result.getBuildJobName());
            experiment.setCustomImageUri(result.getImageUri());

            experiment.setBuildStatus("IMAGE_BUILD_RUNNING");
            experiment.setStatus(ExperimentStatus.RUNNING);
            experiment.setBuildLogs(
                    "Image build job launched: " + result.getBuildJobName()
                            + "\nGenerated image: " + result.getImageUri()
            );
            experiment.setMessage("Custom Docker image build started with Kaniko.");

            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " launched Kaniko build job=" + result.getBuildJobName()
                    + " image=" + result.getImageUri());

            mlExperimentRepository.saveAndFlush(experiment);

        } catch (Exception e) {
            String logs = safe(e.getMessage());

            if (isTransientPersistenceFailure(e)) {
                System.out.println("[CUSTOM-DOCKER] Temporary persistence error before Kaniko creation. "
                        + "The experiment remains actionable and will be retried. experimentId="
                        + experiment.getId()
                        + ", reason="
                        + logs);
                e.printStackTrace();
                return;
            }

            experiment.setBuildLogs(logs);
            markFailedWithAnalyzer(experiment, logs);
            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " failed to launch build job: " + logs);
        }
    }

    private void syncBuildJob(MlExperiment experiment) {
        String buildJobName = experiment.getBuildJobName();

        if (!hasText(buildJobName)) {
            markFailed(experiment, "BUILD", "Build job name is empty.");
            return;
        }

        String status = kubernetesBuildService.getBuildJobStatus(buildJobName);
        String logs = kubernetesBuildService.getBuildJobLogs(buildJobName);

        experiment.setBuildLogs(logs);

        if ("COMPLETED".equalsIgnoreCase(status)) {
            experiment.setBuildStatus("IMAGE_BUILD_COMPLETED");
            experiment.setStatus(ExperimentStatus.RUNNING);
            experiment.setMessage("Custom Docker image built successfully. Training job will be launched.");
            experiment.setErrorMessage(null);
            mlExperimentRepository.saveAndFlush(experiment);
            return;
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            experiment.setBuildStatus("IMAGE_BUILD_FAILED");
            experiment.setBuildLogs(logs);
            markFailedWithAnalyzer(experiment, logs);
            return;
        }

        if ("NOT_FOUND".equalsIgnoreCase(status)) {
            markFailed(
                    experiment,
                    "BUILD",
                    "Build job not found in Kubernetes: " + buildJobName
            );
            return;
        }

        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setBuildStatus("IMAGE_BUILD_RUNNING");
        experiment.setMessage("Custom Docker image build is still running. Status=" + status);
        mlExperimentRepository.saveAndFlush(experiment);
    }

    private void launchTrainingJob(MlExperiment experiment) {
        MlProject project = experiment.getProject();

        if (project == null || project.getId() == null) {
            markFailed(experiment, "TRAINING", "Project is null. Cannot launch training job.");
            return;
        }

        if (!hasText(experiment.getCustomImageUri())) {
            markFailed(experiment, "TRAINING", "Custom image URI is empty. Cannot launch training job.");
            return;
        }

        if (hasText(experiment.getTrainingJobName())) {
            experiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
            experiment.setStatus(ExperimentStatus.RUNNING);
            markDockerTrainingStartedIfNeeded(experiment);
            experiment.setMessage("Training job already exists. Watching existing job: "
                    + experiment.getTrainingJobName());
            mlExperimentRepository.saveAndFlush(experiment);
            return;
        }

        try {
            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " launching custom image training job with image=" + experiment.getCustomImageUri());

            String jobName = kubernetesJobService.launchCustomImageTrainingJob(
                    project,
                    experiment,
                    experiment.getCustomImageUri()
            );

            experiment.setTrainingJobName(jobName);
            experiment.setKubeflowRunName(jobName);

            experiment.setKubeflowStatus("RUNNING");
            experiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
            experiment.setStatus(ExperimentStatus.RUNNING);
            markDockerTrainingStartedIfNeeded(experiment);
            experiment.setMessage("Custom Docker training job launched: " + jobName);
            experiment.setErrorMessage(null);

            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " launched training job=" + jobName);

            mlExperimentRepository.saveAndFlush(experiment);

        } catch (Exception e) {
            String error = safe(e.getMessage());

            System.out.println("[CUSTOM-DOCKER] Experiment id=" + experiment.getId()
                    + " failed to launch custom image training job: " + error);

            experiment.setStatus(ExperimentStatus.FAILED);
            experiment.setBuildStatus("CUSTOM_IMAGE_JOB_FAILED");
            experiment.setErrorMessage(error);
            experiment.setMessage("Failed to launch custom Docker training job.");
            experiment.setTrainingLogs(error);

            mlExperimentRepository.saveAndFlush(experiment);
        }
    }

    private void syncTrainingJob(MlExperiment experiment) {
        String trainingJobName = experiment.getTrainingJobName();

        if (!hasText(trainingJobName) && hasText(experiment.getKubeflowRunName())) {
            trainingJobName = experiment.getKubeflowRunName();
            experiment.setTrainingJobName(trainingJobName);
        }

        if (!hasText(trainingJobName)) {
            markFailed(experiment, "TRAINING", "Training job name is empty.");
            return;
        }

        String status = kubernetesJobService.getCustomJobStatus(trainingJobName);
        String logs = kubernetesJobService.getCustomJobLogs(trainingJobName);

        experiment.setTrainingLogs(logs);

        if ("COMPLETED".equalsIgnoreCase(status)) {
            handleTrainingCompleted(experiment, logs);
            return;
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            experiment.setBuildStatus("CUSTOM_IMAGE_JOB_FAILED");
            experiment.setKubeflowStatus("FAILED");
            experiment.setTrainingLogs(logs);
            markFailedWithAnalyzer(experiment, logs);
            return;
        }

        if ("NOT_FOUND".equalsIgnoreCase(status)) {
            markFailed(
                    experiment,
                    "TRAINING",
                    "Training job not found in Kubernetes: " + trainingJobName
            );
            return;
        }

        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setKubeflowStatus(status);
        experiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
        experiment.setMessage("Custom Docker training job is still running. Status=" + status);
        mlExperimentRepository.saveAndFlush(experiment);
    }

    private void handleTrainingCompleted(MlExperiment experiment, String logs) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        experiment.setStatus(ExperimentStatus.COMPLETED);
        experiment.setKubeflowStatus("COMPLETED");
        experiment.setBuildStatus("CUSTOM_IMAGE_JOB_COMPLETED");
        markDockerTrainingFinishedIfNeeded(experiment);
        experiment.setMessage("Custom Docker training completed successfully.");
        experiment.setErrorMessage(null);

        TrainingOutputAnalyzerService.DetectedTrainingOutput analyzedOutput =
                trainingOutputAnalyzerService.analyze(logs);

        Map<String, Double> detectedMetrics = analyzedOutput != null
                ? analyzedOutput.getMetrics()
                : Map.of();

        System.out.println("[TRAINING_OUTPUT_ANALYZER] metrics=" + detectedMetrics);

        if (analyzedOutput != null) {
            System.out.println("[TRAINING_OUTPUT_ANALYZER] parameters=" + analyzedOutput.getParameters());
            System.out.println("[TRAINING_OUTPUT_ANALYZER] modelFiles=" + analyzedOutput.getModelFiles());
            System.out.println("[TRAINING_OUTPUT_ANALYZER] algorithms=" + analyzedOutput.getAlgorithms());
        }

        if (detectedMetrics != null && !detectedMetrics.isEmpty()) {
            updatePrimaryMetricAndGlobalScore(experiment, detectedMetrics);
            saveDetectedMetrics(experiment, detectedMetrics);

            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            "Metrics detected automatically."
                    )
            );
        } else {
            clearExperimentPerformanceMetrics(experiment);

            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            "No metrics detected in Kubernetes logs. MLflow synchronization may still import metrics later."
                    )
            );
        }

        try {
            ModelArtifactResolver.DetectedModelArtifact detectedArtifact =
                    modelArtifactResolver.resolveFromLogs(logs);

            System.out.println("[CUSTOM-DOCKER-WATCHER] Model artifact detection result for experimentId="
                    + experiment.getId()
                    + " => "
                    + detectedArtifact);

            if (detectedArtifact != null && detectedArtifact.isFound()) {
                experiment.setMessage(
                        appendSafeMessage(
                                experiment.getMessage(),
                                "Model artifact detected: "
                                        + detectedArtifact.getArtifactPath()
                                        + " [format="
                                        + detectedArtifact.getFormat()
                                        + ", framework="
                                        + detectedArtifact.getFramework()
                                        + "]"
                        )
                );
            } else {
                String reason = detectedArtifact != null
                        ? detectedArtifact.getReason()
                        : "Unknown reason.";

                experiment.setMessage(
                        appendSafeMessage(
                                experiment.getMessage(),
                                "No model artifact detected yet. Reason: " + reason
                        )
                );

                System.out.println("[CUSTOM-DOCKER-WATCHER] No model artifact detected for experimentId="
                        + experiment.getId()
                        + " reason="
                        + reason);
            }

        } catch (Exception e) {
            String detectionError = safe(e.getMessage());

            System.out.println("[CUSTOM-DOCKER-WATCHER] Model artifact detection failed for experimentId="
                    + experiment.getId()
                    + " reason="
                    + detectionError);

            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            "Model artifact detection failed: " + detectionError
                    )
            );
        }

        mlExperimentRepository.saveAndFlush(experiment);

        boolean artifactReady =
                tryStandardizeModelArtifactIfPossible(experiment, "training-completed");

        if (!artifactReady) {
            MlExperiment managedExperiment = mlExperimentRepository.findByIdWithFiles(experiment.getId())
                    .orElse(experiment);

            managedExperiment.setProduction(false);
            managedExperiment.setProductionVersion(null);

            managedExperiment.setMessage(
                    appendSafeMessage(
                            managedExperiment.getMessage(),
                            "Training completed, but model artifact is not ready for deployment. Auto-deployment blocked."
                    )
            );

            mlExperimentRepository.saveAndFlush(managedExperiment);

            System.out.println("[CUSTOM-DOCKER-WATCHER] Training completed, but artifact is not ready for deployment. "
                    + "experimentId=" + experiment.getId()
                    + ". Auto-deployment blocked.");

            return;
        }

        MlExperiment refreshedExperiment = mlExperimentRepository.findByIdWithFiles(experiment.getId())
                .orElse(experiment);

        refreshedExperiment.setMessage(
                appendSafeMessage(
                        refreshedExperiment.getMessage(),
                        "Model artifact ready for deployment."
                )
        );

        refreshedExperiment.setErrorMessage(null);

        mlExperimentRepository.saveAndFlush(refreshedExperiment);

        System.out.println("[CUSTOM-DOCKER-WATCHER] Custom Docker training completed and artifact is ready. "
                + "experimentId=" + refreshedExperiment.getId()
                + ", registeredModelName=" + refreshedExperiment.getRegisteredModelName()
                + ", modelVersion=" + refreshedExperiment.getModelVersion()
                + ", productionVersion=" + refreshedExperiment.getProductionVersion());
    }

    private boolean tryStandardizeModelArtifactIfPossible(MlExperiment experiment, String trigger) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        if (isStandardizationAlreadyFinal(experiment)) {
            return true;
        }

        try {
            ModelArtifactStandardizerService.StandardizationResult result =
                    modelArtifactStandardizerService.standardizeIfNeeded(experiment);

            if (result == null) {
                boolean nativeRegistryReady = acceptNativeMlflowRegistryModelIfAvailable(
                        experiment,
                        "Model artifact standardization returned null."
                );

                if (nativeRegistryReady) {
                    return true;
                }

                markModelArtifactNotReady(
                        experiment,
                        "Model artifact standardization returned null."
                );
                return false;
            }

            System.out.println("[CUSTOM-DOCKER-WATCHER] Model artifact standardization result. "
                    + "trigger=" + trigger
                    + ", experimentId=" + experiment.getId()
                    + ", success=" + result.isSuccess()
                    + ", skipped=" + result.isSkipped()
                    + ", standardized=" + result.isStandardized()
                    + ", alreadyMlflowModel=" + result.isAlreadyMlflowModel()
                    + ", newVersion=" + result.getNewVersion()
                    + ", type=" + result.getStandardizationType()
                    + ", source=" + result.getSourceModelFile()
                    + ", message=" + result.getMessage());

            if (result.isSuccess() && result.isAlreadyMlflowModel()) {
                boolean accepted = acceptNativeMlflowRegistryModelIfAvailable(
                        experiment,
                        "Existing valid MLflow model detected by standardizer."
                );

                if (accepted) {
                    return true;
                }
            }

            if (result.isSuccess() && !result.isSkipped()) {
                MlExperiment refreshed = mlExperimentRepository.findByIdWithFiles(experiment.getId())
                        .orElse(experiment);

                refreshed.setMessage(
                        appendSafeMessage(
                                refreshed.getMessage(),
                                "Model artifact ready for deployment."
                        )
                );
                refreshed.setErrorMessage(null);

                mlExperimentRepository.saveAndFlush(refreshed);
                return true;
            }

            boolean nativeRegistryReady = acceptNativeMlflowRegistryModelIfAvailable(
                    experiment,
                    result.getMessage()
            );

            if (nativeRegistryReady) {
                return true;
            }

            if (!result.isSuccess()) {
                markModelArtifactNotReady(
                        experiment,
                        "Model artifact standardization failed: " + result.getMessage()
                );
                return false;
            }

            if (result.isSkipped()) {
                markModelArtifactNotReady(
                        experiment,
                        "Model artifact standardization skipped: " + result.getMessage()
                );
                return false;
            }

            markModelArtifactNotReady(
                    experiment,
                    "Model artifact standardization did not produce a deployable model."
            );
            return false;

        } catch (Exception e) {
            String standardizationError = safe(e.getMessage());

            System.out.println("[CUSTOM-DOCKER-WATCHER] Model artifact standardization failed for experimentId="
                    + experiment.getId()
                    + " reason="
                    + standardizationError);

            boolean nativeRegistryReady = acceptNativeMlflowRegistryModelIfAvailable(
                    experiment,
                    standardizationError
            );

            if (nativeRegistryReady) {
                return true;
            }

            markModelArtifactNotReady(
                    experiment,
                    "Model artifact standardization exception: " + standardizationError
            );

            return false;
        }
    }

    private boolean acceptNativeMlflowRegistryModelIfAvailable(MlExperiment experiment, String reason) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        String registeredModelName = resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)) {
            System.out.println("[CUSTOM-DOCKER-WATCHER] Native MLflow Registry fallback skipped. "
                    + "experimentId=" + experiment.getId()
                    + ", reason=no registered model name");
            return false;
        }

        String productionVersion = getProductionVersionFromRegistryAlias(registeredModelName);

        if (!hasText(productionVersion)) {
            System.out.println("[CUSTOM-DOCKER-WATCHER] Native MLflow Registry fallback failed. "
                    + "experimentId=" + experiment.getId()
                    + ", registeredModelName=" + registeredModelName
                    + ", reason=" + reason);
            return false;
        }

        MlExperiment managedExperiment = mlExperimentRepository.findByIdWithFiles(experiment.getId())
                .orElse(experiment);

        managedExperiment.setRegisteredModelName(registeredModelName);
        managedExperiment.setModelName(registeredModelName);
        // Never copy the current production alias into this run's own version.
        // The exact version is assigned later only by MLflow run_id/source matching.
        managedExperiment.setProductionVersion(productionVersion);
        managedExperiment.setProduction(false);
        managedExperiment.setErrorMessage(null);

        managedExperiment.setMessage(
                appendSafeMessage(
                        managedExperiment.getMessage(),
                        "Native MLflow Registry model ready for deployment. "
                                + "Using models:/"
                                + registeredModelName
                                + "@production."
                )
        );

        managedExperiment.setMessage(
                appendSafeMessage(
                        managedExperiment.getMessage(),
                        "Model artifact ready for deployment."
                )
        );

        mlExperimentRepository.saveAndFlush(managedExperiment);

        System.out.println("[CUSTOM-DOCKER-WATCHER] No local model artifact found, "
                + "but valid MLflow Registry production model exists. "
                + "Continuing deployment with models:/"
                + registeredModelName
                + "@production. experimentId="
                + managedExperiment.getId()
                + ", productionVersion="
                + productionVersion);

        return true;
    }

    private String getProductionVersionFromRegistryAlias(String registeredModelName) {
        if (!hasText(registeredModelName)) {
            return null;
        }

        String cleanName = registeredModelName.trim();

        try {
            String url = localMlflowTrackingUri;

            if (url == null || url.isBlank()) {
                url = "http://mlflow-service.mlops.svc.cluster.local:5000";
            }

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            String requestUrl = url
                    + "/api/2.0/mlflow/registered-models/alias?name="
                    + java.net.URLEncoder.encode(cleanName, java.nio.charset.StandardCharsets.UTF_8)
                    + "&alias="
                    + java.net.URLEncoder.encode("production", java.nio.charset.StandardCharsets.UTF_8);

            org.springframework.web.client.RestTemplate restTemplate =
                    new org.springframework.web.client.RestTemplate();

            org.springframework.http.HttpHeaders headers =
                    new org.springframework.http.HttpHeaders();

            headers.setAccept(java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON));

            org.springframework.http.HttpEntity<Void> entity =
                    new org.springframework.http.HttpEntity<>(headers);

            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.exchange(
                            requestUrl,
                            org.springframework.http.HttpMethod.GET,
                            entity,
                            String.class
                    );

            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null
                    || body.isBlank()) {
                System.out.println("[CUSTOM-DOCKER-WATCHER] Registry alias check failed. "
                        + "registeredModelName=" + cleanName
                        + ", status=" + response.getStatusCode());
                return null;
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode modelVersion = root.path("model_version");

            String version = modelVersion.path("version").asText(null);

            System.out.println("[CUSTOM-DOCKER-WATCHER] Registry alias check. "
                    + "registeredModelName=" + cleanName
                    + ", alias=production"
                    + ", version=" + version
                    + ", ready=" + hasText(version));

            return hasText(version) ? version : null;

        } catch (Exception e) {
            System.out.println("[CUSTOM-DOCKER-WATCHER] Registry alias check failed. "
                    + "registeredModelName=" + cleanName
                    + ", reason=" + e.getMessage());

            return null;
        }
    }

    private String resolveRegisteredModelName(MlExperiment experiment) {
        if (experiment == null) {
            return "";
        }

        if (hasText(experiment.getRegisteredModelName())) {
            return experiment.getRegisteredModelName().trim();
        }

        if (hasText(experiment.getModelName())) {
            return experiment.getModelName().trim();
        }

        return "";
    }

    private void markModelArtifactNotReady(MlExperiment experiment, String reason) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        experiment.setProduction(false);
        experiment.setProductionVersion(null);

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "Model artifact not ready for deployment. Reason: " + reason
                )
        );

        mlExperimentRepository.saveAndFlush(experiment);

        System.out.println("[CUSTOM-DOCKER-WATCHER] Deployment blocked because model artifact is not ready. "
                + "experimentId=" + experiment.getId()
                + ", reason=" + reason);
    }

    private String appendSafeMessage(String oldMessage, String addition) {
        String base = oldMessage == null ? "" : oldMessage.trim();

        if (addition == null || addition.isBlank()) {
            return base;
        }

        if (base.contains(addition)) {
            return base;
        }

        String result = base.isBlank() ? addition : base + " " + addition;

        if (result.length() > 3000) {
            return result.substring(0, 2984) + "... [truncated]";
        }

        return result;
    }

    private boolean isStandardizationAlreadyFinal(MlExperiment experiment) {
        if (experiment == null) {
            return true;
        }

        String message = safe(experiment.getMessage()).toLowerCase();

        boolean standardizedWithVersion =
                message.contains("model artifact standardized automatically")
                        && message.contains("newversion=");

        boolean nativeRegistryAccepted =
                message.contains("native mlflow registry model ready for deployment")
                        && Boolean.TRUE.equals(experiment.getProduction())
                        && hasText(experiment.getRegisteredModelName())
                        && (
                        hasText(experiment.getProductionVersion())
                                || hasText(experiment.getModelVersion())
                );

        return standardizedWithVersion || nativeRegistryAccepted;
    }

    private void markFailedWithAnalyzer(MlExperiment experiment, String logs) {
        String safeLogs = safe(logs);

        RunFailureAnalyzer.FailureAnalysis failure =
                RunFailureAnalyzer.analyze(safeLogs);

        mlExperimentService.markExperimentFailedWithDetails(
                experiment,
                failure.getPhase(),
                failure.getReason(),
                failure.getDetails(),
                failure.getSuggestion(),
                failure.getLogExcerpt(),
                failure.getSourceFile()
        );
    }

    private void markFailed(MlExperiment experiment, String phase, String message) {
        if (experiment == null) {
            return;
        }

        experiment.setStatus(ExperimentStatus.FAILED);
        markDockerTrainingFinishedIfNeeded(experiment);
        experiment.setBuildStatus("CUSTOM_DOCKER_FAILED");
        experiment.setErrorMessage(message);
        experiment.setFailurePhase(phase);
        experiment.setFailureReason(message);
        experiment.setFailureDetails(message);
        experiment.setFailureSuggestion("Check Kubernetes job logs and Dockerfile/custom image configuration.");
        experiment.setFailureLogExcerpt(message);
        experiment.setFailureSourceFile("custom-docker-runtime");
        experiment.setMessage(message);

        mlExperimentRepository.saveAndFlush(experiment);
    }

    private void saveDetectedMetrics(MlExperiment experiment, Map<String, Double> metrics) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        metricRepository.deleteByExperimentId(experiment.getId());
        metricRepository.flush();

        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String metricName = normalizeMetricName(entry.getKey());
            Double metricValue = entry.getValue();

            if (!isDisplayablePerformanceMetric(metricName)) {
                continue;
            }

            MlExperimentMetric metric = new MlExperimentMetric();
            metric.setExperiment(experiment);
            metric.setMetricName(metricName);
            metric.setMetricValue(metricValue);
            metric.setDirection(getDirectionFromMetric(metricName));
            metric.setProduction(false);
            metric.setProductionVersion(experiment.getProductionVersion());
            metric.setGlobalScore(experiment.getGlobalScore());

            metricRepository.save(metric);
        }

        metricRepository.flush();
    }

    private void clearExperimentPerformanceMetrics(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        metricRepository.deleteByExperimentId(experiment.getId());
        metricRepository.flush();

        experiment.setPrimaryMetric(null);
        experiment.setDirection(null);
        experiment.setPrimaryMetricValue(null);
        experiment.setGlobalScore(null);
    }

    private void updatePrimaryMetricAndGlobalScore(
            MlExperiment experiment,
            Map<String, Double> metrics
    ) {
        if (experiment == null || metrics == null || metrics.isEmpty()) {
            return;
        }

        MlProject project = experiment.getProject();

        String primaryMetric = null;

        if (project != null
                && project.getPrimaryMetric1() != null
                && !project.getPrimaryMetric1().isBlank()) {
            primaryMetric = normalizeMetricName(project.getPrimaryMetric1());
        }

        if (!hasText(primaryMetric)) {
            primaryMetric = firstExistingMetric(
                    metrics,
                    "f1_score",
                    "f1",
                    "accuracy",
                    "precision",
                    "recall",
                    "r2",
                    "r2_score",
                    "rmse",
                    "mae",
                    "mse",
                    "loss"
            );
        }

        if (!hasText(primaryMetric)) {
            return;
        }

        Double value = getCompatibleMetricValue(metrics, primaryMetric);

        if (value == null) {
            return;
        }

        experiment.setPrimaryMetric(primaryMetric);
        experiment.setDirection(getDirectionFromMetric(primaryMetric));
        experiment.setPrimaryMetricValue(value);

        Double globalScore = normalizeMetricScore(primaryMetric, value);

        if (globalScore != null) {
            experiment.setGlobalScore(globalScore);
        }
    }

    private String firstExistingMetric(Map<String, Double> metrics, String... names) {
        if (metrics == null || metrics.isEmpty() || names == null) {
            return null;
        }

        for (String name : names) {
            Double value = getCompatibleMetricValue(metrics, name);

            if (value != null) {
                return normalizeMetricName(name);
            }
        }

        return null;
    }

    private Double getCompatibleMetricValue(Map<String, Double> metrics, String metricName) {
        if (metrics == null || metrics.isEmpty() || metricName == null) {
            return null;
        }

        String normalized = normalizeMetricName(metricName);

        if (metrics.containsKey(normalized)) {
            return metrics.get(normalized);
        }

        if (normalized.equals("f1_score") && metrics.containsKey("f1")) {
            return metrics.get("f1");
        }

        if (normalized.equals("f1") && metrics.containsKey("f1_score")) {
            return metrics.get("f1_score");
        }

        if (normalized.equals("r2_score") && metrics.containsKey("r2")) {
            return metrics.get("r2");
        }

        if (normalized.equals("r2") && metrics.containsKey("r2_score")) {
            return metrics.get("r2_score");
        }

        if (normalized.equals("precision") && metrics.containsKey("precision_weighted")) {
            return metrics.get("precision_weighted");
        }

        if (normalized.equals("recall") && metrics.containsKey("recall_weighted")) {
            return metrics.get("recall_weighted");
        }

        return null;
    }

    private Double normalizeMetricScore(String metricName, Double value) {
        if (metricName == null || value == null) {
            return null;
        }

        String direction = getDirectionFromMetric(metricName);

        if ("min".equalsIgnoreCase(direction)) {
            double safeValue = Math.max(value, 0.0);
            return roundDouble(1.0 / (1.0 + safeValue), 6);
        }

        if (value >= 0.0 && value <= 1.0) {
            return roundDouble(value, 6);
        }

        if (value > 1.0 && value <= 100.0) {
            return roundDouble(value / 100.0, 6);
        }

        if (value < 0.0) {
            return 0.0;
        }

        return 1.0;
    }

    private String getDirectionFromMetric(String metric) {
        if (metric == null) {
            return "max";
        }

        String normalizedMetric = normalizeMetricName(metric);

        if (normalizedMetric.equals("loss")
                || normalizedMetric.equals("log_loss")
                || normalizedMetric.equals("val_loss")
                || normalizedMetric.equals("rmse")
                || normalizedMetric.equals("mse")
                || normalizedMetric.equals("mae")
                || normalizedMetric.equals("error")
                || normalizedMetric.contains("loss")
                || normalizedMetric.contains("error")) {
            return "min";
        }

        return "max";
    }

    private boolean isDisplayablePerformanceMetric(String metricName) {
        if (!hasText(metricName)) {
            return false;
        }

        String normalized = normalizeMetricName(metricName);

        if (normalized.equals("prediction")
                || normalized.equals("prediction_example")
                || normalized.equals("sample_prediction")
                || normalized.contains("example")) {
            return false;
        }

        return trainingOutputAnalyzerService.isRecognizedMetric(normalized);
    }

    private boolean isCustomDockerExperiment(MlExperiment experiment) {
        return experiment != null
                && experiment.getRuntimeMode() != null
                && experiment.getRuntimeMode().equalsIgnoreCase("CUSTOM_DOCKERFILE");
    }

    private boolean isTerminalStatus(ExperimentStatus status) {
        return ExperimentStatus.COMPLETED.equals(status)
                || ExperimentStatus.FAILED.equals(status)
                || ExperimentStatus.CANCELLED.equals(status);
    }

    private boolean isWaitingOrEmptyBuildStatus(String buildStatus) {
        return buildStatus == null
                || buildStatus.isBlank()
                || buildStatus.equals("WAITING_FOR_BUILD_STEP")
                || buildStatus.equals("WAITING_FOR_IMAGE_BUILD")
                || buildStatus.equals("IMAGE_BUILD_PENDING")
                || buildStatus.equals("CREATED");
    }

    private boolean isBuildRunningStatus(String buildStatus) {
        return buildStatus != null
                && (
                buildStatus.equals("IMAGE_BUILDING")
                        || buildStatus.equals("IMAGE_BUILD_RUNNING")
                        || buildStatus.equals("BUILD_RUNNING")
                        || buildStatus.equals("KANIKO_RUNNING")
        );
    }

    private boolean isImageBuiltStatus(String buildStatus) {
        return buildStatus != null
                && (
                buildStatus.equals("IMAGE_BUILT")
                        || buildStatus.equals("IMAGE_BUILD_COMPLETED")
                        || buildStatus.equals("BUILD_COMPLETED")
        );
    }

    private boolean isTrainingRunningStatus(String buildStatus) {
        return buildStatus != null
                && (
                buildStatus.equals("CUSTOM_IMAGE_JOB_STARTING")
                        || buildStatus.equals("CUSTOM_IMAGE_JOB_RUNNING")
                        || buildStatus.equals("TRAINING_STARTING")
                        || buildStatus.equals("TRAINING_RUNNING")
        );
    }

    /**
     * Les erreurs Hibernate de session fermée, de verrou ou de concurrence sont
     * temporaires. Elles ne doivent pas transformer un run CREATED en FAILED :
     * le prochain passage du watcher doit pouvoir réessayer.
     */
    private boolean isTransientPersistenceFailure(Throwable error) {
        Throwable current = error;

        while (current != null) {
            String className = current.getClass().getName();
            String message = safe(current.getMessage()).toLowerCase();

            if (className.contains("LazyInitializationException")
                    || className.contains("PessimisticLock")
                    || className.contains("OptimisticLock")
                    || className.contains("StaleObjectState")
                    || className.contains("CannotAcquireLock")
                    || message.contains("could not initialize proxy")
                    || message.contains("no session")
                    || message.contains("lock wait timeout")
                    || message.contains("row was updated or deleted by another transaction")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private String normalizeMetricName(String metricName) {
        return trainingOutputAnalyzerService.normalizeMetricName(metricName);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeExperimentId(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return "null";
        }

        return String.valueOf(experiment.getId());
    }

    private Double roundDouble(Double value, int decimals) {
        if (value == null) {
            return null;
        }

        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private void markDockerTrainingStartedIfNeeded(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        if (experiment.getStartedAt() == null) {
            experiment.setStartedAt(
                    experiment.getCreatedAt() != null
                            ? experiment.getCreatedAt()
                            : java.time.LocalDateTime.now()
            );
        }

        if (!isTerminalStatus(experiment.getStatus())) {
            experiment.setFinishedAt(null);
        }
    }

    private void markDockerTrainingFinishedIfNeeded(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        if (experiment.getStartedAt() == null) {
            experiment.setStartedAt(
                    experiment.getCreatedAt() != null
                            ? experiment.getCreatedAt()
                            : java.time.LocalDateTime.now()
            );
        }

        if (experiment.getFinishedAt() == null) {
            experiment.setFinishedAt(java.time.LocalDateTime.now());
        }
    }
}