package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.DeleteExperimentResponse;
import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.dto.PipelineGatewayResponse;
import com.mlops.platform.dto.PipelineStatusResponse;
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
import org.springframework.data.domain.PageRequest;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final RunPipelineStepService runPipelineStepService;
    private final MetricNormalizationHelper metricNormalizationHelper;
    private final DatasetFileResolverService datasetFileResolverService;

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
            PlatformTransactionManager transactionManager,
            RunPipelineStepService runPipelineStepService,
            MetricNormalizationHelper metricNormalizationHelper,
            DatasetFileResolverService datasetFileResolverService
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
        this.runPipelineStepService = runPipelineStepService;
        this.metricNormalizationHelper = metricNormalizationHelper;
        this.datasetFileResolverService = datasetFileResolverService;
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
                requirementsFile,
                null
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
            MultipartFile requirementsFile,
            String datasetRole
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

        UploadFileNameNormalizer.validate(modelFile.getOriginalFilename(), UploadedFileType.TRAINING_SCRIPT);
        UploadedProjectFile savedModelFile = saveUploadedFile(project, modelFile, UploadedFileType.TRAINING_SCRIPT);
        System.out.println("[UPLOAD] role=TRAINING_SCRIPT"
                + " original=" + savedModelFile.getOriginalFileName()
                + " runtime=" + UploadFileNameNormalizer.runtimeName(
                        savedModelFile.getOriginalFileName(), UploadedFileType.TRAINING_SCRIPT));

        UploadedProjectFile savedDatasetFile = null;
        if (datasetFile != null && !datasetFile.isEmpty()) {
            UploadedFileType resolvedDatasetRole = resolveDatasetRole(
                    datasetRole,
                    datasetFile.getOriginalFilename()
            );
            UploadFileNameNormalizer.validate(datasetFile.getOriginalFilename(), resolvedDatasetRole);
            validateUploadedDatasetRole(resolvedDatasetRole, datasetFile.getOriginalFilename());
            savedDatasetFile = saveUploadedFile(project, datasetFile, resolvedDatasetRole);
            System.out.println("[UPLOAD] role=" + resolvedDatasetRole.name()
                    + " original=" + savedDatasetFile.getOriginalFileName()
                    + " runtime=" + UploadFileNameNormalizer.runtimeName(
                            savedDatasetFile.getOriginalFileName(), resolvedDatasetRole));
        }

        String dockerfilePath = null;
        String requirementsPath = null;

        if (customDockerMode) {
            if (dockerfileFile != null && !dockerfileFile.isEmpty()) {
                UploadFileNameNormalizer.validate(dockerfileFile.getOriginalFilename(), UploadedFileType.DOCKERFILE);
                dockerfilePath = saveRuntimeSupportFile(project, dockerfileFile, "docker", "Dockerfile");
                System.out.println("[UPLOAD] role=DOCKERFILE"
                        + " original=" + dockerfileFile.getOriginalFilename()
                        + " runtime=" + UploadFileNameNormalizer.runtimeName(
                                dockerfileFile.getOriginalFilename(), UploadedFileType.DOCKERFILE));
            }

            if (requirementsFile != null && !requirementsFile.isEmpty()) {
                UploadFileNameNormalizer.validate(requirementsFile.getOriginalFilename(), UploadedFileType.REQUIREMENTS);
                requirementsPath = saveRuntimeSupportFile(project, requirementsFile, "docker", "requirements.txt");
                System.out.println("[UPLOAD] role=REQUIREMENTS"
                        + " original=" + requirementsFile.getOriginalFilename()
                        + " runtime=" + UploadFileNameNormalizer.runtimeName(
                                requirementsFile.getOriginalFilename(), UploadedFileType.REQUIREMENTS));
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

                runPipelineStepService.initializeForExperiment(savedExperiment);
                runPipelineStepService.markSucceeded(
                        savedExperiment,
                        RunPipelineStepService.FILES_VALIDATED,
                        "Fichiers validés (mode Dockerfile personnalisé, image fournie)."
                );

                try {
                    String jobName = kubernetesJobService.launchCustomImageTrainingJob(
                            project,
                            savedExperiment,
                            cleanCustomImageUri
                    );

                    savedExperiment.setKubeflowRunName(jobName);
                    savedExperiment.setTrainingJobName(jobName);
                    savedExperiment.setKubeflowStatus("RUNNING");
                    savedExperiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
                    savedExperiment.setBuildLogs("Kubernetes Job launched successfully: " + jobName);
                    savedExperiment.setMessage(
                            "Custom Docker image Job launched successfully. Waiting for MLflow synchronization."
                    );

                    runPipelineStepService.markSucceeded(
                            savedExperiment,
                            RunPipelineStepService.TRAINING_JOB_CREATED,
                            "Job Kubernetes créé: " + jobName
                    );
                    runPipelineStepService.markRunning(
                            savedExperiment,
                            RunPipelineStepService.TRAINING_RUNNING,
                            "Exécution du Job d'entraînement Kubernetes en cours."
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

                    runPipelineStepService.markFailed(
                            savedExperiment,
                            RunPipelineStepService.TRAINING_JOB_CREATED,
                            "KUBERNETES_JOB_FAILED",
                            e.getMessage(),
                            "Échec de la création du Job Kubernetes d'entraînement.",
                            null
                    );

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

            System.out.println("[CUSTOM_PIPELINE] STEP=UPLOAD_SUCCESS experimentId=" + savedExperiment.getId()
                    + ", projectId=" + projectId
                    + ", runtime=CUSTOM_DOCKERFILE"
                    + ", dockerfilePath=" + savedExperiment.getDockerfilePath()
                    + ", requirementsPath=" + savedExperiment.getRequirementsPath()
                    + ", modelFile=" + (savedExperiment.getModelFile() != null ? savedExperiment.getModelFile().getOriginalFileName() : "null")
                    + ", datasetFile=" + (savedExperiment.getDatasetFile() != null ? savedExperiment.getDatasetFile().getOriginalFileName() : "null")
                    + ", status=CREATED"
                    + ", buildStatus=WAITING_FOR_IMAGE_BUILD");

            runPipelineStepService.initializeForExperiment(savedExperiment);
            runPipelineStepService.markSucceeded(
                    savedExperiment,
                    RunPipelineStepService.FILES_VALIDATED,
                    "Fichiers validés (mode Dockerfile personnalisé)."
            );
            runPipelineStepService.markRunning(
                    savedExperiment,
                    RunPipelineStepService.IMAGE_BUILD_STARTED,
                    "En attente du job Kaniko de construction de l'image."
            );

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

            runPipelineStepService.initializeForExperiment(failedExperiment);
            runPipelineStepService.markSucceeded(
                    failedExperiment,
                    RunPipelineStepService.SUBMITTED,
                    "Fichiers validés."
            );
            runPipelineStepService.markFailed(
                    failedExperiment,
                    RunPipelineStepService.KFP_PIPELINE_CREATED,
                    "UNSUPPORTED_DEPENDENCY_STANDARD_RUNTIME",
                    errorMsg,
                    "Le script utilise des dépendances indisponibles dans le Runtime standard.",
                    null
            );

            notifyExperimentStatusChangedIfNeeded(ExperimentStatus.RUNNING, failedExperiment);
            return mapToResponse(failedExperiment);
        }

        experiment.setMessage("Script compatible with STANDARD_RUNTIME. Launching Kubeflow pipeline without replacement.");
        MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

        runPipelineStepService.initializeForExperiment(savedExperiment);
        runPipelineStepService.markSucceeded(
                savedExperiment,
                RunPipelineStepService.SUBMITTED,
                "Fichiers validés et script analysé pour le Runtime standard."
        );

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

                runPipelineStepService.markFailed(
                        savedExperiment,
                        RunPipelineStepService.KFP_PIPELINE_CREATED,
                        "KUBEFLOW_RUN_FAILED",
                        gatewayResponse.getMessage(),
                        "Échec de la création/soumission du run Kubeflow.",
                        null
                );
            } else {
                savedExperiment.setErrorMessage(null);
                savedExperiment.setMessage(
                        gatewayResponse.getMessage() != null && !gatewayResponse.getMessage().isBlank()
                                ? gatewayResponse.getMessage()
                                : "Kubeflow pipeline launched. Waiting for MLflow synchronization."
                );

                runPipelineStepService.markSucceeded(
                        savedExperiment,
                        RunPipelineStepService.KFP_PIPELINE_CREATED,
                        "Pipeline Kubeflow créée (runId=" + savedExperiment.getKubeflowRunId() + ")."
                );
                runPipelineStepService.markSucceeded(
                        savedExperiment,
                        RunPipelineStepService.KFP_RUN_SUBMITTED,
                        "Run Kubeflow soumis."
                );
                runPipelineStepService.markRunning(
                        savedExperiment,
                        RunPipelineStepService.KFP_TRAINING_RUNNING,
                        "Entraînement Kubeflow en cours."
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

            runPipelineStepService.markFailed(
                    savedExperiment,
                    RunPipelineStepService.KFP_PIPELINE_CREATED,
                    "KUBEFLOW_RUN_FAILED",
                    e.getMessage(),
                    "Échec de la soumission du run Kubeflow.",
                    null
            );

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

        // Un run en échec ne conserve aucune métrique métier ni score global :
        // exposer des valeurs obsolètes masquerait le vrai état du run.
        clearPerformanceMetricsIfPresent(experiment);

        MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);
        notifyExperimentStatusChangedIfNeeded(previousStatus, savedExperiment);

        return savedExperiment;
    }

    private void clearPerformanceMetricsIfPresent(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        try {
            metricRepository.deleteByExperimentId(experiment.getId());
            metricRepository.flush();
        } catch (Exception ignored) {
            // Le nettoyage des métriques ne doit jamais faire échouer le marquage en échec.
        }

        experiment.setPrimaryMetric(null);
        experiment.setDirection(null);
        experiment.setPrimaryMetricValue(null);
        experiment.setGlobalScore(null);
    }

    public MlExperimentResponse getExperimentById(String authHeader, Long projectId, Long experimentId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        MlExperiment experiment = mlExperimentRepository.findByIdAndProject(experimentId, project)
                .orElseThrow(() -> new RuntimeException("Experiment not found"));

        return mapToResponse(experiment);
    }

    /**
     * État courant des étapes de pipeline d'un run (GET /api/experiments/{id}/pipeline-status).
     * Légère : ne rescannne ni MLflow ni Kubernetes, lit uniquement la table run_pipeline_steps.
     */
    public PipelineStatusResponse getPipelineStatus(String authHeader, Long experimentId) {
        MlExperiment experiment = findOwnedExperiment(authHeader, experimentId);
        return runPipelineStepService.getPipelineStatus(experiment);
    }

    /**
     * Logs détaillés d'une étape précise (GET /api/experiments/{id}/pipeline-steps/{stepCode}/logs).
     */
    public String getPipelineStepLogs(String authHeader, Long experimentId, String stepCode) {
        findOwnedExperiment(authHeader, experimentId);
        return runPipelineStepService.getStepLogs(experimentId, stepCode);
    }

    private MlExperiment findOwnedExperiment(String authHeader, Long experimentId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlExperiment experiment = mlExperimentRepository.findById(experimentId)
                .orElseThrow(() -> new RuntimeException("Experiment not found"));

        MlProject project = experiment.getProject();

        if (project == null
                || !owner.getId().equals(project.getOwner().getId())) {
            throw new RuntimeException("Experiment not accessible");
        }

        return experiment;
    }

    public List<MlExperimentResponse> syncProjectResults(String authHeader, Long projectId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        /*
         * Une seule passe des expériences déjà créées, puis import et fusion.
         * Les anciennes trois passes consécutives écrivaient plusieurs fois les
         * mêmes métriques dans une seule transaction.
         */
        syncExistingProjectExperiments(project, "manual-sync-existing");
        importMissingMlflowRunsIntoMysql(project);
        mergeDuplicateMlflowKubeflowRows(project);

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

                if (isAutoPromoteEnabled(project)) {
                    autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                            project,
                            registeredName
                    );
                    refreshProductionVersionFlagsForProject(project, registeredName);
                    recalculateGlobalScoresForProject(project, registeredName);
                }

                enforceProductionConsistencyForModel(registeredName);

            } catch (Exception e) {
                System.out.println("[SYNC] Lifecycle failed for model=" + registeredName);
                System.out.println("[SYNC] Reason: " + e.getMessage());
                e.printStackTrace();
            }
        }

        mergeDuplicateMlflowKubeflowRows(project);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

        return filterAndDeduplicateVisibleExperiments(experiments)
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
            if (experiment == null || experiment.getId() == null) {
                continue;
            }

            syncSingleExperimentInOwnTransaction(project.getId(), experiment.getId(), phase);
        }
    }

    /**
     * Phase A + B + C de l'auto-sync : seuls les expériences candidates
     * (statut actif, mise à jour récente, ou run MLflow sans version)
     * sont re-synchronisées, chacune dans sa propre transaction courte.
     */
    private void syncExistingProjectExperimentsCandidates(
            Long projectId,
            List<Long> candidateIds,
            String phase
    ) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return;
        }

        for (Long experimentId : candidateIds) {
            if (experimentId == null) {
                continue;
            }

            syncSingleExperimentInOwnTransaction(projectId, experimentId, phase);
        }
    }

    /**
     * Synchronise UNE expérience dans sa propre transaction REQUIRES_NEW
     * bornée (120 s). Un échec (timeout, MLflow injoignable, erreur SQL)
     * est isolé : le reste du cycle et les autres expériences continuent.
     */
    private void syncSingleExperimentInOwnTransaction(
            Long projectId,
            Long experimentId,
            String phase
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(120);

        long start = System.currentTimeMillis();

        try {
            template.executeWithoutResult(status -> {
                System.out.println("[SYNC_EXTERNAL] projectId=" + projectId
                        + ", experimentId=" + experimentId
                        + ", phase=" + phase);

                MlProject project = mlProjectRepository.findById(projectId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Project not found during sync. projectId=" + projectId
                        ));

                MlExperiment experiment = mlExperimentRepository.findById(experimentId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Experiment not found during sync. experimentId=" + experimentId
                        ));

                syncSingleExperiment(project, experiment);
            });

            System.out.println("[SYNC_PERSIST] projectId=" + projectId
                    + ", experimentId=" + experimentId
                    + ", durationMs=" + (System.currentTimeMillis() - start)
                    + ", phase=" + phase);

        } catch (Exception e) {
            System.out.println("[SYNC_FAILED] projectId=" + projectId
                    + ", experimentId=" + experimentId
                    + ", phase=" + phase
                    + ", durationMs=" + (System.currentTimeMillis() - start)
                    + ", reason=" + e.getMessage());
        }
    }

    /**
     * Exécute une action dans une transaction REQUIRES_NEW courte et bornée,
     * déconnectée de toute transaction parente. Utilisée pour découper le
     * lifecycle auto en étapes courtes (import, merge, scores, production).
     */
    private void runInRequiresNewTransaction(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(90);
        template.executeWithoutResult(status -> action.run());
    }

    private MlProject loadProjectOrThrow(Long projectId) {
        return mlProjectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException(
                        "Project not found. projectId=" + projectId
                ));
    }

    private void importMissingMlflowRunsIntoMysql(MlProject project) {
        if (project == null || project.getId() == null) {
            return;
        }

        List<MlflowTrackingClientService.MlflowRunDetails> mlflowRuns =
                collectMlflowRunsForImport(project);

        if (mlflowRuns == null || mlflowRuns.isEmpty()) {
            return;
        }

        /*
         * Chaque run est importé dans sa PROPRE transaction courte REQUIRES_NEW
         * (90 s par run). Un run lent, un deadlock ou un timeout ne touche que
         * lui-même : il n'y a plus de budget global de 90 s consommé par
         * l'import de tous les runs manquants d'un projet.
         */
        for (MlflowTrackingClientService.MlflowRunDetails runDetails : mlflowRuns) {
            if (runDetails == null
                    || runDetails.getRunId() == null
                    || runDetails.getRunId().isBlank()) {
                continue;
            }

            try {
                runInRequiresNewTransaction(() -> {
                    MlProject managedProject = loadProjectOrThrow(project.getId());
                    importSingleMlflowRun(managedProject, runDetails);
                });
            } catch (Exception e) {
                System.out.println("[SYNC] Cannot import/link MLflow run:");
                System.out.println("[SYNC] Reason = " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private List<MlflowTrackingClientService.MlflowRunDetails> collectMlflowRunsForImport(
            MlProject project
    ) {
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
        }

        return mlflowRuns;
    }

    private void importSingleMlflowRun(
            MlProject project,
            MlflowTrackingClientService.MlflowRunDetails runDetails
    ) {
        if (project == null || runDetails == null) {
            return;
        }

        String mlflowExperimentName = buildMlflowExperimentName(project);
        String fallbackRegisteredModelName = buildRegisteredModelName(project);

        try {
        if (runDetails == null
                || runDetails.getRunId() == null
                || runDetails.getRunId().isBlank()) {
            return;
        }

        if (!isRealPlatformMlflowRun(runDetails)) {
            System.out.println("[SYNC][MLFLOW] Ignored non-platform MLflow run: "
                    + runDetails.getRunName()
                    + " / "
                    + runDetails.getRunId());
            return;
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
                    return;
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

    return hasText(experiment.getProductionVersion())
            && hasText(experiment.getModelVersion())
            && experiment.getProductionVersion().trim()
            .equalsIgnoreCase(experiment.getModelVersion().trim());
}

private boolean isRegistryAttachableVersion(MlExperiment experiment) {
    return experiment != null
            && experiment.getStatus() == ExperimentStatus.COMPLETED
            && hasText(experiment.getMlflowRunId())
            && hasText(experiment.getModelVersion());
}

private boolean isEligiblePlatformVersion(MlExperiment experiment) {
    return isRegistryAttachableVersion(experiment)
            && experiment.getPrimaryMetricValue() != null
            && experiment.getGlobalScore() != null;
}

private boolean isTerminalFailure(MlExperiment experiment) {
    return experiment != null
            && (experiment.getStatus() == ExperimentStatus.FAILED
            || experiment.getStatus() == ExperimentStatus.CANCELLED);
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
    String registryProductionVersion =
            mlflowTrackingClientService.getProductionVersion(cleanRegisteredModelName);

    mlExperimentRepository.clearProductionByRegisteredModelName(cleanRegisteredModelName);

    if (!hasText(registryProductionVersion)) {
        System.out.println("[PRODUCTION_GUARD] No production alias in MLflow for model="
                + cleanRegisteredModelName);
        return;
    }

    List<MlExperiment> candidates = mlExperimentRepository
            .findEligibleProductionCandidates(cleanRegisteredModelName);

    if (candidates == null || candidates.isEmpty()) {
        return;
    }

    MlExperiment selected = null;

    for (MlExperiment candidate : candidates) {
        if (candidate == null || !hasText(candidate.getModelVersion())) {
            continue;
        }

        candidate.setProductionVersion(registryProductionVersion);
        candidate.setProduction(false);

        if (registryProductionVersion.equals(candidate.getModelVersion().trim())) {
            if (selected == null
                    || compareProductionCandidates(candidate, selected) > 0) {
                selected = candidate;
            }
        }
    }

    if (selected != null) {
        selected.setProduction(true);
    }

    mlExperimentRepository.saveAll(candidates);
    mlExperimentRepository.flush();
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

        return mlExperimentRepository.existsByMlflowRunId(mlflowRunId.trim());
    }

    private boolean isMlflowRunAlreadyLinkedToAnotherExperiment(String mlflowRunId, Long currentExperimentId) {
        if (mlflowRunId == null || mlflowRunId.isBlank()) {
            return false;
        }

        if (currentExperimentId == null) {
            return mlExperimentRepository.existsByMlflowRunId(mlflowRunId.trim());
        }

        return mlExperimentRepository.existsByMlflowRunIdAndIdNot(
                mlflowRunId.trim(),
                currentExperimentId
        );
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

    recordStandardKubeflowStepState(experiment);

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

    runPipelineStepService.markRunning(
            experiment,
            RunPipelineStepService.MLFLOW_REGISTERED,
            "Synchronisation MLflow en cours (runId=" + effectiveRunId + ")."
    );

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

        runPipelineStepService.markSucceeded(
                experiment,
                RunPipelineStepService.METRICS_EXTRACTED,
                "Métriques extraites du run MLflow."
        );
        runPipelineStepService.markSucceeded(
                experiment,
                RunPipelineStepService.MODEL_ARTIFACT_DETECTED,
                "Artefact modèle détecté (version " + modelVersion + ")."
        );
        runPipelineStepService.markSucceeded(
                experiment,
                RunPipelineStepService.MLFLOW_REGISTERED,
                "Modèle enregistré dans le Registry MLflow (version " + modelVersion + ")."
        );
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

    if (isTerminalFailure(experiment)) {
        clearVersioningAndProduction(experiment);
    } else if (!isEligiblePlatformVersion(experiment)) {
        experiment.setProduction(false);
        experiment.setProductionVersion(null);
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

        runPipelineStepService.markSucceeded(
                savedExperiment,
                RunPipelineStepService.PRODUCTION_PROMOTED,
                "Version " + refreshedProductionVersion + " promue en Production."
        );
    }

    if (isTerminalFailure(savedExperiment)) {
        clearVersioningAndProduction(savedExperiment);
    } else if (!isEligiblePlatformVersion(savedExperiment)) {
        savedExperiment.setProduction(false);
        savedExperiment.setProductionVersion(null);
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

/**
 * Enregistre les événements d'étapes réels du runtime STANDARD_RUNTIME à partir de
 * l'état Kubeflow observé (source de vérité : l'API Kubeflow). Ne touche jamais
 * aux étapes d'un run CUSTOM_DOCKERFILE (gérées par le CustomDockerRunWatcher).
 */
private void recordStandardKubeflowStepState(MlExperiment experiment) {
    if (experiment == null || experiment.getId() == null) {
        return;
    }

    if (experiment.getRuntimeMode() != null
            && !RunPipelineStepService.RUNTIME_STANDARD.equalsIgnoreCase(experiment.getRuntimeMode())) {
        return;
    }

    String kfStatus = experiment.getKubeflowStatus() == null
            ? ""
            : experiment.getKubeflowStatus().trim().toUpperCase(Locale.ROOT);

    switch (kfStatus) {
        case "RUNNING" -> {
            runPipelineStepService.markSucceeded(
                    experiment,
                    RunPipelineStepService.DATA_PREPARED,
                    "Données préparées et validées avant l'entraînement."
            );
            runPipelineStepService.markRunning(
                    experiment,
                    RunPipelineStepService.KFP_TRAINING_RUNNING,
                    "Entraînement Kubeflow en cours."
            );
        }
        case "COMPLETED", "SUCCEEDED" -> {
            runPipelineStepService.markSucceeded(
                    experiment,
                    RunPipelineStepService.DATA_PREPARED,
                    "Données préparées et validées."
            );
            runPipelineStepService.markSucceeded(
                    experiment,
                    RunPipelineStepService.KFP_TRAINING_RUNNING,
                    "Entraînement Kubeflow terminé."
            );
        }
        case "FAILED", "ERROR", "CANCELLED", "CANCELED" -> {
            String reason = firstNotBlank(
                    experiment.getFailureReason(),
                    experiment.getErrorMessage()
            );
            runPipelineStepService.markFailed(
                    experiment,
                    RunPipelineStepService.KFP_TRAINING_RUNNING,
                    "KUBEFLOW_RUN_FAILED",
                    reason,
                    "Le run Kubeflow a échoué.",
                    experiment.getFailureLogExcerpt()
            );
        }
        default -> {
            // Statut inconnu ou vide : on ne force aucune transition.
        }
    }
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
        if (experiment == null
                || experiment.getId() == null
                || metrics == null
                || metrics.isEmpty()) {
            return;
        }

        Long experimentId = experiment.getId();
        Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);

        if (normalizedMetrics.isEmpty()) {
            return;
        }

        /*
         * IMPORTANT:
         * Ne plus supprimer puis recréer les lignes de métriques.
         *
         * Les anciennes implémentations faisaient :
         *   DELETE -> clear persistence context -> INSERT
         *
         * Quand plusieurs phases de synchronisation traitaient le même run,
         * Hibernate conservait parfois une ancienne entité et levait :
         * ObjectOptimisticLockingFailureException.
         *
         * L'UPSERT natif est atomique et idempotent grâce à la contrainte unique
         * (experiment_id, metric_name).
         */
        for (Map.Entry<String, Double> entry : normalizedMetrics.entrySet()) {
            String metricName = normalizeMetricKey(entry.getKey());
            Double metricValue = entry.getValue();

            if (metricName.isBlank() || metricValue == null) {
                continue;
            }

            metricRepository.upsertMetric(
                    experimentId,
                    metricName,
                    metricValue,
                    getDirectionFromMetric(metricName),
                    experiment.getProductionVersion(),
                    Boolean.TRUE.equals(experiment.getProduction()),
                    experiment.getGlobalScore()
            );
        }

        metricRepository.flush();
    }

    private boolean isDisplayablePerformanceMetric(String metricName) {
    return metricNormalizationHelper.isDisplayablePerformanceMetric(metricName);
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

    Map<String, Double> normalizedMetrics = metricNormalizationHelper.normalizeMetricsMap(metrics);
    List<String> priorities = metricNormalizationHelper.getProjectMetricPriorities(project);

    for (String metricName : priorities) {
        if (!metricNormalizationHelper.isMetricCompatibleWithProjectType(project, metricName)) {
            continue;
        }

        Double value = metricNormalizationHelper.getMetricValueCompatible(normalizedMetrics, metricName);

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

    List<String> priority = metricNormalizationHelper.isRegressionProject(project)
            ? List.of("rmse", "mae", "r2", "r2_score", "mse")
            : List.of("f1_score", "f1_weighted", "f1", "accuracy", "precision", "precision_weighted", "recall", "recall_weighted");

    for (String metricName : priority) {
        Double value = metricNormalizationHelper.getMetricValueCompatible(metrics, metricName);

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

        if (!metricNormalizationHelper.isMetricCompatibleWithProjectType(project, normalizedMetric)) {
            continue;
        }

        experiment.setPrimaryMetric(normalizedMetric);
        experiment.setDirection(getDirectionFromMetric(normalizedMetric));
        experiment.setPrimaryMetricValue(entry.getValue());
        return;
    }
}


    private Double calculateSingleExperimentGlobalScore(MlProject project, Map<String, Double> metrics) {
    return metricNormalizationHelper.calculatePriorityGlobalScore(project, metrics);
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

    /*
     * La décision principale repose sur le score global recalculé avec les
     * priorités courantes. Les métriques individuelles ne servent que de
     * départage lorsque les scores globaux sont égaux.
     */
    int scoreComparison = compareNullableScores(
            candidate.getGlobalScore(),
            currentBest.getGlobalScore()
    );
    if (scoreComparison != 0) {
        return scoreComparison;
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

        int comparison = "min".equalsIgnoreCase(getDirectionFromMetric(metricName))
                ? Double.compare(bestValue, candidateValue)
                : Double.compare(candidateValue, bestValue);

        if (comparison != 0) {
            return comparison;
        }
    }

    return 0;
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
    return metricNormalizationHelper.getProjectMetricPriorities(project);
    }


    private Double calculatePriorityGlobalScore(MlProject project, Map<String, Double> metrics) {
        return metricNormalizationHelper.calculatePriorityGlobalScore(project, metrics);
    }

    private Double normalizeMetricScore(String metricName, Double value) {
    return metricNormalizationHelper.normalizeMetricScore(metricName, value);
    }


    private void updateMetricRowsGlobalScore(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        /*
         * Bulk updates only: no stale MlExperimentMetric entity is loaded into
         * the persistence context, therefore no optimistic-lock conflict.
         */
        metricRepository.updateGlobalScoreByExperimentId(
                experiment.getId(),
                experiment.getGlobalScore()
        );

        metricRepository.updateProductionStateByExperimentId(
                experiment.getId(),
                Boolean.TRUE.equals(experiment.getProduction()),
                experiment.getProductionVersion()
        );

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
            String storedFileName = extension != null && !extension.isBlank()
                    ? UUID.randomUUID() + "." + extension
                    : UUID.randomUUID().toString();

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
            uploadedFile.setRole(fileType);
            uploadedFile.setFileExtension(extension);
            uploadedFile.setContentType(file.getContentType());
            uploadedFile.setSizeBytes(file.getSize());
            uploadedFile.setProject(project);

            System.out.println(
                    "[UPLOAD-FILE] projectId=" + project.getId()
                            + ", originalFileName=" + originalFileName
                            + ", fileExtension=" + extension
                            + ", contentType=" + file.getContentType()
                            + ", fileType=" + fileType
                            + ", fileRole=" + fileType
                            + ", filePath=" + targetPath
            );

            return uploadedProjectFileRepository.save(uploadedFile);

        } catch (Exception e) {
            throw new RuntimeException("Failed to save uploaded file: " + e.getMessage());
        }
    }

    /**
     * Résolution du rôle explicite d'un fichier dataset.
     * Le rôle déclaré par l'utilisateur est source de vérité ; s'il est absent,
     * on le dérive de l'extension AVEC des règles sûres :
     *   .py/.py3                -> DATASET_PREPARATION_SCRIPT (jamais DATASET_FILE)
     *   zip/tar/gz/7z/rar       -> DATASET_ARCHIVE
     *   sinon                   -> DATASET_FILE
     */
    private UploadedFileType resolveDatasetRole(String declaredRole, String fileName) {
        if (declaredRole != null && !declaredRole.isBlank()) {
            String normalized = declaredRole.trim().toUpperCase().replace('-', '_');
            return switch (normalized) {
                case "DATASET_FILE", "DATASET_ARCHIVE", "DATASET_PREPARATION_SCRIPT",
                     "PREPROCESSING_SCRIPT", "AUXILIARY", "DOCKERFILE", "REQUIREMENTS" ->
                        UploadedFileType.valueOf(normalized);
                default -> deriveDatasetRoleFromExtension(fileName);
            };
        }
        return deriveDatasetRoleFromExtension(fileName);
    }

    private UploadedFileType deriveDatasetRoleFromExtension(String fileName) {
        return datasetFileResolverService.deriveRoleFromFileName(fileName);
    }

    /**
     * Validation du rôle du fichier envoyé dans le champ dataset_file.
     *
     * Un fichier ne peut être le dataset de l'expérience que s'il a un rôle de
     * dataset (DATASET_FILE, DATASET_ARCHIVE) avec une extension de données
     * (.csv, .zip, .parquet...).
     *
     * Un script Python (.py/.py3) est TOUJOURS rejeté : la plateforme ne
     * supporte pas de dataset Python et ne transmet jamais un script comme
     * DATASET_PATH. Les rôles support (AUXILIARY, DOCKERFILE, REQUIREMENTS,
     * TRAINING_SCRIPT...) sont rejetés également.
     *
     * Cette validation à la source empêche de nouveaux entraînements d'associer
     * train.py / preprocessing.py / requirements.txt / Dockerfile comme dataset.
     */
    private void validateUploadedDatasetRole(UploadedFileType role, String originalFileName) {
        String safeName = originalFileName == null ? "?" : originalFileName;

        if (isPythonExtension(originalFileName)) {
            throw new RuntimeException(
                    "Le fichier '" + safeName
                            + "' est un script Python (.py/.py3) : la plateforme ne transmet "
                            + "jamais un script comme dataset. Sélectionnez le vrai dataset "
                            + "(.csv, .zip, .parquet...)."
            );
        }

        if (role == null || !datasetFileResolverService.isDatasetRole(role)) {
            throw new RuntimeException(
                    "Le fichier '" + safeName + "' n'est pas un dataset (rôle '"
                            + (role == null ? "-" : role.name())
                            + "'). Sélectionnez le vrai dataset (.csv, .zip, .parquet...)."
            );
        }
    }

    private boolean isPythonExtension(String fileName) {
        String ext = getExtension(fileName);
        if (ext == null || ext.isBlank()) {
            return false;
        }
        String normalized = ext.trim().toLowerCase();
        return normalized.equals("py") || normalized.equals("py3");
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

        MlExperiment visible = mlExperimentRepository
                .findByIdWithFiles(experimentId)
                .orElse(null);

        if (visible != null) {
            return visible;
        }

        /*
         * La ligne a pu être insérée dans une transaction REQUIRES_NEW alors
         * que la transaction courante (REPEATABLE READ) possède un snapshot
         * antérieur : elle n'y est pas visible. On recharge donc dans une
         * transaction neuve en lecture avant d'abandonner.
         */
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(20);

        return template.execute(status -> mlExperimentRepository
                .findByIdWithFiles(experimentId)
                .orElseThrow(() -> new RuntimeException(
                        "Experiment with project/files not found: "
                                + experimentId
                )));
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

            boolean isRetraining = isRetrainingExperiment(loaded);

            if (currentStatus == ExperimentStatus.COMPLETED) {
                if (isRetraining) {
                    notificationService.notifyRetrainingCompleted(loaded);
                } else {
                    notificationService.notifyRunCompleted(loaded);
                }
            } else {
                if (isRetraining) {
                    notificationService.notifyRetrainingFailed(
                            loaded,
                            loaded.getErrorMessage()
                    );
                } else {
                    notificationService.notifyRunFailed(loaded);
                }
            }

            System.out.println(
                    "[NOTIFICATION] created type="
                            + (currentStatus == ExperimentStatus.COMPLETED
                            ? (isRetraining ? "RETRAINING_COMPLETED" : "RUN_COMPLETED")
                            : (isRetraining ? "RETRAINING_FAILED" : "RUN_FAILED"))
                            + " experimentId="
                            + loaded.getId()
                            + ", isRetraining="
                            + isRetraining
            );

        } catch (Exception e) {
            System.out.println(
                    "[NOTIFICATION] Notification skipped. experimentId="
                            + experiment.getId()
                            + ", reason="
                            + e.getMessage()
            );
        }
    }

    /**
     * Détermine si une expérience est un réentraînement automatique
     * en se basant sur le triggerType ou le préfixe du nom.
     */
    private boolean isRetrainingExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return false;
        }

        if (experiment.getTriggerType() != null
                && !experiment.getTriggerType().isBlank()) {
            return true;
        }

        String name = experiment.getExperimentName();
        if (name != null && name.startsWith("scheduled_retraining_")) {
            return true;
        }

        return false;
    }

private String getDirectionFromMetric(String metric) {
    return metricNormalizationHelper.getDirectionFromMetric(metric);
    }


    private Double getMetricValueIgnoreCase(Map<String, Double> metrics, String metricName) {
    return metricNormalizationHelper.getMetricValueCompatible(metrics, metricName);
    }


    private Double getMetricValueCompatible(Map<String, Double> metrics, String metricName) {
    return metricNormalizationHelper.getMetricValueCompatible(metrics, metricName);
    }


    private String normalizeMetricKey(String metricName) {
    return metricNormalizationHelper.normalizeMetricKey(metricName);
    }

private Map<String, Double> normalizeMetricsMap(Map<String, Double> metrics) {
    return metricNormalizationHelper.normalizeMetricsMap(metrics);
}

private boolean isRegressionProject(MlProject project) {
    return metricNormalizationHelper.isRegressionProject(project);
}

private boolean isMetricCompatibleWithProjectType(MlProject project, String metricName) {
    return metricNormalizationHelper.isMetricCompatibleWithProjectType(project, metricName);
}

private String defaultPrimaryMetricForProject(MlProject project) {
    return metricNormalizationHelper.defaultPrimaryMetricForProject(project);
}


    private Double roundDouble(Double value, int decimals) {
        return metricNormalizationHelper.roundDouble(value, decimals);
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
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        int lastDotIndex = fileName.lastIndexOf(".");

        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }

        /*
         * Returns the extension WITHOUT the leading dot ("py", "csv", "zip"...).
         *
         * Historical bug: this method used to return ".py" while
         * deriveDatasetRoleFromExtension() compared against "py".
         * The comparison never matched, so any Python script uploaded as a
         * dataset was silently classified as DATASET_FILE instead of
         * DATASET_PREPARATION_SCRIPT, and its path was transmitted as
         * DATASET_PATH (the runner then failed with DATASET_INVALID /
         * "dataset path not found").
         */
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * Construit une réponse sans accéder à des proxys Hibernate détachés.
     * L'expérience est systématiquement rechargée avec project/modelFile/datasetFile.
     */
    private MlExperimentResponse mapToResponse(MlExperiment experiment) {
        if (experiment == null) {
            throw new IllegalArgumentException("experiment is required");
        }

        MlExperiment loaded = experiment;

        if (experiment.getId() != null) {
            loaded = mlExperimentRepository
                    .findByIdWithFiles(experiment.getId())
                    .orElse(experiment);
        }

        MlProject project = loaded.getProject();

        if (project == null || project.getId() == null) {
            throw new IllegalStateException(
                    "Experiment has no initialized project. experimentId="
                            + loaded.getId()
            );
        }

        UploadedProjectFile datasetFile = loaded.getDatasetFile();
        UploadedProjectFile modelFile = loaded.getModelFile();

        MlExperimentResponse response = new MlExperimentResponse(
                loaded.getId(),
                loaded.getExperimentName(),
                loaded.getStatus() != null ? loaded.getStatus().name() : "-",

                project.getId(),
                project.getName(),

                datasetFile != null ? datasetFile.getId() : null,
                datasetFile != null ? datasetFile.getOriginalFileName() : null,

                modelFile != null ? modelFile.getId() : null,
                modelFile != null ? modelFile.getOriginalFileName() : null,

                loaded.getModelType(),
                loaded.getModelName(),
                loaded.getPrimaryMetric(),
                loaded.getDirection(),

                loaded.getKubeflowRunId(),
                loaded.getKubeflowRunName(),
                loaded.getKubeflowStatus(),

                loaded.getMlflowExperimentName(),
                loaded.getMlflowExperimentId(),
                loaded.getMlflowRunId(),

                loaded.getRegisteredModelName(),
                loaded.getModelVersion(),
                loaded.getLatestVersion(),
                loaded.getProductionVersion(),

                loaded.getProduction(),
                loaded.getGlobalScore(),

                loaded.getPrimaryMetricValue(),
                getMetricsMap(loaded),

                loaded.getMessage(),
                loaded.getErrorMessage(),

                toUtcIsoString(loaded.getCreatedAt()),
                toUtcIsoString(loaded.getUpdatedAt())
        );

        response.setStartedAt(toUtcIsoString(loaded.getStartedAt()));
        response.setFinishedAt(toUtcIsoString(loaded.getFinishedAt()));
        response.setCreatedAt(toUtcIsoString(loaded.getCreatedAt()));
        response.setUpdatedAt(toUtcIsoString(loaded.getUpdatedAt()));

        response.setDetectedAlgorithms(loaded.getDetectedAlgorithms());
        response.setRuntimeMode(loaded.getRuntimeMode());
        response.setCustomImageUri(loaded.getCustomImageUri());
        response.setDockerfilePath(loaded.getDockerfilePath());
        response.setRequirementsPath(loaded.getRequirementsPath());
        response.setBuildStatus(loaded.getBuildStatus());
        response.setBuildLogs(loaded.getBuildLogs());
        response.setTrainingLogs(loaded.getTrainingLogs());
        response.setFailurePhase(loaded.getFailurePhase());
        response.setFailureReason(loaded.getFailureReason());
        response.setFailureDetails(loaded.getFailureDetails());
        response.setFailureSuggestion(loaded.getFailureSuggestion());
        response.setFailureLogExcerpt(loaded.getFailureLogExcerpt());
        response.setFailureSourceFile(loaded.getFailureSourceFile());

        // Un run FAILED / CANCELLED ne doit JAMAIS exposer de métriques métier
        // ou de score global à l'interface : celles-ci n'ont pas de sens
        // (échec d'entraînement) et masquent le vrai état du run.
        ExperimentStatus status = loaded.getStatus();
        if (status == ExperimentStatus.FAILED || status == ExperimentStatus.CANCELLED) {
            response.setGlobalScore(null);
            response.setPrimaryMetricValue(null);
            response.setMetrics(null);
        }

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
    long start = System.currentTimeMillis();

    /*
     * Phase A : on ne charge que les IDs des projets. Plus aucun
     * findAll() d'entités complètes pour piloter le scheduler.
     */
    List<Long> projectIds = mlProjectRepository.findAllProjectIds();

    if (projectIds.isEmpty()) {
        System.out.println("[SYNC_SCHEDULER] No projects found.");
        return;
    }

    System.out.println("[SYNC_SCHEDULER_START] projects=" + projectIds.size());

    /*
     * Chaque projet, chaque expérience et chaque étape du lifecycle
     * sont traités dans des transactions courtes indépendantes.
     * Plus aucune transaction longue de 120 s par projet : les appels
     * MLflow/Kubeflow/Kubernetes ne restent jamais ouverts sur une
     * connexion SQL pendant tout le traitement du projet.
     */
    for (Long projectId : projectIds) {
        try {
            syncSingleProjectAutomatically(projectId);
        } catch (Exception e) {
            System.out.println("[SYNC_FAILED] projectId=" + projectId
                    + ", reason=" + e.getMessage());
        }
    }

    System.out.println("[SYNC_SCHEDULER_END] durationMs="
            + (System.currentTimeMillis() - start));
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

private void syncSingleProjectAutomatically(Long projectId) {
        if (projectId == null) {
            return;
        }

        MlProject projectSnapshot = loadProjectOrThrow(projectId);

        System.out.println("[AUTO_SYNC] Start project id=" + projectId
                + ", name=" + projectSnapshot.getName());

        /*
         * Phase A : sélection des expériences candidates uniquement.
         * Les expériences terminales (COMPLETED/FAILED) stables et non
         * modifiées depuis la fenêtre sont ignorées : on ne re-scanne plus
         * tout le projet à chaque cycle.
         */
        List<Long> candidateIds = mlExperimentRepository.findCandidateExperimentIds(
                projectId,
                List.of(
                        ExperimentStatus.CREATED,
                        ExperimentStatus.RUNNING,
                        ExperimentStatus.CANCELLED
                ),
                utcNow().minusHours(6),
                PageRequest.of(0, 200)
        );

        System.out.println("[SYNC_CANDIDATES] projectId=" + projectId
                + ", candidates=" + candidateIds.size());

        /*
         * Phase B + C : chaque expérience candidate est synchronisée dans
         * sa propre transaction courte REQUIRES_NEW (appels MLflow/Kubeflow
         * bornés, erreurs isolées par expérience).
         */
        syncExistingProjectExperimentsCandidates(projectId, candidateIds, "auto-sync-existing");

        /*
         * Import MLflow + fusion des doublons : étapes courtes, chacune dans
         * sa propre transaction REQUIRES_NEW.
         */
        importMissingMlflowRunsIntoMysqlInTransaction(projectId);
        mergeDuplicateMlflowKubeflowRowsInTransaction(projectId);

        List<String> registeredNames =
                mlExperimentRepository.findDistinctRegisteredModelNamesByProjectId(projectId);

        for (String registeredName : registeredNames) {
            try {
                runInRequiresNewTransaction(() -> {
                    MlProject project = loadProjectOrThrow(projectId);
                    recalculateGlobalScoresForProject(project, registeredName);
                    refreshProductionVersionFlagsForProject(project, registeredName);
                });

                if (isAutoPromoteEnabled(projectSnapshot)) {
                    runInRequiresNewTransaction(() -> {
                        MlProject project = loadProjectOrThrow(projectId);
                        autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                                project,
                                registeredName
                        );
                    });

                    /*
                     * Après promotion, rafraîchir uniquement les champs liés au
                     * score et à Production. Ne pas retraiter tout le run.
                     */
                    runInRequiresNewTransaction(() -> {
                        MlProject project = loadProjectOrThrow(projectId);
                        refreshProductionVersionFlagsForProject(project, registeredName);
                        recalculateGlobalScoresForProject(project, registeredName);
                    });
                }

                runInRequiresNewTransaction(() ->
                        enforceProductionConsistencyForModel(registeredName));

            } catch (Exception e) {
                System.out.println("[AUTO_SYNC] Auto lifecycle failed for model=" + registeredName);
                System.out.println("[AUTO_SYNC] Reason: " + e.getMessage());
                e.printStackTrace();
            }
        }

        mergeDuplicateMlflowKubeflowRowsInTransaction(projectId);
        deleteUselessOrphanExperimentRowsInTransaction(projectId);

        System.out.println("[AUTO_SYNC] Finished project id=" + projectId
                + ", name=" + projectSnapshot.getName());
    }

    private void importMissingMlflowRunsIntoMysqlInTransaction(Long projectId) {
        /*
         * Phase A (lecture courte) : on ne garde AUCUNE transaction ouverte sur
         * la collecte MLflow (réseau). Chaque run est ensuite importé dans sa
         * propre transaction REQUIRES_NEW par importMissingMlflowRunsIntoMysql.
         */
        MlProject project = mlProjectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return;
        }

        importMissingMlflowRunsIntoMysql(project);
    }

    private void mergeDuplicateMlflowKubeflowRowsInTransaction(Long projectId) {
        runInRequiresNewTransaction(() -> {
            MlProject project = loadProjectOrThrow(projectId);
            mergeDuplicateMlflowKubeflowRows(project);
        });
    }

    private void deleteUselessOrphanExperimentRowsInTransaction(Long projectId) {
        runInRequiresNewTransaction(() -> {
            MlProject project = loadProjectOrThrow(projectId);
            deleteUselessOrphanExperimentRows(project);
        });
    }


    /*
     * Helpers temporels manquants dans la version précédente du service.
     * Toutes les dates internes sont calculées en UTC.
     */
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

    private void applyExecutionTimestampsForStatus(
            MlExperiment experiment,
            ExperimentStatus status
    ) {
        if (experiment == null || status == null) {
            return;
        }

        if (status == ExperimentStatus.CREATED) {
            return;
        }

        if (status == ExperimentStatus.RUNNING) {
            markExperimentStarted(experiment);
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

    /**
     * Lance un nouveau run à partir des fichiers d'une expérience existante.
     *
     * La transaction reste ouverte pendant tout le chargement et la création
     * de la nouvelle ligne. Cela évite les LazyInitializationException sur
     * MlProject, modelFile et datasetFile.
     *
     * STANDARD_RUNTIME : lancement par Kubeflow.
     * CUSTOM_DOCKERFILE avec image : Job Kubernetes direct.
     * CUSTOM_DOCKERFILE avec Dockerfile : ligne CREATED, puis prise en charge
     * par CustomDockerRunWatcher pour Kaniko et le Job d'entraînement.
     */
    @Transactional
    public MlExperimentResponse retrainFromExistingExperiment(
            Long projectId,
            Long sourceExperimentId
    ) {
        return retrainFromExistingExperiment(
                projectId,
                sourceExperimentId,
                null,
                null
        );
    }

    public MlExperimentResponse retrainFromExistingExperiment(
            Long projectId,
            Long sourceExperimentId,
            String triggerType,
            String triggerMetadata
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId is required for scheduled retraining."
            );
        }

        if (sourceExperimentId == null) {
            throw new IllegalArgumentException(
                    "sourceExperimentId is required for scheduled retraining."
            );
        }

        MlExperiment sourceExperiment = mlExperimentRepository
                .findByIdWithFiles(sourceExperimentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Source experiment with project/files not found: "
                                + sourceExperimentId
                ));

        MlProject project = sourceExperiment.getProject();

        if (project == null || project.getId() == null) {
            throw new IllegalStateException(
                    "Source experiment has no initialized project. sourceExperimentId="
                            + sourceExperimentId
            );
        }

        if (!projectId.equals(project.getId())) {
            throw new IllegalArgumentException(
                    "Source experiment does not belong to projectId="
                            + projectId
                            + ". sourceProjectId="
                            + project.getId()
            );
        }

        UploadedProjectFile sourceModelFile = sourceExperiment.getModelFile();
        UploadedProjectFile sourceDatasetFile = datasetFileResolverService.resolveDatasetFile(
                project,
                sourceExperiment
        );

        if (sourceModelFile == null
                || !hasText(sourceModelFile.getFilePath())) {
            throw new IllegalStateException(
                    "Production source has no model/script file. sourceExperimentId="
                            + sourceExperimentId
            );
        }

        if (sourceDatasetFile == null
                || !hasText(sourceDatasetFile.getFilePath())) {
            throw new IllegalStateException(
                    "Production source has no usable dataset file (missing or misassigned script). "
                            + "sourceExperimentId="
                            + sourceExperimentId
            );
        }

        String runtimeMode = firstNotBlank(
                sourceExperiment.getRuntimeMode(),
                getProjectRuntimeMode(project),
                "STANDARD_RUNTIME"
        );

        runtimeMode = runtimeMode.trim().toUpperCase(Locale.ROOT);

        if (!runtimeMode.equals("STANDARD_RUNTIME")
                && !runtimeMode.equals("CUSTOM_DOCKERFILE")) {
            throw new IllegalStateException(
                    "Unsupported runtime mode for retraining: " + runtimeMode
            );
        }

        boolean customDockerMode = isCustomDockerfileMode(runtimeMode);

        String registeredModelName = defaultValue(
                sourceExperiment.getRegisteredModelName(),
                buildRegisteredModelName(project)
        );

        String modelName = defaultValue(
                sourceExperiment.getModelName(),
                registeredModelName
        );

        String primaryMetric = normalizeMetricKey(
                firstNotBlank(
                        sourceExperiment.getPrimaryMetric(),
                        project.getPrimaryMetric1(),
                        defaultPrimaryMetricForProject(project)
                )
        );

        if (!hasText(primaryMetric)) {
            primaryMetric = defaultPrimaryMetricForProject(project);
        }

        String direction = getDirectionFromMetric(primaryMetric);
        String projectType = normalizeProjectType(project.getProjectType());

        String newRunName = buildUniqueRunName(
                "scheduled_retraining_" + buildMlflowExperimentName(project)
        );

        MlExperiment retraining = new MlExperiment();
        retraining.setProject(project);
        retraining.setExperimentName(newRunName);
        retraining.setModelType(defaultValue(sourceExperiment.getModelType(), "CUSTOM"));
        retraining.setModelName(modelName);
        retraining.setRegisteredModelName(registeredModelName);
        if (hasText(triggerType)) {
            retraining.setTriggerType(triggerType);
            retraining.setTriggerMetadata(triggerMetadata);
        }
        retraining.setMlflowExperimentName(
                customDockerMode
                        ? registeredModelName
                        : buildMlflowExperimentName(project)
        );
        retraining.setPrimaryMetric(primaryMetric);
        retraining.setDirection(direction);
        retraining.setRuntimeMode(runtimeMode);
        retraining.setTargetColumn(sourceExperiment.getTargetColumn());
        retraining.setModelFile(sourceModelFile);
        retraining.setDatasetFile(sourceDatasetFile);
        retraining.setCustomImageUri(sourceExperiment.getCustomImageUri());
        retraining.setDockerfilePath(sourceExperiment.getDockerfilePath());
        retraining.setRequirementsPath(sourceExperiment.getRequirementsPath());

        retraining.setProduction(false);
        retraining.setModelVersion(null);
        retraining.setLatestVersion(null);
        retraining.setProductionVersion(null);
        retraining.setGlobalScore(null);
        retraining.setPrimaryMetricValue(null);
        retraining.setMlflowRunId(null);
        retraining.setKubeflowRunId(null);
        retraining.setKubeflowRunName(null);
        retraining.setKubeflowStatus(null);
        retraining.setErrorMessage(null);

        if (customDockerMode) {
            String customImageUri = sourceExperiment.getCustomImageUri();

            System.out.println("[RETRAINING] Source experiment=" + sourceExperimentId
                    + ", projectId=" + projectId
                    + ", runtime=CUSTOM_DOCKERFILE"
                    + ", hasCustomImageUri=" + hasText(customImageUri)
                    + ", hasDockerfilePath=" + hasText(sourceExperiment.getDockerfilePath()));

                /* Image déjà construite : lancer directement un Job Kubernetes. */
                if (hasText(customImageUri)) {
                System.out.println("[RETRAINING] customImageUri present, launching Kubernetes training job directly."
                        + " imageUri=" + customImageUri
                        + ", sourceExperimentId=" + sourceExperimentId);

                // Persist initial experiment record in a separate transaction
                // so that the DB row exists even if job launch later fails.
                retraining.setStatus(ExperimentStatus.CREATED);
                retraining.setBuildStatus("CUSTOM_IMAGE_JOB_STARTING");
                retraining.setBuildLogs(
                    "Preparing to launch Kubernetes Job with existing image: " + customImageUri
                );
                retraining.setMessage(
                    "Scheduled CUSTOM_DOCKERFILE retraining created from Production source "
                        + sourceExperimentId
                        + ". Launching custom image Job."
                );

                MlExperiment savedExperiment = saveExperimentInNewTransaction(retraining);

                System.out.println("[RETRAINING] New experiment created. experimentId=" + savedExperiment.getId()
                        + ", sourceExperimentId=" + sourceExperimentId
                        + ", runtime=CUSTOM_DOCKERFILE");

                try {
                    String jobName = kubernetesJobService
                        .launchCustomImageTrainingJob(
                            project,
                            savedExperiment,
                            customImageUri
                        );

                    System.out.println("[K8S] Training job created: " + jobName
                            + ", experimentId=" + savedExperiment.getId()
                            + ", projectId=" + projectId);

                    savedExperiment.setKubeflowRunName(jobName);
                    savedExperiment.setTrainingJobName(jobName);
                    savedExperiment.setKubeflowStatus("RUNNING");
                    savedExperiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
                    savedExperiment.setBuildLogs(
                        "Kubernetes Job launched successfully: " + jobName
                    );
                    savedExperiment.setMessage(
                        "Custom image retraining Job launched. Waiting for MLflow synchronization."
                    );

                    MlExperiment updated = persistLaunchOutcomeInNewTransaction(
                            savedExperiment.getId(),
                            latest -> {
                                latest.setKubeflowRunName(jobName);
                                latest.setTrainingJobName(jobName);
                                latest.setKubeflowStatus("RUNNING");
                                latest.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
                                latest.setBuildLogs(
                                        "Kubernetes Job launched successfully: " + jobName
                                );
                                latest.setMessage(
                                        "Custom image retraining Job launched. Waiting for MLflow synchronization."
                                );
                            }
                    );

                    System.out.println("[RETRAINING] Retraining launched successfully."
                            + " experimentId=" + updated.getId()
                            + ", sourceExperimentId=" + sourceExperimentId
                            + ", projectId=" + projectId
                            + ", jobName=" + jobName
                            + ", runtime=CUSTOM_DOCKERFILE");

                    triggerImmediateProjectSync(projectId);
                    return mapToResponse(reloadExperimentForResponse(updated.getId()));

                } catch (Exception error) {
                    MlExperiment failed = persistLaunchOutcomeInNewTransaction(
                            savedExperiment.getId(),
                            latest -> {
                                latest.setStatus(ExperimentStatus.FAILED);
                                applyExecutionTimestampsForStatus(latest, ExperimentStatus.FAILED);
                                latest.setBuildStatus("CUSTOM_IMAGE_JOB_FAILED");
                                latest.setBuildLogs(limitForDatabase(error.getMessage(), 10000));
                                latest.setErrorMessage(limitForDatabase(error.getMessage(), 2000));
                                latest.setMessage("Scheduled retraining could not launch the custom image Job.");
                            }
                    );
                    notifyExperimentStatusChangedIfNeeded(ExperimentStatus.CREATED, failed);
                    return mapToResponse(reloadExperimentForResponse(failed.getId()));
                }
                }

            /* Dockerfile personnalisé : le watcher doit construire l'image avec Kaniko. */
            if (!hasText(sourceExperiment.getDockerfilePath())) {
                throw new IllegalStateException(
                        "CUSTOM_DOCKERFILE Production source has neither "
                                + "customImageUri nor Dockerfile path. sourceExperimentId="
                                + sourceExperimentId
                );
            }

            System.out.println("[RETRAINING] No customImageUri, Kaniko build required."
                    + " dockerfilePath=" + sourceExperiment.getDockerfilePath()
                    + ", sourceExperimentId=" + sourceExperimentId);

            retraining.setStatus(ExperimentStatus.CREATED);
            retraining.setBuildStatus("WAITING_FOR_IMAGE_BUILD");
            retraining.setBuildLogs(
                    "Waiting for CustomDockerRunWatcher to build "
                            + "the retraining image with Kaniko."
            );
            retraining.setMessage(
                    "Scheduled CUSTOM_DOCKERFILE retraining created from Production source "
                            + sourceExperimentId
                            + ". Waiting for Kaniko image build."
            );

            MlExperiment savedExperiment = mlExperimentRepository
                    .saveAndFlush(retraining);

            System.out.println("[RETRAINING] New experiment created for Kaniko build."
                    + " experimentId=" + savedExperiment.getId()
                    + ", sourceExperimentId=" + sourceExperimentId
                    + ", projectId=" + projectId);

            triggerImmediateProjectSync(projectId);

            return mapToResponse(
                    reloadExperimentForResponse(savedExperiment.getId())
            );
        }

        /* STANDARD_RUNTIME : lancement par Kubeflow. */
        // Persist initial experiment record in a new transaction before calling
        // the external pipeline gateway so the DB reflects the retraining request.
        retraining.setStatus(ExperimentStatus.CREATED);
        retraining.setBuildStatus(null);
        retraining.setBuildLogs(null);
        retraining.setMessage(
            "Scheduled STANDARD_RUNTIME retraining created from Production source "
                + sourceExperimentId
                + ". Launching Kubeflow pipeline."
        );

        MlExperiment savedExperiment = saveExperimentInNewTransaction(retraining);

        try {
            PipelineGatewayResponse gatewayResponse =
                pipelineClientService.launchCustomModelPipeline(
                    savedExperiment.getId(),
                    project.getId(),
                    project.getName(),
                    modelName,
                    registeredModelName,
                    newRunName,
                    sourceExperiment.getTargetColumn(),
                    primaryMetric,
                    direction,
                    projectType,
                    runtimeMode,
                    sourceModelFile,
                    sourceDatasetFile
                );

            savedExperiment.setKubeflowRunId(gatewayResponse.getKubeflowRunId());
            savedExperiment.setKubeflowRunName(
                defaultValue(gatewayResponse.getKubeflowRunName(), newRunName)
            );
            savedExperiment.setRegisteredModelName(registeredModelName);

            if (hasText(gatewayResponse.getMlflowRunId())
                && !isMlflowRunAlreadyLinkedToAnotherExperiment(
                    gatewayResponse.getMlflowRunId(),
                    savedExperiment.getId()
                )) {
            savedExperiment.setMlflowRunId(gatewayResponse.getMlflowRunId());
            }

            ExperimentStatus gatewayStatus = detectStatusFromGatewayResponse(gatewayResponse);

            ExperimentStatus finalStatus =
                gatewayStatus == ExperimentStatus.FAILED
                    ? ExperimentStatus.FAILED
                    : ExperimentStatus.RUNNING;

            MlExperiment updatedExperiment = persistLaunchOutcomeInNewTransaction(
                    savedExperiment.getId(),
                    latest -> {
                        latest.setKubeflowRunId(gatewayResponse.getKubeflowRunId());
                        latest.setKubeflowRunName(
                            defaultValue(gatewayResponse.getKubeflowRunName(), newRunName)
                        );
                        latest.setRegisteredModelName(registeredModelName);

                        if (hasText(gatewayResponse.getMlflowRunId())
                            && !isMlflowRunAlreadyLinkedToAnotherExperiment(
                                gatewayResponse.getMlflowRunId(),
                                latest.getId()
                            )) {
                        latest.setMlflowRunId(gatewayResponse.getMlflowRunId());
                        }

                        latest.setStatus(finalStatus);
                        applyExecutionTimestampsForStatus(latest, finalStatus);

                        if (gatewayStatus == ExperimentStatus.FAILED) {
                        latest.setErrorMessage(limitForDatabase(gatewayResponse.getMessage(), 2000));
                        latest.setMessage("Scheduled retraining pipeline launch failed: " + safeText(gatewayResponse.getMessage()));
                        } else {
                        latest.setErrorMessage(null);
                        latest.setMessage(hasText(gatewayResponse.getMessage()) ? "Scheduled retraining launched. " + gatewayResponse.getMessage() : "Scheduled retraining launched. Waiting for MLflow synchronization.");
                        }
                    }
            );
            triggerImmediateProjectSync(projectId);
            return mapToResponse(reloadExperimentForResponse(updatedExperiment.getId()));

        } catch (Exception error) {
            MlExperiment failedExperiment = persistLaunchOutcomeInNewTransaction(
                    savedExperiment.getId(),
                    latest -> {
                        latest.setStatus(ExperimentStatus.FAILED);
                        applyExecutionTimestampsForStatus(latest, ExperimentStatus.FAILED);
                        latest.setErrorMessage(limitForDatabase(error.getMessage(), 2000));
                        latest.setMessage("Scheduled retraining could not launch the Kubeflow pipeline.");
                    }
            );
            notifyExperimentStatusChangedIfNeeded(ExperimentStatus.CREATED, failedExperiment);
            return mapToResponse(reloadExperimentForResponse(failedExperiment.getId()));
        }
    }

        private MlExperiment saveExperimentInNewTransaction(MlExperiment experiment) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(20);

        return template.execute(status -> mlExperimentRepository.saveAndFlush(experiment));
        }

    /**
     * Persiste l'état post-lancement dans une transaction REQUIRES_NEW.
     *
     * L'expérience est d'abord insérée dans une transaction séparée pour que
     * la ligne existe avant l'appel externe (pipeline/Job). La transaction
     * englobante, en isolation REPEATABLE READ, ne voit pas cette ligne
     * (snapshot antérieur à l'insertion) : réutiliser la même instance pour
     * un saveAndFlush provoque un StaleStateException
     * ("Row was updated or deleted by another transaction").
     *
     * Ici on relit la ligne dans une transaction neuve (READ COMMITTED) puis
     * on applique l'état avant de sauvegarder, ce qui évite le conflit.
     */
    private MlExperiment persistLaunchOutcomeInNewTransaction(
            Long experimentId,
            Consumer<MlExperiment> stateUpdater
    ) {
        if (experimentId == null) {
            throw new IllegalStateException(
                    "Cannot persist retraining launch state without experimentId."
            );
        }

        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(20);

        return template.execute(status -> {
            MlExperiment latest = mlExperimentRepository
                    .findById(experimentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Retraining experiment no longer exists. experimentId="
                                    + experimentId
                    ));

            stateUpdater.accept(latest);

            return mlExperimentRepository.saveAndFlush(latest);
        });
    }

    /**
     * Point d'entrée appelé par AutoRetrainingService.
     *
     * Règle importante :
     * - la source déjà résolue dans retraining_config est utilisée en priorité ;
     * - si elle n'est plus exploitable, la Production courante est recherchée ;
     * - la validation repose sur le projet, le statut, le run MLflow, les versions
     *   et les fichiers, pas sur le booléen isProduction qui peut être temporairement
     *   modifié par le reconciler.
     */
    @Transactional
    public boolean hasActiveRetrainingForProject(Long projectId) {
        if (projectId == null) {
            return false;
        }

        Long activeCount = mlExperimentRepository.countActiveScheduledRetrainingForProject(projectId);
        return activeCount != null && activeCount > 0;
    }

    @Transactional
    public MlExperimentResponse retrainFromCurrentProductionExperiment(
            Long projectId,
            Long preferredSourceExperimentId
    ) {
        return retrainFromCurrentProductionExperiment(
                projectId,
                preferredSourceExperimentId,
                null,
                null
        );
    }

    @Transactional
    public MlExperimentResponse retrainFromCurrentProductionExperiment(
            Long projectId,
            Long preferredSourceExperimentId,
            String triggerType,
            String triggerMetadata
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId is required for scheduled retraining."
            );
        }

        if (preferredSourceExperimentId != null) {
            MlExperiment preferredSource = mlExperimentRepository
                    .findByIdWithFiles(preferredSourceExperimentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Preferred retraining source was not found with its files. "
                                    + "sourceExperimentId="
                                    + preferredSourceExperimentId
                    ));

            validateRetrainingSourceOrThrow(
                    preferredSource,
                    projectId,
                    "preferred"
            );

            System.out.println(
                    "[RETRAINING_VALIDATION] Preferred source accepted. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + preferredSource.getId()
                            + ", registeredModel="
                            + preferredSource.getRegisteredModelName()
                            + ", modelVersion="
                            + preferredSource.getModelVersion()
                            + ", productionVersion="
                            + preferredSource.getProductionVersion()
                            + ", runtimeMode="
                            + preferredSource.getRuntimeMode()
            );

            return retrainFromExistingExperiment(
                    projectId,
                    preferredSource.getId(),
                    triggerType,
                    triggerMetadata
            );
        }

        MlExperiment currentProduction = mlExperimentRepository
                .findCurrentProductionExperimentForProject(projectId)
                .flatMap(production ->
                        mlExperimentRepository.findByIdWithFiles(
                                production.getId()
                        )
                )
                .orElseThrow(() -> new IllegalStateException(
                        "No current Production source was found for projectId="
                                + projectId
                ));

        validateRetrainingSourceOrThrow(
                currentProduction,
                projectId,
                "current-production"
        );

        System.out.println(
                "[RETRAINING_VALIDATION] Current Production source accepted. "
                        + "projectId="
                        + projectId
                        + ", sourceExperimentId="
                        + currentProduction.getId()
                        + ", registeredModel="
                        + currentProduction.getRegisteredModelName()
                        + ", modelVersion="
                        + currentProduction.getModelVersion()
                        + ", productionVersion="
                        + currentProduction.getProductionVersion()
                        + ", runtimeMode="
                        + currentProduction.getRuntimeMode()
        );

        return retrainFromExistingExperiment(
                projectId,
                currentProduction.getId(),
                triggerType,
                triggerMetadata
        );
    }

    private void validateRetrainingSourceOrThrow(
            MlExperiment experiment,
            Long expectedProjectId,
            String sourceKind
    ) {
        if (experiment == null) {
            throw new IllegalStateException(
                    "Retraining source is null. sourceKind=" + sourceKind
            );
        }

        if (experiment.getId() == null) {
            throw new IllegalStateException(
                    "Retraining source has no id. sourceKind=" + sourceKind
            );
        }

        if (experiment.getProject() == null
                || experiment.getProject().getId() == null) {
            throw new IllegalStateException(
                    "Retraining source has no initialized project. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        if (!expectedProjectId.equals(
                experiment.getProject().getId()
        )) {
            throw new IllegalStateException(
                    "Retraining source belongs to another project. "
                            + "sourceExperimentId="
                            + experiment.getId()
                            + ", expectedProjectId="
                            + expectedProjectId
                            + ", actualProjectId="
                            + experiment.getProject().getId()
            );
        }

        if (experiment.getStatus()
                != ExperimentStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Retraining source is not COMPLETED. "
                            + "sourceExperimentId="
                            + experiment.getId()
                            + ", status="
                            + experiment.getStatus()
            );
        }

        if (!hasText(experiment.getMlflowRunId())) {
            throw new IllegalStateException(
                    "Retraining source has no MLflow Run ID. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        if (!hasText(
                experiment.getRegisteredModelName()
        )) {
            throw new IllegalStateException(
                    "Retraining source has no registered model name. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        if (!hasText(experiment.getModelVersion())) {
            throw new IllegalStateException(
                    "Retraining source has no modelVersion. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        if (!hasText(
                experiment.getProductionVersion()
        )) {
            throw new IllegalStateException(
                    "Retraining source has no productionVersion. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        String modelVersion =
                experiment.getModelVersion().trim();

        String productionVersion =
                experiment.getProductionVersion().trim();

        if (!modelVersion.equalsIgnoreCase(
                productionVersion
        )) {
            throw new IllegalStateException(
                    "Retraining source version is not the Production version. "
                            + "sourceExperimentId="
                            + experiment.getId()
                            + ", modelVersion="
                            + modelVersion
                            + ", productionVersion="
                            + productionVersion
            );
        }

        UploadedProjectFile modelFile =
                experiment.getModelFile();

        if (modelFile == null
                || !hasText(modelFile.getFilePath())) {
            throw new IllegalStateException(
                    "Retraining source has no usable model/script file. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        UploadedProjectFile datasetFile = datasetFileResolverService.resolveDatasetFile(
                experiment.getProject(),
                experiment
        );

        if (datasetFile == null
                || !hasText(datasetFile.getFilePath())) {
            throw new IllegalStateException(
                    "Retraining source has no usable dataset file. "
                            + "sourceExperimentId="
                            + experiment.getId()
            );
        }

        String runtimeMode = firstNotBlank(
                experiment.getRuntimeMode(),
                getProjectRuntimeMode(experiment.getProject()),
                "STANDARD_RUNTIME"
        );

        runtimeMode = runtimeMode
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!runtimeMode.equals("STANDARD_RUNTIME")
                && !runtimeMode.equals("CUSTOM_DOCKERFILE")) {
            throw new IllegalStateException(
                    "Unsupported retraining runtime mode. "
                            + "sourceExperimentId="
                            + experiment.getId()
                            + ", runtimeMode="
                            + runtimeMode
            );
        }

        if (runtimeMode.equals("CUSTOM_DOCKERFILE")
                && !hasText(experiment.getCustomImageUri())
                && !hasText(experiment.getDockerfilePath())) {
            throw new IllegalStateException(
                    "CUSTOM_DOCKERFILE source has neither customImageUri "
                            + "nor Dockerfile path. sourceExperimentId="
                            + experiment.getId()
            );
        }
    }

    private boolean isValidProductionRetrainingSource(
            MlExperiment experiment,
            String expectedRegistry
    ) {
        if (experiment == null
                || experiment.getProject() == null
                || experiment.getProject().getId() == null) {
            return false;
        }

        try {
            validateRetrainingSourceOrThrow(
                    experiment,
                    experiment.getProject().getId(),
                    "compatibility-validation"
            );

            if (hasText(expectedRegistry)
                    && !sameRegistryName(
                            experiment.getRegisteredModelName(),
                            expectedRegistry
                    )) {
                System.out.println(
                        "[RETRAINING_VALIDATION] Registry name differs, "
                                + "but the source remains valid. "
                                + "sourceExperimentId="
                                + experiment.getId()
                                + ", actualRegistry="
                                + experiment.getRegisteredModelName()
                                + ", expectedRegistry="
                                + expectedRegistry
                );
            }

            return true;

        } catch (RuntimeException validationError) {
            System.out.println(
                    "[RETRAINING_VALIDATION] Source rejected. "
                            + "sourceExperimentId="
                            + experiment.getId()
                            + ", reason="
                            + safeText(validationError.getMessage())
            );

            return false;
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value;
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