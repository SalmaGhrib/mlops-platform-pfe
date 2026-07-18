package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.DeleteExperimentResponse;
import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.dto.PipelineGatewayResponse;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MlExperimentService {

    private final MlExperimentRepository mlExperimentRepository;
    private final MlProjectRepository mlProjectRepository;
    private final UploadedProjectFileRepository uploadedProjectFileRepository;
    private final MlExperimentMetricRepository metricRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PipelineClientService pipelineClientService;
    private final ModelPromotionService modelPromotionService;
    private final AutoModelLifecycleService autoModelLifecycleService;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final KubernetesJobService kubernetesJobService;
    private final RestTemplate restTemplate;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.runtime.analysis-api-url:http://127.0.0.1:8001/api/runtime/analyze-script}")
    private String runtimeAnalysisApiUrl;

    private final AtomicBoolean lightProjectSyncRunning = new AtomicBoolean(false);

    public MlExperimentService(
            MlExperimentRepository mlExperimentRepository,
            MlProjectRepository mlProjectRepository,
            UploadedProjectFileRepository uploadedProjectFileRepository,
            MlExperimentMetricRepository metricRepository,
            UserRepository userRepository,
            JwtService jwtService,
            PipelineClientService pipelineClientService,
           ModelPromotionService modelPromotionService,
            AutoModelLifecycleService autoModelLifecycleService,
            MlflowTrackingClientService mlflowTrackingClientService,
            KubernetesJobService kubernetesJobService,
            RestTemplate restTemplate,
            NotificationService notificationService,
            PlatformTransactionManager transactionManager
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.uploadedProjectFileRepository = uploadedProjectFileRepository;
        this.metricRepository = metricRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.pipelineClientService = pipelineClientService;
        this.modelPromotionService = modelPromotionService;
        this.autoModelLifecycleService = autoModelLifecycleService;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.kubernetesJobService = kubernetesJobService;
        this.restTemplate = restTemplate;
        this.notificationService = notificationService;
        this.transactionManager = transactionManager;
    }

    /*
     * Compatibility overload.
     * If the controller still sends an old extra parameter related to replacement/injection,
     * we ignore it and call the clean method below.
     */
    public MlExperimentResponse uploadAndLaunchExperiment(
            String authHeader,
            Long projectId,
            String modelName,
            String runName,
            String primaryMetric,
            String targetColumn,
            String ignoredOldReplacementOrInjectionParam,
            MultipartFile modelFile,
            MultipartFile datasetFile,
            String runtimeMode,
            String customImageUri,
            MultipartFile dockerfileFile,
            MultipartFile requirementsFile
    ) {
        return uploadAndLaunchExperiment(
                authHeader,
                projectId,
                modelName,
                runName,
                primaryMetric,
                targetColumn,
                modelFile,
                datasetFile,
                runtimeMode,
                customImageUri,
                dockerfileFile,
                requirementsFile
        );
    }

    /*
     * IMPORTANT: no @Transactional here.
     * The MySQL row must be committed before Kubeflow/MLflow starts.
     * Metrics are synchronized later by syncProjectResults().
     */
    public MlExperimentResponse uploadAndLaunchExperiment(
            String authHeader,
            Long projectId,
            String modelName,
            String runName,
            String primaryMetric,
            String targetColumn,
            MultipartFile modelFile,
            MultipartFile datasetFile,
            String runtimeMode,
            String customImageUri,
            MultipartFile dockerfileFile,
            MultipartFile requirementsFile
    ) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (modelFile == null || modelFile.isEmpty()) {
            throw new RuntimeException("Model file is required");
        }

        String effectiveRuntimeMode = resolveRuntimeMode(project, runtimeMode);
        boolean customDockerMode = isCustomDockerfileMode(effectiveRuntimeMode);

        String cleanCustomImageUri = customImageUri != null ? customImageUri.trim() : null;
        if (cleanCustomImageUri != null && cleanCustomImageUri.isBlank()) {
            cleanCustomImageUri = null;
        }

        if (customDockerMode
                && (dockerfileFile == null || dockerfileFile.isEmpty())
                && cleanCustomImageUri == null) {
            throw new RuntimeException("Dockerfile or custom image URI is required for CUSTOM_DOCKERFILE mode");
        }

        UploadedProjectFile savedModelFile = saveUploadedFile(project, modelFile, UploadedFileType.MODEL_FILE);

        UploadedProjectFile savedDatasetFile = null;
        if (datasetFile != null && !datasetFile.isEmpty()) {
            savedDatasetFile = saveUploadedFile(project, datasetFile, UploadedFileType.DATASET);
        }

        String dockerfilePath = null;
        String requirementsPath = null;

        if (customDockerMode) {
            if (dockerfileFile != null && !dockerfileFile.isEmpty()) {
                dockerfilePath = saveRuntimeSupportFile(project, dockerfileFile, "docker", "Dockerfile");
            }

            if (requirementsFile != null && !requirementsFile.isEmpty()) {
                requirementsPath = saveRuntimeSupportFile(project, requirementsFile, "docker", "requirements.txt");
            }
        }

        String cleanRunName = buildUniqueRunName(defaultValue(runName, "training-run"));
        String cleanModelName = defaultValue(modelName, "custom-model");
        String registeredModelName = buildRegisteredModelName(project);
        String mlflowExperimentName = buildMlflowExperimentName(project);

        String cleanTargetColumn = targetColumn != null ? targetColumn.trim() : null;
        if (cleanTargetColumn != null && cleanTargetColumn.isBlank()) {
            cleanTargetColumn = null;
        }

        String projectType = normalizeProjectType(project.getProjectType());

        String projectPrimaryMetric = firstNotBlank(
                project.getPrimaryMetric1(),
                primaryMetric,
                defaultPrimaryMetricForProject(project)
        );

        if (projectPrimaryMetric == null || projectPrimaryMetric.isBlank()) {
            projectPrimaryMetric = defaultPrimaryMetricForProject(project);
        }

        projectPrimaryMetric = normalizeMetricKey(projectPrimaryMetric);

        String primaryMode = getDirectionFromMetric(projectPrimaryMetric);

        MlExperiment experiment = new MlExperiment();
        experiment.setExperimentName(cleanRunName);
        experiment.setProject(project);
        experiment.setDatasetFile(savedDatasetFile);
        experiment.setModelFile(savedModelFile);
        experiment.setModelType("CUSTOM");
        experiment.setModelName(cleanModelName);
        experiment.setRegisteredModelName(registeredModelName);
        experiment.setMlflowExperimentName(mlflowExperimentName);
        experiment.setPrimaryMetric(projectPrimaryMetric);
        experiment.setDirection(primaryMode);
        experiment.setProduction(false);
        experiment.setRuntimeMode(effectiveRuntimeMode);
        experiment.setCustomImageUri(cleanCustomImageUri);
        experiment.setDockerfilePath(dockerfilePath);
        experiment.setRequirementsPath(requirementsPath);
        experiment.setTargetColumn(cleanTargetColumn);

        /*
         * Create the MLflow experiment once, but never block the upload if MLflow is
         * temporarily unavailable. The training job can still create/use it later.
         */
        try {
            String mlflowExperimentId = mlflowTrackingClientService
                    .getOrCreateExperimentId(mlflowExperimentName);
            experiment.setMlflowExperimentId(mlflowExperimentId);
        } catch (Exception e) {
            System.out.println("[UPLOAD] MLflow experiment preparation skipped. projectId="
                    + projectId + ", name=" + mlflowExperimentName + ", reason=" + e.getMessage());
            experiment.setMlflowExperimentId(null);
        }

        /*
         * CUSTOM_DOCKERFILE:
         * No injection, no replacement.
         */
        if (customDockerMode) {
            experiment.setKubeflowRunId(null);
            experiment.setKubeflowRunName(null);
            experiment.setKubeflowStatus(null);
            experiment.setMlflowRunId(null);
            // Keep the MLflow experiment id prepared above.
            experiment.setModelVersion(null);
            experiment.setLatestVersion(null);
            experiment.setProductionVersion(null);
            experiment.setProduction(false);
            experiment.setGlobalScore(null);
            experiment.setPrimaryMetricValue(null);
            experiment.setErrorMessage(null);

            if (cleanCustomImageUri != null && !cleanCustomImageUri.isBlank()) {
                experiment.setStatus(ExperimentStatus.RUNNING);
                markExperimentStarted(experiment);
                experiment.setBuildStatus("CUSTOM_IMAGE_JOB_STARTING");
                experiment.setBuildLogs("Launching Kubernetes Job with custom image: " + cleanCustomImageUri);
                experiment.setMessage("Launching custom Docker image training Job.");

                MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

                try {
                    String jobName = kubernetesJobService.launchCustomImageTrainingJob(
                            project,
                            savedExperiment,
                            cleanCustomImageUri
                    );

                    savedExperiment.setKubeflowRunName(jobName);
                    savedExperiment.setTrainingJobName(jobName);
                    savedExperiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
                    savedExperiment.setBuildLogs("Kubernetes Job launched successfully: " + jobName);
                    savedExperiment.setMessage(
                            "Custom Docker image Job launched successfully. Waiting for MLflow synchronization."
                    );

                    MlExperiment updatedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
                    triggerImmediateProjectSync(project.getId());
                    return mapToResponse(updatedExperiment);

                } catch (Exception e) {
                    savedExperiment.setStatus(ExperimentStatus.FAILED);
                    applyExecutionTimestampsForStatus(savedExperiment, ExperimentStatus.FAILED);
                    savedExperiment.setBuildStatus("CUSTOM_IMAGE_JOB_FAILED");
                    savedExperiment.setBuildLogs(e.getMessage());
                    savedExperiment.setErrorMessage(e.getMessage());
                    savedExperiment.setMessage("Failed to launch custom Docker image Job.");

                    MlExperiment failedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
                    notifyExperimentStatusChangedIfNeeded(ExperimentStatus.RUNNING, failedExperiment);
                    return mapToResponse(failedExperiment);
                }
            }

            experiment.setBuildStatus("WAITING_FOR_IMAGE_BUILD");
experiment.setStatus(ExperimentStatus.CREATED);
experiment.setMessage(
        "CUSTOM_DOCKERFILE experiment saved. Waiting for CustomDockerRunWatcher to build image with Kaniko."
);

            MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);
            return mapToResponse(savedExperiment);
        }

        /*
         * STANDARD_RUNTIME:
         * No injection, no replacement.
         * Analyze imports/modules, then launch original script if compatible.
         */
        experiment.setStatus(ExperimentStatus.RUNNING);
        markExperimentStarted(experiment);
        experiment.setBuildStatus(null);
        experiment.setBuildLogs(null);
        experiment.setMessage("Analyzing script compatibility with STANDARD_RUNTIME.");
        experiment.setErrorMessage(null);

        System.out.println("[MlExperimentService] Runtime mode = " + effectiveRuntimeMode);
        System.out.println("[MlExperimentService] Algorithm injection disabled.");
        System.out.println("[MlExperimentService] Replacement disabled.");
        System.out.println("[MlExperimentService] Original script will be used.");

        ScriptAnalysisResult analysisResult = analyzeScriptCompatibility(savedModelFile);

        if (analysisResult.getDetectedAlgorithms() != null && !analysisResult.getDetectedAlgorithms().isBlank()) {
            experiment.setDetectedAlgorithms(analysisResult.getDetectedAlgorithms());
        }

        if (analysisResult.getDetectedModules() != null && !analysisResult.getDetectedModules().isBlank()) {
            experiment.setUsedAlgorithms(analysisResult.getDetectedModules());
        }

        if (!analysisResult.isRuntimeCompatible()) {
            String errorMsg = "Script not compatible with STANDARD_RUNTIME";

            if (analysisResult.getUnsupportedModules() != null && !analysisResult.getUnsupportedModules().isBlank()) {
                errorMsg += ". Unsupported modules: " + analysisResult.getUnsupportedModules();
            } else if (analysisResult.getMessage() != null && !analysisResult.getMessage().isBlank()) {
                errorMsg += ". " + analysisResult.getMessage();
            }

            experiment.setStatus(ExperimentStatus.FAILED);
            applyExecutionTimestampsForStatus(experiment, ExperimentStatus.FAILED);
            experiment.setErrorMessage(errorMsg);
            experiment.setMessage(
                    "Script incompatible with STANDARD_RUNTIME. Use CUSTOM_DOCKERFILE mode or add missing dependencies."
            );

            MlExperiment failedExperiment = mlExperimentRepository.saveAndFlush(experiment);
            notifyExperimentStatusChangedIfNeeded(ExperimentStatus.RUNNING, failedExperiment);
            return mapToResponse(failedExperiment);
        }

        experiment.setMessage("Script compatible with STANDARD_RUNTIME. Launching Kubeflow pipeline without replacement.");
        MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

        try {
            PipelineGatewayResponse gatewayResponse = pipelineClientService.launchCustomModelPipeline(
                savedExperiment.getId(),     // ID MySQL de l'expérience
                project.getId(),             // ID du projet
                project.getName(),
                cleanModelName,
                registeredModelName,
                cleanRunName,
                cleanTargetColumn,
                projectPrimaryMetric,
                primaryMode,
                projectType,
                effectiveRuntimeMode,
                savedModelFile,
                savedDatasetFile
            );

            savedExperiment.setKubeflowRunId(gatewayResponse.getKubeflowRunId());
            savedExperiment.setKubeflowRunName(defaultValue(gatewayResponse.getKubeflowRunName(), cleanRunName));
            savedExperiment.setMlflowExperimentName(mlflowExperimentName);

            if (gatewayResponse.getMlflowRunId() != null && !gatewayResponse.getMlflowRunId().isBlank()) {
                if (!isMlflowRunAlreadyLinkedToAnotherExperiment(
                        gatewayResponse.getMlflowRunId(),
                        savedExperiment.getId()
                )) {
                    savedExperiment.setMlflowRunId(gatewayResponse.getMlflowRunId());
                }
            }

            // Un projet = un seul Registered Model MLflow.
            // On ne laisse jamais la réponse gateway écraser ce nom avec "model"
            // ou "project_model_model".
            savedExperiment.setRegisteredModelName(registeredModelName);
            savedExperiment.setMlflowExperimentName(mlflowExperimentName);

            ExperimentStatus gatewayStatus = detectStatusFromGatewayResponse(gatewayResponse);

            ExperimentStatus finalGatewayStatus =
                    gatewayStatus == ExperimentStatus.FAILED
                            ? ExperimentStatus.FAILED
                            : ExperimentStatus.RUNNING;

            savedExperiment.setStatus(finalGatewayStatus);
            applyExecutionTimestampsForStatus(savedExperiment, finalGatewayStatus);

            if (gatewayStatus == ExperimentStatus.FAILED) {
                savedExperiment.setErrorMessage(gatewayResponse.getMessage());
                savedExperiment.setMessage("Kubeflow pipeline launch failed: " + gatewayResponse.getMessage());
            } else {
                savedExperiment.setErrorMessage(null);
                savedExperiment.setMessage(
                        gatewayResponse.getMessage() != null && !gatewayResponse.getMessage().isBlank()
                                ? gatewayResponse.getMessage()
                                : "Kubeflow pipeline launched. Waiting for MLflow synchronization."
                );
            }

            MlExperiment updatedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
            triggerImmediateProjectSync(project.getId());
            return mapToResponse(updatedExperiment);

        } catch (Exception e) {
            savedExperiment.setStatus(ExperimentStatus.FAILED);
            applyExecutionTimestampsForStatus(savedExperiment, ExperimentStatus.FAILED);
            savedExperiment.setErrorMessage(e.getMessage());
            savedExperiment.setMessage("Failed to launch Kubeflow pipeline, but the experiment remains saved in MySQL.");

            MlExperiment failedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
            notifyExperimentStatusChangedIfNeeded(ExperimentStatus.RUNNING, failedExperiment);
            return mapToResponse(failedExperiment);
        }
    }

    private String resolveRuntimeMode(MlProject project, String requestedRuntimeMode) {
        String mode = firstNotBlank(requestedRuntimeMode, getProjectRuntimeMode(project));

        if (mode == null || mode.isBlank()) {
            mode = "STANDARD_RUNTIME";
        }

        mode = mode.trim().toUpperCase();

        if (!mode.equals("STANDARD_RUNTIME") && !mode.equals("CUSTOM_DOCKERFILE")) {
            throw new RuntimeException("Invalid runtime mode. Allowed values: STANDARD_RUNTIME, CUSTOM_DOCKERFILE");
        }

        return mode;
    }

    private String getProjectRuntimeMode(MlProject project) {
        if (project == null) {
            return null;
        }

        try {
            Object value = project.getClass().getMethod("getRuntimeMode").invoke(project);
            return value != null ? String.valueOf(value) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isCustomDockerfileMode(String runtimeMode) {
        return runtimeMode != null && runtimeMode.trim().equalsIgnoreCase("CUSTOM_DOCKERFILE");
    }

    private String saveRuntimeSupportFile(
            MlProject project,
            MultipartFile file,
            String subDirectory,
            String preferredFileName
    ) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        try {
            String originalFileName = cleanFileName(
                    preferredFileName != null && !preferredFileName.isBlank()
                            ? preferredFileName
                            : file.getOriginalFilename()
            );

            String storedFileName = UUID.randomUUID() + "_" + originalFileName;

            Path runtimeUploadPath = Paths.get(
                            uploadDir,
                            "projects",
                            String.valueOf(project.getId()),
                            subDirectory == null || subDirectory.isBlank() ? "runtime" : subDirectory
                    )
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(runtimeUploadPath);

            Path targetPath = runtimeUploadPath.resolve(storedFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            return targetPath.toString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to save runtime support file: " + e.getMessage());
        }
    }

    public List<MlExperimentResponse> getExperimentsByProject(String authHeader, Long projectId) {
    User owner = getUserFromAuthorizationHeader(authHeader);

    MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
            .orElseThrow(() -> new RuntimeException("Project not found"));

    // Read endpoints must not start database-writing synchronizations.
    // The distributed scheduled lifecycle performs synchronization safely.
    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    List<MlExperiment> visibleExperiments =
            filterAndDeduplicateVisibleExperiments(experiments);

    return visibleExperiments
            .stream()
            .map(this::mapToResponse)
            .toList();
}

    public void triggerImmediateProjectSync(Long projectId) {
        // Intentionally do not create unmanaged threads here. With multiple backend
        // replicas, those threads can race with AUTO_SYNC/CUSTOM_DOCKER/PRODUCTION
        // and lock the same ml_experiments row. The distributed lifecycle scheduler
        // runs every 30 seconds and is protected by ShedLock.
        if (projectId != null) {
            System.out.println("[AUTO_SYNC] Project queued for scheduled sync. projectId=" + projectId);
        }
    }

    private void syncPendingExperimentsLightIfNeeded(MlProject project) {
        if (project == null || project.getId() == null) {
            return;
        }

        List<MlExperiment> experiments = mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

        if (experiments == null || experiments.isEmpty()) {
            return;
        }

        boolean needsSync = experiments.stream().anyMatch(this::needsImmediateMlflowSync);

        if (!needsSync) {
            return;
        }

        if (!lightProjectSyncRunning.compareAndSet(false, true)) {
            System.out.println("[AUTO_SYNC] Light project sync already running. Skip projectId=" + project.getId());
            return;
        }

        try {
            System.out.println("[AUTO_SYNC] Light sync for projectId=" + project.getId()
                    + ", name=" + project.getName());
            syncExistingProjectExperiments(project, "light-ui-refresh");
        } finally {
            lightProjectSyncRunning.set(false);
        }
    }

    private boolean needsImmediateMlflowSync(MlExperiment experiment) {
        if (experiment == null) {
            return false;
        }

        if (experiment.getStatus() == ExperimentStatus.CREATED
                || experiment.getStatus() == ExperimentStatus.RUNNING) {
            return true;
        }

        if (experiment.getMlflowRunId() == null || experiment.getMlflowRunId().isBlank()) {
            return isKubeflowSucceeded(experiment.getKubeflowStatus());
        }

        return false;
    }

    private boolean isKubeflowSucceeded(String kubeflowStatus) {
        if (kubeflowStatus == null || kubeflowStatus.isBlank()) {
            return false;
        }

        String normalized = kubeflowStatus.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("SUCCEEDED")
                || normalized.contains("SUCCESS")
                || normalized.contains("COMPLETED");
    }

    @Transactional
    public MlExperiment markExperimentFailedWithDetails(
            Long experimentId,
            String phase,
            String reason,
            String details,
            String suggestion,
            String logExcerpt,
            String sourceFile
    ) {
        MlExperiment experiment = mlExperimentRepository.findById(experimentId)
                .orElseThrow(() -> new RuntimeException("Experiment not found: " + experimentId));

        return markExperimentFailedWithDetails(
                experiment,
                phase,
                reason,
                details,
                suggestion,
                logExcerpt,
                sourceFile
        );
    }

    public MlExperiment markExperimentFailedWithDetails(
            MlExperiment experiment,
            String phase,
            String reason,
            String details,
            String suggestion,
            String logExcerpt,
            String sourceFile
    ) {
        if (experiment == null || experiment.getId() == null) {
            throw new RuntimeException("Experiment is required to mark as failed.");
        }

        ExperimentStatus previousStatus = experiment.getStatus();

        String normalizedPhase = firstNonBlank(phase, "UNKNOWN").toUpperCase(Locale.ROOT);
        String normalizedReason = firstNonBlank(reason, "Erreur non classifiée");
        String normalizedSuggestion = firstNonBlank(suggestion, "Consultez les logs détaillés.");
        String normalizedSourceFile = firstNonBlank(sourceFile, "unknown");

        experiment.setStatus(ExperimentStatus.FAILED);
        applyExecutionTimestampsForStatus(experiment, ExperimentStatus.FAILED);

        experiment.setErrorMessage(normalizedReason);
        experiment.setFailurePhase(normalizedPhase);
        experiment.setFailureReason(normalizedReason);
        experiment.setFailureDetails(details != null ? details : "");
        experiment.setFailureSuggestion(normalizedSuggestion);
        experiment.setFailureLogExcerpt(logExcerpt != null ? logExcerpt : "");
        experiment.setFailureSourceFile(normalizedSourceFile);
        experiment.setLastErrorAt(utcNow());

        if (experiment.getBuildStatus() == null || experiment.getBuildStatus().isBlank()) {
            experiment.setBuildStatus("FAILED");
        }

        MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);
        notifyExperimentStatusChangedIfNeeded(previousStatus, savedExperiment);

        return savedExperiment;
    }

    public MlExperimentResponse getExperimentById(String authHeader, Long projectId, Long experimentId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        MlExperiment experiment = mlExperimentRepository.findByIdAndProject(experimentId, project)
                .orElseThrow(() -> new RuntimeException("Experiment not found"));

        return mapToResponse(experiment);
    }

    public List<MlExperimentResponse> syncProjectResults(String authHeader, Long projectId) {
    User owner = getUserFromAuthorizationHeader(authHeader);

    MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
            .orElseThrow(() -> new RuntimeException("Project not found"));

    /*
     * 1) Sync first the rows already created by Spring Boot/Kubeflow.
     * This prevents creating a second MySQL row when the Kubeflow row already exists.
     */
    syncExistingProjectExperiments(project, "before-mlflow-import");

    /*
     * 2) Import MLflow runs only after trying to enrich existing rows.
     * The import method below attaches MLflow details to an existing Kubeflow row
     * before creating a new row.
     */
    importMissingMlflowRunsIntoMysql(project);

    /*
     * 3) Merge old duplicates if they already exist:
     * - one row has kubeflow_run_id only
     * - one row has mlflow_run_id/version/production only
     * They become one complete row.
     */
    mergeDuplicateMlflowKubeflowRows(project);

    /*
     * 4) Resync after import/merge.
     */
    syncExistingProjectExperiments(project, "after-mlflow-import");

    List<MlExperiment> refreshedExperiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    List<String> registeredNames = refreshedExperiments == null
            ? new ArrayList<>()
            : refreshedExperiments.stream()
                    .map(MlExperiment::getRegisteredModelName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();

    for (String registeredName : registeredNames) {
        try {
            recalculateGlobalScoresForProject(project, registeredName);
            refreshProductionVersionFlagsForProject(project, registeredName);
            enforceProductionConsistencyForModel(registeredName);

            if (isAutoPromoteEnabled(project)) {
                autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(project, registeredName);
                recalculateGlobalScoresForProject(project, registeredName);
            }
        } catch (Exception e) {
            System.out.println("[SYNC] Auto-promotion/global score failed for model=" + registeredName);
            System.out.println("[SYNC] Reason: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /*
     * 5) Final sync after promotion to refresh:
     * model_version, production_version, is_production, primary metric and global score.
     */
    syncExistingProjectExperiments(project, "after-production-promotion");
    mergeDuplicateMlflowKubeflowRows(project);

    /*
     * 6) Récupérer les expériences finales visibles.
     */
    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    List<MlExperiment> visibleExperiments =
            filterAndDeduplicateVisibleExperiments(experiments);


    /*
     * 8) Retourner la réponse Angular.
     */
    return visibleExperiments
            .stream()
            .map(this::mapToResponse)
            .toList();
}

    private void syncExistingProjectExperiments(MlProject project, String phase) {
        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

        if (experiments == null || experiments.isEmpty()) {
            return;
        }

        for (MlExperiment experiment : experiments) {
            try {
                syncSingleExperiment(project, experiment);
            } catch (Exception e) {
                System.out.println("[SYNC][" + phase + "] Cannot sync experiment id=" + experiment.getId());
                System.out.println("[SYNC][" + phase + "] Reason: " + e.getMessage());
                e.printStackTrace();

                // Never continue with the same Hibernate session after a persistence error.
                // The current project transaction must roll back cleanly; the next project
                // will run in its own REQUIRES_NEW transaction.
                throw e;
            }
        }
    }

    private void importMissingMlflowRunsIntoMysql(MlProject project) {
        if (project == null || project.getId() == null) {
            return;
        }

        String mlflowExperimentName = buildMlflowExperimentName(project);
        String fallbackRegisteredModelName = buildRegisteredModelName(project);

        /*
         * IMPORTANT:
         * STANDARD_RUNTIME writes MLflow runs under the project experiment name (ex: exx).
         * CUSTOM_DOCKERFILE writes MLflow runs under the registered model name (ex: exx_model).
         * Therefore we must search both MLflow experiments during synchronization.
         */
        List<MlflowTrackingClientService.MlflowRunDetails> mlflowRuns = new ArrayList<>();
        Map<String, MlflowTrackingClientService.MlflowRunDetails> uniqueRunsById = new LinkedHashMap<>();

        List<String> mlflowExperimentCandidates = List.of(
                mlflowExperimentName,
                fallbackRegisteredModelName
        );

        for (String candidateExperimentName : mlflowExperimentCandidates) {
            if (candidateExperimentName == null || candidateExperimentName.isBlank()) {
                continue;
            }

            List<MlflowTrackingClientService.MlflowRunDetails> candidateRuns =
                    mlflowTrackingClientService.findAllRunsByExperimentName(
                            candidateExperimentName,
                            fallbackRegisteredModelName
                    );

            if (candidateRuns == null || candidateRuns.isEmpty()) {
                continue;
            }

            for (MlflowTrackingClientService.MlflowRunDetails candidateRun : candidateRuns) {
                if (candidateRun == null
                        || candidateRun.getRunId() == null
                        || candidateRun.getRunId().isBlank()) {
                    continue;
                }

                uniqueRunsById.put(candidateRun.getRunId().trim(), candidateRun);
            }
        }

        mlflowRuns.addAll(uniqueRunsById.values());

        if (mlflowRuns.isEmpty()) {
            System.out.println("[SYNC] No MLflow runs found to import for project=" + project.getName()
                    + " in experiments=" + mlflowExperimentCandidates);
            return;
        }

        for (MlflowTrackingClientService.MlflowRunDetails runDetails : mlflowRuns) {
    try {
        if (runDetails == null
                || runDetails.getRunId() == null
                || runDetails.getRunId().isBlank()) {
            continue;
        }

        if (!isRealPlatformMlflowRun(runDetails)) {
            System.out.println("[SYNC][MLFLOW] Ignored non-platform MLflow run: "
                    + runDetails.getRunName()
                    + " / "
                    + runDetails.getRunId());
            continue;
        }

        String mlflowRunId = runDetails.getRunId().trim();

                String runName = runDetails.getRunName();
                if (runName == null || runName.isBlank()) {
                    runName = "mlflow_run_" + mlflowRunId;
                }

                String registeredModelName = firstNotBlank(
                        runDetails.getRegisteredModelName(),
                        runDetails.getTag("registered_model_name"),
                        runDetails.getTag("mlflow_registered_model_name"),
                        fallbackRegisteredModelName
                );

                if (registeredModelName == null || registeredModelName.isBlank()) {
                    registeredModelName = fallbackRegisteredModelName;
                }

                registeredModelName = normalizeRegisteredModelNameForProject(
                        registeredModelName,
                        fallbackRegisteredModelName
                );

                if (!registeredModelName.equals(fallbackRegisteredModelName)) {
                    System.out.println(
                            "[SYNC][MLFLOW] Ignored run " + mlflowRunId
                                    + " because registered_model_name=" + registeredModelName
                                    + " does not match project registered model=" + fallbackRegisteredModelName
                    );
                    continue;
                }

                String primaryMetric = firstNotBlank(
                        project.getPrimaryMetric1(),
                        defaultPrimaryMetricForProject(project)
                );

                if (primaryMetric == null || primaryMetric.isBlank()) {
                    primaryMetric = defaultPrimaryMetricForProject(project);
                }

                primaryMetric = normalizeMetricKey(primaryMetric);

                Map<String, Double> metrics = runDetails.getMetrics();

                String modelVersion = null;

                MlflowTrackingClientService.ModelVersionInfo runVersionInfo =
                        mlflowTrackingClientService.findModelVersionInfoByRunId(
                                registeredModelName,
                                mlflowRunId
                        );

                if (runVersionInfo != null
                        && runVersionInfo.getVersion() != null
                        && !runVersionInfo.getVersion().isBlank()) {
                    modelVersion = runVersionInfo.getVersion();
                } else if (isPlatformManagedMlflowRun(runDetails)) {
                    modelVersion = firstNotBlank(
                            runDetails.getModelVersion(),
                            runDetails.getTag("model_version"),
                            runDetails.getTag("registered_model_version"),
                            runDetails.getTag("platform_model_version")
                    );
                }

                String productionVersion = firstNotBlank(
                        mlflowTrackingClientService.getProductionVersion(registeredModelName),
                        runDetails.getTag("production_version"),
                        runDetails.getTag("production_model_version")
                );

                System.out.println("[MLFLOW_SYNC] import runId="
                        + mlflowRunId
                        + ", runVersion="
                        + modelVersion
                        + ", productionVersion="
                        + productionVersion
                        + ", registeredModel="
                        + registeredModelName);

                String kubeflowRunIdFromTags = firstNotBlank(
        runDetails.getTag("kubeflow_run_id"),
        runDetails.getTag("kubeflowRunId"),
        runDetails.getTag("kubeflow.run_id")
);

MlExperiment experimentToSave = findExistingExperimentToAttachMlflowRun(
        project,
        runDetails,
        registeredModelName
);

if (experimentToSave == null) {
    experimentToSave = findExistingExperimentBeforeCreate(
            project.getId(),
            runName,
            registeredModelName,
            mlflowRunId,
            kubeflowRunIdFromTags
    ).orElse(null);
}

if (experimentToSave == null) {
    experimentToSave = new MlExperiment();
    experimentToSave.setProject(project);
    experimentToSave.setExperimentName(runName);
    experimentToSave.setModelType("CUSTOM");
    experimentToSave.setModelName(registeredModelName);
    experimentToSave.setPrimaryMetric(primaryMetric);
    experimentToSave.setDirection(getDirectionFromMetric(primaryMetric));
    experimentToSave.setProduction(false);
    experimentToSave.setRuntimeMode("STANDARD_RUNTIME");
    experimentToSave.setMessage("Imported from MLflow during synchronization.");
} else {
    experimentToSave.setMessage("Existing Kubeflow/MySQL experiment linked with MLflow run.");
}

                ExperimentStatus previousStatus = experimentToSave.getStatus();

                ExperimentStatus importedStatus = mapMlflowStatusToExperimentStatus(runDetails.getStatus());

                ExperimentStatus mappedKubeflowStatus =
                        mapKubeflowStatusToExperimentStatus(experimentToSave.getKubeflowStatus());

                if (mappedKubeflowStatus == ExperimentStatus.FAILED
                        || mappedKubeflowStatus == ExperimentStatus.CANCELLED) {
                    importedStatus = mappedKubeflowStatus;
                }

                if (experimentToSave.getStatus() == ExperimentStatus.FAILED
                        || experimentToSave.getStatus() == ExperimentStatus.CANCELLED) {
                    importedStatus = experimentToSave.getStatus();
                }

                experimentToSave.setStatus(importedStatus);
                applyExecutionTimestampsForStatus(experimentToSave, importedStatus);
                experimentToSave.setRegisteredModelName(registeredModelName);
                experimentToSave.setMlflowExperimentName(mlflowExperimentName);
                experimentToSave.setMlflowExperimentId(runDetails.getExperimentId());
                experimentToSave.setMlflowRunId(mlflowRunId);
                experimentToSave.setModelType(defaultValue(experimentToSave.getModelType(), "CUSTOM"));
                experimentToSave.setModelName(defaultValue(experimentToSave.getModelName(), registeredModelName));
                experimentToSave.setPrimaryMetric(defaultValue(experimentToSave.getPrimaryMetric(), primaryMetric));
                experimentToSave.setDirection(getDirectionFromMetric(experimentToSave.getPrimaryMetric()));
                experimentToSave.setErrorMessage(null);

                if (modelVersion != null && !modelVersion.isBlank()) {
                    experimentToSave.setModelVersion(modelVersion);
                    experimentToSave.setLatestVersion(modelVersion);
                }

                if (productionVersion != null && !productionVersion.isBlank()) {
                    experimentToSave.setProductionVersion(productionVersion);
                }

                boolean eligibleForProduction = experimentToSave.getStatus() == ExperimentStatus.COMPLETED
                        && experimentToSave.getProductionVersion() != null
                        && experimentToSave.getModelVersion() != null
                        && experimentToSave.getProductionVersion().equals(experimentToSave.getModelVersion());

                experimentToSave.setProduction(eligibleForProduction);

                if (!eligibleForProduction) {
                    experimentToSave.setProduction(false);
                }

                if (metrics != null && !metrics.isEmpty()) {
                    updatePrimaryMetricFromProjectPriority(project, experimentToSave, metrics);

                    Double globalScore = calculateSingleExperimentGlobalScore(project, metrics);
                    if (globalScore != null) {
                        experimentToSave.setGlobalScore(globalScore);
                    }
                }

                if (!isEligiblePlatformVersion(experimentToSave)) {
                    clearVersioningAndProduction(experimentToSave);
                }

                syncAlgorithmMetadataFromRunTags(experimentToSave, runDetails);

                MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experimentToSave);

                if (metrics != null && !metrics.isEmpty()) {
                    saveExperimentMetrics(savedExperiment, metrics);
                }

                mlflowTrackingClientService.syncModelRegistryForExperiment(savedExperiment);

                /*
                 * Protection versioning :
                 * la version affichée sur cette ligne doit rester la version associée
                 * au run MLflow importé, pas la version production globale.
                 */
                if (hasText(modelVersion)) {
                    savedExperiment.setModelVersion(modelVersion);
                    savedExperiment.setLatestVersion(modelVersion);
                }

                String refreshedProductionVersion =
                        mlflowTrackingClientService.getProductionVersion(registeredModelName);

                if (isEligiblePlatformVersion(savedExperiment) && hasText(refreshedProductionVersion)) {
                    savedExperiment.setProductionVersion(refreshedProductionVersion);
                }

                if (!isEligiblePlatformVersion(savedExperiment)) {
                    clearVersioningAndProduction(savedExperiment);
                }

                savedExperiment.setProduction(isProductionExperimentRow(savedExperiment));

                System.out.println("[MLFLOW_SYNC] imported runId="
                        + mlflowRunId
                        + ", runVersion="
                        + savedExperiment.getModelVersion()
                        + ", productionVersion="
                        + savedExperiment.getProductionVersion()
                        + ", isProduction="
                        + savedExperiment.getProduction());

                MlExperiment finalSavedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
                notifyExperimentStatusChangedIfNeeded(previousStatus, finalSavedExperiment);

            } catch (Exception e) {
                System.out.println("[SYNC] Cannot import/link MLflow run:");
                System.out.println("[SYNC] Reason = " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private boolean isRealPlatformMlflowRun(MlflowTrackingClientService.MlflowRunDetails runDetails) {
    if (runDetails == null || runDetails.getRunId() == null || runDetails.getRunId().isBlank()) {
        return false;
    }

    String springExperimentId = firstNotBlank(
            runDetails.getTag("spring_experiment_id"),
            runDetails.getTag("springExperimentId"),
            runDetails.getTag("mysql_experiment_id"),
            runDetails.getTag("experiment_db_id")
    );

    String platformRunner = firstNotBlank(
            runDetails.getTag("platform_runner"),
            runDetails.getTag("runtime_mode"),
            runDetails.getTag("runner")
    );

    String registeredModelName = firstNotBlank(
            runDetails.getRegisteredModelName(),
            runDetails.getTag("registered_model_name"),
            runDetails.getTag("registeredModelName"),
            runDetails.getTag("platform_registered_model_name")
    );

    String kubeflowRunId = firstNotBlank(
            runDetails.getTag("kubeflow_run_id"),
            runDetails.getTag("kubeflowRunId"),
            runDetails.getTag("kubeflow.run_id")
    );

    String kubeflowRunName = firstNotBlank(
            runDetails.getTag("kubeflow_run_name"),
            runDetails.getTag("kubeflowRunName"),
            runDetails.getTag("kubeflow.run_name")
    );

    String trainingJobName = firstNotBlank(
            runDetails.getTag("training_job_name"),
            runDetails.getTag("trainingJobName"),
            runDetails.getTag("kubernetes_job_name")
    );

    boolean hasIdentityTags =
            hasText(springExperimentId)
                    || (hasText(platformRunner) && hasText(registeredModelName))
                    || hasText(kubeflowRunId)
                    || hasText(kubeflowRunName)
                    || hasText(trainingJobName);

    if (hasIdentityTags) {
        return true;
    }

    /*
     * Protection contre les runs MLflow créés directement par le script utilisateur.
     * Exemple : run_name="custom_run" avec artifact "model".
     *
     * Ces runs peuvent être utiles dans MLflow, mais ils ne doivent pas être importés
     * comme lignes plateforme, sinon ils créent des doublons et des versions fausses.
     *
     * La plateforme importe seulement :
     * - les runs avec tags plateforme,
     * - ou les anciens runs dont le nom suit le format project_run_yyyymmdd...
     */
    return isPlatformGeneratedRunName(runDetails.getRunName());
}

private boolean isPlatformManagedMlflowRun(MlflowTrackingClientService.MlflowRunDetails runDetails) {
    if (runDetails == null) {
        return false;
    }

    return "true".equalsIgnoreCase(firstNotBlank(
            runDetails.getTag("mlops.managed"),
            runDetails.getTag("platform.managed")
    ))
            || hasText(runDetails.getTag("platform_runner"))
            || hasText(runDetails.getTag("spring_experiment_id"))
            || hasText(runDetails.getTag("training_job_name"))
            || isPlatformGeneratedRunName(runDetails.getRunName());
}

private boolean isPlatformGeneratedRunName(String runName) {
    if (!hasText(runName)) {
        return false;
    }

    String clean = runName.trim().toLowerCase(Locale.ROOT);

    /*
     * Format créé par la plateforme : project_run_20260706_151522...
     */
    if (clean.matches("[a-z0-9][a-z0-9_-]*_run_\\d{8}_\\d{6}.*")) {
        return true;
    }

    /*
     * Compatibilité avec quelques anciens runs standard de la plateforme.
     */
    return clean.startsWith("standard-runtime")
            || clean.startsWith("custom-runtime")
            || clean.startsWith("custom_run_");
}

private boolean isSuccessfulMlflowRun(
        MlflowTrackingClientService.MlflowRunDetails runDetails,
        Map<String, Double> metrics
) {
    if (runDetails == null) {
        return false;
    }

    String status = runDetails.getStatus();

    boolean mlflowFinished =
            status != null &&
                    (
                            status.equalsIgnoreCase("FINISHED")
                                    || status.equalsIgnoreCase("COMPLETED")
                                    || status.equalsIgnoreCase("SUCCEEDED")
                    );

    boolean hasUsefulMetric =
            metrics != null &&
                    metrics.entrySet().stream()
                            .anyMatch(e ->
                                    e.getKey() != null
                                            && e.getValue() != null
                                            && isDisplayablePerformanceMetric(e.getKey())
                            );

    String modelMarker = firstNotBlank(
            runDetails.getTag("main_model_file"),
            runDetails.getTag("model_file"),
            runDetails.getTag("main_model_logged_as"),
            runDetails.getTag("model_version"),
            runDetails.getModelVersion()
    );

    boolean hasModel = hasText(modelMarker);

    return mlflowFinished && hasUsefulMetric && hasModel;
}
private boolean isVisibleExperimentRow(MlExperiment experiment) {
    if (experiment == null) {
        return false;
    }

    ExperimentStatus status = experiment.getStatus();

    boolean hasKubernetesIdentity = hasKubeflowIdentity(experiment);

    boolean hasMlflowIdentity = hasText(experiment.getMlflowRunId());

    boolean hasModelVersion = hasText(experiment.getModelVersion());

    boolean hasUsefulMetrics =
            experiment.getPrimaryMetricValue() != null
                    || experiment.getGlobalScore() != null;

    boolean isProductionVersion = isProductionExperimentRow(experiment);

    boolean isCustomDockerWaiting =
            experiment.getRuntimeMode() != null
                    && experiment.getRuntimeMode().equalsIgnoreCase("CUSTOM_DOCKERFILE")
                    && experiment.getBuildStatus() != null
                    && !experiment.getBuildStatus().isBlank();

    /*
     * Important :
     * On ne veut plus afficher une ligne RUNNING/CREATED qui n'a :
     * - aucun MLflow run
     * - aucun Kubeflow run
     * - aucun training job
     * - aucune version
     *
     * Ce sont les lignes orphelines qui polluent l'interface.
     */
    boolean hasRealExecutionIdentity =
            hasKubernetesIdentity
                    || hasMlflowIdentity
                    || hasModelVersion
                    || isCustomDockerWaiting;

    if (status == ExperimentStatus.CREATED
            || status == ExperimentStatus.RUNNING) {
        return hasRealExecutionIdentity;
    }

    if (status == ExperimentStatus.COMPLETED) {
        return isProductionVersion
                || hasMlflowIdentity
                || hasModelVersion
                || hasUsefulMetrics;
    }

    if (status == ExperimentStatus.FAILED
            || status == ExperimentStatus.CANCELLED) {
        return hasRealExecutionIdentity
                || hasText(experiment.getErrorMessage())
                || hasText(experiment.getFailureReason());
    }

    return false;
}


private void deleteUselessOrphanExperimentRows(MlProject project) {
    if (project == null || project.getId() == null) {
        return;
    }

    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    if (experiments == null || experiments.isEmpty()) {
        return;
    }

    boolean projectHasCompletedRealRun = experiments.stream()
            .anyMatch(exp ->
                    exp != null
                            && exp.getStatus() == ExperimentStatus.COMPLETED
                            && (
                            hasText(exp.getMlflowRunId())
                                    || hasText(exp.getModelVersion())
                                    || exp.getGlobalScore() != null
                                    || exp.getPrimaryMetricValue() != null
                    )
            );

    if (!projectHasCompletedRealRun) {
        return;
    }

    for (MlExperiment experiment : new ArrayList<>(experiments)) {
        if (experiment == null || experiment.getId() == null) {
            continue;
        }

        boolean pendingStatus =
                experiment.getStatus() == ExperimentStatus.RUNNING
                        || experiment.getStatus() == ExperimentStatus.CREATED;

        boolean noMlflow =
                !hasText(experiment.getMlflowRunId())
                        && !hasText(experiment.getMlflowExperimentId());

        boolean noKubeflow =
                !hasText(experiment.getKubeflowRunId())
                        && !hasText(experiment.getKubeflowRunName())
                        && !hasText(experiment.getTrainingJobName());

        boolean noModelVersion = !hasText(experiment.getModelVersion());

        boolean noBuildInProgress =
                !hasText(experiment.getBuildStatus())
                        || experiment.getBuildStatus().equalsIgnoreCase("FAILED");

        boolean uselessOrphan =
                pendingStatus
                        && noMlflow
                        && noKubeflow
                        && noModelVersion
                        && noBuildInProgress;

        if (!uselessOrphan) {
            continue;
        }

        try {
            metricRepository.deleteByExperimentId(experiment.getId());
            metricRepository.flush();

            mlExperimentRepository.delete(experiment);
            mlExperimentRepository.flush();

            System.out.println("[SYNC][CLEANUP] Deleted useless orphan experiment row id="
                    + experiment.getId()
                    + ", name="
                    + experiment.getExperimentName());

        } catch (Exception e) {
            System.out.println("[SYNC][CLEANUP] Could not delete orphan experiment row id="
                    + experiment.getId());
            System.out.println("[SYNC][CLEANUP] Reason: " + e.getMessage());
        }
    }
}

private boolean isProductionExperimentRow(MlExperiment experiment) {
    if (!isEligiblePlatformVersion(experiment)) {
        return false;
    }

    return Boolean.TRUE.equals(experiment.getProduction())
            && hasText(experiment.getProductionVersion())
            && hasText(experiment.getModelVersion())
            && experiment.getProductionVersion().trim()
            .equalsIgnoreCase(experiment.getModelVersion().trim());
}

private boolean isEligiblePlatformVersion(MlExperiment experiment) {
    if (experiment == null) {
        return false;
    }

    return experiment.getStatus() == ExperimentStatus.COMPLETED
            && hasText(experiment.getMlflowRunId())
            && hasText(experiment.getModelVersion())
            && experiment.getPrimaryMetricValue() != null
            && experiment.getGlobalScore() != null;
}

private void clearVersioningAndProduction(MlExperiment experiment) {
    if (experiment == null) {
        return;
    }

    experiment.setProduction(false);
    experiment.setModelVersion(null);
    experiment.setLatestVersion(null);
    experiment.setProductionVersion(null);
}

private void normalizeVersioningState(MlExperiment experiment) {
    if (experiment == null) {
        return;
    }

    if (!isEligiblePlatformVersion(experiment)) {
        clearVersioningAndProduction(experiment);
    } else {
        experiment.setProduction(false);
        experiment.setProductionVersion(null);
    }
}

@Transactional
public void enforceProductionConsistencyForAllModels() {
    try {
        mlExperimentRepository.invalidateAllNonEligibleVersions();
    } catch (Exception e) {
        System.out.println("[PRODUCTION_GUARD] Could not invalidate non eligible versions: " + e.getMessage());
    }

    List<String> registeredModelNames = mlExperimentRepository.findDistinctRegisteredModelNames();
    if (registeredModelNames == null || registeredModelNames.isEmpty()) {
        return;
    }

    for (String registeredModelName : registeredModelNames) {
        enforceProductionConsistencyForModel(registeredModelName);
    }
}

@Transactional
public void enforceProductionConsistencyForModel(String registeredModelName) {
    if (!hasText(registeredModelName)) {
        return;
    }

    String cleanRegisteredModelName = registeredModelName.trim();

    mlExperimentRepository.clearProductionByRegisteredModelName(cleanRegisteredModelName);

    List<MlExperiment> candidates = mlExperimentRepository
            .findEligibleProductionCandidates(cleanRegisteredModelName);

    if (candidates == null || candidates.isEmpty()) {
        return;
    }

    MlExperiment selected = candidates.get(0);
    selected.setProduction(true);
    selected.setProductionVersion(selected.getModelVersion());
    mlExperimentRepository.saveAndFlush(selected);
}

private List<MlExperiment> filterAndDeduplicateVisibleExperiments(List<MlExperiment> experiments) {
    if (experiments == null || experiments.isEmpty()) {
        return List.of();
    }

    Map<String, MlExperiment> unique = new LinkedHashMap<>();

    for (MlExperiment experiment : experiments) {
        if (!isVisibleExperimentRow(experiment)) {
            continue;
        }

        String key = buildExperimentDeduplicationKey(experiment);

        MlExperiment existing = unique.get(key);

        if (existing == null) {
            unique.put(key, experiment);
            continue;
        }

        unique.put(key, chooseBestExperimentRow(existing, experiment));
    }

    return new ArrayList<>(unique.values());
}

private String buildExperimentDeduplicationKey(MlExperiment experiment) {
    if (experiment == null) {
        return "null";
    }

    /*
     * Priorité 1 : si deux lignes ont le même MLflow run id,
     * c'est clairement le même run.
     */
    if (hasText(experiment.getMlflowRunId())) {
        return "mlflow:" + experiment.getMlflowRunId().trim();
    }

    /*
     * Priorité 2 : même Kubeflow run id.
     */
    if (hasText(experiment.getKubeflowRunId())) {
        return "kubeflow:" + experiment.getKubeflowRunId().trim();
    }

    /*
     * Priorité 3 : même nom Kubeflow.
     */
    if (hasText(experiment.getKubeflowRunName())) {
        return "kubeflowName:" + experiment.getKubeflowRunName().trim();
    }

    /*
     * Priorité 4 : même job training/build.
     */
    if (hasText(experiment.getTrainingJobName())) {
        return "job:" + experiment.getTrainingJobName().trim();
    }

    /*
     * Fallback : garder la ligne par id.
     * Cela évite de fusionner deux vrais runs différents par erreur.
     */
    return "experiment:" + experiment.getId();
}

private MlExperiment chooseBestExperimentRow(MlExperiment first, MlExperiment second) {
    int firstRank = experimentVisibilityRank(first);
    int secondRank = experimentVisibilityRank(second);

    if (secondRank > firstRank) {
        return second;
    }

    if (firstRank > secondRank) {
        return first;
    }

    /*
     * Si même qualité, garder le plus récent selon id.
     */
    Long firstId = first != null ? first.getId() : null;
    Long secondId = second != null ? second.getId() : null;

    if (firstId == null) {
        return second;
    }

    if (secondId == null) {
        return first;
    }

    return secondId > firstId ? second : first;
}

private int experimentVisibilityRank(MlExperiment experiment) {
    if (experiment == null) {
        return 0;
    }

    ExperimentStatus status = experiment.getStatus();

    if (isProductionExperimentRow(experiment)) {
        return 120;
    }

    boolean hasMetrics =
            experiment.getPrimaryMetricValue() != null
                    || experiment.getGlobalScore() != null;

    boolean hasKubernetesIdentity =
            hasText(experiment.getKubeflowRunId())
                    || hasText(experiment.getKubeflowRunName())
                    || hasText(experiment.getTrainingJobName());

    if (status == ExperimentStatus.COMPLETED && hasMetrics) {
        return 100;
    }

    if (status == ExperimentStatus.FAILED
            || status == ExperimentStatus.CANCELLED) {
        return 90;
    }

    if (status == ExperimentStatus.RUNNING && hasKubernetesIdentity) {
        return 80;
    }

    if (status == ExperimentStatus.RUNNING) {
        return 70;
    }

    if (status == ExperimentStatus.CREATED && hasKubernetesIdentity) {
        return 60;
    }

    if (status == ExperimentStatus.CREATED) {
        return 50;
    }

    return 10;
}
   private MlExperiment findExistingExperimentToAttachMlflowRun(
        MlProject project,
        MlflowTrackingClientService.MlflowRunDetails runDetails,
        String registeredModelName
) {
    if (project == null || runDetails == null) {
        return null;
    }

    String runId = runDetails.getRunId();
    String runName = runDetails.getRunName();

    String kubeflowRunId = firstNotBlank(
            runDetails.getTag("kubeflow_run_id"),
            runDetails.getTag("kubeflowRunId"),
            runDetails.getTag("kubeflow.run_id")
    );

    String kubeflowRunName = firstNotBlank(
            runDetails.getTag("kubeflow_run_name"),
            runDetails.getTag("kubeflowRunName"),
            runDetails.getTag("kubeflow.run_name")
    );

    String springExperimentId = firstNotBlank(
            runDetails.getTag("spring_experiment_id"),
            runDetails.getTag("springExperimentId"),
            runDetails.getTag("mysql_experiment_id"),
            runDetails.getTag("experiment_db_id")
    );

    List<MlExperiment> projectExperiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    if (projectExperiments == null || projectExperiments.isEmpty()) {
        return null;
    }

    // 1. Matching direct par mlflow_run_id.
    if (hasText(runId)) {
        for (MlExperiment exp : projectExperiments) {
            if (hasText(exp.getMlflowRunId()) && exp.getMlflowRunId().equals(runId)) {
                return exp;
            }
        }
    }

    // 2. Matching par ID MySQL/Spring tagué dans MLflow.
    if (hasText(springExperimentId)) {
        try {
            Long id = Long.parseLong(springExperimentId.trim());

            for (MlExperiment exp : projectExperiments) {
                if (exp.getId() != null && exp.getId().equals(id)) {
                    return exp;
                }
            }
        } catch (NumberFormatException ignored) {
            // Continue.
        }
    }

    // 3. Matching par kubeflow_run_id.
    if (hasText(kubeflowRunId)) {
        for (MlExperiment exp : projectExperiments) {
            if (hasText(exp.getKubeflowRunId())
                    && exp.getKubeflowRunId().equals(kubeflowRunId)) {
                return exp;
            }
        }
    }

    // 4. Matching par kubeflow_run_name.
    if (hasText(kubeflowRunName)) {
        for (MlExperiment exp : projectExperiments) {
            if (hasText(exp.getKubeflowRunName())
                    && exp.getKubeflowRunName().equals(kubeflowRunName)) {
                return exp;
            }

            if (hasText(exp.getTrainingJobName())
                    && exp.getTrainingJobName().equals(kubeflowRunName)) {
                return exp;
            }
        }
    }

    // 5. Matching par nom du run. On accepte aussi FAILED/CANCELLED.
    if (hasText(runName)) {
        for (MlExperiment exp : projectExperiments) {
            boolean waitingMlflow = isWaitingForMlflow(exp);
            boolean sameRegistry = sameRegistryName(exp.getRegisteredModelName(), registeredModelName);
            boolean sameRun = sameRunNameLoose(exp.getExperimentName(), runName)
                    || sameRunNameLoose(exp.getKubeflowRunName(), runName)
                    || sameRunNameLoose(exp.getTrainingJobName(), runName);

            if (waitingMlflow && sameRegistry && sameRun) {
                return exp;
            }
        }
    }

    // 6. Fallback contrôlé : si une ligne Kubeflow/custom job attend MLflow.
    // Très important pour les runs FAILED et les jobs custom Docker.
    MlExperiment onlyCandidate = null;
    int candidateCount = 0;

    for (MlExperiment exp : projectExperiments) {
        boolean waitingMlflow = isWaitingForMlflow(exp);
        boolean sameRegistry = sameRegistryName(exp.getRegisteredModelName(), registeredModelName);
        boolean hasKubeIdentity = hasKubeflowIdentity(exp);

        if (waitingMlflow && sameRegistry && hasKubeIdentity) {
            onlyCandidate = exp;
            candidateCount++;
        }
    }

    if (candidateCount == 1) {
        return onlyCandidate;
    }

    return null;
}
private boolean hasText(String value) {
    return value != null && !value.isBlank();
}

private String normalizeCompareKey(String value) {
    if (value == null) {
        return "";
    }

    return value.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
}

private boolean sameRegistryName(String a, String b) {
    String left = normalizeCompareKey(a);
    String right = normalizeCompareKey(b);

    if (left.isBlank() || right.isBlank()) {
        return true;
    }

    return left.equals(right);
}

private boolean sameRunNameLoose(String a, String b) {
    String left = normalizeCompareKey(a);
    String right = normalizeCompareKey(b);

    if (left.isBlank() || right.isBlank()) {
        return false;
    }

    return left.equals(right)
            || left.contains(right)
            || right.contains(left);
}

private boolean hasKubeflowIdentity(MlExperiment experiment) {
    if (experiment == null) {
        return false;
    }

    return hasText(experiment.getKubeflowRunId())
            || hasText(experiment.getKubeflowRunName())
            || hasText(experiment.getTrainingJobName());
}

private boolean isWaitingForMlflow(MlExperiment experiment) {
    return experiment != null
            && (experiment.getMlflowRunId() == null || experiment.getMlflowRunId().isBlank());
}

private boolean isMlflowOnlyRow(MlExperiment experiment) {
    return experiment != null
            && hasText(experiment.getMlflowRunId())
            && !hasKubeflowIdentity(experiment);
}

private boolean isKubeflowOnlyRow(MlExperiment experiment) {
    return experiment != null
            && isWaitingForMlflow(experiment)
            && hasKubeflowIdentity(experiment);
}

private void copyKubeflowIdentityIntoMlflowRow(MlExperiment sourceKubeflow, MlExperiment targetMlflow) {
    if (sourceKubeflow == null || targetMlflow == null) {
        return;
    }

    if (!hasText(targetMlflow.getKubeflowRunId())) {
        targetMlflow.setKubeflowRunId(sourceKubeflow.getKubeflowRunId());
    }

    if (!hasText(targetMlflow.getKubeflowRunName())) {
        targetMlflow.setKubeflowRunName(sourceKubeflow.getKubeflowRunName());
    }

    if (!hasText(targetMlflow.getKubeflowStatus())) {
        targetMlflow.setKubeflowStatus(sourceKubeflow.getKubeflowStatus());
    }

    if (!hasText(targetMlflow.getTrainingJobName())) {
        targetMlflow.setTrainingJobName(sourceKubeflow.getTrainingJobName());
    }

    if (!hasText(targetMlflow.getBuildStatus())) {
        targetMlflow.setBuildStatus(sourceKubeflow.getBuildStatus());
    }

    if (!hasText(targetMlflow.getBuildLogs())) {
        targetMlflow.setBuildLogs(sourceKubeflow.getBuildLogs());
    }

    if (!hasText(targetMlflow.getRuntimeMode())) {
        targetMlflow.setRuntimeMode(sourceKubeflow.getRuntimeMode());
    }

    if (!hasText(targetMlflow.getTargetColumn())) {
        targetMlflow.setTargetColumn(sourceKubeflow.getTargetColumn());
    }

    if (!hasText(targetMlflow.getDetectedAlgorithms())) {
        targetMlflow.setDetectedAlgorithms(sourceKubeflow.getDetectedAlgorithms());
    }

    if (!hasText(targetMlflow.getUsedAlgorithms())) {
        targetMlflow.setUsedAlgorithms(sourceKubeflow.getUsedAlgorithms());
    }
}

    private void mergeDuplicateMlflowKubeflowRows(MlProject project) {
    if (project == null) {
        return;
    }

    List<MlExperiment> experiments = mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    if (experiments == null || experiments.size() < 2) {
        return;
    }

    Map<String, MlExperiment> keeperByMlflowRunId = new LinkedHashMap<>();

    for (MlExperiment experiment : new ArrayList<>(experiments)) {
        if (experiment == null || !hasText(experiment.getMlflowRunId())) {
            continue;
        }

        String mlflowRunId = experiment.getMlflowRunId().trim();
        MlExperiment existing = keeperByMlflowRunId.get(mlflowRunId);

        if (existing == null) {
            keeperByMlflowRunId.put(mlflowRunId, experiment);
            continue;
        }

        MlExperiment keeper = chooseBestExperimentRow(existing, experiment);
        MlExperiment duplicate = keeper.getId().equals(existing.getId()) ? experiment : existing;

        if (keeper.getId().equals(duplicate.getId())) {
            continue;
        }

        if (hasKubeflowIdentity(keeper) && isMlflowOnlyRow(duplicate)) {
            copyMlflowDataIntoKubeflowRow(duplicate, keeper);
            mlExperimentRepository.saveAndFlush(keeper);
            deleteDuplicateExperimentRow(duplicate, keeper);
        } else if (hasKubeflowIdentity(duplicate) && isMlflowOnlyRow(keeper)) {
            copyMlflowDataIntoKubeflowRow(keeper, duplicate);
            mlExperimentRepository.saveAndFlush(duplicate);
            deleteDuplicateExperimentRow(keeper, duplicate);
            keeperByMlflowRunId.put(mlflowRunId, duplicate);
        }
    }

    experiments = mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    for (MlExperiment mlflowOnly : new ArrayList<>(experiments)) {
        if (!isMlflowOnlyRow(mlflowOnly)) {
            continue;
        }

        String registry = defaultValue(
                mlflowOnly.getRegisteredModelName(),
                buildRegisteredModelName(project)
        );

        MlExperiment kubeflowOnly = null;

        // 1. Chercher une ligne Kubeflow-only avec le même nom de run.
        for (MlExperiment candidate : experiments) {
            if (candidate == null || candidate.getId() == null || mlflowOnly.getId() == null) {
                continue;
            }

            if (candidate.getId().equals(mlflowOnly.getId())) {
                continue;
            }

            if (!isKubeflowOnlyRow(candidate)) {
                continue;
            }

            if (!sameRegistryName(candidate.getRegisteredModelName(), registry)) {
                continue;
            }

            boolean sameRun =
                    sameRunNameLoose(candidate.getExperimentName(), mlflowOnly.getExperimentName())
                            || sameRunNameLoose(candidate.getKubeflowRunName(), mlflowOnly.getExperimentName())
                            || sameRunNameLoose(candidate.getTrainingJobName(), mlflowOnly.getExperimentName());

            if (sameRun) {
                kubeflowOnly = candidate;
                break;
            }
        }

        // 2. Fallback : s'il n'y a qu'un seul candidat Kubeflow-only pour ce registry, on fusionne.
        if (kubeflowOnly == null) {
            MlExperiment onlyCandidate = null;
            int count = 0;

            for (MlExperiment candidate : experiments) {
                if (candidate == null || candidate.getId() == null || mlflowOnly.getId() == null) {
                    continue;
                }

                if (candidate.getId().equals(mlflowOnly.getId())) {
                    continue;
                }

                if (!isKubeflowOnlyRow(candidate)) {
                    continue;
                }

                if (!sameRegistryName(candidate.getRegisteredModelName(), registry)) {
                    continue;
                }

                onlyCandidate = candidate;
                count++;
            }

            if (count == 1) {
                kubeflowOnly = onlyCandidate;
            }
        }

        if (kubeflowOnly == null) {
            continue;
        }

        /*
         * On garde la ligne Kubeflow comme ligne principale,
         * car elle est celle créée par Spring Boot au lancement.
         */
        copyMlflowDataIntoKubeflowRow(mlflowOnly, kubeflowOnly);

        /*
         * On garde le statut Kubeflow FAILED/CANCELLED s'il est terminal.
         * Cela évite qu'un run MLflow FINISHED écrase un pipeline Kubeflow FAILED.
         */
        ExperimentStatus kubeflowStatus = mapKubeflowStatusToExperimentStatus(kubeflowOnly.getKubeflowStatus());
        ExperimentStatus mlflowStatus = mlflowOnly.getStatus();

        ExperimentStatus finalStatus = chooseFinalStatus(
                kubeflowStatus,
                mlflowStatus,
                kubeflowOnly.getGlobalScore() != null || kubeflowOnly.getPrimaryMetricValue() != null
        );

        kubeflowOnly.setStatus(finalStatus);
        applyExecutionTimestampsForStatus(kubeflowOnly, finalStatus);

        if (finalStatus == ExperimentStatus.FAILED) {
            kubeflowOnly.setMessage("Kubeflow row merged with MLflow FAILED run details.");
            if (!hasText(kubeflowOnly.getErrorMessage())) {
                kubeflowOnly.setErrorMessage("Pipeline failed. MLflow run attached successfully.");
            }
        } else if (finalStatus == ExperimentStatus.COMPLETED) {
            kubeflowOnly.setMessage("Kubeflow row merged with MLflow completed run details.");
            kubeflowOnly.setErrorMessage(null);
        } else {
            kubeflowOnly.setMessage("Kubeflow row merged with MLflow run details.");
        }

        MlExperiment merged = mlExperimentRepository.saveAndFlush(kubeflowOnly);

        try {
            List<MlExperimentMetric> sourceMetrics = metricRepository.findByExperimentId(mlflowOnly.getId());

            if (sourceMetrics != null && !sourceMetrics.isEmpty()) {
                metricRepository.deleteByExperimentId(merged.getId());
                metricRepository.flush();

                Map<String, Double> metrics = new LinkedHashMap<>();

                for (MlExperimentMetric metric : sourceMetrics) {
                    if (metric.getMetricName() != null && metric.getMetricValue() != null) {
                        metrics.put(metric.getMetricName(), metric.getMetricValue());
                    }
                }

                if (!metrics.isEmpty()) {
                    saveExperimentMetrics(merged, metrics);
                }
            }
        } catch (Exception e) {
            System.out.println("[SYNC][MERGE] Metrics merge failed: " + e.getMessage());
        }

        try {
            metricRepository.deleteByExperimentId(mlflowOnly.getId());
            metricRepository.flush();

            mlExperimentRepository.delete(mlflowOnly);
            mlExperimentRepository.flush();

            System.out.println("[SYNC][MERGE] Merged duplicate MLflow row id="
                    + mlflowOnly.getId()
                    + " into Kubeflow row id="
                    + merged.getId());

        } catch (Exception e) {
            System.out.println("[SYNC][MERGE] Could not delete duplicate MLflow row id=" + mlflowOnly.getId());
            System.out.println("[SYNC][MERGE] Reason: " + e.getMessage());
        }
    }
}

private void deleteDuplicateExperimentRow(MlExperiment duplicate, MlExperiment keeper) {
    if (duplicate == null || keeper == null || duplicate.getId() == null) {
        return;
    }

    try {
        List<MlExperimentMetric> sourceMetrics = metricRepository.findByExperimentId(duplicate.getId());

        if (sourceMetrics != null && !sourceMetrics.isEmpty()) {
            metricRepository.deleteByExperimentId(keeper.getId());
            metricRepository.flush();

            Map<String, Double> metrics = new LinkedHashMap<>();

            for (MlExperimentMetric metric : sourceMetrics) {
                if (metric.getMetricName() != null && metric.getMetricValue() != null) {
                    metrics.put(metric.getMetricName(), metric.getMetricValue());
                }
            }

            if (!metrics.isEmpty()) {
                saveExperimentMetrics(keeper, metrics);
            }
        }

        metricRepository.deleteByExperimentId(duplicate.getId());
        metricRepository.flush();
        mlExperimentRepository.delete(duplicate);
        mlExperimentRepository.flush();

        System.out.println("[SYNC][MERGE] Merged duplicate row id="
                + duplicate.getId()
                + " into row id="
                + keeper.getId());
    } catch (Exception e) {
        System.out.println("[SYNC][MERGE] Could not delete duplicate row id=" + duplicate.getId());
        System.out.println("[SYNC][MERGE] Reason: " + e.getMessage());
    }
}

    private void copyMlflowDataIntoKubeflowRow(MlExperiment source, MlExperiment target) {
    if (source == null || target == null) {
        return;
    }

    target.setMlflowRunId(source.getMlflowRunId());
    target.setMlflowExperimentId(source.getMlflowExperimentId());

    if (hasText(source.getMlflowExperimentName())) {
        target.setMlflowExperimentName(source.getMlflowExperimentName());
    }

    if (hasText(source.getRegisteredModelName())) {
        target.setRegisteredModelName(source.getRegisteredModelName());
    }

    if (hasText(source.getModelVersion())) {
        target.setModelVersion(source.getModelVersion());
    }

    if (hasText(source.getLatestVersion())) {
        target.setLatestVersion(source.getLatestVersion());
    }

    if (hasText(source.getProductionVersion())) {
        target.setProductionVersion(source.getProductionVersion());
    }

    target.setProduction(Boolean.TRUE.equals(source.getProduction()));

    if (hasText(source.getPrimaryMetric())) {
        target.setPrimaryMetric(source.getPrimaryMetric());
    }

    if (source.getPrimaryMetricValue() != null) {
        target.setPrimaryMetricValue(source.getPrimaryMetricValue());
    }

    if (hasText(source.getDirection())) {
        target.setDirection(source.getDirection());
    }

    if (source.getGlobalScore() != null) {
        target.setGlobalScore(source.getGlobalScore());
    }

    /*
     * Ne pas écraser FAILED/CANCELLED venant de Kubeflow avec un statut MLflow ambigu.
     */
    if (target.getStatus() != ExperimentStatus.FAILED
            && target.getStatus() != ExperimentStatus.CANCELLED) {
        target.setStatus(source.getStatus());
    }

    if (source.getStartedAt() != null && target.getStartedAt() == null) {
        target.setStartedAt(source.getStartedAt());
    }

    if (source.getFinishedAt() != null && target.getFinishedAt() == null) {
        target.setFinishedAt(source.getFinishedAt());
    }

    applyExecutionTimestampsForStatus(target, target.getStatus());

    if (hasText(source.getErrorMessage()) && !hasText(target.getErrorMessage())) {
        target.setErrorMessage(source.getErrorMessage());
    }

    target.setMessage("Kubeflow row merged with MLflow run details.");

    if (hasText(source.getDetectedAlgorithms())) {
        target.setDetectedAlgorithms(source.getDetectedAlgorithms());
    }

    if (hasText(source.getUsedAlgorithms())) {
        target.setUsedAlgorithms(source.getUsedAlgorithms());
    }

    if (!hasText(target.getRuntimeMode())) {
        target.setRuntimeMode(source.getRuntimeMode());
    }

    if (!hasText(target.getTargetColumn())) {
        target.setTargetColumn(source.getTargetColumn());
    }
}
    private String normalizeRegisteredModelNameForProject(String candidate, String fallbackRegisteredModelName) {
        String clean = candidate == null ? "" : candidate.trim();
        if (clean.isBlank() || clean.equalsIgnoreCase("model")) {
            return fallbackRegisteredModelName;
        }

        clean = clean.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        while (clean.endsWith("_model_model")) {
            clean = clean.substring(0, clean.length() - "_model".length());
        }

        if (!clean.endsWith("_model")) {
            clean = clean + "_model";
        }

        return clean;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";

        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }

        return "";
    }

    @Transactional
    public List<MlExperimentResponse> syncRegisteredModelExperimentsToDatabase(String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new RuntimeException("registeredModelName is required");
        }

        List<MlExperiment> experiments =
                mlExperimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(
                        registeredModelName.trim()
                );

        List<MlExperimentResponse> responses = new ArrayList<>();

        for (MlExperiment experiment : experiments) {
            try {
                MlProject project = experiment.getProject();
                syncSingleExperiment(project, experiment);

                if (project != null) {
                    recalculateGlobalScoresForProject(project, experiment.getRegisteredModelName());

                    if (isAutoPromoteEnabled(project)) {
                        autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                        project,
                        experiment.getRegisteredModelName()
                        );
                        recalculateGlobalScoresForProject(project, experiment.getRegisteredModelName());
                    }
                }

                MlExperiment refreshed = mlExperimentRepository.findById(experiment.getId())
                        .orElse(experiment);

                if (isVisibleExperimentRow(refreshed)) {
    responses.add(mapToResponse(refreshed));
}

            } catch (Exception e) {
                System.out.println("Cannot sync experiment id=" + experiment.getId());
                System.out.println("Reason: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return responses;
    }

    @Transactional
    public MlExperimentResponse recalculateProduction(String authHeader, Long projectId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        importMissingMlflowRunsIntoMysql(project);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

        for (MlExperiment experiment : experiments) {
            syncSingleExperiment(project, experiment);
        }

        List<String> registeredNames = experiments.stream()
                .map(MlExperiment::getRegisteredModelName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();

        MlExperiment bestExperiment = null;

        for (String registeredName : registeredNames) {
            recalculateGlobalScoresForProject(project, registeredName);

            if (isAutoPromoteEnabled(project)) {
                MlExperiment candidate = autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                    project,
                    registeredName
                );

                recalculateGlobalScoresForProject(project, registeredName);

                if (candidate != null
        && compareExperimentsByMetricPriority(project, candidate, bestExperiment) > 0) {
    bestExperiment = candidate;
}
            }
        }

        if (bestExperiment == null) {
            for (String registeredName : registeredNames) {
                MlExperiment candidate = findBestExperimentByMetricPriority(project, registeredName);

                if (candidate != null
        && compareExperimentsByMetricPriority(project, candidate, bestExperiment) > 0) {
    bestExperiment = candidate;
}
            }
        }

        if (bestExperiment == null) {
            throw new RuntimeException("No eligible experiment found for recalculation");
        }

        syncSingleExperiment(project, bestExperiment);

        MlExperiment refreshed = mlExperimentRepository.findById(bestExperiment.getId())
                .orElse(bestExperiment);

        return mapToResponse(refreshed);
    }

    private void clearWrongMlflowLinkAndMetrics(MlExperiment experiment, boolean clearMlflowRunId) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        if (clearMlflowRunId) {
            experiment.setMlflowRunId(null);
        }

        experiment.setModelVersion(null);
        experiment.setPrimaryMetricValue(null);
        experiment.setGlobalScore(null);
        experiment.setProduction(false);
        experiment.setErrorMessage(null);

        metricRepository.deleteByExperimentId(experiment.getId());
        metricRepository.flush();
    }

    private boolean isMlflowRunLinkedToAnyExperiment(String mlflowRunId) {
        if (mlflowRunId == null || mlflowRunId.isBlank()) {
            return false;
        }

        List<MlExperiment> linkedExperiments = mlExperimentRepository.findAllByMlflowRunId(mlflowRunId.trim());
        return linkedExperiments != null && !linkedExperiments.isEmpty();
    }

    private boolean isMlflowRunAlreadyLinkedToAnotherExperiment(String mlflowRunId, Long currentExperimentId) {
        if (mlflowRunId == null || mlflowRunId.isBlank()) {
            return false;
        }

        List<MlExperiment> linkedExperiments = mlExperimentRepository.findAllByMlflowRunId(mlflowRunId.trim());

        if (linkedExperiments == null || linkedExperiments.isEmpty()) {
            return false;
        }

        for (MlExperiment linkedExperiment : linkedExperiments) {
            if (linkedExperiment == null || linkedExperiment.getId() == null) {
                continue;
            }

            if (currentExperimentId == null || !linkedExperiment.getId().equals(currentExperimentId)) {
                return true;
            }
        }

        return false;
    }

    private void syncKubeflowRunState(MlExperiment experiment) {
        if (experiment == null || experiment.getKubeflowRunId() == null || experiment.getKubeflowRunId().isBlank()) {
            return;
        }

        PipelineClientService.KubeflowRunInfo runInfo = pipelineClientService.getKubeflowRunInfo(experiment.getKubeflowRunId());

        if (runInfo == null) {
            System.out.println("[SYNC][KUBEFLOW] runId=" + experiment.getKubeflowRunId() + " not found or unreachable");
            return;
        }

        System.out.println("[SYNC][KUBEFLOW] runId=" + runInfo.getRunId() + " status=" + runInfo.getStatus());

        if (runInfo.getRunName() != null && !runInfo.getRunName().isBlank()) {
            experiment.setKubeflowRunName(runInfo.getRunName());
        }

        if (runInfo.getStatus() != null && !runInfo.getStatus().isBlank()) {
            experiment.setKubeflowStatus(runInfo.getStatus());
        }
    }

    private ExperimentStatus mapKubeflowStatusToExperimentStatus(String kubeflowStatus) {
        if (kubeflowStatus == null) {
            return null;
        }

        String normalized = kubeflowStatus.trim().toUpperCase();

        if (normalized.contains("SUCCEEDED")
                || normalized.contains("SUCCESS")
                || normalized.contains("COMPLETED")) {
            return ExperimentStatus.COMPLETED;
        }

        if (normalized.contains("FAILED") || normalized.contains("ERROR")) {
            return ExperimentStatus.FAILED;
        }

        if (normalized.contains("KILLED")
                || normalized.contains("CANCELLED")
                || normalized.contains("CANCELED")) {
            return ExperimentStatus.CANCELLED;
        }

        return ExperimentStatus.RUNNING;
    }

    private boolean isCustomDockerExperimentWaitingForBuild(MlExperiment experiment) {
        if (experiment == null || isTerminalExperimentStatus(experiment.getStatus())) {
            return false;
        }

        String runtimeMode = experiment.getRuntimeMode();
        String buildStatus = experiment.getBuildStatus();

        boolean customDockerMode =
                runtimeMode != null && runtimeMode.equalsIgnoreCase("CUSTOM_DOCKERFILE");

        boolean waitingForBuild =
                buildStatus != null && (
                        buildStatus.equalsIgnoreCase("WAITING_FOR_BUILD_STEP")
                                || buildStatus.equalsIgnoreCase("WAITING_FOR_IMAGE_BUILD")
                                || buildStatus.equalsIgnoreCase("IMAGE_BUILD_PENDING")
                );

        boolean noKubeflowYet =
                experiment.getKubeflowRunId() == null || experiment.getKubeflowRunId().isBlank();

        boolean noMlflowYet =
                experiment.getMlflowRunId() == null || experiment.getMlflowRunId().isBlank();

        return customDockerMode && waitingForBuild && noKubeflowYet && noMlflowYet;
    }
    private boolean isTerminalExperimentStatus(ExperimentStatus status) {
    return status == ExperimentStatus.COMPLETED
            || status == ExperimentStatus.FAILED
            || status == ExperimentStatus.CANCELLED;
}

private ExperimentStatus chooseFinalStatus(
        ExperimentStatus kubeflowStatus,
        ExperimentStatus mlflowStatus,
        boolean hasMetrics
) {
    /*
     * Si Kubeflow a réellement échoué ou a été annulé, on garde cette vérité.
     */
    if (kubeflowStatus == ExperimentStatus.FAILED
            || kubeflowStatus == ExperimentStatus.CANCELLED) {
        return kubeflowStatus;
    }

    /*
     * Si MLflow est terminé avec des métriques, c'est un vrai succès modèle.
     * Très important pour CUSTOM_DOCKERFILE où Kubeflow peut rester RUNNING/null.
     */
    if (mlflowStatus == ExperimentStatus.COMPLETED && hasMetrics) {
        return ExperimentStatus.COMPLETED;
    }

    /*
     * Kubeflow terminé sans métriques MLflow : rester RUNNING jusqu'à preuve MLflow.
     */
    if (kubeflowStatus == ExperimentStatus.COMPLETED && !hasMetrics) {
        return ExperimentStatus.RUNNING;
    }

    if (kubeflowStatus == ExperimentStatus.COMPLETED && hasMetrics) {
        return ExperimentStatus.COMPLETED;
    }

    /*
     * Si MLflow a échoué et qu'on n'a pas de métriques, on marque FAILED.
     */
    if (mlflowStatus == ExperimentStatus.FAILED
            || mlflowStatus == ExperimentStatus.CANCELLED) {
        return mlflowStatus;
    }

    /*
     * Fallback : des métriques présentes veulent dire que l'entraînement a produit un résultat exploitable.
     */
    if (hasMetrics) {
        return ExperimentStatus.COMPLETED;
    }

    return ExperimentStatus.RUNNING;
}
private List<String> getMissingSelectedMetrics(MlProject project, Map<String, Double> metrics) {
    List<String> missingMetrics = new ArrayList<>();

    if (project == null || metrics == null || metrics.isEmpty()) {
        return missingMetrics;
    }

    List<String> priorities = getProjectMetricPriorities(project);

    if (priorities == null || priorities.isEmpty()) {
        return missingMetrics;
    }

    for (String metricName : priorities) {
        if (metricName == null || metricName.isBlank()) {
            continue;
        }

        Double value = getMetricValueCompatible(metrics, metricName);

        if (value == null) {
            missingMetrics.add(metricName);
        }
    }

    return missingMetrics;
}

private List<String> getAvailableSelectedMetrics(MlProject project, Map<String, Double> metrics) {
    List<String> availableMetrics = new ArrayList<>();

    if (project == null || metrics == null || metrics.isEmpty()) {
        return availableMetrics;
    }

    List<String> priorities = getProjectMetricPriorities(project);

    if (priorities == null || priorities.isEmpty()) {
        return availableMetrics;
    }

    for (String metricName : priorities) {
        if (metricName == null || metricName.isBlank()) {
            continue;
        }

        Double value = getMetricValueCompatible(metrics, metricName);

        if (value != null) {
            availableMetrics.add(metricName);
        }
    }

    return availableMetrics;
}

private String buildMetricWarningMessage(MlProject project, Map<String, Double> metrics) {
    List<String> missingMetrics = getMissingSelectedMetrics(project, metrics);
    List<String> availableMetrics = getAvailableSelectedMetrics(project, metrics);

    if (missingMetrics.isEmpty()) {
        return null;
    }

    if (availableMetrics.isEmpty()) {
        return "Warning: aucune métrique sélectionnée dans le projet n'a été trouvée dans ce run. "
                + "Le score global n'a pas été calculé. "
                + "Métriques manquantes: " + String.join(", ", missingMetrics) + ".";
    }

    return "Warning: certaines métriques sélectionnées dans le projet sont absentes du run: "
            + String.join(", ", missingMetrics)
            + ". Le score global a été recalculé uniquement avec les métriques disponibles: "
            + String.join(", ", availableMetrics)
            + ".";
}

private void appendMetricWarningToMessage(MlExperiment experiment, String warningMessage) {
    if (experiment == null || warningMessage == null || warningMessage.isBlank()) {
        return;
    }

    String currentMessage = experiment.getMessage();

    if (currentMessage == null || currentMessage.isBlank()) {
        experiment.setMessage(warningMessage);
        return;
    }

    if (!currentMessage.contains(warningMessage)) {
        experiment.setMessage(currentMessage + " " + warningMessage);
    }
}

private void updateMessageFromFinalStatus(
        MlExperiment experiment,
        ExperimentStatus finalStatus,
        String mlflowRunStatus
) {
    if (experiment == null) {
        return;
    }

    if (finalStatus == ExperimentStatus.FAILED) {
        experiment.setErrorMessage("Pipeline status: FAILED. MLflow status: " + mlflowRunStatus);
        experiment.setMessage("Synchronization finished with FAILED status.");
        return;
    }

    if (finalStatus == ExperimentStatus.CANCELLED) {
        experiment.setErrorMessage("Pipeline status: CANCELLED. MLflow status: " + mlflowRunStatus);
        experiment.setMessage("Synchronization finished with CANCELLED status.");
        return;
    }

    if (finalStatus == ExperimentStatus.COMPLETED) {
        experiment.setErrorMessage(null);
        experiment.setMessage("Kubeflow and MLflow results synchronized successfully.");
        return;
    }

    experiment.setErrorMessage(null);
    experiment.setMessage("Synchronization pending. Waiting for Kubeflow/MLflow completion.");
}

    private void syncSingleExperiment(MlProject project, MlExperiment experiment) {
    if (project == null || experiment == null) {
        return;
    }

    ExperimentStatus previousStatus = experiment.getStatus();

    if (isCustomDockerExperimentWaitingForBuild(experiment)) {
        if (experiment.getStatus() != ExperimentStatus.CREATED) {
            experiment.setStatus(ExperimentStatus.CREATED);
        }

        if (experiment.getMessage() == null
                || !experiment.getMessage().contains("waiting for image build")) {
            experiment.setMessage(
                    "CUSTOM_DOCKERFILE experiment is waiting for image build / dynamic Kubeflow launch. "
                            + "MLflow synchronization is skipped for now."
            );
        }

        experiment.setErrorMessage(null);
        mlExperimentRepository.saveAndFlush(experiment);
        return;
    }

    String preferredRegisteredModelName = defaultValue(
            experiment.getRegisteredModelName(),
            buildRegisteredModelName(project)
    );

    String preferredMlflowExperimentName;

    if (experiment.getRuntimeMode() != null
            && experiment.getRuntimeMode().equalsIgnoreCase("CUSTOM_DOCKERFILE")) {
        preferredMlflowExperimentName = preferredRegisteredModelName;
    } else {
        preferredMlflowExperimentName = defaultValue(
                experiment.getMlflowExperimentName(),
                buildMlflowExperimentName(project)
        );

        if (preferredMlflowExperimentName.endsWith("_model")) {
            preferredMlflowExperimentName = buildMlflowExperimentName(project);
        }
    }

    experiment.setMlflowExperimentName(preferredMlflowExperimentName);

    if (experiment.getRegisteredModelName() == null || experiment.getRegisteredModelName().isBlank()) {
        experiment.setRegisteredModelName(preferredRegisteredModelName);
    }

    syncKubeflowRunState(experiment);

    System.out.println("[AUTO_SYNC] Sync experimentId=" + experiment.getId()
            + ", projectId=" + (project != null ? project.getId() : null)
            + ", kubeflowStatus=" + experiment.getKubeflowStatus()
            + ", mlflowRunId=" + experiment.getMlflowRunId()
            + ", registeredModel=" + preferredRegisteredModelName
            + ", mlflowExperiment=" + preferredMlflowExperimentName);

    MlflowTrackingClientService.MlflowRunDetails runDetails =
            mlflowTrackingClientService.syncExperimentWithMlflow(experiment);

    if (runDetails == null || runDetails.getRunId() == null || runDetails.getRunId().isBlank()) {
                /*
         * Très important :
         * Si l'expérience est déjà terminale, l'auto-sync ne doit jamais la remettre RUNNING.
         * Exemple :
         * - CustomDockerRunWatcher a déjà marqué COMPLETED
         * - puis AUTO_SYNC ne trouve pas encore MLflow pendant quelques secondes
         * => on garde COMPLETED.
         */
        if (isTerminalExperimentStatus(experiment.getStatus())) {
            System.out.println("[AUTO_SYNC] MLflow run not found, but experiment is already terminal. Keep status="
                    + experiment.getStatus()
                    + ", experimentId="
                    + experiment.getId());

            MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);
            notifyExperimentStatusChangedIfNeeded(previousStatus, savedExperiment);
            return;
        }
        if (experiment.getStatus() != ExperimentStatus.FAILED
                && experiment.getStatus() != ExperimentStatus.CANCELLED) {

            if (experiment.getKubeflowStatus() != null && !experiment.getKubeflowStatus().isBlank()) {
                ExperimentStatus mappedKubeflowStatus =
                        mapKubeflowStatusToExperimentStatus(experiment.getKubeflowStatus());

                if (mappedKubeflowStatus == ExperimentStatus.FAILED
                        || mappedKubeflowStatus == ExperimentStatus.CANCELLED) {
                    experiment.setStatus(mappedKubeflowStatus);
                } else {
                    experiment.setStatus(ExperimentStatus.RUNNING);
                }
            } else {
                experiment.setStatus(ExperimentStatus.RUNNING);
            }
        }

        if (isKubeflowSucceeded(experiment.getKubeflowStatus())) {
            experiment.setMessage("Kubeflow succeeded. Waiting for MLflow run synchronization.");
        } else {
            experiment.setMessage(
                    "MLflow run details not found yet. Kubeflow status=" + experiment.getKubeflowStatus()
            );
        }
        experiment.setErrorMessage(null);

        System.out.println("[AUTO_SYNC] MLflow run not found yet for experimentId=" + experiment.getId()
                + ", kubeflowStatus=" + experiment.getKubeflowStatus());

        applyExecutionTimestampsForStatus(experiment, experiment.getStatus());

        MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);
        notifyExperimentStatusChangedIfNeeded(previousStatus, savedExperiment);

        return;
    }

    System.out.println("[AUTO_SYNC] MLflow run found experimentId=" + experiment.getId()
            + ", runId=" + runDetails.getRunId()
            + ", status=" + runDetails.getStatus()
            + ", modelVersion=" + runDetails.getModelVersion()
            + ", metrics=" + (runDetails.getMetrics() != null ? runDetails.getMetrics().size() : 0));

    String effectiveRunId = runDetails.getRunId();

    if (isMlflowRunAlreadyLinkedToAnotherExperiment(effectiveRunId, experiment.getId())) {
        List<MlExperiment> linkedExperiments = mlExperimentRepository.findAllByMlflowRunId(effectiveRunId);

        MlExperiment mlflowOwner = null;

        if (linkedExperiments != null) {
            for (MlExperiment linked : linkedExperiments) {
                if (linked == null || linked.getId() == null) {
                    continue;
                }

                if (!linked.getId().equals(experiment.getId())) {
                    mlflowOwner = linked;
                    break;
                }
            }
        }

        if (mlflowOwner != null
                && isKubeflowOnlyRow(experiment)
                && isMlflowOnlyRow(mlflowOwner)
                && sameRegistryName(experiment.getRegisteredModelName(), mlflowOwner.getRegisteredModelName())) {

            copyMlflowDataIntoKubeflowRow(mlflowOwner, experiment);

            MlExperiment merged = mlExperimentRepository.saveAndFlush(experiment);

            try {
                List<MlExperimentMetric> sourceMetrics = metricRepository.findByExperimentId(mlflowOwner.getId());

                if (sourceMetrics != null && !sourceMetrics.isEmpty()) {
                    metricRepository.deleteByExperimentId(merged.getId());
                    metricRepository.flush();

                    Map<String, Double> metrics = new LinkedHashMap<>();

                    for (MlExperimentMetric metric : sourceMetrics) {
                        if (metric.getMetricName() != null && metric.getMetricValue() != null) {
                            metrics.put(metric.getMetricName(), metric.getMetricValue());
                        }
                    }

                    if (!metrics.isEmpty()) {
                        saveExperimentMetrics(merged, metrics);
                    }
                }

                metricRepository.deleteByExperimentId(mlflowOwner.getId());
                metricRepository.flush();

                mlExperimentRepository.delete(mlflowOwner);
                mlExperimentRepository.flush();

                System.out.println("[SYNC][DUPLICATE] Merged MLflow owner row id="
                        + mlflowOwner.getId()
                        + " into current Kubeflow row id="
                        + merged.getId());

            } catch (Exception e) {
                System.out.println("[SYNC][DUPLICATE] Could not remove duplicate MLflow owner row.");
                System.out.println("[SYNC][DUPLICATE] Reason: " + e.getMessage());
            }

            return;
        }

        experiment.setMessage(
                "Synchronization skipped: MLflow run "
                        + effectiveRunId
                        + " is already linked to another MySQL experiment."
        );

        mlExperimentRepository.saveAndFlush(experiment);
        return;
    }

    experiment.setMlflowRunId(effectiveRunId);
    experiment.setMlflowExperimentId(runDetails.getExperimentId());

    experiment.setRegisteredModelName(preferredRegisteredModelName);
    experiment.setMlflowExperimentName(preferredMlflowExperimentName);

    syncAlgorithmMetadataFromRunTags(experiment, runDetails);

    String runStatus = firstNotBlank(
            runDetails.getStatus(),
            mlflowTrackingClientService.getRunStatus(effectiveRunId)
    );

    String modelVersion = null;

    MlflowTrackingClientService.ModelVersionInfo versionInfo =
            mlflowTrackingClientService.findModelVersionInfoByRunId(
                    preferredRegisteredModelName,
                    effectiveRunId
            );

    if (versionInfo != null
            && versionInfo.getVersion() != null
            && !versionInfo.getVersion().isBlank()) {
        modelVersion = versionInfo.getVersion();
    } else if (isPlatformManagedMlflowRun(runDetails)) {
        modelVersion = firstNotBlank(
                runDetails.getModelVersion(),
                runDetails.getTag("model_version"),
                runDetails.getTag("registered_model_version"),
                runDetails.getTag("platform_model_version")
        );
    }

    if (modelVersion != null && !modelVersion.isBlank()) {
        experiment.setModelVersion(modelVersion);
    }

    String productionVersion = firstNotBlank(
            mlflowTrackingClientService.getProductionVersion(preferredRegisteredModelName),
            runDetails.getTag("production_version"),
            runDetails.getTag("production_model_version")
    );

    if (productionVersion != null && !productionVersion.isBlank()) {
        experiment.setProductionVersion(productionVersion);
    }

    experiment.setProduction(false);

    Map<String, Double> mlflowMetrics = new LinkedHashMap<>();

    if (runDetails.getMetrics() != null && !runDetails.getMetrics().isEmpty()) {
        mlflowMetrics.putAll(runDetails.getMetrics());
    }

    Map<String, Double> metricsFromRun =
            mlflowTrackingClientService.getMetricsByRunId(effectiveRunId);

    if (metricsFromRun != null && !metricsFromRun.isEmpty()) {
        mlflowMetrics.putAll(metricsFromRun);
    }

        /*
     * Très important :
     * Les métriques peuvent venir de deux sources :
     * 1) MLflow metrics
     * 2) Metrics déjà détectées par CustomDockerRunWatcher / TrainingOutputAnalyzer
     *
     * Si MLflow retourne 0 métrique, on ne doit PAS supprimer les métriques déjà détectées.
     */
    Map<String, Double> existingMetrics = getMetricsMap(experiment);

    Map<String, Double> effectiveMetrics = new LinkedHashMap<>();

    if (existingMetrics != null && !existingMetrics.isEmpty()) {
        effectiveMetrics.putAll(existingMetrics);
    }

    if (mlflowMetrics != null && !mlflowMetrics.isEmpty()) {
        effectiveMetrics.putAll(mlflowMetrics);
    }

    boolean hasMetrics = !effectiveMetrics.isEmpty();
    String metricWarningMessage = null;

    if (hasMetrics) {
        updatePrimaryMetricFromProjectPriority(project, experiment, effectiveMetrics);

        Double globalScore = calculateSingleExperimentGlobalScore(project, effectiveMetrics);

        if (globalScore != null) {
            experiment.setGlobalScore(globalScore);
        }

        metricWarningMessage = buildMetricWarningMessage(project, effectiveMetrics);
    } else {
        /*
         * On ne force plus globalScore=null agressivement.
         * On garde l'ancien état si l'expérience avait déjà un résultat exploitable.
         */
        if (experiment.getPrimaryMetricValue() == null) {
            experiment.setGlobalScore(null);
        }

        ExperimentStatus currentKubeflowStatus =
                mapKubeflowStatusToExperimentStatus(experiment.getKubeflowStatus());

        ExperimentStatus currentMlflowStatus =
                mapMlflowStatusToExperimentStatus(runStatus);

        boolean isFinishedWithoutMetrics =
                currentKubeflowStatus == ExperimentStatus.COMPLETED
                        || currentKubeflowStatus == ExperimentStatus.FAILED
                        || currentKubeflowStatus == ExperimentStatus.CANCELLED
                        || currentMlflowStatus == ExperimentStatus.COMPLETED
                        || currentMlflowStatus == ExperimentStatus.FAILED
                        || currentMlflowStatus == ExperimentStatus.CANCELLED;

        if (isFinishedWithoutMetrics) {
            metricWarningMessage =
                    "Warning: aucune métrique MLflow n'a été trouvée dans ce run. "
                            + "Les métriques déjà détectées par les logs sont conservées si elles existent.";
        }
    }

    ExperimentStatus mappedKubeflowStatus =
            mapKubeflowStatusToExperimentStatus(experiment.getKubeflowStatus());

    ExperimentStatus mappedMlflowStatus =
            mapMlflowStatusToExperimentStatus(runStatus);

        ExperimentStatus finalStatus = chooseFinalStatus(
            mappedKubeflowStatus,
            mappedMlflowStatus,
            hasMetrics
    );

    if (isSuccessfulMlflowRun(runDetails, effectiveMetrics)) {
        finalStatus = ExperimentStatus.COMPLETED;
    }

    /*
     * Si MLflow est FINISHED et qu'une version modèle existe,
     * c'est un résultat exploitable même si MLflow metrics=0.
     * Cas fréquent avec CUSTOM_DOCKERFILE où les métriques viennent des logs.
     */
    if (mappedMlflowStatus == ExperimentStatus.COMPLETED
            && hasText(modelVersion)) {
        finalStatus = ExperimentStatus.COMPLETED;
    }

    finalStatus = keepStableFinalStatus(experiment.getStatus(), finalStatus);

    experiment.setStatus(finalStatus);
    applyExecutionTimestampsForStatus(experiment, finalStatus);
    updateMessageFromFinalStatus(experiment, finalStatus, runStatus);

    if (metricWarningMessage != null && !metricWarningMessage.isBlank()) {
        appendMetricWarningToMessage(experiment, metricWarningMessage);
    }

    if (!isEligiblePlatformVersion(experiment)) {
        clearVersioningAndProduction(experiment);
    }

    MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

        if (hasMetrics) {
        saveExperimentMetrics(savedExperiment, effectiveMetrics);
    }

    mlflowTrackingClientService.syncModelRegistryForExperiment(savedExperiment);

    /*
     * Protection versioning :
     * syncModelRegistryForExperiment peut rafraîchir les infos Registry.
     * Mais la version d'une ligne doit rester la version liée à SON run_id,
     * jamais la version globale pointée par l'alias production.
     */
    if (hasText(modelVersion)) {
        savedExperiment.setModelVersion(modelVersion);
        savedExperiment.setLatestVersion(modelVersion);
    }

    String refreshedProductionVersion =
            mlflowTrackingClientService.getProductionVersion(preferredRegisteredModelName);

    if (isEligiblePlatformVersion(savedExperiment) && hasText(refreshedProductionVersion)) {
        savedExperiment.setProductionVersion(refreshedProductionVersion);
    }

    if (!isEligiblePlatformVersion(savedExperiment)) {
        clearVersioningAndProduction(savedExperiment);
    }

    savedExperiment.setProduction(isProductionExperimentRow(savedExperiment));

    System.out.println("[MLFLOW_SYNC] runId="
            + effectiveRunId
            + ", runVersion="
            + savedExperiment.getModelVersion()
            + ", productionVersion="
            + savedExperiment.getProductionVersion()
            + ", isProduction="
            + savedExperiment.getProduction());

    MlExperiment finalSavedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);

    notifyExperimentStatusChangedIfNeeded(previousStatus, finalSavedExperiment);

    System.out.println("[AUTO_SYNC] Experiment marked " + finalSavedExperiment.getStatus()
            + " experimentId=" + finalSavedExperiment.getId()
            + ", modelVersion=" + finalSavedExperiment.getModelVersion()
            + ", productionVersion=" + finalSavedExperiment.getProductionVersion()
            + ", globalScore=" + finalSavedExperiment.getGlobalScore());
}


private ExperimentStatus keepStableFinalStatus(
        ExperimentStatus currentStatus,
        ExperimentStatus proposedStatus
) {
    if (proposedStatus == null) {
        return currentStatus != null ? currentStatus : ExperimentStatus.RUNNING;
    }

    if (currentStatus == null) {
        return proposedStatus;
    }

    /*
     * FAILED et CANCELLED restent prioritaires.
     */
    if (currentStatus == ExperimentStatus.FAILED
            || currentStatus == ExperimentStatus.CANCELLED) {
        return currentStatus;
    }

    /*
     * COMPLETED ne doit jamais redevenir RUNNING.
     */
    if (currentStatus == ExperimentStatus.COMPLETED
            && proposedStatus == ExperimentStatus.RUNNING) {
        return ExperimentStatus.COMPLETED;
    }

    return proposedStatus;
}

private String limitForDatabase(String value, int maxLength) {
    if (value == null) {
        return null;
    }

    if (value.length() <= maxLength) {
        return value;
    }

    return value.substring(0, maxLength) + "... [truncated]";
}


    @Transactional
    public DeleteExperimentResponse deleteExperimentCascade(MlExperiment experiment) {
        if (experiment == null) {
            throw new RuntimeException("Experiment is null");
        }

        Long experimentId = experiment.getId();

        String mlflowRunId = experiment.getMlflowRunId();
        String kubeflowRunId = experiment.getKubeflowRunId();
        String registeredModelName = experiment.getRegisteredModelName();
        String modelVersion = experiment.getModelVersion();

        boolean mlflowRunDeleted = false;
        boolean mlflowModelVersionDeleted = false;
        boolean kubeflowRunDeleted;
        boolean mysqlMetricsDeleted;
        boolean mysqlDeleted;

        if (registeredModelName != null
                && !registeredModelName.isBlank()
                && modelVersion != null
                && !modelVersion.isBlank()) {
            mlflowModelVersionDeleted = mlflowTrackingClientService.deleteModelVersion(
                    registeredModelName,
                    modelVersion
            );
        }

        if (mlflowRunId != null && !mlflowRunId.isBlank()) {
            mlflowRunDeleted = mlflowTrackingClientService.deleteRun(mlflowRunId);
        }

        if (kubeflowRunId != null && !kubeflowRunId.isBlank()) {
            kubeflowRunDeleted = pipelineClientService.deleteKubeflowRun(kubeflowRunId);
        } else {
            kubeflowRunDeleted = false;
        }

        metricRepository.deleteByExperimentId(experimentId);
        metricRepository.flush();
        mysqlMetricsDeleted = true;

        mlExperimentRepository.delete(experiment);
        mlExperimentRepository.flush();
        mysqlDeleted = true;

        return new DeleteExperimentResponse(
                experimentId,
                "Experiment deleted successfully.",
                mysqlDeleted,
                mysqlMetricsDeleted,
                mlflowRunDeleted,
                mlflowModelVersionDeleted
        );
    }

    private void saveExperimentMetrics(MlExperiment experiment, Map<String, Double> metrics) {
        if (experiment == null || experiment.getId() == null || metrics == null || metrics.isEmpty()) {
            return;
        }

        Long experimentId = experiment.getId();

        MlExperiment managedExperiment = mlExperimentRepository.findById(experimentId)
                .orElseThrow(() -> new RuntimeException(
                        "Experiment not found while saving metrics: " + experimentId
                ));

        Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);

        // Bulk delete is executed in its own repository transaction. It avoids leaving
        // stale managed metric entities in the persistence context.
        metricRepository.deleteAllByExperimentIdBulk(experimentId);

        if (normalizedMetrics.isEmpty()) {
            return;
        }

        List<MlExperimentMetric> metricsToSave = new ArrayList<>();

        for (Map.Entry<String, Double> entry : normalizedMetrics.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }

            MlExperimentMetric metric = new MlExperimentMetric();
            metric.setExperiment(managedExperiment);
            metric.setMetricName(entry.getKey());
            metric.setMetricValue(entry.getValue());
            metric.setDirection(getDirectionFromMetric(entry.getKey()));
            metric.setProductionVersion(managedExperiment.getProductionVersion());
            metric.setProduction(Boolean.TRUE.equals(managedExperiment.getProduction()));
            metric.setGlobalScore(managedExperiment.getGlobalScore());

            metricsToSave.add(metric);
        }

        if (!metricsToSave.isEmpty()) {
            metricRepository.saveAllAndFlush(metricsToSave);
        }
    }

    private boolean isDisplayablePerformanceMetric(String metricName) {
    String normalized = normalizeMetricKey(metricName);

    if (normalized.isBlank()) {
        return false;
    }

    if (normalized.equals("prediction")
            || normalized.equals("prediction_example")
            || normalized.equals("sample_prediction")
            || normalized.equals("return_code")
            || normalized.equals("status")
            || normalized.equals("epoch")
            || normalized.equals("step")
            || normalized.contains("example")) {
        return false;
    }

    return normalized.equals("accuracy")
            || normalized.equals("acc")
            || normalized.equals("precision")
            || normalized.equals("precision_weighted")
            || normalized.equals("recall")
            || normalized.equals("recall_weighted")
            || normalized.equals("f1")
            || normalized.equals("f1_score")
            || normalized.equals("f1_weighted")
            || normalized.equals("r2")
            || normalized.equals("r2_score")
            || normalized.equals("rmse")
            || normalized.equals("mae")
            || normalized.equals("mse")
            || normalized.equals("log_loss")
            || normalized.equals("loss")
            || normalized.contains("loss")
            || normalized.contains("error");
}


   private Map<String, Double> getMetricsMap(MlExperiment experiment) {
    Map<String, Double> result = new LinkedHashMap<>();

    if (experiment == null || experiment.getId() == null) {
        return result;
    }

    List<MlExperimentMetric> metrics = metricRepository.findByExperimentId(experiment.getId());

    if (metrics == null || metrics.isEmpty()) {
        return result;
    }

    for (MlExperimentMetric metric : metrics) {
        if (metric == null) {
            continue;
        }

        String metricName = normalizeMetricKey(metric.getMetricName());

        if (metricName.isBlank() || !isDisplayablePerformanceMetric(metricName)) {
            continue;
        }

        if (metric.getMetricValue() != null) {
            result.put(metricName, metric.getMetricValue());
        }
    }

    return result;
}


    private void updatePrimaryMetricFromProjectPriority(
        MlProject project,
        MlExperiment experiment,
        Map<String, Double> metrics
) {
    if (experiment == null || metrics == null || metrics.isEmpty()) {
        return;
    }

    Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);
    List<String> priorities = getProjectMetricPriorities(project);

    for (String metricName : priorities) {
        if (!isMetricCompatibleWithProjectType(project, metricName)) {
            continue;
        }

        Double value = getMetricValueCompatible(normalizedMetrics, metricName);

        if (value != null) {
            String normalizedMetric = normalizeMetricKey(metricName);
            experiment.setPrimaryMetric(normalizedMetric);
            experiment.setDirection(getDirectionFromMetric(normalizedMetric));
            experiment.setPrimaryMetricValue(value);
            return;
        }
    }

    updatePrimaryMetricFromAvailableMetrics(project, experiment, normalizedMetrics);
}


    private void updatePrimaryMetricFromAvailableMetrics(
        MlProject project,
        MlExperiment experiment,
        Map<String, Double> metrics
) {
    if (experiment == null || metrics == null || metrics.isEmpty()) {
        return;
    }

    List<String> priority = isRegressionProject(project)
            ? List.of("rmse", "mae", "r2", "r2_score", "mse")
            : List.of("f1_score", "f1_weighted", "f1", "accuracy", "precision", "precision_weighted", "recall", "recall_weighted");

    for (String metricName : priority) {
        Double value = getMetricValueCompatible(metrics, metricName);

        if (value != null) {
            String normalizedMetric = normalizeMetricKey(metricName);
            experiment.setPrimaryMetric(normalizedMetric);
            experiment.setDirection(getDirectionFromMetric(normalizedMetric));
            experiment.setPrimaryMetricValue(value);
            return;
        }
    }

    for (Map.Entry<String, Double> entry : metrics.entrySet()) {
        if (entry == null || entry.getKey() == null || entry.getValue() == null) {
            continue;
        }

        String normalizedMetric = normalizeMetricKey(entry.getKey());

        if (!isMetricCompatibleWithProjectType(project, normalizedMetric)) {
            continue;
        }

        experiment.setPrimaryMetric(normalizedMetric);
        experiment.setDirection(getDirectionFromMetric(normalizedMetric));
        experiment.setPrimaryMetricValue(entry.getValue());
        return;
    }
}


    private Double calculateSingleExperimentGlobalScore(MlProject project, Map<String, Double> metrics) {
    return calculatePriorityGlobalScore(project, metrics);
}

    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
public void recalculateGlobalScoresForProject(MlProject project, String registeredModelName) {
    if (project == null || registeredModelName == null || registeredModelName.isBlank()) {
        return;
    }

    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                    project,
                    registeredModelName
            );

    if (experiments == null || experiments.isEmpty()) {
        return;
    }

    for (MlExperiment experiment : experiments) {
        Map<String, Double> metrics = getMetricsMap(experiment);

        if (metrics == null || metrics.isEmpty()) {
            experiment.setGlobalScore(null);

            String fallbackPrimaryMetric = firstNotBlank(
                    project.getPrimaryMetric1(),
                    defaultPrimaryMetricForProject(project)
            );

            fallbackPrimaryMetric = normalizeMetricKey(fallbackPrimaryMetric);

            experiment.setPrimaryMetric(fallbackPrimaryMetric);
            experiment.setPrimaryMetricValue(null);
            experiment.setDirection(getDirectionFromMetric(fallbackPrimaryMetric));

            mlExperimentRepository.saveAndFlush(experiment);
            updateMetricRowsGlobalScore(experiment);
            continue;
        }

        updatePrimaryMetricFromProjectPriority(project, experiment, metrics);

        Double globalScore = calculatePriorityGlobalScore(project, metrics);
        experiment.setGlobalScore(globalScore);

        mlExperimentRepository.saveAndFlush(experiment);
        updateMetricRowsGlobalScore(experiment);
    }
}
private int compareExperimentsByMetricPriority(
        MlProject project,
        MlExperiment candidate,
        MlExperiment currentBest
) {
    if (candidate == null) {
        return -1;
    }

    if (currentBest == null) {
        return 1;
    }

    Map<String, Double> candidateMetrics = getMetricsMap(candidate);
    Map<String, Double> bestMetrics = getMetricsMap(currentBest);

    List<String> priorities = getProjectMetricPriorities(project);

    if (priorities == null || priorities.isEmpty()) {
        return compareNullableScores(candidate.getGlobalScore(), currentBest.getGlobalScore());
    }

    for (String metricName : priorities) {
        if (metricName == null || metricName.isBlank()) {
            continue;
        }

        Double candidateValue = getMetricValueCompatible(candidateMetrics, metricName);
        Double bestValue = getMetricValueCompatible(bestMetrics, metricName);

        if (candidateValue == null && bestValue == null) {
            continue;
        }

        if (candidateValue != null && bestValue == null) {
            return 1;
        }

        if (candidateValue == null) {
            return -1;
        }

        String direction = getDirectionFromMetric(metricName);

        int comparison;

        if ("min".equalsIgnoreCase(direction)) {
            /*
             * Pour rmse, mae, mse, loss :
             * plus petit = meilleur.
             */
            comparison = Double.compare(bestValue, candidateValue);
        } else {
            /*
             * Pour accuracy, f1, precision, recall, r2 :
             * plus grand = meilleur.
             */
            comparison = Double.compare(candidateValue, bestValue);
        }

        if (comparison != 0) {
            return comparison;
        }
    }

    return compareNullableScores(candidate.getGlobalScore(), currentBest.getGlobalScore());
}

private int compareNullableScores(Double candidateScore, Double bestScore) {
    if (candidateScore == null && bestScore == null) {
        return 0;
    }

    if (candidateScore != null && bestScore == null) {
        return 1;
    }

    if (candidateScore == null) {
        return -1;
    }

    return Double.compare(candidateScore, bestScore);
}

@org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
public void recalculateAllGlobalScoresForProject(MlProject project) {
    if (project == null || project.getId() == null) {
        return;
    }

    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    if (experiments == null || experiments.isEmpty()) {
        return;
    }

    List<String> registeredNames = experiments.stream()
        .map(MlExperiment::getRegisteredModelName)
        .filter(name -> name != null && !name.isBlank())
        .distinct()
        .toList();

    for (String registeredName : registeredNames) {
        recalculateGlobalScoresForProject(project, registeredName);
    }
}

    private List<String> getProjectMetricPriorities(MlProject project) {
    List<String> priorities = new ArrayList<>();

    if (project == null) {
        priorities.add("accuracy");
        return priorities;
    }

    List<String> configured = new ArrayList<>();
    configured.add(project.getPrimaryMetric1());
    configured.add(project.getPrimaryMetric2());
    configured.add(project.getPrimaryMetric3());

    for (String metric : configured) {
        String normalized = normalizeMetricKey(metric);

        if (normalized.isBlank()) {
            continue;
        }

        if (!isMetricCompatibleWithProjectType(project, normalized)) {
            System.out.println("[METRICS] Ignored incompatible project metric. project="
                    + project.getName()
                    + ", projectType="
                    + project.getProjectType()
                    + ", metric="
                    + metric);
            continue;
        }

        if (!priorities.contains(normalized)) {
            priorities.add(normalized);
        }
    }

    if (!priorities.isEmpty()) {
        return priorities;
    }

    if (isRegressionProject(project)) {
        priorities.add("rmse");
        priorities.add("mae");
        priorities.add("r2");
        priorities.add("mse");
        return priorities;
    }

    priorities.add("f1_score");
    priorities.add("accuracy");
    priorities.add("precision");
    priorities.add("recall");
    return priorities;
}


    private Double calculatePriorityGlobalScore(MlProject project, Map<String, Double> metrics) {
    if (project == null || metrics == null || metrics.isEmpty()) {
        return null;
    }

    Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);
    List<String> priorities = getProjectMetricPriorities(project);

    if (priorities == null || priorities.isEmpty()) {
        return null;
    }

    for (String metricName : priorities) {
        if (metricName == null || metricName.isBlank()) {
            continue;
        }

        if (!isMetricCompatibleWithProjectType(project, metricName)) {
            continue;
        }

        Double metricValue = getMetricValueCompatible(normalizedMetrics, metricName);

        if (metricValue == null) {
            continue;
        }

        Double normalizedScore = normalizeMetricScore(metricName, metricValue);

        System.out.println("[METRICS] projectType="
                + project.getProjectType()
                + ", selectedMetric="
                + normalizeMetricKey(metricName)
                + ", value="
                + metricValue
                + ", globalScore="
                + normalizedScore);

        if (normalizedScore != null) {
            return normalizedScore;
        }
    }

    return null;
}

    private Double normalizeMetricScore(String metricName, Double value) {
    if (metricName == null || value == null) {
        return null;
    }

    String normalizedMetric = normalizeMetricKey(metricName);
    String direction = getDirectionFromMetric(normalizedMetric);

    /*
     * Les métriques à minimiser comme RMSE/MAE/MSE sont transformées en score 0..1 :
     * plus l'erreur est petite, plus le score est proche de 1.
     */
    if ("min".equalsIgnoreCase(direction)) {
        double safeValue = Math.max(value, 0.0);
        return roundDouble(1.0 / (1.0 + safeValue), 6);
    }

    /*
     * Accuracy/F1/Precision/Recall/R2 sont naturellement des scores.
     */
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


    private void updateMetricRowsGlobalScore(MlExperiment experiment) {
    if (experiment == null || experiment.getId() == null) {
        return;
    }

    List<MlExperimentMetric> metrics = metricRepository.findByExperimentId(experiment.getId());

    if (metrics == null || metrics.isEmpty()) {
        return;
    }

    for (MlExperimentMetric metric : metrics) {
        if (metric == null) {
            continue;
        }

        metric.setGlobalScore(experiment.getGlobalScore());
        metric.setProductionVersion(experiment.getProductionVersion());
        metric.setProduction(Boolean.TRUE.equals(experiment.getProduction()));
    }

    metricRepository.saveAll(metrics);
    metricRepository.flush();
}

    private MlExperiment findBestExperimentByMetricPriority(MlProject project, String registeredModelName) {
    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                    project,
                    registeredModelName
            );

    if (experiments == null || experiments.isEmpty()) {
        return null;
    }

    MlExperiment best = null;

    for (MlExperiment experiment : experiments) {
        if (experiment == null) {
            continue;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            continue;
        }

        if (compareExperimentsByMetricPriority(project, experiment, best) > 0) {
            best = experiment;
        }
    }

    return best;
}

    private boolean isAutoPromoteEnabled(MlProject project) {
        return project == null
                || project.getAutoPromoteBestModel() == null
                || Boolean.TRUE.equals(project.getAutoPromoteBestModel());
    }

    private UploadedProjectFile saveUploadedFile(
            MlProject project,
            MultipartFile file,
            UploadedFileType fileType
    ) {
        try {
            String originalFileName = cleanFileName(file.getOriginalFilename());
            String extension = getExtension(originalFileName);
            String storedFileName = UUID.randomUUID() + extension;

            Path projectUploadPath = Paths.get(uploadDir, "projects", String.valueOf(project.getId()))
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(projectUploadPath);

            Path targetPath = projectUploadPath.resolve(storedFileName);

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            UploadedProjectFile uploadedFile = new UploadedProjectFile();
            uploadedFile.setOriginalFileName(originalFileName);
            uploadedFile.setStoredFileName(storedFileName);
            uploadedFile.setFilePath(targetPath.toString());
            uploadedFile.setFileType(fileType);
            uploadedFile.setContentType(file.getContentType());
            uploadedFile.setSizeBytes(file.getSize());
            uploadedFile.setProject(project);

            return uploadedProjectFileRepository.save(uploadedFile);

        } catch (Exception e) {
            throw new RuntimeException("Failed to save uploaded file: " + e.getMessage());
        }
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

    private ExperimentStatus detectStatusFromGatewayResponse(PipelineGatewayResponse gatewayResponse) {
        if (gatewayResponse == null) {
            return ExperimentStatus.FAILED;
        }

        String status = gatewayResponse.getStatus();
        String message = gatewayResponse.getMessage();

        if (status != null) {
            String normalizedStatus = status.trim().toUpperCase();

            if (normalizedStatus.contains("FAILED") || normalizedStatus.contains("ERROR")) {
                return ExperimentStatus.FAILED;
            }

            if (normalizedStatus.contains("RUNNING")
                    || normalizedStatus.contains("PENDING")
                    || normalizedStatus.contains("CREATED")) {
                return ExperimentStatus.RUNNING;
            }

            if (normalizedStatus.contains("COMPLETED")
                    || normalizedStatus.contains("SUCCESS")
                    || normalizedStatus.contains("SUCCEEDED")) {
                return ExperimentStatus.COMPLETED;
            }
        }

        if (message != null) {
            String normalizedMessage = message.toLowerCase();

            if (normalizedMessage.contains("failed")
                    || normalizedMessage.contains("error")
                    || normalizedMessage.contains("exception")
                    || normalizedMessage.contains("traceback")) {
                return ExperimentStatus.FAILED;
            }

            if (normalizedMessage.contains("pipeline launched")
                    || normalizedMessage.contains("pipeline kubeflow lancé")
                    || normalizedMessage.contains("lancé avec succès")
                    || normalizedMessage.contains("launched successfully")) {
                return ExperimentStatus.RUNNING;
            }

            if (normalizedMessage.contains("completed")
                    || normalizedMessage.contains("succeeded")) {
                return ExperimentStatus.COMPLETED;
            }
        }

        return ExperimentStatus.RUNNING;
    }

    private ExperimentStatus mapMlflowStatusToExperimentStatus(String mlflowStatus) {
        if (mlflowStatus == null) {
            return ExperimentStatus.RUNNING;
        }

        String normalized = mlflowStatus.trim().toUpperCase();

        if (normalized.equals("FINISHED")
                || normalized.equals("COMPLETED")
                || normalized.equals("SUCCEEDED")
                || normalized.equals("READY")) {
            return ExperimentStatus.COMPLETED;
        }

        if (normalized.equals("FAILED")
                || normalized.equals("KILLED")
                || normalized.equals("ERROR")) {
            return ExperimentStatus.FAILED;
        }

        return ExperimentStatus.RUNNING;
    }



    private MlExperiment reloadExperimentForResponse(Long experimentId) {
        if (experimentId == null) {
            throw new RuntimeException(
                    "experimentId is required to reload experiment response."
            );
        }

        return mlExperimentRepository
                .findByIdWithFiles(experimentId)
                .orElseThrow(() -> new RuntimeException(
                        "Experiment with project/files not found: "
                                + experimentId
                ));
    }

    private void notifyExperimentStatusChangedIfNeeded(
            ExperimentStatus previousStatus,
            MlExperiment experiment
    ) {
        if (experiment == null
                || experiment.getId() == null
                || experiment.getStatus() == null) {
            return;
        }

        ExperimentStatus currentStatus = experiment.getStatus();

        if (currentStatus != ExperimentStatus.COMPLETED
                && currentStatus != ExperimentStatus.FAILED) {
            return;
        }

        if (previousStatus == null
                || previousStatus == ExperimentStatus.COMPLETED
                || previousStatus == ExperimentStatus.FAILED
                || previousStatus == currentStatus) {
            return;
        }

        try {
            MlExperiment loaded =
                    mlExperimentRepository
                            .findByIdWithFiles(experiment.getId())
                            .orElse(experiment);

            if (currentStatus == ExperimentStatus.COMPLETED) {
                notificationService.notifyRunCompleted(loaded);
            } else {
                notificationService.notifyRunFailed(loaded);
            }

        } catch (Exception e) {
            System.out.println(
                    "[NOTIFICATION] Notification skipped. experimentId="
                            + experiment.getId()
                            + ", reason="
                            + e.getMessage()
            );
        }
    }

    private String getDirectionFromMetric(String metric) {
    String normalizedMetric = normalizeMetricKey(metric);

    if (normalizedMetric.isBlank()) {
        return "max";
    }

    if (normalizedMetric.equals("loss")
            || normalizedMetric.equals("log_loss")
            || normalizedMetric.equals("rmse")
            || normalizedMetric.equals("mse")
            || normalizedMetric.equals("mae")
            || normalizedMetric.equals("error")
            || normalizedMetric.endsWith("_loss")
            || normalizedMetric.endsWith("_error")
            || normalizedMetric.contains("loss")
            || normalizedMetric.contains("error")) {
        return "min";
    }

    return "max";
}


    private Double getMetricValueIgnoreCase(Map<String, Double> metrics, String metricName) {
    if (metrics == null || metrics.isEmpty() || metricName == null || metricName.isBlank()) {
        return null;
    }

    String normalizedMetricName = normalizeMetricKey(metricName);

    for (Map.Entry<String, Double> entry : metrics.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
            continue;
        }

        String key = normalizeMetricKey(entry.getKey());

        if (key.equals(normalizedMetricName)) {
            return entry.getValue();
        }
    }

    return null;
}


    private Double getMetricValueCompatible(Map<String, Double> metrics, String metricName) {
    if (metrics == null || metrics.isEmpty() || metricName == null || metricName.isBlank()) {
        return null;
    }

    Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);
    String normalized = normalizeMetricKey(metricName);

    if (normalizedMetrics.containsKey(normalized)) {
        return normalizedMetrics.get(normalized);
    }

    if (normalized.equals("f1") || normalized.equals("f1_score")) {
        return firstMetricValue(normalizedMetrics, "f1_score", "f1", "f1_weighted");
    }

    if (normalized.equals("precision")) {
        return firstMetricValue(normalizedMetrics, "precision", "precision_weighted");
    }

    if (normalized.equals("recall")) {
        return firstMetricValue(normalizedMetrics, "recall", "recall_weighted");
    }

    if (normalized.equals("accuracy") || normalized.equals("acc")) {
        return firstMetricValue(normalizedMetrics, "accuracy", "acc");
    }

    if (normalized.equals("r2") || normalized.equals("r2_score")) {
        return firstMetricValue(normalizedMetrics, "r2", "r2_score");
    }

    if (normalized.equals("rmse")) {
        return firstMetricValue(normalizedMetrics, "rmse", "root_mean_squared_error");
    }

    if (normalized.equals("mse")) {
        return firstMetricValue(normalizedMetrics, "mse", "mean_squared_error");
    }

    if (normalized.equals("mae")) {
        return firstMetricValue(normalizedMetrics, "mae", "mean_absolute_error");
    }

    return null;
}


    private String normalizeMetricKey(String metricName) {
    if (metricName == null) {
        return "";
    }

    String normalized = metricName.trim()
            .toLowerCase(Locale.ROOT)
            .replace("-", "_")
            .replace(" ", "_")
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");

    if (normalized.equals("f1score") || normalized.equals("f1_score_score")) {
        return "f1_score";
    }

    if (normalized.equals("f1_score") || normalized.equals("f1")) {
        return normalized;
    }

    if (normalized.equals("acc")) {
        return "accuracy";
    }

    if (normalized.equals("r2score") || normalized.equals("r2_score")) {
        return "r2";
    }

    if (normalized.equals("root_mean_squared_error")) {
        return "rmse";
    }

    if (normalized.equals("mean_absolute_error")) {
        return "mae";
    }

    if (normalized.equals("mean_squared_error")) {
        return "mse";
    }

    return normalized;
}

private Map<String, Double> normalizeMetricsMap(Map<String, Double> metrics) {
    Map<String, Double> normalizedMetrics = new LinkedHashMap<>();

    if (metrics == null || metrics.isEmpty()) {
        return normalizedMetrics;
    }

    for (Map.Entry<String, Double> entry : metrics.entrySet()) {
        if (entry == null || entry.getKey() == null || entry.getValue() == null) {
            continue;
        }

        String normalizedKey = normalizeMetricKey(entry.getKey());

        if (normalizedKey.isBlank() || !isDisplayablePerformanceMetric(normalizedKey)) {
            continue;
        }

        normalizedMetrics.put(normalizedKey, entry.getValue());
    }

    return normalizedMetrics;
}

private Double firstMetricValue(Map<String, Double> metrics, String... names) {
    if (metrics == null || metrics.isEmpty() || names == null) {
        return null;
    }

    for (String name : names) {
        String normalizedName = normalizeMetricKey(name);

        if (metrics.containsKey(normalizedName)) {
            return metrics.get(normalizedName);
        }
    }

    return null;
}

private boolean isRegressionProject(MlProject project) {
    if (project == null || project.getProjectType() == null) {
        return false;
    }

    return project.getProjectType().trim().equalsIgnoreCase("regression");
}

private boolean isClassificationProject(MlProject project) {
    if (project == null || project.getProjectType() == null) {
        return true;
    }

    return project.getProjectType().trim().equalsIgnoreCase("classification");
}

private boolean isMetricCompatibleWithProjectType(MlProject project, String metricName) {
    String normalized = normalizeMetricKey(metricName);

    if (normalized.isBlank()) {
        return false;
    }

    if (isRegressionProject(project)) {
        return normalized.equals("rmse")
                || normalized.equals("mae")
                || normalized.equals("mse")
                || normalized.equals("r2")
                || normalized.equals("r2_score")
                || normalized.equals("loss")
                || normalized.contains("error");
    }

    return normalized.equals("accuracy")
            || normalized.equals("acc")
            || normalized.equals("precision")
            || normalized.equals("precision_weighted")
            || normalized.equals("recall")
            || normalized.equals("recall_weighted")
            || normalized.equals("f1")
            || normalized.equals("f1_score")
            || normalized.equals("f1_weighted")
            || normalized.equals("log_loss")
            || normalized.equals("loss");
}

private String defaultPrimaryMetricForProject(MlProject project) {
    if (isRegressionProject(project)) {
        return "rmse";
    }

    return "accuracy";
}


    private Double roundDouble(Double value, int decimals) {
        if (value == null) {
            return null;
        }

        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private String buildMlflowExperimentName(MlProject project) {
        String baseName = null;

        if (project != null && project.getName() != null && !project.getName().isBlank()) {
            baseName = project.getName();
        } else if (project != null && project.getId() != null) {
            baseName = "project_" + project.getId();
        } else {
            baseName = "project";
        }

        String cleanName = baseName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = project != null && project.getId() != null
                    ? "project_" + project.getId()
                    : "project";
        }

        return cleanName;
    }

    private String buildRegisteredModelName(MlProject project) {
        String baseName = null;

        if (project != null && project.getName() != null && !project.getName().isBlank()) {
            baseName = project.getName();
        } else if (project != null && project.getId() != null) {
            baseName = "project_" + project.getId();
        } else {
            baseName = "project";
        }

        String cleanName = baseName
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = project != null && project.getId() != null
                    ? "project_" + project.getId()
                    : "project";
        }

        if (cleanName.endsWith("_model")) {
            return cleanName;
        }

        return cleanName + "_model";
    }

    private String defaultValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private String buildUniqueRunName(String runName) {
        String clean = defaultValue(runName, "training-run");

        String suffix = utcNow()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String shortId = UUID.randomUUID().toString().substring(0, 8);

        return clean + "_" + suffix + "_" + shortId;
    }

    private String normalizeProjectType(String projectType) {
        if (projectType == null || projectType.isBlank()) {
            return "classification";
        }

        String normalized = projectType.trim().toLowerCase();

        if (!normalized.equals("regression") && !normalized.equals("classification")) {
            throw new RuntimeException("Invalid project type. Allowed values: regression, classification");
        }

        return normalized;
    }

    private String cleanFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "uploaded-file";
        }

        return Paths.get(fileName)
                .getFileName()
                .toString()
                .replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf(".");

        if (lastDotIndex == -1) {
            return "";
        }

        return fileName.substring(lastDotIndex);
    }

    private MlExperimentResponse mapToResponse(MlExperiment experiment) {
        UploadedProjectFile datasetFile = experiment.getDatasetFile();
        UploadedProjectFile modelFile = experiment.getModelFile();

        MlExperimentResponse response = new MlExperimentResponse(
                experiment.getId(),
                experiment.getExperimentName(),
                experiment.getStatus() != null ? experiment.getStatus().name() : "-",

                experiment.getProject().getId(),
                experiment.getProject().getName(),

                datasetFile != null ? datasetFile.getId() : null,
                datasetFile != null ? datasetFile.getOriginalFileName() : null,

                modelFile != null ? modelFile.getId() : null,
                modelFile != null ? modelFile.getOriginalFileName() : null,

                experiment.getModelType(),
                experiment.getModelName(),
                experiment.getPrimaryMetric(),
                experiment.getDirection(),

                experiment.getKubeflowRunId(),
                experiment.getKubeflowRunName(),
                experiment.getKubeflowStatus(),

                experiment.getMlflowExperimentName(),
                experiment.getMlflowExperimentId(),
                experiment.getMlflowRunId(),

                experiment.getRegisteredModelName(),
                experiment.getModelVersion(),
                experiment.getLatestVersion(),
                experiment.getProductionVersion(),

                experiment.getProduction(),
                experiment.getGlobalScore(),

                experiment.getPrimaryMetricValue(),
                getMetricsMap(experiment),

                experiment.getMessage(),
                experiment.getErrorMessage(),

                toUtcIsoString(experiment.getCreatedAt()),
                toUtcIsoString(experiment.getUpdatedAt())
        );

        response.setStartedAt(toUtcIsoString(experiment.getStartedAt()));
        response.setFinishedAt(toUtcIsoString(experiment.getFinishedAt()));
        response.setCreatedAt(toUtcIsoString(experiment.getCreatedAt()));
        response.setUpdatedAt(toUtcIsoString(experiment.getUpdatedAt()));

        response.setDetectedAlgorithms(experiment.getDetectedAlgorithms());
        response.setRuntimeMode(experiment.getRuntimeMode());
        response.setCustomImageUri(experiment.getCustomImageUri());
        response.setDockerfilePath(experiment.getDockerfilePath());
        response.setRequirementsPath(experiment.getRequirementsPath());
        response.setBuildStatus(experiment.getBuildStatus());
        response.setBuildLogs(experiment.getBuildLogs());
        response.setTrainingLogs(experiment.getTrainingLogs());
        response.setFailurePhase(experiment.getFailurePhase());
        response.setFailureReason(experiment.getFailureReason());
        response.setFailureDetails(experiment.getFailureDetails());
        response.setFailureSuggestion(experiment.getFailureSuggestion());
        response.setFailureLogExcerpt(experiment.getFailureLogExcerpt());
        response.setFailureSourceFile(experiment.getFailureSourceFile());

        return response;
    }

    private void syncAlgorithmMetadataFromRunTags(
            MlExperiment experiment,
            MlflowTrackingClientService.MlflowRunDetails runDetails
    ) {
        if (experiment == null || runDetails == null) {
            return;
        }

        String detectedAlgorithms = firstNotBlank(
                runDetails.getTag("detected_algorithms"),
                runDetails.getTag("detectedAlgorithms"),
                runDetails.getTag("mlops.detected_algorithms"),
                runDetails.getTag("mlops.detectedAlgorithms")
        );

        String usedAlgorithms = firstNotBlank(
                runDetails.getTag("used_algorithms"),
                runDetails.getTag("usedAlgorithms"),
                runDetails.getTag("mlops.used_algorithms"),
                runDetails.getTag("mlops.usedAlgorithms")
        );

        if (detectedAlgorithms != null && !detectedAlgorithms.isBlank()) {
            experiment.setDetectedAlgorithms(detectedAlgorithms);
        }

        if (usedAlgorithms != null && !usedAlgorithms.isBlank()) {
            experiment.setUsedAlgorithms(usedAlgorithms);
        }

        System.out.println("[MlExperimentService] Synced metadata from MLflow: "
                + "runtimeMode=" + experiment.getRuntimeMode()
                + ", detected=" + experiment.getDetectedAlgorithms()
                + ", used=" + experiment.getUsedAlgorithms());
    }

    private ScriptAnalysisResult analyzeScriptCompatibility(
            UploadedProjectFile modelFile
    ) {
        ScriptAnalysisResult result = new ScriptAnalysisResult();

        if (modelFile == null || modelFile.getFilePath() == null || modelFile.getFilePath().isBlank()) {
            result.setRuntimeCompatible(false);
            result.setMessage("Model file not found");
            return result;
        }

        try {
            Path scriptPath = Paths.get(modelFile.getFilePath()).toAbsolutePath().normalize();

            if (!Files.exists(scriptPath)) {
                result.setRuntimeCompatible(false);
                result.setMessage("Script file does not exist: " + scriptPath);
                return result;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("script_file", new FileSystemResource(scriptPath));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            System.out.println("[MlExperimentService] Calling script analysis API: " + runtimeAnalysisApiUrl);
            System.out.println("[MlExperimentService] Script path: " + scriptPath);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    runtimeAnalysisApiUrl,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode().isError()) {
                result.setRuntimeCompatible(false);
                result.setMessage("Script analysis API error: " + response.getStatusCode());
                return result;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode responseNode = mapper.readTree(response.getBody() != null ? response.getBody() : "{}");

            String detectedModules = firstNonBlank(
                    extractJsonArray(responseNode, "detectedModules"),
                    extractJsonArray(responseNode, "detected_modules"),
                    extractJsonArray(responseNode, "detectedImports"),
                    extractJsonArray(responseNode, "detected_imports")
            );

            String supportedModules = firstNonBlank(
                    extractJsonArray(responseNode, "supportedModules"),
                    extractJsonArray(responseNode, "supported_modules"),
                    extractJsonArray(responseNode, "supportedImports"),
                    extractJsonArray(responseNode, "supported_imports")
            );

            String unsupportedModules = firstNonBlank(
                    extractJsonArray(responseNode, "unsupportedModules"),
                    extractJsonArray(responseNode, "unsupported_modules"),
                    extractJsonArray(responseNode, "unsupportedImports"),
                    extractJsonArray(responseNode, "unsupported_imports")
            );

            String detectedAlgorithms = firstNonBlank(
                    extractJsonArray(responseNode, "detectedAlgorithms"),
                    extractJsonArray(responseNode, "detected_algorithms"),
                    extractJsonArray(responseNode, "detectedComponents"),
                    extractJsonArray(responseNode, "detected_components")
            );

            boolean runtimeCompatible;

            if (responseNode.has("runtimeCompatible")) {
                runtimeCompatible = responseNode.path("runtimeCompatible").asBoolean(false);
            } else if (responseNode.has("runtime_compatible")) {
                runtimeCompatible = responseNode.path("runtime_compatible").asBoolean(false);
            } else if (responseNode.has("supported")) {
                runtimeCompatible = responseNode.path("supported").asBoolean(false);
            } else {
                runtimeCompatible = unsupportedModules == null || unsupportedModules.isBlank();
            }

            result.setDetectedModules(detectedModules);
            result.setSupportedModules(supportedModules);
            result.setUnsupportedModules(unsupportedModules);
            result.setDetectedAlgorithms(detectedAlgorithms);
            result.setRuntimeCompatible(runtimeCompatible);

            result.setMessage(firstNonBlank(
                    getTextField(responseNode, "message"),
                    runtimeCompatible
                            ? "Script compatible with STANDARD_RUNTIME"
                            : "Script not compatible with STANDARD_RUNTIME"
            ));

            result.setRecommendation(firstNonBlank(
                    getTextField(responseNode, "recommendation"),
                    runtimeCompatible
                            ? "Runtime standard compatible"
                            : "Use CUSTOM_DOCKERFILE mode or add missing dependencies."
            ));

            System.out.println("[MlExperimentService] Script analysis result: "
                    + "runtimeCompatible=" + result.isRuntimeCompatible()
                    + ", detectedModules=" + result.getDetectedModules()
                    + ", unsupportedModules=" + result.getUnsupportedModules()
                    + ", detectedAlgorithms=" + result.getDetectedAlgorithms());

            return result;

        } catch (Exception e) {
            System.out.println("[RUNTIME_ANALYSIS] Analysis API unavailable, continuing with STANDARD_RUNTIME.");
            System.out.println("[RUNTIME_ANALYSIS] Reason: " + e.getMessage());

            result.setRuntimeCompatible(true);
            result.setMessage("Runtime analysis unavailable in AKS. Continuing with STANDARD_RUNTIME.");
            result.setRecommendation("Runtime analysis unavailable in AKS. Continuing with STANDARD_RUNTIME.");

            return result;
        }
    }

    private String extractJsonArray(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }

        JsonNode field = node.get(fieldName);

        if (field == null || field.isNull() || field.isMissingNode()) {
            return "";
        }

        List<String> items = new ArrayList<>();

        if (field.isArray()) {
            for (JsonNode item : field) {
                if (item == null || item.isNull()) {
                    continue;
                }

                if (item.isTextual()) {
                    String value = item.asText();
                    if (value != null && !value.isBlank()) {
                        items.add(value.trim());
                    }
                } else if (item.isObject()) {
                    String name = firstNonBlank(
                            getTextField(item, "name"),
                            getTextField(item, "module"),
                            getTextField(item, "importName"),
                            getTextField(item, "packageName")
                    );

                    if (name != null && !name.isBlank()) {
                        items.add(name.trim());
                    }
                } else {
                    String value = item.asText();
                    if (value != null && !value.isBlank()) {
                        items.add(value.trim());
                    }
                }
            }

            return String.join(", ", items);
        }

        if (field.isTextual()) {
            return field.asText("");
        }

        return "";
    }

    private String getTextField(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }

        JsonNode field = node.get(fieldName);

        if (field == null || field.isNull() || field.isMissingNode()) {
            return "";
        }

        return field.asText("");
    }

public void syncAllProjectsAutomatically() {
    List<Long> projectIds = mlProjectRepository.findAll()
            .stream()
            .filter(project -> project != null && project.getId() != null)
            .map(MlProject::getId)
            .toList();

    if (projectIds.isEmpty()) {
        System.out.println("[AUTO_SYNC] No projects found.");
        return;
    }

    for (Long projectId : projectIds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(120);

        try {
            template.executeWithoutResult(status -> {
                MlProject project = mlProjectRepository.findById(projectId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Project not found during AUTO_SYNC. projectId=" + projectId
                        ));

                syncSingleProjectAutomatically(project);
            });

        } catch (Exception e) {
            System.out.println("[AUTO_SYNC] Failed for project id=" + projectId);
            System.out.println("[AUTO_SYNC] Reason: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

private Optional<MlExperiment> findExistingExperimentBeforeCreate(
        Long projectId,
        String experimentName,
        String registeredModelName,
        String mlflowRunId,
        String kubeflowRunId
) {
    if (mlflowRunId != null && !mlflowRunId.isBlank()) {
        Optional<MlExperiment> byMlflowRunId =
                mlExperimentRepository.findByMlflowRunId(mlflowRunId);

        if (byMlflowRunId.isPresent()) {
            return byMlflowRunId;
        }
    }

    if (kubeflowRunId != null && !kubeflowRunId.isBlank()) {
        Optional<MlExperiment> byKubeflowRunId =
                mlExperimentRepository.findByKubeflowRunId(kubeflowRunId);

        if (byKubeflowRunId.isPresent()) {
            return byKubeflowRunId;
        }
    }

    if (projectId != null
            && experimentName != null && !experimentName.isBlank()
            && registeredModelName != null && !registeredModelName.isBlank()) {
        return mlExperimentRepository
                .findTopByProjectIdAndExperimentNameAndRegisteredModelNameOrderByUpdatedAtDesc(
                        projectId,
                        experimentName,
                        registeredModelName
                );
    }

    return Optional.empty();
}
private void refreshProductionVersionFlagsForProject(MlProject project, String registeredModelName) {
    if (project == null || !hasText(registeredModelName)) {
        return;
    }

    String cleanRegisteredModelName = registeredModelName.trim();
    String registryProductionVersion = mlflowTrackingClientService
            .getProductionVersion(cleanRegisteredModelName);

    if (hasText(registryProductionVersion)) {
        registryProductionVersion = registryProductionVersion.trim();
    } else {
        registryProductionVersion = null;
    }

    List<MlExperiment> experiments = mlExperimentRepository
            .findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                    project,
                    cleanRegisteredModelName
            );

    if (experiments == null || experiments.isEmpty()) {
        return;
    }

    MlExperiment productionExperiment = null;

    for (MlExperiment experiment : experiments) {
        if (experiment == null) {
            continue;
        }

        /*
         * Source de vérité de modelVersion : la ModelVersion MLflow dont
         * run_id correspond exactement au mlflowRunId de cette ligne.
         * On ne recopie jamais productionVersion dans modelVersion.
         */
        if (hasText(experiment.getMlflowRunId())) {
            String exactVersion = mlflowTrackingClientService.findModelVersionByRunId(
                    cleanRegisteredModelName,
                    experiment.getMlflowRunId().trim()
            );

            if (hasText(exactVersion)) {
                experiment.setModelVersion(exactVersion.trim());
            }
        }

        experiment.setProductionVersion(registryProductionVersion);
        experiment.setProduction(false);

        boolean exactProductionRun =
                registryProductionVersion != null
                        && hasText(experiment.getModelVersion())
                        && registryProductionVersion.equals(experiment.getModelVersion().trim())
                        && isEligiblePlatformVersion(experiment);

        if (exactProductionRun
                && (productionExperiment == null
                || compareProductionCandidates(experiment, productionExperiment) > 0)) {
            productionExperiment = experiment;
        }
    }

    if (productionExperiment != null) {
        productionExperiment.setProduction(true);
    }

    /*
     * Important : cette méthode ne modifie jamais l'alias MLflow.
     * L'alias est modifié uniquement par le workflow explicite de promotion,
     * après validation du candidat. Une simple synchronisation ne doit pas
     * changer la version Production.
     */
    mlExperimentRepository.saveAll(experiments);
    mlExperimentRepository.flush();
}

private boolean isEligibleProductionCandidate(MlExperiment experiment, String productionVersion) {
    if (!isEligiblePlatformVersion(experiment) || !hasText(productionVersion)) {
        return false;
    }

    return productionVersion.trim().equalsIgnoreCase(experiment.getModelVersion().trim());
}
private int compareProductionCandidates(MlExperiment left, MlExperiment right) {
    if (left == null && right == null) {
        return 0;
    }

    if (left == null) {
        return -1;
    }

    if (right == null) {
        return 1;
    }

    int scoreComparison = Double.compare(scoreForProductionCandidate(left), scoreForProductionCandidate(right));
    if (scoreComparison != 0) {
        return scoreComparison;
    }

    LocalDateTime leftDate = firstNonNullDate(left.getFinishedAt(), left.getStartedAt(), left.getCreatedAt(), left.getUpdatedAt());
    LocalDateTime rightDate = firstNonNullDate(right.getFinishedAt(), right.getStartedAt(), right.getCreatedAt(), right.getUpdatedAt());

    if (leftDate != null && rightDate != null) {
        int dateComparison = leftDate.compareTo(rightDate);
        if (dateComparison != 0) {
            return dateComparison;
        }
    } else if (leftDate != null) {
        return 1;
    } else if (rightDate != null) {
        return -1;
    }

    Long leftId = left.getId() != null ? left.getId() : -1L;
    Long rightId = right.getId() != null ? right.getId() : -1L;
    return leftId.compareTo(rightId);
}

private double scoreForProductionCandidate(MlExperiment experiment) {
    if (experiment == null) {
        return -1.0;
    }

    if (experiment.getGlobalScore() != null) {
        return experiment.getGlobalScore();
    }

    if (experiment.getPrimaryMetricValue() != null) {
        return experiment.getPrimaryMetricValue();
    }

    return -1.0;
}

private LocalDateTime firstNonNullDate(LocalDateTime... values) {
    if (values == null) {
        return null;
    }

    for (LocalDateTime value : values) {
        if (value != null) {
            return value;
        }
    }

    return null;
}

private void syncSingleProjectAutomatically(MlProject project) {
    if (project == null || project.getId() == null) {
        return;
    }

    System.out.println("[AUTO_SYNC] Start project id=" + project.getId()
            + ", name=" + project.getName());

    /*
     * 1) Synchroniser les expériences déjà créées en MySQL.
     */
    syncExistingProjectExperiments(project, "auto-before-mlflow-import");

    /*
     * 2) Importer les runs MLflow manquants.
     */
    importMissingMlflowRunsIntoMysql(project);

    /*
     * 3) Fusionner les doublons Kubeflow-only / MLflow-only.
     */
    mergeDuplicateMlflowKubeflowRows(project);

    /*
     * 4) Resynchroniser après import et fusion.
     */
    syncExistingProjectExperiments(project, "auto-after-mlflow-import");

    List<MlExperiment> refreshedExperiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    List<String> registeredNames = refreshedExperiments == null
            ? new ArrayList<>()
            : refreshedExperiments.stream()
                    .map(MlExperiment::getRegisteredModelName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();

    for (String registeredName : registeredNames) {
        try {
            /*
             * 5) Recalculer les scores globaux.
             */
            recalculateGlobalScoresForProject(project, registeredName);

            /*
             * 6) Synchroniser les flags production depuis MLflow.
             */
            refreshProductionVersionFlagsForProject(project, registeredName);

            /*
             * 7) Auto-promotion + auto-déploiement si activé.
             */
            if (isAutoPromoteEnabled(project)) {
                autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                        project,
                        registeredName
                );

                /*
                 * Recalcul final pour rafraîchir les métriques et flags production.
                 */
                recalculateGlobalScoresForProject(project, registeredName);
            }

        } catch (Exception e) {
            System.out.println("[AUTO_SYNC] Auto lifecycle failed for model=" + registeredName);
            System.out.println("[AUTO_SYNC] Reason: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /*
     * 8) Dernière sync pour rafraîchir model_version, production_version, is_production.
     */
    syncExistingProjectExperiments(project, "auto-after-production-promotion");

    /*
     * 9) Dernière fusion au cas où une ligne MLflow-only vient d'être créée.
     */
    mergeDuplicateMlflowKubeflowRows(project);

    /*
     * 10) Appliquer la règle globale définitive :
     * pas de métrique + pas de score => pas de version, pas de Production, pas de déploiement.
     */
    enforceProductionConsistencyForAllModels();

    /*
     * 11) Nettoyer les lignes RUNNING/CREATED inutiles :
     * - sans MLflow
     * - sans Kubeflow
     * - sans training job
     * - sans version
     */
    deleteUselessOrphanExperimentRows(project);

    System.out.println("[AUTO_SYNC] Finished project id=" + project.getId()
            + ", name=" + project.getName());
}
    


private LocalDateTime utcNow() {
    return LocalDateTime.now(ZoneOffset.UTC);
}

private String toUtcIsoString(LocalDateTime value) {
    if (value == null) {
        return null;
    }

    return value
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
}

private void markExperimentStarted(MlExperiment experiment) {
    if (experiment == null) {
        return;
    }

    if (experiment.getStartedAt() == null) {
        experiment.setStartedAt(utcNow());
    }

    experiment.setFinishedAt(null);
}

private void applyExecutionTimestampsForStatus(MlExperiment experiment, ExperimentStatus status) {
    if (experiment == null || status == null) {
        return;
    }

    if (status == ExperimentStatus.RUNNING) {
        if (experiment.getStartedAt() == null) {
            experiment.setStartedAt(utcNow());
        }

        experiment.setFinishedAt(null);
        return;
    }

    if (status == ExperimentStatus.COMPLETED
            || status == ExperimentStatus.FAILED
            || status == ExperimentStatus.CANCELLED) {

        if (experiment.getStartedAt() == null) {
            experiment.setStartedAt(
                    experiment.getCreatedAt() != null
                            ? experiment.getCreatedAt()
                            : utcNow()
            );
        }

        if (experiment.getFinishedAt() == null) {
            experiment.setFinishedAt(utcNow());
        }
    }
}

public MlExperimentResponse retrainFromExistingExperiment(
        Long projectId,
        Long sourceExperimentId
) {
    if (projectId == null) {
        throw new RuntimeException("projectId is required for scheduled retraining.");
    }

    if (sourceExperimentId == null) {
        throw new RuntimeException("sourceExperimentId is required for scheduled retraining.");
    }

    /*
     * IMPORTANT:
     * The retraining workflow runs outside a long Hibernate session.
     * Load project + modelFile + datasetFile eagerly in one query so that
     * UploadedProjectFile proxies remain usable after the repository call.
     */
    MlExperiment sourceExperiment = mlExperimentRepository
            .findByIdWithFiles(sourceExperimentId)
            .orElseThrow(() -> new RuntimeException(
                    "Source experiment with project/files not found: "
                            + sourceExperimentId
            ));

    MlProject project = sourceExperiment.getProject();

    if (project == null || project.getId() == null) {
        throw new RuntimeException("Source experiment has no project.");
    }

    if (!project.getId().equals(projectId)) {
        throw new RuntimeException(
                "Source experiment does not belong to projectId=" + projectId
                        + ". sourceProjectId=" + project.getId()
        );
    }

    UploadedProjectFile sourceModelFile = sourceExperiment.getModelFile();

    if (sourceModelFile == null
            || sourceModelFile.getFilePath() == null
            || sourceModelFile.getFilePath().isBlank()) {
        throw new RuntimeException(
                "Source experiment has no model/script file. Cannot schedule retraining."
        );
    }

    UploadedProjectFile sourceDatasetFile = sourceExperiment.getDatasetFile();

    if (sourceDatasetFile == null
            || sourceDatasetFile.getFilePath() == null
            || sourceDatasetFile.getFilePath().isBlank()) {
        throw new RuntimeException(
                "Source experiment has no dataset file. Cannot schedule retraining."
        );
    }

    String effectiveRuntimeMode = defaultValue(
            sourceExperiment.getRuntimeMode(),
            getProjectRuntimeMode(project)
    );

    if (effectiveRuntimeMode == null || effectiveRuntimeMode.isBlank()) {
        effectiveRuntimeMode = "STANDARD_RUNTIME";
    }

    effectiveRuntimeMode = effectiveRuntimeMode.trim().toUpperCase(Locale.ROOT);

    String registeredModelName = defaultValue(
            sourceExperiment.getRegisteredModelName(),
            buildRegisteredModelName(project)
    );

    String mlflowExperimentName = isCustomDockerfileMode(effectiveRuntimeMode)
            ? registeredModelName
            : buildMlflowExperimentName(project);

    String primaryMetric = firstNotBlank(
            sourceExperiment.getPrimaryMetric(),
            project.getPrimaryMetric1(),
            defaultPrimaryMetricForProject(project)
    );

    if (primaryMetric == null || primaryMetric.isBlank()) {
        primaryMetric = defaultPrimaryMetricForProject(project);
    }

    primaryMetric = normalizeMetricKey(primaryMetric);

    String direction = defaultValue(
            sourceExperiment.getDirection(),
            getDirectionFromMetric(primaryMetric)
    );

    String projectType = normalizeProjectType(project.getProjectType());

    String cleanModelName = defaultValue(
            sourceExperiment.getModelName(),
            registeredModelName
    );

    String cleanTargetColumn = defaultValue(
            sourceExperiment.getTargetColumn(),
            project.getTargetColumn()
    );

    String newRunName = buildUniqueRunName(
            "scheduled_retraining_" + project.getName()
    );

    MlExperiment newExperiment = new MlExperiment();
    newExperiment.setExperimentName(newRunName);
    newExperiment.setProject(project);
    newExperiment.setDatasetFile(sourceDatasetFile);
    newExperiment.setModelFile(sourceModelFile);

    newExperiment.setModelType(defaultValue(sourceExperiment.getModelType(), "CUSTOM"));
    newExperiment.setModelName(cleanModelName);
    newExperiment.setRegisteredModelName(registeredModelName);
    newExperiment.setMlflowExperimentName(mlflowExperimentName);

    newExperiment.setPrimaryMetric(primaryMetric);
    newExperiment.setDirection(direction);
    newExperiment.setTargetColumn(cleanTargetColumn);

    newExperiment.setProduction(false);
    newExperiment.setRuntimeMode(effectiveRuntimeMode);

    newExperiment.setCustomImageUri(sourceExperiment.getCustomImageUri());
    newExperiment.setDockerfilePath(sourceExperiment.getDockerfilePath());
    newExperiment.setRequirementsPath(sourceExperiment.getRequirementsPath());

    newExperiment.setDetectedAlgorithms(sourceExperiment.getDetectedAlgorithms());
    newExperiment.setUsedAlgorithms(sourceExperiment.getUsedAlgorithms());

    newExperiment.setMlflowRunId(null);
    newExperiment.setMlflowExperimentId(null);
    newExperiment.setKubeflowRunId(null);
    newExperiment.setKubeflowRunName(null);
    newExperiment.setKubeflowStatus(null);
    newExperiment.setTrainingJobName(null);
    newExperiment.setBuildJobName(null);

    newExperiment.setModelVersion(null);
    newExperiment.setLatestVersion(null);
    newExperiment.setProductionVersion(null);
    newExperiment.setGlobalScore(null);
    newExperiment.setPrimaryMetricValue(null);
    newExperiment.setErrorMessage(null);

    System.out.println("[AUTO_RETRAINING] Creating new scheduled experiment from sourceExperimentId="
            + sourceExperimentId
            + ", projectId="
            + projectId
            + ", runtimeMode="
            + effectiveRuntimeMode
            + ", registeredModelName="
            + registeredModelName
            + ", runName="
            + newRunName);

    if (isCustomDockerfileMode(effectiveRuntimeMode)) {
        return launchScheduledCustomDockerRetraining(
                project,
                newExperiment
        );
    }

    return launchScheduledStandardRuntimeRetraining(
            project,
            newExperiment,
            cleanModelName,
            registeredModelName,
            newRunName,
            cleanTargetColumn,
            primaryMetric,
            direction,
            projectType,
            effectiveRuntimeMode,
            sourceModelFile,
            sourceDatasetFile
    );
}

private MlExperimentResponse launchScheduledCustomDockerRetraining(
        MlProject project,
        MlExperiment newExperiment
) {
    String cleanCustomImageUri = newExperiment.getCustomImageUri();

    if (cleanCustomImageUri != null && cleanCustomImageUri.isBlank()) {
        cleanCustomImageUri = null;
        newExperiment.setCustomImageUri(null);
    }

    /*
     * Si l'image Production existe, on la réutilise.
     * Il n'y a donc volontairement aucun Job Kaniko mlops-build-*.
     * Seul le Job d'entraînement mlops-custom-* est créé.
     */
    if (cleanCustomImageUri != null) {
        newExperiment.setStatus(ExperimentStatus.RUNNING);
        markExperimentStarted(newExperiment);
        newExperiment.setBuildStatus("IMAGE_REUSED");
        newExperiment.setBuildLogs(
                "Production image reused: " + cleanCustomImageUri
        );
        newExperiment.setMessage(
                "Scheduled retraining uses the existing Production image."
        );

        MlExperiment savedExperiment =
                mlExperimentRepository.saveAndFlush(newExperiment);

        String jobName;

        try {
            jobName = kubernetesJobService.launchCustomImageTrainingJob(
                    project,
                    savedExperiment,
                    cleanCustomImageUri
            );
        } catch (Exception launchError) {
            savedExperiment.setStatus(ExperimentStatus.FAILED);
            applyExecutionTimestampsForStatus(
                    savedExperiment,
                    ExperimentStatus.FAILED
            );
            savedExperiment.setBuildStatus("TRAINING_JOB_FAILED");
            savedExperiment.setBuildLogs(launchError.getMessage());
            savedExperiment.setErrorMessage(launchError.getMessage());
            savedExperiment.setMessage(
                    "Kubernetes rejected the scheduled training Job."
            );

            MlExperiment failed =
                    mlExperimentRepository.saveAndFlush(savedExperiment);

            notifyExperimentStatusChangedIfNeeded(
                    ExperimentStatus.RUNNING,
                    failed
            );

            return mapToResponse(
                    reloadExperimentForResponse(failed.getId())
            );
        }

        savedExperiment.setKubeflowRunName(jobName);
        savedExperiment.setTrainingJobName(jobName);
        savedExperiment.setKubeflowStatus("RUNNING");
        savedExperiment.setBuildStatus("TRAINING_JOB_RUNNING");
        savedExperiment.setBuildLogs(
                "Kubernetes training Job created: " + jobName
        );
        savedExperiment.setMessage(
                "Scheduled retraining Job launched. "
                        + "Waiting for MLflow synchronization."
        );
        savedExperiment.setErrorMessage(null);

        MlExperiment updated =
                mlExperimentRepository.saveAndFlush(savedExperiment);

        triggerImmediateProjectSync(project.getId());

        System.out.println(
                "[AUTO_RETRAINING] Production image reused. experimentId="
                        + updated.getId()
                        + ", jobName="
                        + jobName
                        + ", image="
                        + cleanCustomImageUri
        );

        return mapToResponse(
                reloadExperimentForResponse(updated.getId())
        );
    }

    /*
     * Sans image réutilisable, le watcher doit reconstruire l'image avec Kaniko.
     */
    if (newExperiment.getDockerfilePath() == null
            || newExperiment.getDockerfilePath().isBlank()) {
        throw new RuntimeException(
                "No reusable custom image and no Dockerfile are available."
        );
    }

    newExperiment.setStatus(ExperimentStatus.CREATED);
    newExperiment.setBuildStatus("WAITING_FOR_IMAGE_BUILD");
    newExperiment.setMessage(
            "Waiting for CustomDockerRunWatcher/Kaniko image build."
    );
    newExperiment.setErrorMessage(null);

    MlExperiment saved =
            mlExperimentRepository.saveAndFlush(newExperiment);

    return mapToResponse(
            reloadExperimentForResponse(saved.getId())
    );
}

private MlExperimentResponse launchScheduledStandardRuntimeRetraining(
        MlProject project,
        MlExperiment newExperiment,
        String cleanModelName,
        String registeredModelName,
        String newRunName,
        String cleanTargetColumn,
        String primaryMetric,
        String direction,
        String projectType,
        String effectiveRuntimeMode,
        UploadedProjectFile sourceModelFile,
        UploadedProjectFile sourceDatasetFile
) {
    newExperiment.setStatus(ExperimentStatus.RUNNING);
    markExperimentStarted(newExperiment);
    newExperiment.setBuildStatus(null);
    newExperiment.setBuildLogs(null);
    newExperiment.setMessage("Scheduled retraining: launching STANDARD_RUNTIME pipeline.");
    newExperiment.setErrorMessage(null);

    MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(newExperiment);

    try {
        PipelineGatewayResponse gatewayResponse =
                pipelineClientService.launchCustomModelPipeline(
                        savedExperiment.getId(),
                        project.getId(),
                        project.getName(),
                        cleanModelName,
                        registeredModelName,
                        newRunName,
                        cleanTargetColumn,
                        primaryMetric,
                        direction,
                        projectType,
                        effectiveRuntimeMode,
                        sourceModelFile,
                        sourceDatasetFile
                );

        savedExperiment.setKubeflowRunId(gatewayResponse.getKubeflowRunId());
        savedExperiment.setKubeflowRunName(defaultValue(gatewayResponse.getKubeflowRunName(), newRunName));
        savedExperiment.setMlflowExperimentName(buildMlflowExperimentName(project));
        savedExperiment.setRegisteredModelName(registeredModelName);

        if (gatewayResponse.getMlflowRunId() != null && !gatewayResponse.getMlflowRunId().isBlank()) {
            if (!isMlflowRunAlreadyLinkedToAnotherExperiment(
                    gatewayResponse.getMlflowRunId(),
                    savedExperiment.getId()
            )) {
                savedExperiment.setMlflowRunId(gatewayResponse.getMlflowRunId());
            }
        }

        ExperimentStatus gatewayStatus = detectStatusFromGatewayResponse(gatewayResponse);

        ExperimentStatus finalGatewayStatus =
                gatewayStatus == ExperimentStatus.FAILED
                        ? ExperimentStatus.FAILED
                        : ExperimentStatus.RUNNING;

        savedExperiment.setStatus(finalGatewayStatus);
        applyExecutionTimestampsForStatus(savedExperiment, finalGatewayStatus);

        if (gatewayStatus == ExperimentStatus.FAILED) {
            savedExperiment.setErrorMessage(gatewayResponse.getMessage());
            savedExperiment.setMessage(
                    "Scheduled retraining Kubeflow pipeline launch failed: "
                            + gatewayResponse.getMessage()
            );
        } else {
            savedExperiment.setErrorMessage(null);
            savedExperiment.setMessage(
                    gatewayResponse.getMessage() != null && !gatewayResponse.getMessage().isBlank()
                            ? "Scheduled retraining launched. " + gatewayResponse.getMessage()
                            : "Scheduled retraining Kubeflow pipeline launched. Waiting for MLflow synchronization."
            );
        }

        MlExperiment updatedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);

        triggerImmediateProjectSync(project.getId());

        return mapToResponse(updatedExperiment);

    } catch (Exception e) {
        savedExperiment.setStatus(ExperimentStatus.FAILED);
        applyExecutionTimestampsForStatus(savedExperiment, ExperimentStatus.FAILED);
        savedExperiment.setErrorMessage(e.getMessage());
        savedExperiment.setMessage(
                "Scheduled retraining failed to launch Kubeflow pipeline, "
                        + "but the experiment remains saved in MySQL."
        );

        MlExperiment failedExperiment = mlExperimentRepository.saveAndFlush(savedExperiment);
        notifyExperimentStatusChangedIfNeeded(ExperimentStatus.RUNNING, failedExperiment);

        return mapToResponse(failedExperiment);
    }
}

public MlExperimentResponse retrainFromCurrentProductionExperiment(
        Long projectId,
        Long fallbackSourceExperimentId
) {
    if (projectId == null) {
        throw new RuntimeException("projectId is required for scheduled retraining.");
    }

    MlProject project = mlProjectRepository.findById(projectId)
            .orElseThrow(() -> new RuntimeException(
                    "Project not found for scheduled retraining: " + projectId
            ));

    MlExperiment productionExperiment =
            findCurrentProductionExperimentForRetraining(project);

    if (productionExperiment != null && productionExperiment.getId() != null) {
        System.out.println("[AUTO_RETRAINING] Production source selected. projectId="
                + projectId
                + ", sourceExperimentId="
                + productionExperiment.getId()
                + ", model="
                + productionExperiment.getRegisteredModelName()
                + ", version="
                + productionExperiment.getModelVersion());

        return retrainFromExistingExperiment(
                projectId,
                productionExperiment.getId()
        );
    }

    if (fallbackSourceExperimentId != null) {
        MlExperiment fallback = mlExperimentRepository
                .findByIdWithFiles(fallbackSourceExperimentId)
                .orElseThrow(() -> new RuntimeException(
                        "Fallback source experiment with project/files not found: "
                                + fallbackSourceExperimentId
                ));

        String expectedRegisteredModelName =
                buildRegisteredModelName(project);

        if (!isValidProductionRetrainingSource(
                fallback,
                expectedRegisteredModelName
        )) {
            throw new RuntimeException(
                    "Fallback source experiment is not a valid Production "
                            + "retraining source. projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + fallbackSourceExperimentId
            );
        }

        System.out.println(
                "[AUTO_RETRAINING] No direct production row found. "
                        + "Using validated fallback sourceExperimentId="
                        + fallbackSourceExperimentId
        );

        return retrainFromExistingExperiment(
                projectId,
                fallbackSourceExperimentId
        );
    }

    throw new RuntimeException(
            "No production experiment found and no fallback sourceExperimentId configured for projectId="
                    + projectId
    );
}

private MlExperiment findCurrentProductionExperimentForRetraining(MlProject project) {
    if (project == null || project.getId() == null) {
        return null;
    }

    String expectedRegisteredModelName =
            buildRegisteredModelName(project);

    /*
     * Load the production candidate and its files eagerly.
     * This avoids LazyInitializationException on modelFile/datasetFile.
     */
    List<MlExperiment> candidates =
            mlExperimentRepository
                    .findProductionRetrainingSourcesWithFiles(
                            project.getId(),
                            expectedRegisteredModelName
                    );

    if (candidates == null || candidates.isEmpty()) {
        return null;
    }

    for (MlExperiment candidate : candidates) {
        if (isValidProductionRetrainingSource(
                candidate,
                expectedRegisteredModelName
        )) {
            return candidate;
        }
    }

    return null;
}

private boolean isValidProductionRetrainingSource(
        MlExperiment experiment,
        String expectedRegisteredModelName
) {
    if (experiment == null || experiment.getId() == null) {
        return false;
    }

    if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
        return false;
    }

    /*
     * A retraining source must be a real platform production version.
     * Runs without score/metric are not eligible, even if MLflow has a technical version.
     */
    if (!Boolean.TRUE.equals(experiment.getProduction())) {
        return false;
    }

    if (experiment.getPrimaryMetricValue() == null || experiment.getGlobalScore() == null) {
        return false;
    }

    if (experiment.getMlflowRunId() == null || experiment.getMlflowRunId().isBlank()) {
        return false;
    }

    if (experiment.getModelVersion() == null || experiment.getModelVersion().isBlank()) {
        return false;
    }

    if (experiment.getProductionVersion() == null || experiment.getProductionVersion().isBlank()) {
        return false;
    }

    if (!experiment.getModelVersion().trim().equalsIgnoreCase(experiment.getProductionVersion().trim())) {
        return false;
    }

    if (experiment.getModelFile() == null
            || experiment.getModelFile().getFilePath() == null
            || experiment.getModelFile().getFilePath().isBlank()) {
        return false;
    }

    if (experiment.getDatasetFile() == null
            || experiment.getDatasetFile().getFilePath() == null
            || experiment.getDatasetFile().getFilePath().isBlank()) {
        return false;
    }

    if (experiment.getRegisteredModelName() == null
            || experiment.getRegisteredModelName().isBlank()) {
        return false;
    }

    if (expectedRegisteredModelName != null
            && !expectedRegisteredModelName.isBlank()
            && !experiment.getRegisteredModelName().equalsIgnoreCase(expectedRegisteredModelName)) {
        return false;
    }

    String runtimeMode = defaultValue(experiment.getRuntimeMode(), "STANDARD_RUNTIME").trim().toUpperCase(Locale.ROOT);

    if (isCustomDockerfileMode(runtimeMode)) {
        boolean hasDockerfile = experiment.getDockerfilePath() != null && !experiment.getDockerfilePath().isBlank();
        boolean hasCustomImage = experiment.getCustomImageUri() != null && !experiment.getCustomImageUri().isBlank();
        return hasDockerfile || hasCustomImage;
    }

    return true;
}

private void notifyTerminalExperimentIfNeeded(MlExperiment experiment) {
    // Intentionnellement vide : les notifications sont émises uniquement
    // au moment d'une vraie transition de statut via
    // notifyExperimentStatusChangedIfNeeded(...).
}

    public static class ScriptAnalysisResult {
        private String detectedModules;
        private String unsupportedModules;
        private String supportedModules;
        private String detectedAlgorithms;
        private boolean runtimeCompatible;
        private String message;
        private String recommendation;

        public ScriptAnalysisResult() {
        }

        public String getDetectedModules() {
            return detectedModules;
        }

        public void setDetectedModules(String detectedModules) {
            this.detectedModules = detectedModules;
        }

        public String getUnsupportedModules() {
            return unsupportedModules;
        }

        public void setUnsupportedModules(String unsupportedModules) {
            this.unsupportedModules = unsupportedModules;
        }

        public String getSupportedModules() {
            return supportedModules;
        }

        public void setSupportedModules(String supportedModules) {
            this.supportedModules = supportedModules;
        }

        public String getDetectedAlgorithms() {
            return detectedAlgorithms;
        }

        public void setDetectedAlgorithms(String detectedAlgorithms) {
            this.detectedAlgorithms = detectedAlgorithms;
        }

        public boolean isRuntimeCompatible() {
            return runtimeCompatible;
        }

        public void setRuntimeCompatible(boolean runtimeCompatible) {
            this.runtimeCompatible = runtimeCompatible;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public void setRecommendation(String recommendation) {
            this.recommendation = recommendation;
        }
    }
}