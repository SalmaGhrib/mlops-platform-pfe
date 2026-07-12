package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedProjectFile;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KubernetesBuildService {

    private static final String STANDARD_TRAIN_FILE = "train.py";
    private static final String STANDARD_DATASET_FILE = "dataset.csv";
    private static final String STANDARD_REQUIREMENTS_FILE = "requirements.txt";
    private static final String STANDARD_DOCKERFILE = "Dockerfile";
    private static final String STANDARD_PLATFORM_RUNNER_FILE = "platform_runner.py";

    /*
     * Kubernetes ConfigMap limit is ~1 MiB.
     * The build context ConfigMap must only contain small build files.
     * Large files such as datasets must stay outside the ConfigMap
     * and be mounted/read at training time from /app/uploads through a PVC.
     */
    private static final long CONFIGMAP_SAFE_MAX_BYTES = 900_000L;

    @Value("${app.kubernetes.namespace:mlops}")
    private String namespace;

    @Value("${app.kaniko.image:gcr.io/kaniko-project/executor:latest}")
    private String kanikoImage;

    @Value("${app.docker.registry:acrmlopssalma.azurecr.io}")
    private String dockerRegistry;

    @Value("${app.docker.custom-image-prefix:mlops-custom}")
    private String customImagePrefix;

    @Value("${app.kubernetes.custom-job.ttl-seconds:3600}")
    private Integer ttlSecondsAfterFinished;

    @Value("${app.kubernetes.custom-job.backoff-limit:0}")
    private Integer backoffLimit;

    @Value("${app.uploads.pvc-name:mlops-uploads-pvc}")
private String uploadsPvcName;

@Value("${app.uploads.mount-path:/app/uploads}")
private String uploadsMountPath;

    @Value("${app.kaniko.docker-config-secret:kaniko-docker-config}")
    private String kanikoDockerConfigSecret;

    @Value("${app.kaniko.cache-repo:acrmlopssalma.azurecr.io/mlops-kaniko-cache}")
    private String kanikoCacheRepo;

    @Value("${app.platform-runner.path:/app/mini-mlops/src/custom_models/platform_runner.py}")
    private String platformRunnerPath;

    @Value("${app.platform-runtime-root:/app/mini-mlops}")
    private String platformRuntimeRoot;

    public static class BuildJobResult {
        private String buildJobName;
        private String buildConfigMapName;
        private String imageUri;

        public BuildJobResult() {
        }

        public BuildJobResult(String buildJobName, String buildConfigMapName, String imageUri) {
            this.buildJobName = buildJobName;
            this.buildConfigMapName = buildConfigMapName;
            this.imageUri = imageUri;
        }

        public String getBuildJobName() {
            return buildJobName;
        }

        public void setBuildJobName(String buildJobName) {
            this.buildJobName = buildJobName;
        }

        public String getBuildConfigMapName() {
            return buildConfigMapName;
        }

        public void setBuildConfigMapName(String buildConfigMapName) {
            this.buildConfigMapName = buildConfigMapName;
        }

        public String getImageUri() {
            return imageUri;
        }

        public void setImageUri(String imageUri) {
            this.imageUri = imageUri;
        }
    }

    public BuildJobResult launchImageBuildJob(MlProject project, MlExperiment experiment) {
        validateInputs(project, experiment);

        String buildJobName = buildBuildJobName(project.getName(), experiment.getId());
        String configMapName = buildConfigMapName(project.getName(), experiment.getId());
        String imageUri = buildGeneratedImageUri(project.getName(), experiment.getId());

        System.out.println("[KubernetesBuildService] Launching build job for project='" + project.getName() + "' expId=" + experiment.getId());
        System.out.println("[KubernetesBuildService] buildJobName='" + buildJobName + "', configMapName='" + configMapName + "', imageUri='" + imageUri + "'");

        Map<String, String> buildContextFiles = buildContextFiles(project, experiment);
        System.out.println("[KubernetesBuildService] Build context contains files=" + buildContextFiles.keySet());

        assertConfigMapKeysAreSafe(buildContextFiles);
 
        System.out.println("[CUSTOM-DOCKER] ConfigMap keys are Kubernetes-safe=" + areConfigMapKeysKubernetesSafe(buildContextFiles));
        System.out.println("[CUSTOM-DOCKER] build context contains platform_runner.py=" + buildContextFiles.containsKey(STANDARD_PLATFORM_RUNNER_FILE));
        System.out.println("[CUSTOM-DOCKER] build context contains src__custom_models__generic_pyfunc_wrapper.py=" + buildContextFiles.containsKey("src__custom_models__generic_pyfunc_wrapper.py"));
        System.out.println("[CUSTOM-DOCKER] build context contains src__custom_models__import_detector.py=" + buildContextFiles.containsKey("src__custom_models__import_detector.py"));
        String dockerfileForCheck = buildContextFiles.get(STANDARD_DOCKERFILE) == null ? "" : buildContextFiles.get(STANDARD_DOCKERFILE).toLowerCase(Locale.ROOT);
        System.out.println("[CUSTOM-DOCKER] Dockerfile contains COPY src /workspace/src=" + dockerfileForCheck.contains("copy src") );
        System.out.println("[CUSTOM-DOCKER] Dockerfile contains ENV PYTHONPATH=/workspace=" + dockerfileForCheck.contains("pythonpath=/workspace") );
        System.out.println("[CUSTOM-DOCKER] Reconstructed build context will contain src/custom_models/generic_pyfunc_wrapper.py=" + buildContextFiles.containsKey("src__custom_models__generic_pyfunc_wrapper.py"));
        System.out.println("[CUSTOM-DOCKER] Reconstructed build context will contain src/custom_models/import_detector.py=" + buildContextFiles.containsKey("src__custom_models__import_detector.py"));

        try {
            validateBuildContextAgainstDockerfile(buildContextFiles, buildContextFiles.get(STANDARD_DOCKERFILE));
        } catch (RuntimeException e) {
            System.out.println("[KubernetesBuildService] Build context validation failed: " + e.getMessage());
            throw e;
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            validateKanikoDockerConfigSecret(client);

            deleteExistingJobIfExists(client, buildJobName);
            deleteExistingConfigMapIfExists(client, configMapName);

            ConfigMap configMap = buildConfigMap(configMapName, buildContextFiles);

            client.configMaps()
                    .inNamespace(namespace)
                    .resource(configMap)
                    .createOrReplace();

            System.out.println("[KubernetesBuildService] ConfigMap created: " + configMapName);

            Job job = buildKanikoJob(
                    buildJobName,
                    configMapName,
                    imageUri,
                    project,
                    experiment
            );

            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .resource(job)
                    .create();

            System.out.println("[KubernetesBuildService] Kubernetes Job created: " + buildJobName);

            return new BuildJobResult(buildJobName, configMapName, imageUri);

        } catch (Exception e) {
            throw new RuntimeException("Failed to launch image build job: " + e.getMessage(), e);
        }
    }

    public String getBuildJobStatus(String buildJobName) {
        if (buildJobName == null || buildJobName.isBlank()) {
            return "UNKNOWN";
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Job job = client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(buildJobName)
                    .get();

            if (job == null) {
                return "NOT_FOUND";
            }

            if (job.getStatus() != null && job.getStatus().getConditions() != null) {
                for (JobCondition condition : job.getStatus().getConditions()) {
                    if ("True".equalsIgnoreCase(condition.getStatus())) {
                        if ("Complete".equalsIgnoreCase(condition.getType())) {
                            return "COMPLETED";
                        }

                        if ("Failed".equalsIgnoreCase(condition.getType())) {
                            return "FAILED";
                        }
                    }
                }
            }

            Integer active = job.getStatus() != null ? job.getStatus().getActive() : null;
            Integer succeeded = job.getStatus() != null ? job.getStatus().getSucceeded() : null;
            Integer failed = job.getStatus() != null ? job.getStatus().getFailed() : null;

            if (succeeded != null && succeeded > 0) {
                return "COMPLETED";
            }

            if (failed != null && failed > 0) {
                return "FAILED";
            }

            if (active != null && active > 0) {
                return "RUNNING";
            }

            return "PENDING";

        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public boolean isBuildCompleted(String buildJobName) {
        return "COMPLETED".equalsIgnoreCase(getBuildJobStatus(buildJobName));
    }

    public boolean isBuildFailed(String buildJobName) {
        return "FAILED".equalsIgnoreCase(getBuildJobStatus(buildJobName));
    }

    public String getBuildJobLogs(String buildJobName) {
        if (buildJobName == null || buildJobName.isBlank()) {
            return "";
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Pod> pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("job-name", buildJobName)
                    .list()
                    .getItems();

            if (pods == null || pods.isEmpty()) {
                return "";
            }

            StringBuilder logs = new StringBuilder();

            for (Pod pod : pods) {
                if (pod == null || pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                    continue;
                }

                String podName = pod.getMetadata().getName();

                logs.append("===== POD: ")
                        .append(podName)
                        .append(" =====\n");

                try {
                    String podLogs = client.pods()
                            .inNamespace(namespace)
                            .withName(podName)
                            .inContainer("kaniko")
                            .getLog();

                    logs.append(podLogs != null ? podLogs : "");
                } catch (Exception e) {
                    logs.append("Failed to read logs for pod ")
                            .append(podName)
                            .append(": ")
                            .append(e.getMessage())
                            .append("\n");
                }

                logs.append("\n");
            }

            return logs.toString();

        } catch (Exception e) {
            return "Failed to read build job logs: " + e.getMessage();
        }
    }

    public String buildGeneratedImageUri(String projectName, Long experimentId) {
        String registry = normalizeRegistryForKubernetes(dockerRegistry);
        String projectSlug = slugify(projectName);
        String tag = projectSlug + "-exp-" + experimentId;

        return registry + "/" + customImagePrefix + ":" + tag;
    }

    private boolean isDockerHubRegistry(String registry) {
        if (registry == null) {
            return false;
        }

        String clean = registry.trim().toLowerCase(Locale.ROOT);

        return clean.startsWith("docker.io/")
                || clean.startsWith("index.docker.io/")
                || clean.matches("^[a-z0-9][a-z0-9_-]*$");
    }

    private void validateInputs(MlProject project, MlExperiment experiment) {
        if (project == null || project.getId() == null) {
            throw new RuntimeException("Project is required to build custom Docker image.");
        }

        if (experiment == null || experiment.getId() == null) {
            throw new RuntimeException("Experiment is required to build custom Docker image.");
        }

        if (experiment.getDockerfilePath() == null || experiment.getDockerfilePath().isBlank()) {
            throw new RuntimeException("Dockerfile path is required to build custom Docker image.");
        }

        if (!Files.exists(Path.of(experiment.getDockerfilePath()))) {
            throw new RuntimeException("Dockerfile not found: " + experiment.getDockerfilePath());
        }

        UploadedProjectFile modelFile = experiment.getModelFile();

        if (modelFile == null || modelFile.getFilePath() == null || modelFile.getFilePath().isBlank()) {
            throw new RuntimeException("Model Python file is required to build custom Docker image.");
        }

        if (!Files.exists(Path.of(modelFile.getFilePath()))) {
            throw new RuntimeException("Model Python file not found: " + modelFile.getFilePath());
        }

        UploadedProjectFile datasetFile = experiment.getDatasetFile();

        if (datasetFile != null && datasetFile.getFilePath() != null && !datasetFile.getFilePath().isBlank()) {
            if (!Files.exists(Path.of(datasetFile.getFilePath()))) {
                throw new RuntimeException("Dataset file not found: " + datasetFile.getFilePath());
            }
        }
    }

    private Map<String, String> buildContextFiles(MlProject project, MlExperiment experiment) {
        Map<String, String> files = new LinkedHashMap<>();

        String dockerfileContent = readFile(experiment.getDockerfilePath());
        System.out.println("[KubernetesBuildService] Original Dockerfile content:\n" + truncateForLog(dockerfileContent));

        UploadedProjectFile modelFile = experiment.getModelFile();
        String originalModelFileName = modelFile != null
                ? safeFileName(modelFile.getOriginalFileName(), STANDARD_TRAIN_FILE)
                : null;

        UploadedProjectFile datasetFile = experiment.getDatasetFile();

        boolean datasetProvided = datasetFile != null
                && datasetFile.getFilePath() != null
                && !datasetFile.getFilePath().isBlank()
                && Files.exists(Path.of(datasetFile.getFilePath()));

        /*
         * IMPORTANT SCALABILITY FIX:
         * Do not put dataset content in the ConfigMap.
         * The dataset remains in /app/uploads/... and must be made available to
         * training pods through a shared PVC mounted at the same path.
         */
        String datasetRuntimePath = datasetProvided
                ? normalizeContainerPath(datasetFile.getFilePath())
                : "";

        String originalDatasetFileName = datasetProvided
                ? safeFileName(datasetFile.getOriginalFileName(), STANDARD_DATASET_FILE)
                : STANDARD_DATASET_FILE;

        System.out.println("[KubernetesBuildService] originalModelFileName=" + originalModelFileName);
        System.out.println("[KubernetesBuildService] datasetProvided=" + datasetProvided
                + ", datasetRuntimePath=" + (datasetRuntimePath.isBlank() ? "-" : datasetRuntimePath)
                + ", originalDatasetFileName=" + originalDatasetFileName);

        dockerfileContent = forcePlatformRunnerCommand(
                dockerfileContent,
                originalModelFileName,
                datasetProvided,
                datasetRuntimePath,
                originalDatasetFileName
        );

        files.put(STANDARD_DOCKERFILE, dockerfileContent);

        String requirementsContent = "";

        if (experiment.getRequirementsPath() != null
                && !experiment.getRequirementsPath().isBlank()
                && Files.exists(Path.of(experiment.getRequirementsPath()))) {
            requirementsContent = readFile(experiment.getRequirementsPath());
        }

        files.put(STANDARD_REQUIREMENTS_FILE, enrichRequirements(requirementsContent));
        // Load the platform runner from the mini-mlops runtime to ensure PLATFORM_RECOVERY logic is present
        files.put(
                STANDARD_PLATFORM_RUNNER_FILE,
                loadPlatformRunnerSource()
        );

        // Inject minimal platform runtime package so user image can `import src...`
        files.put("src__init__.py", "");
        files.put("src__custom_models__init__.py", "");
        files.put(
                "src__custom_models__generic_pyfunc_wrapper.py",
                readRequiredPlatformFile("src/custom_models/generic_pyfunc_wrapper.py")
        );
        files.put(
                "src__custom_models__import_detector.py",
                readRequiredPlatformFile("src/custom_models/import_detector.py")
        );

        if (modelFile != null
                && modelFile.getFilePath() != null
                && !modelFile.getFilePath().isBlank()
                && Files.exists(Path.of(modelFile.getFilePath()))) {
            String modelContent = readFile(modelFile.getFilePath());

            files.put(STANDARD_TRAIN_FILE, modelContent);

            originalModelFileName = safeFileName(
                    modelFile.getOriginalFileName(),
                    STANDARD_TRAIN_FILE
            );

            putIfSafeAndDifferent(
                    files,
                    originalModelFileName,
                    STANDARD_TRAIN_FILE,
                    modelContent
            );
        }

        if (datasetProvided) {
            try {
                long datasetSize = Files.size(Path.of(datasetFile.getFilePath()));
                System.out.println("[KubernetesBuildService] Dataset kept outside ConfigMap. "
                        + "path=" + datasetRuntimePath
                        + ", sizeBytes=" + datasetSize
                        + ". Training job must mount the shared uploads PVC at /app/uploads.");
            } catch (Exception ignored) {
                System.out.println("[KubernetesBuildService] Dataset kept outside ConfigMap. "
                        + "path=" + datasetRuntimePath
                        + ". Training job must mount the shared uploads PVC at /app/uploads.");
            }
        }

        System.out.println("[KubernetesBuildService] Final build context file keys=" + files.keySet());
        System.out.println("[KubernetesBuildService] Dockerfile final injected content:\n" + truncateForLog(files.get(STANDARD_DOCKERFILE)));

        return files;
    }

    private String normalizeContainerPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        return path.trim().replace("\\", "/");
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return "";
        }

        if (value.length() <= 3000) {
            return value;
        }

        return value.substring(0, 3000) + "\n...[truncated]...";
    }

    private String loadPlatformRunnerSource() {
        List<Path> candidates = new ArrayList<>();

        if (platformRunnerPath != null && !platformRunnerPath.isBlank()) {
            candidates.add(Path.of(platformRunnerPath));
        }

        candidates.add(Path.of("/app/mini-mlops/src/custom_models/platform_runner.py"));
        candidates.add(Path.of("mini-mlops/src/custom_models/platform_runner.py"));
        candidates.add(Path.of("../mini-mlops/src/custom_models/platform_runner.py"));

        for (Path path : candidates) {
            try {
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);

                    if (!content.contains("PLATFORM_RECOVERY")
                            || !content.contains("USER_MLFLOW_FAILURE_PATTERNS")
                            || !content.contains("Recovery completed successfully")) {
                        throw new IllegalStateException(
                                "platform_runner.py found but it does not contain PLATFORM_RECOVERY logic. path=" + path
                        );
                    }

                    System.out.println("[KubernetesBuildService] Using platform_runner.py from: " + path.toAbsolutePath());
                    System.out.println("[KubernetesBuildService] platform_runner.py size=" + content.length());
                    System.out.println("[KubernetesBuildService] platform_runner.py contains PLATFORM_RECOVERY=true");

                    return content;
                }
            } catch (Exception e) {
                System.out.println("[KubernetesBuildService] Failed to read platform_runner candidate: "
                        + path + ", reason=" + e.getMessage());
            }
        }

        throw new IllegalStateException(
                "No valid platform_runner.py with PLATFORM_RECOVERY found. "
                        + "Expected path: /app/mini-mlops/src/custom_models/platform_runner.py. "
                        + "Make sure mini-mlops is copied into the backend Docker image."
        );
    }

    private String readRequiredPlatformFile(String relativePath) {
        List<java.nio.file.Path> candidates = new ArrayList<>();

        if (platformRuntimeRoot != null && !platformRuntimeRoot.isBlank()) {
            try {
                candidates.add(java.nio.file.Path.of(platformRuntimeRoot).resolve(relativePath));
            } catch (Exception ignored) {
            }
        }

        candidates.add(java.nio.file.Path.of("/app/mini-mlops").resolve(relativePath));
        candidates.add(java.nio.file.Path.of("mini-mlops").resolve(relativePath));
        candidates.add(java.nio.file.Path.of("..", "mini-mlops").resolve(relativePath));

        for (java.nio.file.Path path : candidates) {
            try {
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    System.out.println("[CUSTOM-DOCKER] Runtime file loaded: " + path.toAbsolutePath());
                    return content;
                }
            } catch (Exception e) {
                System.out.println("[CUSTOM-DOCKER] Failed to read runtime file: " + path + ", reason=" + e.getMessage());
            }
        }

        throw new IllegalStateException("Required platform runtime file not found: " + relativePath);
    }

    private void putIfSafeAndDifferent(
            Map<String, String> files,
            String candidateFileName,
            String standardFileName,
            String content
    ) {
        if (candidateFileName == null || candidateFileName.isBlank()) {
            return;
        }

        String safeCandidate = safeFileName(candidateFileName, standardFileName);

        if (safeCandidate.isBlank()) {
            return;
        }

        if (safeCandidate.equals(standardFileName)) {
            return;
        }

        if (files.containsKey(safeCandidate)) {
            return;
        }

        files.put(safeCandidate, content);
    }

    private String enrichRequirements(String originalRequirements) {
        String content = originalRequirements == null ? "" : originalRequirements.trim();

        String[] platformRequirements = {
                "pip",
                "setuptools",
                "wheel",
                "packaging",
                "importlib-metadata",
                "mlflow==2.17.2",
                "cloudpickle",
                "pyyaml",
                "requests",
                "numpy==1.26.4",
                "pandas",
                "scikit-learn",
                "joblib"
        };

        for (String requirement : platformRequirements) {
            String packageName = extractPackageName(requirement);

            if (!containsRequirement(content, packageName)) {
                content = appendRequirement(content, requirement);
            }
        }

        return content + "\n";
    }

    private String extractPackageName(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return "";
        }

        String clean = requirement.trim();

        String[] separators = {"==", ">=", "<=", "~=", ">", "<"};

        for (String separator : separators) {
            int index = clean.indexOf(separator);

            if (index > 0) {
                return clean.substring(0, index).trim();
            }
        }

        return clean;
    }

    private boolean containsRequirement(String requirements, String packageName) {
        if (requirements == null || requirements.isBlank()) {
            return false;
        }

        String target = packageName.toLowerCase(Locale.ROOT);

        for (String line : requirements.split("\\R")) {
            String clean = line.trim().toLowerCase(Locale.ROOT);

            if (clean.isBlank() || clean.startsWith("#")) {
                continue;
            }

            if (clean.startsWith("--")) {
                continue;
            }

            if (clean.equals(target)
                    || clean.startsWith(target + "==")
                    || clean.startsWith(target + ">=")
                    || clean.startsWith(target + "<=")
                    || clean.startsWith(target + "~=")
                    || clean.startsWith(target + ">")
                    || clean.startsWith(target + "<")) {
                return true;
            }
        }

        return false;
    }

    private String appendRequirement(String content, String requirement) {
        if (content == null || content.isBlank()) {
            return requirement;
        }

        return content + "\n" + requirement;
    }

    private String forcePlatformRunnerCommand(
            String dockerfileContent,
            String originalModelFileName,
            boolean datasetProvided,
            String datasetRuntimePath,
            String originalDatasetFileName
    ) {
        String content = dockerfileContent == null ? "" : dockerfileContent.trim();

        try {
            java.util.regex.Pattern copyPattern = java.util.regex.Pattern.compile(
                    "(?im)^[\\s]*?(COPY|ADD)\\s+(.+?)\\s+(.+)$",
                    java.util.regex.Pattern.MULTILINE
            );

            java.util.regex.Matcher matcher = copyPattern.matcher(content);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String srcPart = matcher.group(2);
                boolean removeLine = false;

                for (String token : srcPart.split("\\s+")) {
                    String t = token.trim().replaceAll("^[\\\"']|[\\\"']$", "");

                    if (t.isBlank() || t.startsWith("--")) {
                        continue;
                    }

                    String base = t.contains("/") ? t.substring(t.lastIndexOf('/') + 1) : t;
                    String lowerBase = base.toLowerCase(Locale.ROOT);
                    String lowerOriginalModel = originalModelFileName == null
                            ? ""
                            : originalModelFileName.toLowerCase(Locale.ROOT);
                    String lowerOriginalDataset = originalDatasetFileName == null
                            ? ""
                            : originalDatasetFileName.toLowerCase(Locale.ROOT);

                    /*
                     * Remove user Python files from the original Dockerfile because the platform
                     * injects the standardized script as train.py.
                     */
                    if (lowerBase.endsWith(".py")) {
                        if (lowerBase.equals("train.py") || lowerBase.equals("platform_runner.py")) {
                            continue;
                        }

                        if (!lowerOriginalModel.isBlank() && lowerBase.equals(lowerOriginalModel)) {
                            removeLine = true;
                            break;
                        }

                        removeLine = true;
                        break;
                    }

                    /*
                     * SCALABILITY FIX:
                     * Never COPY datasets/CSV files into the image through ConfigMap.
                     * Large datasets stay outside the image and are read from /app/uploads
                     * at runtime through the shared uploads PVC.
                     */
                    if (lowerBase.equals("dataset.csv")
                            || (!lowerOriginalDataset.isBlank() && lowerBase.equals(lowerOriginalDataset))
                            || lowerBase.endsWith(".csv")) {
                        removeLine = true;
                        break;
                    }
                }

                if (removeLine) {
                    matcher.appendReplacement(sb, "");
                } else {
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
                }
            }

            matcher.appendTail(sb);
            content = sb.toString();

        } catch (Exception ignored) {
        }

        String workdir = detectLastWorkdir(content);

        if (workdir == null || workdir.isBlank()) {
            workdir = "/workspace";
        }

        String directory = normalizeWorkdir(workdir);

        System.out.println("[KubernetesBuildService] Detected WORKDIR='" + workdir + "', final WORKDIR='" + directory + "'");

        String datasetEnvPath = datasetProvided && datasetRuntimePath != null && !datasetRuntimePath.isBlank()
                ? datasetRuntimePath
                : directory + "/dataset.csv";

        String datasetComment = datasetProvided
                ? "# Dataset is not copied into the image. It is read from the shared uploads PVC at runtime.\n"
                : "";

        String platformInjection = """

# ---- Injected by MLOps platform ----
# The user script is standardized as train.py.
# The platform runner executes train.py, detects model artifacts,
# logs metrics/artifacts to MLflow, and reports user script failures.
%6$s
COPY requirements.txt %1$s/requirements.txt
RUN python -m pip install --no-cache-dir -r %1$s/requirements.txt

COPY platform_runner.py %1$s/platform_runner.py
COPY src %1$s/src
COPY train.py %1$s/train.py

WORKDIR %1$s
ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=%1$s
ENV OUTPUT_DIR=%1$s/outputs
ENV ML_OUTPUT_DIR=/opt/ml/model
ENV MODEL_OUTPUT_DIR=/opt/ml/model
ENV DATA_PATH=%2$s
ENV DATASET_PATH=%2$s
ENV PLATFORM_DATASET_EXTERNAL=%3$s
ENV PLATFORM_DATASET_SOURCE=%4$s

RUN mkdir -p %1$s/outputs %1$s/artifacts /opt/ml/model

CMD ["python", "platform_runner.py"]
""".formatted(
                directory,
                dockerfileEnvValue(datasetEnvPath),
                datasetProvided ? "true" : "false",
                dockerfileEnvValue(datasetRuntimePath == null ? "" : datasetRuntimePath),
                datasetProvided ? "true" : "false",
                datasetComment
        );

        content = content.replaceAll("(?im)^\\s*CMD\\s+.*$", "");
        content = content.replaceAll("(?im)^\\s*ENTRYPOINT\\s+.*$", "");

        return content.trim() + "\n" + platformInjection;
    }

    private String dockerfileEnvValue(String value) {
        if (value == null) {
            return "";
        }

        return value.trim().replace("\\", "/").replace("\"", "\\\"");
    }

    private String detectLastWorkdir(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String workdir = null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?im)^\\s*WORKDIR\\s+(.+)$");
        java.util.regex.Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String candidate = matcher.group(1).trim();

            if (!candidate.isBlank()) {
                workdir = stripQuotes(candidate);
            }
        }

        return workdir;
    }

    private String normalizeWorkdir(String workdir) {
        if (workdir == null || workdir.isBlank()) {
            return "/workspace";
        }

        String normalized = workdir.trim();

        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isBlank()) {
            return "/workspace";
        }

        return normalized;
    }

    private String stripQuotes(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String trimmed = value.trim();

        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }

        return trimmed;
    }

    private String buildPlatformRunnerContent(MlProject project, MlExperiment experiment, boolean datasetProvided, String datasetPath) {
        String generatedTargetColumn = firstNonBlank(
                readStringByGetter(experiment, "getTargetColumn"),
                readStringByGetter(experiment, "getTarget"),
                readStringByGetter(experiment, "getTargetName"),
                readStringByGetter(experiment, "getTargetVariable"),
                readStringByGetter(experiment, "getDatasetTargetColumn"),
                readStringByGetter(project, "getTargetColumn"),
                readStringByGetter(project, "getTarget"),
                readStringByGetter(project, "getTargetVariable")
        );

        String generatedProblemType = firstNonBlank(
                readStringByGetter(experiment, "getProjectType"),
                readStringByGetter(experiment, "getProblemType"),
                readStringByGetter(project, "getProjectType"),
                readStringByGetter(project, "getProblemType")
        );

        String generatedPrimaryMetric = firstNonBlank(
                readStringByGetter(experiment, "getPrimaryMetric"),
                readStringByGetter(experiment, "getPrimaryMetricName"),
                readStringByGetter(project, "getPrimaryMetric"),
                readStringByGetter(project, "getPrimaryMetricName"),
                readStringByGetter(project, "getPrimaryMetric1")
        );

        return """
import os
import re
import glob
import json
import math
import time
import pickle
import subprocess
from pathlib import Path

import mlflow
from mlflow.tracking import MlflowClient


USER_SCRIPT = os.getenv("USER_SCRIPT", "train.py")

GENERATED_TARGET_COLUMN = __TARGET_COLUMN__
GENERATED_PROBLEM_TYPE = __PROBLEM_TYPE__
GENERATED_PRIMARY_METRIC = __PRIMARY_METRIC__
GENERATED_DATASET_PATH = __DATASET_PATH__
DATASET_PROVIDED = __DATASET_PROVIDED__

PLATFORM_WORKDIR = Path.cwd()
PLATFORM_OUTPUT_DIR = PLATFORM_WORKDIR / "outputs"
PLATFORM_ARTIFACTS_DIR = PLATFORM_WORKDIR / "artifacts"
PLATFORM_ML_OUTPUT_DIR = Path("/opt/ml/model")

for directory in [PLATFORM_OUTPUT_DIR, PLATFORM_ARTIFACTS_DIR, PLATFORM_ML_OUTPUT_DIR]:
    try:
        directory.mkdir(parents=True, exist_ok=True)
    except Exception as exc:
        print(f"[PLATFORM] Could not create directory {directory}: {exc}")

os.environ.setdefault("OUTPUT_DIR", str(PLATFORM_OUTPUT_DIR))
os.environ.setdefault("ML_OUTPUT_DIR", str(PLATFORM_ML_OUTPUT_DIR))
os.environ.setdefault("MODEL_OUTPUT_DIR", str(PLATFORM_ML_OUTPUT_DIR))

if DATASET_PROVIDED:
    os.environ.setdefault("DATA_PATH", GENERATED_DATASET_PATH or "dataset.csv")
    os.environ.setdefault("DATASET_PATH", GENERATED_DATASET_PATH or "dataset.csv")

print(f"[PLATFORM] Platform runner started.")
print(f"[PLATFORM] datasetProvided={DATASET_PROVIDED}")
print(f"[PLATFORM] datasetPath={GENERATED_DATASET_PATH or '-'}")

MODEL_EXTENSIONS = (
    ".pkl", ".joblib", ".pickle",
    ".pt", ".pth",
    ".h5", ".keras",
    ".onnx",
    ".model", ".cbm", ".ubj", ".bin"
)

IGNORED_FILES = {
    "train.py",
    "platform_runner.py",
    "requirements.txt",
    "dataset.csv"
}

PREFERRED_MODEL_NAMES = (
    "best_model",
    "model",
    "modele",
    "classifier",
    "regressor",
    "estimator",
    "pipeline"
)


def normalize_metric_name(name):
    value = str(name or "").strip().lower()
    value = value.replace("-", "_").replace(" ", "_")

    aliases = {
        "f1": "f1_score",
        "f1_weighted": "f1_score",
        "precision_weighted": "precision",
        "recall_weighted": "recall",
        "r2_score": "r2",
        "root_mean_squared_error": "rmse",
        "mean_squared_error": "mse",
        "mean_absolute_error": "mae",
    }

    return aliases.get(value, value)


def clean_env_value(value):
    value = str(value or "").strip()

    if value.lower() in {"null", "none", "-", "undefined"}:
        return ""

    return value


def get_target_column():
    return clean_env_value(
        os.getenv("TARGET_COLUMN")
        or os.getenv("ML_TARGET_COLUMN")
        or os.getenv("DATASET_TARGET_COLUMN")
        or GENERATED_TARGET_COLUMN
    )


def get_primary_metric():
    return normalize_metric_name(
        clean_env_value(
            os.getenv("PRIMARY_METRIC")
            or os.getenv("PRIMARY_METRIC_1")
            or os.getenv("ML_PRIMARY_METRIC")
            or GENERATED_PRIMARY_METRIC
        )
    )


def get_problem_type():
    value = clean_env_value(
        os.getenv("PROBLEM_TYPE")
        or os.getenv("PROJECT_TYPE")
        or os.getenv("ML_PROBLEM_TYPE")
        or GENERATED_PROBLEM_TYPE
    ).lower().replace("-", "_").replace(" ", "_")

    if value == "regression":
        return "regression"

    if value in {
        "classification",
        "sentiment",
        "sentiment_analysis",
        "qa",
        "question_answering",
        "image_classification"
    }:
        return "classification"

    # Translation, LLM fine-tuning and custom jobs are not evaluated with
    # sklearn classification/regression metrics. We leave the value empty
    # so auto_evaluate_metrics can infer it from y_true + primary metric.
    if value in {"translation", "llm", "llm_finetuning", "custom"}:
        return ""

    return value


def list_files():
    result = set()

    search_roots = [
        Path("."),
        PLATFORM_OUTPUT_DIR,
        PLATFORM_ARTIFACTS_DIR,
        PLATFORM_ML_OUTPUT_DIR,
        Path("/workspace"),
    ]

    for root in search_roots:
        try:
            if not root.exists():
                continue

            for path in root.glob("**/*"):
                if path.is_file():
                    try:
                        if path.is_absolute():
                            normalized = str(path)
                        else:
                            normalized = str(path).replace("\\\\", "/")

                        result.add(normalized.replace("\\\\", "/"))
                    except Exception:
                        pass

        except Exception as exc:
            print(f"[PLATFORM] Could not scan {root}: {exc}")

    return result

def is_model_candidate(path):
    p = Path(path)
    name = p.name.lower()

    if name in IGNORED_FILES:
        return False

    return name.endswith(MODEL_EXTENSIONS)


def score_model_candidate(path):
    p = Path(path)
    name = p.name.lower()
    normalized = str(p).replace("\\\\", "/").lower()

    score = 0

    if name.startswith("best_model") or name.startswith("best") or name.startswith("best-modele"):
        score += 1000

    for keyword in PREFERRED_MODEL_NAMES:
        if keyword in name:
            score += 500

    if normalized.startswith("outputs/") or "/outputs/" in normalized:
        score += 200

    if normalized.startswith("model/") or "/model/" in normalized:
        score += 200

    if normalized.startswith("artifacts/") or "/artifacts/" in normalized:
        score += 100

    if name.endswith((".pkl", ".joblib", ".pickle")):
        score += 90
    elif name.endswith((".pt", ".pth", ".keras", ".h5", ".onnx")):
        score += 80
    else:
        score += 40

    try:
        size_mb = p.stat().st_size / (1024 * 1024)
        score += min(size_mb, 100)
    except Exception:
        pass

    return score


def choose_main_model(model_files):
    if not model_files:
        return None

    if len(model_files) == 1:
        return model_files[0]

    ranked = sorted(
        model_files,
        key=lambda f: score_model_candidate(f),
        reverse=True
    )

    return ranked[0]

def discover_model_files_from_all_locations():
    candidates = []

    search_roots = [
        Path("."),
        PLATFORM_OUTPUT_DIR,
        PLATFORM_ARTIFACTS_DIR,
        PLATFORM_ML_OUTPUT_DIR,
        Path("/workspace"),
    ]

    for root in search_roots:
        try:
            if not root.exists():
                continue

            for path in root.glob("**/*"):
                if path.is_file():
                    path_str = str(path).replace("\\\\", "/")

                    if is_model_candidate(path_str):
                        candidates.append(path_str)

        except Exception as exc:
            print(f"[PLATFORM_MODEL] Could not discover models in {root}: {exc}")

    unique = []
    seen = set()

    for item in candidates:
        clean = item.strip()

        if clean and clean not in seen:
            seen.add(clean)
            unique.append(clean)

    return unique


def extract_metrics(text):
    metrics = {}

    pattern = re.compile(
        r"(?i)\\b(accuracy|precision|recall|f1_score|f1|f1_weighted|precision_weighted|recall_weighted|r2|r2_score|rmse|mae|mse|loss|val_loss|val_accuracy|log_loss)\\b\\s*[:= ]\\s*(-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)"
    )

    for match in pattern.finditer(text or ""):
        name = normalize_metric_name(match.group(1))
        value = float(match.group(2))
        metrics[name] = value

    return metrics


def load_metrics_json():
    candidates = [
        "metrics.json",
        "outputs/metrics.json",
        "artifacts/metrics.json",
        str(PLATFORM_OUTPUT_DIR / "metrics.json"),
        str(PLATFORM_ARTIFACTS_DIR / "metrics.json"),
        str(PLATFORM_ML_OUTPUT_DIR / "metrics.json"),
        "/opt/ml/model/metrics.json",
    ]

    for candidate in candidates:
        p = Path(candidate)

        if not p.exists():
            continue

        try:
            with p.open("r", encoding="utf-8") as f:
                data = json.load(f)

            metrics = {}

            for k, v in data.items():
                try:
                    metrics[normalize_metric_name(k)] = float(v)
                except Exception:
                    pass

            print(f"[PLATFORM_METRICS] metrics loaded from {candidate}")
            return metrics

        except Exception as e:
            print(f"[PLATFORM] Failed to read metrics file {candidate}: {e}")

    return {}

def load_model_object(model_path):
    lower = str(model_path).lower()

    if lower.endswith((".pkl", ".joblib", ".pickle")):
        try:
            import joblib
            return joblib.load(model_path)
        except Exception as first_error:
            try:
                with open(model_path, "rb") as f:
                    return pickle.load(f)
            except Exception as second_error:
                raise RuntimeError(
                    f"Could not load model file {model_path}. "
                    f"joblib error={first_error}; pickle error={second_error}"
                )

    raise RuntimeError(f"Automatic metric evaluation supports only pickle/joblib models for now. Got: {model_path}")


def find_dataset_path():
    if not DATASET_PROVIDED:
        print("[PLATFORM_METRICS] No dataset provided by platform; automatic CSV metrics skipped.")
        return None

    candidates = [
        os.getenv("DATASET_PATH", "").strip(),
        os.getenv("DATA_PATH", "").strip(),
        GENERATED_DATASET_PATH,
        "dataset.csv",
        "data.csv",
        "train.csv",
        str(PLATFORM_WORKDIR / "dataset.csv"),
        str(PLATFORM_OUTPUT_DIR / "dataset.csv"),
    ]

    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate

    csv_files = [
        str(p) for p in Path(".").glob("*.csv")
        if p.name.lower() not in IGNORED_FILES
    ]

    if csv_files:
        return csv_files[0]

    return None


def infer_problem_type(y_true, primary_metric):
    primary_metric = normalize_metric_name(primary_metric)

    if primary_metric in {"rmse", "mae", "mse", "r2"}:
        return "regression"

    if primary_metric in {"accuracy", "precision", "recall", "f1_score"}:
        return "classification"

    try:
        unique_count = y_true.nunique(dropna=True)
        is_numeric = str(y_true.dtype).lower() in {
            "int64", "int32", "int16", "int8",
            "float64", "float32", "float16"
        }

        if is_numeric and unique_count > 20:
            return "regression"

        return "classification"
    except Exception:
        return "classification"


def prepare_features_for_prediction(model, X):
    original_X = X.copy()

    try:
        return model.predict(original_X)
    except Exception as raw_error:
        print(f"[PLATFORM_METRICS] raw predict failed, trying encoded/aligned features: {raw_error}")

    import pandas as pd

    X_encoded = original_X.copy()

    for column in X_encoded.columns:
        if str(X_encoded[column].dtype) == "object":
            X_encoded[column] = X_encoded[column].fillna("missing").astype(str)

    X_encoded = pd.get_dummies(X_encoded)

    expected_columns = getattr(model, "feature_names_in_", None)

    if expected_columns is not None:
        for column in expected_columns:
            if column not in X_encoded.columns:
                X_encoded[column] = 0

        X_encoded = X_encoded[list(expected_columns)]

    return model.predict(X_encoded)


def auto_evaluate_metrics(main_model):
    if not main_model:
        print("[PLATFORM_METRICS] no main model detected; automatic metrics skipped.")
        return {}

    if not str(main_model).lower().endswith((".pkl", ".joblib", ".pickle")):
        print(f"[PLATFORM_METRICS] automatic metrics skipped for non pickle/joblib model: {main_model}")
        return {}

    dataset_path = find_dataset_path()

    if not dataset_path:
        mlflow.set_tag("auto_metrics_enabled", "false")
        mlflow.set_tag("auto_metrics_skipped_reason", "missing_dataset")
        print("[PLATFORM_METRICS] dataset not found; automatic metrics skipped.")
        return {}

    target_column = get_target_column()

    if not target_column:
        mlflow.set_tag("auto_metrics_enabled", "false")
        mlflow.set_tag("auto_metrics_skipped_reason", "missing_target_column")
        print("[PLATFORM_METRICS] target column not configured; automatic metrics skipped.")
        return {}

    try:
        import pandas as pd
        from sklearn.metrics import (
            mean_squared_error,
            mean_absolute_error,
            r2_score,
            accuracy_score,
            precision_score,
            recall_score,
            f1_score,
        )

        df = pd.read_csv(dataset_path)

        if df.empty:
            print("[PLATFORM_METRICS] dataset is empty; automatic metrics skipped.")
            return {}

        if target_column not in df.columns:
            mlflow.set_tag("auto_metrics_enabled", "false")
            mlflow.set_tag("auto_metrics_skipped_reason", "target_column_not_found")
            print(f"[PLATFORM_METRICS] target column '{target_column}' not found in dataset; automatic metrics skipped.")
            print(f"[PLATFORM_METRICS] available columns: {list(df.columns)}")
            return {}

        X = df.drop(columns=[target_column])
        y_true = df[target_column]

        model = load_model_object(main_model)
        y_pred = prepare_features_for_prediction(model, X)

        primary_metric = get_primary_metric()
        problem_type = get_problem_type()

        if problem_type not in {"regression", "classification"}:
            problem_type = infer_problem_type(y_true, primary_metric)

        metrics = {}

        if problem_type == "regression":
            mse = mean_squared_error(y_true, y_pred)
            metrics["mse"] = float(mse)
            metrics["rmse"] = float(math.sqrt(mse))
            metrics["mae"] = float(mean_absolute_error(y_true, y_pred))
            metrics["r2"] = float(r2_score(y_true, y_pred))

        elif problem_type == "classification":
            metrics["accuracy"] = float(accuracy_score(y_true, y_pred))
            metrics["precision"] = float(precision_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["recall"] = float(recall_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["f1_score"] = float(f1_score(y_true, y_pred, average="weighted", zero_division=0))

        else:
            print(f"[PLATFORM_METRICS] unknown problem type '{problem_type}'; automatic metrics skipped.")
            return {}

        mlflow.set_tag("auto_metrics_enabled", "true")
        mlflow.set_tag("auto_metrics_dataset", dataset_path)
        mlflow.set_tag("auto_metrics_target_column", str(target_column))
        mlflow.set_tag("auto_metrics_problem_type", str(problem_type))

        print(f"[PLATFORM_METRICS] automatic evaluation succeeded using dataset={dataset_path}, target={target_column}, problem_type={problem_type}")

        for name, value in metrics.items():
            print(f"[PLATFORM_METRIC_AUTO] {name}={value}")

        return metrics

    except Exception as e:
        mlflow.set_tag("auto_metrics_enabled", "false")
        mlflow.set_tag("auto_metrics_error", str(e)[:250])
        print(f"[PLATFORM_METRICS] automatic evaluation failed: {e}")
        return {}


class TorchMNISTPyFunc(mlflow.pyfunc.PythonModel):
    def load_context(self, context):
        import torch
        import torch.nn as nn

        self.torch = torch
        model_file = context.artifacts["model_file"]

        class SimpleNet(nn.Module):
            def __init__(self):
                super(SimpleNet, self).__init__()
                self.fc1 = nn.Linear(28 * 28, 128)
                self.fc2 = nn.Linear(128, 10)

            def forward(self, x):
                x = x.view(-1, 28 * 28)
                x = torch.relu(self.fc1(x))
                x = self.fc2(x)
                return x

        try:
            self.model = torch.jit.load(model_file, map_location="cpu")
            self.model.eval()
            self.mode = "torchscript"
            return
        except Exception:
            pass

        self.model = SimpleNet()
        self.model.load_state_dict(torch.load(model_file, map_location="cpu"))
        self.model.eval()
        self.mode = "state_dict_mnist"

    def predict(self, context, model_input):
        import numpy as np
        import pandas as pd

        if isinstance(model_input, pd.DataFrame):
            if "pixels" in model_input.columns:
                pixels = model_input.iloc[0]["pixels"]
            else:
                pixels = model_input.iloc[0].values.tolist()
        elif isinstance(model_input, dict):
            pixels = model_input.get("pixels")
        else:
            pixels = model_input

        arr = np.array(pixels, dtype=np.float32)

        if arr.max() > 1:
            arr = arr / 255.0

        if arr.ndim == 1:
            x = arr.reshape(1, 1, 28, 28)
        else:
            x = arr.reshape(1, -1)

        x = self.torch.tensor(x).float()
        x = (x - 0.1307) / 0.3081

        with self.torch.no_grad():
            output = self.model(x)

        if hasattr(output, "detach"):
            output_np = output.detach().cpu().numpy()
        else:
            output_np = np.array(output)

        if output_np.ndim == 2 and output_np.shape[1] > 1:
            pred = int(self.torch.argmax(output, dim=1).item())
            return [{"predicted_digit": pred}]

        return [{"prediction": output_np.tolist()}]


class KerasPyFuncModel(mlflow.pyfunc.PythonModel):
    def load_context(self, context):
        import tensorflow as tf
        self.model = tf.keras.models.load_model(context.artifacts["model_file"])

    def predict(self, context, model_input):
        import numpy as np
        import pandas as pd

        if isinstance(model_input, pd.DataFrame):
            data = model_input.values
        elif isinstance(model_input, dict):
            data = np.array(list(model_input.values()), dtype=np.float32).reshape(1, -1)
        else:
            data = np.array(model_input, dtype=np.float32)

        return self.model.predict(data).tolist()


class OnnxPyFuncModel(mlflow.pyfunc.PythonModel):
    def load_context(self, context):
        import onnxruntime as ort
        self.session = ort.InferenceSession(context.artifacts["model_file"])
        self.input_name = self.session.get_inputs()[0].name

    def predict(self, context, model_input):
        import numpy as np
        import pandas as pd

        if isinstance(model_input, pd.DataFrame):
            data = model_input.values.astype(np.float32)
        elif isinstance(model_input, dict):
            data = np.array(list(model_input.values()), dtype=np.float32)
        else:
            data = np.array(model_input, dtype=np.float32)

        if data.ndim == 1:
            data = data.reshape(1, -1)

        outputs = self.session.run(None, {self.input_name: data})
        return outputs[0].tolist()


def find_or_create_registered_model(registered_model_name):
    client = MlflowClient()
    try:
        client.get_registered_model(registered_model_name)
    except Exception:
        client.create_registered_model(registered_model_name)
    return client


def find_model_version_for_run(registered_model_name, run_id):
    if not registered_model_name or not run_id:
        return None

    client = MlflowClient()

    try:
        versions = client.search_model_versions(f"name='{registered_model_name}'")
        matched = [v for v in versions if getattr(v, "run_id", None) == run_id]

        if not matched:
            return None

        latest = sorted(matched, key=lambda v: int(getattr(v, "version", "0")))
        return str(getattr(latest[-1], "version", ""))

    except Exception as e:
        print(f"[PLATFORM_MODEL] failed to find model version for run {run_id}: {e}")
        return None


def log_sklearn_model(main_model, registered_model_name):
    try:
        import joblib
        from mlflow import sklearn as mlflow_sklearn

        loaded_model = joblib.load(main_model)

        if registered_model_name:
            find_or_create_registered_model(registered_model_name)
            mlflow_sklearn.log_model(
                sk_model=loaded_model,
                artifact_path="model",
                registered_model_name=registered_model_name
            )
        else:
            mlflow_sklearn.log_model(
                sk_model=loaded_model,
                artifact_path="model"
            )

        run = mlflow.active_run()
        if run is not None and registered_model_name:
            version = find_model_version_for_run(registered_model_name, run.info.run_id)
            if version:
                mlflow.set_tag("model_version", version)

        mlflow.set_tag("model_flavor", "sklearn")
        mlflow.set_tag("prediction_supported", "true")
        mlflow.set_tag("main_model_file", main_model)
        mlflow.set_tag("main_model_logged_as", "mlflow_sklearn_model")
        print(f"[PLATFORM_MODEL] main_model={main_model}")
        print("[PLATFORM_MODEL] main model logged with mlflow.sklearn.log_model")
        return True

    except Exception as e:
        print(f"[PLATFORM_MODEL] sklearn model logging failed: {e}")
        return False


def log_pytorch_model(main_model, registered_model_name):
    try:
        mlflow.log_artifact(main_model, artifact_path="raw_model_file")
        mlflow.set_tag("raw_model_file", main_model)
    except Exception as e:
        print(f"[PLATFORM_MODEL] failed to log raw PyTorch artifact {main_model}: {e}")

    try:
        if registered_model_name:
            find_or_create_registered_model(registered_model_name)

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=TorchMNISTPyFunc(),
            artifacts={"model_file": main_model},
            registered_model_name=registered_model_name if registered_model_name else None,
            pip_requirements=[
                "mlflow==2.17.2",
                "torch==2.2.0",
                "torchvision==0.17.0",
                "numpy==1.26.4",
                "pandas"
            ]
        )

        run = mlflow.active_run()
        if run is not None and registered_model_name:
            version = find_model_version_for_run(registered_model_name, run.info.run_id)
            if version:
                mlflow.set_tag("model_version", version)

        mlflow.set_tag("model_flavor", "pytorch_pyfunc")
        mlflow.set_tag("prediction_supported", "true")
        mlflow.set_tag("main_model_file", main_model)
        mlflow.set_tag("main_model_logged_as", "mlflow_pyfunc_pytorch")
        print(f"[PLATFORM_MODEL] main_model={main_model}")
        print("[PLATFORM_MODEL] main model logged with mlflow.pyfunc.log_model using TorchMNISTPyFunc")
        return True

    except Exception as e:
        print(f"[PLATFORM_MODEL] pytorch model logging failed: {e}")
        return False


def log_keras_model(main_model, registered_model_name):
    try:
        if registered_model_name:
            find_or_create_registered_model(registered_model_name)

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=KerasPyFuncModel(),
            artifacts={"model_file": main_model},
            registered_model_name=registered_model_name if registered_model_name else None,
            pip_requirements=[
                "mlflow==2.17.2",
                "tensorflow==2.14.0",
                "numpy==1.26.4",
                "pandas"
            ]
        )

        run = mlflow.active_run()
        if run is not None and registered_model_name:
            version = find_model_version_for_run(registered_model_name, run.info.run_id)
            if version:
                mlflow.set_tag("model_version", version)

        mlflow.set_tag("model_flavor", "keras_pyfunc")
        mlflow.set_tag("prediction_supported", "true")
        mlflow.set_tag("main_model_file", main_model)
        mlflow.set_tag("main_model_logged_as", "mlflow_pyfunc_keras")
        print(f"[PLATFORM_MODEL] main_model={main_model}")
        print("[PLATFORM_MODEL] main model logged with mlflow.pyfunc.log_model using KerasPyFuncModel")
        return True

    except Exception as e:
        print(f"[PLATFORM_MODEL] keras model logging failed: {e}")
        return False


def log_onnx_model(main_model, registered_model_name):
    try:
        if registered_model_name:
            find_or_create_registered_model(registered_model_name)

        mlflow.pyfunc.log_model(
            artifact_path="model",
            python_model=OnnxPyFuncModel(),
            artifacts={"model_file": main_model},
            registered_model_name=registered_model_name if registered_model_name else None,
            pip_requirements=[
                "mlflow==2.17.2",
                "onnxruntime==1.16.0",
                "numpy==1.26.4",
                "pandas"
            ]
        )

        run = mlflow.active_run()
        if run is not None and registered_model_name:
            version = find_model_version_for_run(registered_model_name, run.info.run_id)
            if version:
                mlflow.set_tag("model_version", version)

        mlflow.set_tag("model_flavor", "onnx_pyfunc")
        mlflow.set_tag("prediction_supported", "true")
        mlflow.set_tag("main_model_file", main_model)
        mlflow.set_tag("main_model_logged_as", "mlflow_pyfunc_onnx")
        print(f"[PLATFORM_MODEL] main_model={main_model}")
        print("[PLATFORM_MODEL] main model logged with mlflow.pyfunc.log_model using OnnxPyFuncModel")
        return True

    except Exception as e:
        print(f"[PLATFORM_MODEL] onnx model logging failed: {e}")
        return False


def log_raw_model(main_model, registered_model_name):
    try:
        mlflow.log_artifact(main_model, artifact_path="raw_model_file")
        mlflow.set_tag("raw_model_file", main_model)
        mlflow.set_tag("model_flavor", "raw_artifact")
        mlflow.set_tag("prediction_supported", "false")
        mlflow.set_tag("main_model_file", main_model)
        mlflow.set_tag("main_model_logged_as", "raw_artifact_only")
        print(f"[PLATFORM_MODEL] raw model artifact logged: {main_model}")
        return True
    except Exception as e:
        print(f"[PLATFORM_MODEL] failed to log raw model artifact {main_model}: {e}")
        return False


def log_main_model(main_model, registered_model_name):
    if not main_model:
        print("[PLATFORM_MODEL] No model candidate detected.")
        return

    lower = main_model.lower()

    if lower.endswith((".pkl", ".joblib", ".pickle")):
        if log_sklearn_model(main_model, registered_model_name):
            return

    elif lower.endswith((".pt", ".pth")):
        if log_pytorch_model(main_model, registered_model_name):
            return

    elif lower.endswith((".h5", ".keras")):
        if log_keras_model(main_model, registered_model_name):
            return

    elif lower.endswith(".onnx"):
        if log_onnx_model(main_model, registered_model_name):
            return

    elif lower.endswith((".model", ".cbm", ".ubj", ".bin")):
        print(f"[PLATFORM_MODEL] format {lower} is not supported for auto prediction yet.")
        log_raw_model(main_model, registered_model_name)
        return

    print(f"[PLATFORM_MODEL] default raw logging for unsupported format: {main_model}")
    log_raw_model(main_model, registered_model_name)


def classify_user_error(stderr, stdout):
    text = ((stderr or "") + "\\n" + (stdout or "")).lower()

    if "numpy is not available" in text or "compiled using numpy 1.x cannot be run in numpy 2" in text:
        return {
            "phase": "USER_SCRIPT",
            "reason": "NumPy incompatible with PyTorch or another compiled dependency",
            "details": "RuntimeError: Numpy is not available",
            "suggestion": "Pin a compatible NumPy version in requirements.txt, for example numpy==1.26.4 with torch==2.2.0.",
            "sourceFile": "requirements.txt"
        }

    if "modulenotfounderror" in text or "no module named" in text:
        return {
            "phase": "USER_SCRIPT",
            "reason": "Missing Python dependency",
            "details": first_error_line(stderr),
            "suggestion": "Add the missing module to requirements.txt.",
            "sourceFile": "requirements.txt"
        }

    if "syntaxerror" in text:
        return {
            "phase": "USER_SCRIPT",
            "reason": "Python syntax error",
            "details": first_error_line(stderr),
            "suggestion": "Fix the Python syntax in train.py.",
            "sourceFile": "train.py"
        }

    if "filenotfounderror" in text or "no such file or directory" in text:
        return {
            "phase": "USER_SCRIPT",
            "reason": "File not found",
            "details": first_error_line(stderr),
            "suggestion": "Check file paths in train.py and make sure required files are uploaded.",
            "sourceFile": "train.py"
        }

    if "temporary failure in name resolution" in text or "connection timed out" in text or "name or service not known" in text:
        return {
            "phase": "USER_SCRIPT",
            "reason": "Network or DNS error",
            "details": first_error_line(stderr),
            "suggestion": "Check Kubernetes internet/DNS access or avoid external downloads during training.",
            "sourceFile": "environment"
        }

    return {
        "phase": "USER_SCRIPT",
        "reason": "User script failed",
        "details": first_error_line(stderr) or "Script exited with non-zero status.",
        "suggestion": "Check train.py, requirements.txt and the full stderr logs.",
        "sourceFile": "train.py"
    }


def first_error_line(text):
    for line in (text or "").splitlines():
        clean = line.strip()
        if clean:
            return clean
    return ""



def snapshot_mlflow_model_versions():

     # Prend une photo des versions MLflow existantes avant l'exécution du script utilisateur.
    # Cela permet ensuite de détecter les nouvelles versions créées par le script lui-même.
    tracking_uri = os.getenv("MLFLOW_TRACKING_URI", "").strip()

    if tracking_uri:
        mlflow.set_tracking_uri(tracking_uri)

    snapshot = set()

    try:
        client = MlflowClient()
        registered_models = client.search_registered_models()

        for registered_model in registered_models:
            model_name = getattr(registered_model, "name", None)

            if not model_name:
                continue

            try:
                versions = client.search_model_versions(f"name = '{model_name}'")
            except Exception as e:
                print(f"[PLATFORM_MODEL] Could not list versions for model {model_name}: {e}")
                continue

            for version in versions:
                version_number = str(getattr(version, "version", "") or "")
                if version_number:
                    snapshot.add((model_name, version_number))

    except Exception as e:
        print(f"[PLATFORM_MODEL] Could not create MLflow model snapshot: {e}")

    return snapshot


def find_user_created_mlflow_model(before_snapshot):
    # Cherche un modèle MLflow créé par le script utilisateur pendant ce run.
    # Exemple :
    # - le script crée loan_approval_docker_model v2
    # - la plateforme doit le détecter puis le réenregistrer sous bofh_model
    tracking_uri = os.getenv("MLFLOW_TRACKING_URI", "").strip()
    platform_registered_model_name = os.getenv("REGISTERED_MODEL_NAME", "").strip()

    if tracking_uri:
        mlflow.set_tracking_uri(tracking_uri)

    try:
        client = MlflowClient()
        candidates = []

        registered_models = client.search_registered_models()

        for registered_model in registered_models:
            model_name = getattr(registered_model, "name", None)

            if not model_name:
                continue

            # On cherche les modèles créés par le script utilisateur,
            # pas le modèle officiel de la plateforme.
            if platform_registered_model_name and model_name == platform_registered_model_name:
                continue

            try:
                versions = client.search_model_versions(f"name = '{model_name}'")
            except Exception as e:
                print(f"[PLATFORM_MODEL] Could not search versions for {model_name}: {e}")
                continue

            for version in versions:
                version_number = str(getattr(version, "version", "") or "")
                run_id = str(getattr(version, "run_id", "") or "")
                source = str(getattr(version, "source", "") or "")
                creation_timestamp = int(getattr(version, "creation_timestamp", 0) or 0)

                if not version_number:
                    continue

                key = (model_name, version_number)

                # Très important :
                # si cette version n'existait pas avant le script, elle vient probablement du script utilisateur.
                if key in before_snapshot:
                    continue

                candidates.append({
                    "name": model_name,
                    "version": version_number,
                    "run_id": run_id,
                    "source": source,
                    "creation_timestamp": creation_timestamp,
                })

        if not candidates:
            return None

        candidates = sorted(
            candidates,
            key=lambda item: int(item.get("creation_timestamp", 0) or 0),
            reverse=True
        )

        return candidates[0]

    except Exception as e:
        print(f"[PLATFORM_MODEL] Could not detect user-created MLflow model: {e}")
        return None


def re_register_user_model_as_platform_model(user_model, registered_model_name):
    # Réenregistre le modèle créé par le script utilisateur sous le nom officiel de la plateforme.
    if not user_model:
        return False

    if not registered_model_name:
        print("[PLATFORM_MODEL] Cannot re-register user MLflow model: REGISTERED_MODEL_NAME is empty.")
        return False

    user_model_name = user_model.get("name")
    user_model_version = user_model.get("version")

    if not user_model_name or not user_model_version:
        print("[PLATFORM_MODEL] Invalid user MLflow model candidate.")
        return False

    source_uri = f"models:/{user_model_name}/{user_model_version}"

    try:
        print(
            "[PLATFORM_MODEL] User MLflow model detected: "
            f"{user_model_name} v{user_model_version}"
        )

        print(
            "[PLATFORM_MODEL] Re-registering user model as platform model: "
            f"{registered_model_name}"
        )

        find_or_create_registered_model(registered_model_name)

        registered_version = mlflow.register_model(
            model_uri=source_uri,
            name=registered_model_name
        )

        platform_version = str(getattr(registered_version, "version", "") or "")

        mlflow.set_tag("platform_model_source_type", "USER_MLFLOW_MODEL")
        mlflow.set_tag("user_mlflow_model_name", user_model_name)
        mlflow.set_tag("user_mlflow_model_version", user_model_version)
        mlflow.set_tag("user_mlflow_model_run_id", user_model.get("run_id", ""))
        mlflow.set_tag("registered_model_name", registered_model_name)

        if platform_version:
            mlflow.set_tag("model_version", platform_version)
            mlflow.set_tag("registered_model_version", platform_version)

        print(
            "[PLATFORM_MODEL] Platform model registered successfully: "
            f"{registered_model_name} v{platform_version or '-'}"
        )

        return True

    except Exception as e:
        print(
            "[PLATFORM_MODEL] Failed to re-register user MLflow model "
            f"{user_model_name} v{user_model_version} as {registered_model_name}: {e}"
        )
        return False

def log_to_mlflow(stdout, stderr, return_code, before_files, after_files, before_mlflow_versions):
    tracking_uri = os.getenv("MLFLOW_TRACKING_URI", "").strip()
    experiment_name = os.getenv("MLFLOW_EXPERIMENT_NAME", "default").strip() or "default"
    run_name = os.getenv("RUN_NAME", "custom-docker-run").strip() or "custom-docker-run"
    registered_model_name = os.getenv("REGISTERED_MODEL_NAME", "").strip()

    if tracking_uri:
        mlflow.set_tracking_uri(tracking_uri)

    mlflow.set_experiment(experiment_name)

    new_files = sorted(list(after_files - before_files))

    model_files = [
        f for f in new_files
        if is_model_candidate(f)
    ]

    if not model_files:
        print("[PLATFORM_MODEL] No new model file detected. Searching all common output locations...")
        model_files = discover_model_files_from_all_locations()

    main_model = choose_main_model(model_files)

    print(f"[PLATFORM_MODEL] detected_model_files={model_files}")
    print(f"[PLATFORM_MODEL] selected_main_model={main_model or '-'}")

    metrics = {}
    metrics.update(extract_metrics(stdout + "\\n" + stderr))
    metrics.update(load_metrics_json())

    with mlflow.start_run(run_name=run_name):
        mlflow.set_tag("runtime_mode", os.getenv("RUNTIME_MODE", "CUSTOM_DOCKERFILE"))
        mlflow.set_tag("spring_experiment_id", os.getenv("SPRING_EXPERIMENT_ID", ""))
        mlflow.set_tag("platform_runner", "true")
        mlflow.set_tag("user_script", USER_SCRIPT)
        mlflow.set_tag("return_code", str(return_code))
        mlflow.set_tag("target_column", get_target_column())
        mlflow.set_tag("problem_type", get_problem_type())
        mlflow.set_tag("primary_metric", get_primary_metric())

        if registered_model_name:
            mlflow.set_tag("registered_model_name", registered_model_name)

        mlflow.log_text(stdout or "", "logs/stdout.txt")
        mlflow.log_text(stderr or "", "logs/stderr.txt")

        auto_metrics = auto_evaluate_metrics(main_model)

        for name, value in auto_metrics.items():
            metrics.setdefault(normalize_metric_name(name), value)

        for name, value in metrics.items():
            metric_name = normalize_metric_name(name)
            mlflow.log_metric(metric_name, float(value))
            print(f"[PLATFORM_METRIC] {metric_name}={value}")

        if main_model:
            log_main_model(main_model, registered_model_name)
        else:
            print("[PLATFORM_MODEL] No local model candidate detected.")
            print("[PLATFORM_MODEL] Searching user-created MLflow models...")

            user_model = find_user_created_mlflow_model(before_mlflow_versions)

            if user_model:
                re_register_user_model_as_platform_model(
                    user_model=user_model,
                    registered_model_name=registered_model_name
                )
            else:
                print("[PLATFORM_MODEL] No model candidate detected.")

        for f in model_files:
            if f == main_model:
                continue

            try:
                mlflow.log_artifact(f, artifact_path="model/secondary")
                print(f"[PLATFORM_MODEL] secondary_model={f}")
            except Exception as e:
                print(f"[PLATFORM_MODEL] failed to log secondary artifact {f}: {e}")

        for f in new_files:
            lower = f.lower()

            if lower.endswith((".json", ".yaml", ".yml", ".txt", ".csv")):
                if Path(f).name.lower() not in IGNORED_FILES:
                    try:
                        mlflow.log_artifact(f, artifact_path="artifacts")
                    except Exception:
                        pass

def main():
    print(f"[PLATFORM] User script: {USER_SCRIPT}")
    print(f"[PLATFORM] Target column: {get_target_column() or '-'}")
    print(f"[PLATFORM] Problem type: {get_problem_type() or '-'}")
    print(f"[PLATFORM] Primary metric: {get_primary_metric() or '-'}")

    before_files = list_files()

    # IMPORTANT:
    # Snapshot MLflow avant l'exécution du script utilisateur.
    # Cela permet de détecter les modèles MLflow créés directement par le script utilisateur.
    before_mlflow_versions = snapshot_mlflow_model_versions()

    start = time.time()

    commands = []

    if DATASET_PROVIDED:
        dataset_arg = GENERATED_DATASET_PATH or "dataset.csv"

        commands.append([
            "python",
            USER_SCRIPT,
            "--data_path",
            dataset_arg,
            "--output_dir",
            str(PLATFORM_OUTPUT_DIR),
        ])

        commands.append([
            "python",
            USER_SCRIPT,
            "--data-path",
            dataset_arg,
            "--output-dir",
            str(PLATFORM_OUTPUT_DIR),
        ])

        commands.append([
            "python",
            USER_SCRIPT,
            "--dataset_path",
            dataset_arg,
            "--output_dir",
            str(PLATFORM_OUTPUT_DIR),
        ])

        commands.append([
            "python",
            USER_SCRIPT,
            "--dataset",
            dataset_arg,
            "--output_dir",
            str(PLATFORM_OUTPUT_DIR),
        ])

        commands.append([
            "python",
            USER_SCRIPT,
            dataset_arg,
            str(PLATFORM_OUTPUT_DIR),
        ])

    commands.append(["python", USER_SCRIPT])

    process = None

    for command in commands:
        print(f"[PLATFORM] Trying command: {' '.join(command)}")

        candidate = subprocess.run(
            command,
            capture_output=True,
            text=True,
            env=os.environ.copy()
        )

        process = candidate

        if candidate.returncode == 0:
            print("[PLATFORM] User script executed successfully with this command.")
            break

        stderr_lower = (candidate.stderr or "").lower()

        argument_error = (
            "the following arguments are required" in stderr_lower
            or "unrecognized arguments" in stderr_lower
            or "usage:" in stderr_lower
            or "error: argument" in stderr_lower
        )

        if argument_error:
            print("[PLATFORM] Argument mismatch detected. Trying another command format...")
            continue

        print("[PLATFORM] Script failed with a real execution error. Stop retrying.")
        break

    if process is None:
        raise RuntimeError("No command was executed for user script.")

    duration = time.time() - start

    stdout = process.stdout or ""
    stderr = process.stderr or ""

    print("[PLATFORM] User script stdout:")
    print(stdout)

    if stderr.strip():
        print("[PLATFORM] User script stderr:")
        print(stderr)

    after_files = list_files()

    print(f"[PLATFORM] User script return_code={process.returncode}")
    print(f"[PLATFORM] User script duration_seconds={duration}")

    # IMPORTANT:
    # On passe before_mlflow_versions pour que log_to_mlflow puisse détecter
    # les modèles MLflow créés directement par le script utilisateur.
    log_to_mlflow(
        stdout=stdout,
        stderr=stderr,
        return_code=process.returncode,
        before_files=before_files,
        after_files=after_files,
        before_mlflow_versions=before_mlflow_versions
    )

    if process.returncode != 0:
        error_summary = classify_user_error(stderr, stdout)

        try:
            with open("error_summary.json", "w", encoding="utf-8") as ef:
                json.dump(error_summary, ef, ensure_ascii=False)

            print("[PLATFORM_ERROR_PHASE] " + error_summary.get("phase", "USER_SCRIPT"))
            print("[PLATFORM_ERROR_REASON] " + error_summary.get("reason", "User script failed"))
            print("[PLATFORM_ERROR_DETAILS] " + str(error_summary.get("details", ""))[:1000])
            print("[PLATFORM_ERROR_SUGGESTION] " + error_summary.get("suggestion", ""))
            print("[PLATFORM_ERROR_SOURCE_FILE] " + error_summary.get("sourceFile", ""))
        except Exception as e:
            print(f"[PLATFORM] Failed to write error_summary.json: {e}")

        try:
            mlflow.log_text(json.dumps(error_summary, ensure_ascii=False), "error_summary.json")
        except Exception:
            pass

        raise RuntimeError("User script failed. Check logs/stderr.txt and error_summary.json in MLflow artifacts.")

    print("[PLATFORM] Platform runner completed successfully.")


if __name__ == "__main__":
    main()
"""
                .replace("__TARGET_COLUMN__", pythonStringLiteral(generatedTargetColumn))
                .replace("__PROBLEM_TYPE__", pythonStringLiteral(generatedProblemType))
                .replace("__PRIMARY_METRIC__", pythonStringLiteral(generatedPrimaryMetric))
                .replace("__DATASET_PATH__", pythonStringLiteral(datasetPath))
                .replace("__DATASET_PROVIDED__", pythonBooleanLiteral(datasetProvided));
    }

    private String readStringByGetter(Object source, String getterName) {
        if (source == null || getterName == null || getterName.isBlank()) {
            return "";
        }

        try {
            java.lang.reflect.Method method = source.getClass().getMethod(getterName);
            Object value = method.invoke(source);

            if (value == null) {
                return "";
            }

            return String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }

    private String pythonStringLiteral(String value) {
        if (value == null) {
            value = "";
        }

        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");

        return "\"" + escaped + "\"";
    }

    private String pythonBooleanLiteral(boolean value) {
        return value ? "True" : "False";
    }

    private void assertConfigMapSafeSize(Map<String, String> data, String configMapName) {
        long totalBytes = 0L;
        Map<String, Long> sizes = new LinkedHashMap<>();

        if (data != null) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue();

                long keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
                long valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
                long entryBytes = keyBytes + valueBytes;

                sizes.put(key, entryBytes);
                totalBytes += entryBytes;
            }
        }

        System.out.println("[KubernetesBuildService] ConfigMap estimated size for "
                + configMapName
                + " = "
                + totalBytes
                + " bytes, files="
                + sizes);

        if (totalBytes > CONFIGMAP_SAFE_MAX_BYTES) {
            throw new RuntimeException(
                    "Build context ConfigMap is too large: "
                            + totalBytes
                            + " bytes for "
                            + configMapName
                            + ". Kubernetes ConfigMaps are limited to about 1MiB. "
                            + "Do not put datasets or large files in ConfigMap. "
                            + "Keep only Dockerfile, requirements.txt, platform_runner.py and small train.py. "
                            + "Datasets must be stored under /app/uploads and mounted in training pods using a shared PVC. "
                            + "Current files="
                            + sizes
            );
        }
    }

    private void assertConfigMapKeysAreSafe(Map<String, String> buildContextFiles) {
        if (buildContextFiles == null) {
            return;
        }

        for (String key : buildContextFiles.keySet()) {
            if (key != null && key.contains("/")) {
                throw new RuntimeException(
                        "Invalid ConfigMap key for build context: '" + key + "'. "
                                + "Kubernetes ConfigMap keys cannot contain '/'. "
                                + "Use '__' instead of '/'."
                );
            }
        }
    }

    private boolean areConfigMapKeysKubernetesSafe(Map<String, String> buildContextFiles) {
        if (buildContextFiles == null) {
            return true;
        }

        for (String key : buildContextFiles.keySet()) {
            if (key != null && key.contains("/")) {
                return false;
            }
        }

        return true;
    }

    private ConfigMap buildConfigMap(String configMapName, Map<String, String> buildContextFiles) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(namespace)
                    .addToLabels("app", "mlops-custom-build")
                    .addToLabels("mlops.kind", "build-context")
                .endMetadata()
                .withData(buildContextFiles)
                .build();
    }

    private Job buildKanikoJob(
            String buildJobName,
            String configMapName,
            String imageUri,
            MlProject project,
            MlExperiment experiment
    ) {
        List<String> kanikoArgs = buildKanikoArgs(imageUri);

        return new JobBuilder()
                .withNewMetadata()
                    .withName(buildJobName)
                    .withNamespace(namespace)
                    .addToLabels("app", "mlops-custom-build")
                    .addToLabels("mlops.project.id", String.valueOf(project.getId()))
                    .addToLabels("mlops.experiment.id", String.valueOf(experiment.getId()))
                    .addToLabels("mlops.runtime", "custom-dockerfile")
                    .addToLabels("mlops.phase", "image-build")
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(backoffLimit)
                    .withTtlSecondsAfterFinished(ttlSecondsAfterFinished)
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "mlops-custom-build")
                            .addToLabels("job-name", buildJobName)
                            .addToLabels("mlops.project.id", String.valueOf(project.getId()))
                            .addToLabels("mlops.experiment.id", String.valueOf(experiment.getId()))
                            .addToLabels("mlops.runtime", "custom-dockerfile")
                            .addToLabels("mlops.phase", "image-build")
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")

                            .addNewVolume()
                                .withName("build-context-configmap")
                                .withNewConfigMap()
                                    .withName(configMapName)
                                .endConfigMap()
                            .endVolume()

                            .addNewVolume()
                                .withName("build-context-workdir")
                                .withNewEmptyDir()
                                .endEmptyDir()
                            .endVolume()

                            .addNewVolume()
                                .withName("kaniko-docker-config")
                                .withNewSecret()
                                    .withSecretName(kanikoDockerConfigSecret)
                                .endSecret()
                            .endVolume()

                            .addNewInitContainer()
                                .withName("prepare-build-context")
                                .withImage("busybox:1.36")
                                .withImagePullPolicy("IfNotPresent")
                                .withCommand(
                                        "sh",
                                        "-c",
                                        "mkdir -p /kaniko-context/src/custom_models && "
                                                + "cp -L /configmap-src/Dockerfile /kaniko-context/Dockerfile && "
                                                + "cp -L /configmap-src/requirements.txt /kaniko-context/requirements.txt && "
                                                + "cp -L /configmap-src/platform_runner.py /kaniko-context/platform_runner.py && "
                                                + "cp -L /configmap-src/train.py /kaniko-context/train.py && "
                                                + "cp -L /configmap-src/src__init__.py /kaniko-context/src/__init__.py && "
                                                + "cp -L /configmap-src/src__custom_models__init__.py /kaniko-context/src/custom_models/__init__.py && "
                                                + "cp -L /configmap-src/src__custom_models__generic_pyfunc_wrapper.py /kaniko-context/src/custom_models/generic_pyfunc_wrapper.py && "
                                                + "cp -L /configmap-src/src__custom_models__import_detector.py /kaniko-context/src/custom_models/import_detector.py && "
                                                + "ls -laR /kaniko-context"
                                )
                                .addNewVolumeMount()
                                    .withName("build-context-configmap")
                                    .withMountPath("/configmap-src")
                                    .withReadOnly(true)
                                .endVolumeMount()
                                .addNewVolumeMount()
                                    .withName("build-context-workdir")
                                    .withMountPath("/kaniko-context")
                                .endVolumeMount()
                            .endInitContainer()

                            .addNewContainer()
                                .withName("kaniko")
                                .withImage(kanikoImage)
                                .withImagePullPolicy("IfNotPresent")
                                .withArgs(kanikoArgs)
                                .addNewVolumeMount()
                                    .withName("build-context-workdir")
                                    .withMountPath("/kaniko-context")
                                .endVolumeMount()
                                .addNewVolumeMount()
                                    .withName("kaniko-docker-config")
                                    .withMountPath("/kaniko/.docker")
                                    .withReadOnly(true)
                                .endVolumeMount()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private List<String> buildKanikoArgs(String imageUri) {
        List<String> args = new ArrayList<>();

        args.add("--dockerfile=/kaniko-context/Dockerfile");
        args.add("--context=dir:///kaniko-context");
        args.add("--destination=" + imageUri);

        // Important pour éviter les échecs temporaires Docker Hub : 502, timeout, réseau instable.
        args.add("--push-retry=5");
        args.add("--image-download-retry=3");
        args.add("--image-fs-extract-retry=3");

        if (kanikoCacheRepo != null && !kanikoCacheRepo.isBlank()) {
            args.add("--cache=true");
            args.add("--cache-repo=" + kanikoCacheRepo.trim());
            args.add("--cache-ttl=168h");
            args.add("--cache-copy-layers=true");
        } else {
            args.add("--cache=false");
        }

        if (isLocalInsecureRegistry(imageUri)) {
            args.add("--insecure");
            args.add("--skip-tls-verify");
        }

        return args;
    }

    private boolean isLocalInsecureRegistry(String imageUri) {
        if (imageUri == null) {
            return false;
        }

        String clean = imageUri.toLowerCase(Locale.ROOT);

        return clean.startsWith("localhost:")
                || clean.startsWith("127.0.0.1:")
                || clean.startsWith("172.")
                || clean.startsWith("host.docker.internal:");
    }

    private void deleteExistingJobIfExists(KubernetesClient client, String jobName) {
        try {
            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .delete();
        } catch (Exception ignored) {
        }
    }

    private void deleteExistingConfigMapIfExists(KubernetesClient client, String configMapName) {
        try {
            client.configMaps()
                    .inNamespace(namespace)
                    .withName(configMapName)
                    .delete();
        } catch (Exception ignored) {
        }
    }

    private void validateKanikoDockerConfigSecret(KubernetesClient client) {
        if (kanikoDockerConfigSecret == null || kanikoDockerConfigSecret.isBlank()) {
            throw new RuntimeException("Kaniko Docker config secret name is not configured.");
        }

        try {
            if (client.secrets()
                    .inNamespace(namespace)
                    .withName(kanikoDockerConfigSecret)
                    .get() == null) {
                throw new RuntimeException("Kaniko Docker config secret '" + kanikoDockerConfigSecret
                        + "' not found in namespace '" + namespace + "'.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate Kaniko Docker config secret '"
                    + kanikoDockerConfigSecret + "': " + e.getMessage(), e);
        }
    }

    private String readFile(String path) {
        try {
            return Files.readString(Path.of(path).toAbsolutePath().normalize());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + path + " | " + e.getMessage());
        }
    }

    private void validateBuildContextAgainstDockerfile(Map<String, String> buildContextFiles, String dockerfileContent) {
        if (dockerfileContent == null || dockerfileContent.isBlank()) {
            throw new RuntimeException("Dockerfile content is empty after injection");
        }

        if (buildContextFiles == null) {
            throw new RuntimeException("Build context is empty");
        }

        if (!buildContextFiles.containsKey(STANDARD_DOCKERFILE)) {
            throw new RuntimeException("Build context missing Dockerfile");
        }

        if (!buildContextFiles.containsKey(STANDARD_PLATFORM_RUNNER_FILE)) {
            throw new RuntimeException("Build context missing " + STANDARD_PLATFORM_RUNNER_FILE);
        }

        if (!buildContextFiles.containsKey(STANDARD_TRAIN_FILE)) {
            throw new RuntimeException("Build context missing " + STANDARD_TRAIN_FILE + ". The platform standardizes the uploaded script as train.py.");
        }

        java.util.regex.Pattern copyPattern = java.util.regex.Pattern.compile(
                "(?im)^[\\s]*(COPY|ADD)\\s+(.+?)\\s+(.+)$",
                java.util.regex.Pattern.MULTILINE
        );

        java.util.regex.Matcher matcher = copyPattern.matcher(dockerfileContent);

        while (matcher.find()) {
            String srcPart = matcher.group(2);

            for (String token : srcPart.split("\\s+")) {
                String t = token.trim().replaceAll("^[\\\"']|[\\\"']$", "");

                if (t.isBlank()) {
                    continue;
                }

                if (t.startsWith("--")) {
                    continue;
                }

                if (t.startsWith("http://") || t.startsWith("https://")) {
                    continue;
                }

                if (t.contains("*") || t.contains("$") || t.contains("${")) {
                    continue;
                }

                String base = t.contains("/") ? t.substring(t.lastIndexOf('/') + 1) : t;

                if (base.isBlank()) {
                    continue;
                }

                if (base.equalsIgnoreCase(".") || base.equalsIgnoreCase("./")) {
                    continue;
                }

                if (base.toLowerCase(Locale.ROOT).endsWith(".py")
                        || base.equalsIgnoreCase("requirements.txt")
                        || base.equalsIgnoreCase("dataset.csv")) {

                    if (!buildContextFiles.containsKey(base) && !buildContextFiles.containsKey(t)) {
                        throw new RuntimeException(
                                "Dockerfile references '" + base + "' but it is not present in the build context. "
                                        + "The platform standardizes the uploaded script as train.py."
                        );
                    }
                }
            }
        }
    }

    private String buildBuildJobName(String projectName, Long experimentId) {
        String base = "mlops-build-" + slugify(projectName) + "-" + experimentId;
        return shortenKubernetesName(base);
    }

    private String buildConfigMapName(String projectName, Long experimentId) {
        String base = "mlops-build-context-" + slugify(projectName) + "-" + experimentId;
        return shortenKubernetesName(base);
    }

    private String shortenKubernetesName(String value) {
        String clean = value == null ? "mlops-job" : value;

        if (clean.length() > 63) {
            clean = clean.substring(0, 63);
        }

        clean = clean.replaceAll("-+$", "");

        if (clean.isBlank()) {
            clean = "mlops-job";
        }

        return clean;
    }

    private String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "project";
        }

        String slug = value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        if (slug.isBlank()) {
            return "project";
        }

        return slug;
    }

    private String safeFileName(String originalName, String fallback) {
        String value = originalName;

        if (value == null || value.isBlank()) {
            value = fallback;
        }

        value = value.replace("\\\\", "/");

        if (value.contains("/")) {
            value = value.substring(value.lastIndexOf("/") + 1);
        }

        value = value.trim();

        if (value.isBlank()) {
            return fallback;
        }

        return value;
    }

    private String normalizeRegistryForKubernetes(String registry) {
        if (registry == null || registry.isBlank()) {
            return "acrmlopssalma.azurecr.io";
        }

        String clean = registry.trim();

        if (clean.startsWith("localhost:")) {
            return clean.replace("localhost:", "host.docker.internal:");
        }

        if (clean.startsWith("127.0.0.1:")) {
            return clean.replace("127.0.0.1:", "host.docker.internal:");
        }

        return clean.toLowerCase(Locale.ROOT);
    }
}