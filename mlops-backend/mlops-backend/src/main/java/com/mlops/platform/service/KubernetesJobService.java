package com.mlops.platform.service;

import com.mlops.platform.config.UploadsStorageProperties;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.file.*;

@Service
public class KubernetesJobService {

    private static final String UPLOADS_VOLUME_NAME = "mlops-uploads";

    @Value("${app.kubernetes.namespace:mlops}")
    private String namespace;

    @Value("${app.kubernetes.custom-job.service-account:default}")
    private String serviceAccountName;

    @Value("${app.kubernetes.custom-job.ttl-seconds:3600}")
    private Integer ttlSecondsAfterFinished;

    @Value("${app.kubernetes.custom-job.backoff-limit:0}")
    private Integer backoffLimit;

    @Value("${app.mlflow.tracking-uri:${mlflow.tracking.uri:http://mlflow-service:5000}}")
    private String mlflowTrackingUri;

    @Value("${app.docker.image-pull-secret:dockerhub-secret}")
    private String imagePullSecret;

    @Autowired
    private UploadsStorageProperties uploadsStorageProperties;

    /*
     * Shared uploads PVC.
     *
     * This is required for scalable custom Docker runs:
     * - datasets are NOT copied into the Kaniko ConfigMap
     * - datasets remain under /app/uploads/...
     * - the custom training job must mount the same PVC at /app/uploads
     */
    private boolean isUploadsPvcEnabled() {
        return uploadsStorageProperties.getPvcName() != null
                && !uploadsStorageProperties.getPvcName().isBlank();
    }

    private String getUploadsPvcName() {
        return uploadsStorageProperties.getPvcName();
    }

    private String getUploadsMountPath() {
        return uploadsStorageProperties.getMountPath();
    }

    private final DatasetFileResolverService datasetFileResolverService;

    public KubernetesJobService(DatasetFileResolverService datasetFileResolverService) {
        this.datasetFileResolverService = datasetFileResolverService;
    }

    public String launchCustomImageTrainingJob(
            MlProject project,
            MlExperiment experiment,
            String customImageUri
    ) {
        if (project == null || project.getId() == null) {
            throw new RuntimeException("Project is required to launch Kubernetes Job.");
        }

        if (experiment == null || experiment.getId() == null) {
            throw new RuntimeException("Experiment is required to launch Kubernetes Job.");
        }

        if (!hasText(customImageUri)) {
            throw new RuntimeException("customImageUri is required to launch Kubernetes Job.");
        }

        String jobName = buildJobName(project.getName(), experiment.getId());

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            deleteExistingJobIfExists(client, jobName);

            boolean attachImagePullSecret = imagePullSecretExists(client);

            if (!attachImagePullSecret && hasText(imagePullSecret)) {
                System.out.println("[KubernetesJobService] Image pull secret '" + imagePullSecret
                        + "' not found in namespace '" + namespace + "'. Training job will be created without it.");
            }

            boolean attachUploadsPvc = shouldAttachUploadsPvc();

            if (attachUploadsPvc) {
                validateUploadsPvcExists(client);

                System.out.println("[KubernetesJobService] Shared uploads PVC will be mounted. "
                        + "pvc=" + getUploadsPvcName()
                        + ", mountPath=" + getUploadsMountPath()
                        + ", jobName=" + jobName);
            } else {
                System.out.println("[KubernetesJobService] Shared uploads PVC mount is disabled or not configured. "
                        + "Large external datasets under /app/uploads may not be visible to the training job.");
            }

            Job job = buildJob(
                    jobName,
                    project,
                    experiment,
                    customImageUri.trim(),
                    attachImagePullSecret,
                    attachUploadsPvc
            );

            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .resource(job)
                    .create();

            System.out.println("[KubernetesJobService] Custom image training job created. "
                    + "jobName=" + jobName
                    + ", experimentId=" + experiment.getId()
                    + ", projectId=" + project.getId()
                    + ", image=" + customImageUri
                    + ", uploadsPvcMounted=" + attachUploadsPvc);

            return jobName;

        } catch (Exception e) {
            throw new RuntimeException("Failed to launch Kubernetes custom image Job: " + e.getMessage(), e);
        }
    }

    public String getCustomJobStatus(String jobName) {
        if (!hasText(jobName)) {
            return "UNKNOWN";
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Job job = client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .get();

            if (job == null) {
                return "NOT_FOUND";
            }

            if (job.getStatus() != null && job.getStatus().getConditions() != null) {
                for (JobCondition condition : job.getStatus().getConditions()) {
                    if (!"True".equalsIgnoreCase(condition.getStatus())) {
                        continue;
                    }

                    if ("Complete".equalsIgnoreCase(condition.getType())) {
                        return "COMPLETED";
                    }

                    if ("Failed".equalsIgnoreCase(condition.getType())) {
                        return "FAILED";
                    }
                }
            }

            Integer succeeded = job.getStatus() != null ? job.getStatus().getSucceeded() : null;
            Integer failed = job.getStatus() != null ? job.getStatus().getFailed() : null;
            Integer active = job.getStatus() != null ? job.getStatus().getActive() : null;

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
            System.out.println("[KubernetesJobService] Failed to get job status. "
                    + "jobName=" + jobName
                    + ", reason=" + e.getMessage());
            return "UNKNOWN";
        }
    }

    public boolean isCustomJobCompleted(String jobName) {
        return "COMPLETED".equalsIgnoreCase(getCustomJobStatus(jobName));
    }

    public boolean isCustomJobFailed(String jobName) {
        return "FAILED".equalsIgnoreCase(getCustomJobStatus(jobName));
    }

    public String getCustomJobLogs(String jobName) {
        if (!hasText(jobName)) {
            return "";
        }

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<Pod> pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("job-name", jobName)
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
            return "Failed to read custom training job logs: " + e.getMessage();
        }
    }

    private Job buildJob(
            String jobName,
            MlProject project,
            MlExperiment experiment,
            String imageUri,
            boolean attachImagePullSecret,
            boolean attachUploadsPvc
    ) {
        List<EnvVar> envVars = buildEnvironmentVariables(project, experiment);

        ContainerBuilder trainingContainerBuilder = new ContainerBuilder()
                .withName("custom-training")
                .withImage(imageUri)
                .withImagePullPolicy(resolveImagePullPolicy(imageUri))
                .withEnv(envVars)
                .withCommand("sh", "-c")
                .withArgs(buildRunnerShellCommand());

        if (attachUploadsPvc) {
            trainingContainerBuilder
                    .addNewVolumeMount()
                        .withName(UPLOADS_VOLUME_NAME)
                        .withMountPath(normalizeMountPath(getUploadsMountPath()))
                    .endVolumeMount();
        }

        Container trainingContainer = trainingContainerBuilder.build();

        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withServiceAccountName(serviceAccountName)
                .withContainers(trainingContainer);

        if (attachUploadsPvc) {
            podSpecBuilder
                    .addNewVolume()
                        .withName(UPLOADS_VOLUME_NAME)
                        .withNewPersistentVolumeClaim()
                            .withClaimName(getUploadsPvcName().trim())
                        .endPersistentVolumeClaim()
                    .endVolume();
        }

        if (attachImagePullSecret && hasText(imagePullSecret)) {
            podSpecBuilder
                    .addNewImagePullSecret()
                    .withName(imagePullSecret.trim())
                    .endImagePullSecret();
        }

        return new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .addToLabels("app", "mlops-custom-training")
                    .addToLabels("job-name", jobName)
                    .addToLabels("mlops.project.id", String.valueOf(project.getId()))
                    .addToLabels("mlops.experiment.id", String.valueOf(experiment.getId()))
                    .addToLabels("mlops.runtime", "custom-image")
                    .addToLabels("mlops.phase", "training")
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(backoffLimit)
                    .withTtlSecondsAfterFinished(ttlSecondsAfterFinished)
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "mlops-custom-training")
                            .addToLabels("job-name", jobName)
                            .addToLabels("mlops.project.id", String.valueOf(project.getId()))
                            .addToLabels("mlops.experiment.id", String.valueOf(experiment.getId()))
                            .addToLabels("mlops.runtime", "custom-image")
                            .addToLabels("mlops.phase", "training")
                        .endMetadata()
                        .withSpec(podSpecBuilder.build())
                    .endTemplate()
                .endSpec()
                .build();
    }

    private String buildRunnerShellCommand() {
        return """
            set -e

            echo "[CUSTOM-TRAINING] Starting platform runner"
            echo "[CUSTOM-TRAINING] USER_SCRIPT=${USER_SCRIPT}"
            echo "[CUSTOM-TRAINING] DATASET_PATH=${DATASET_PATH}"
            echo "[CUSTOM-TRAINING] DATA_PATH=${DATA_PATH}"
            echo "[CUSTOM-TRAINING] MLFLOW_TRACKING_URI=${MLFLOW_TRACKING_URI}"
            echo "[CUSTOM-TRAINING] MLFLOW_EXPERIMENT_NAME=${MLFLOW_EXPERIMENT_NAME}"
            echo "[CUSTOM-TRAINING] RUN_NAME=${RUN_NAME}"
            echo "[CUSTOM-TRAINING] REGISTERED_MODEL_NAME=${REGISTERED_MODEL_NAME}"

            export PYTHONUNBUFFERED=1
            export PYTHONPATH="/app:/workspace:${PYTHONPATH:-}"

            # Defensive cleanup:
            # an empty optional variable must behave as if it was not defined.
            # This preserves the same fallback behavior as local execution.
            if [ -z "${TARGET_COLUMN:-}" ]; then
              unset TARGET_COLUMN
            fi

            if [ -z "${ML_TARGET_COLUMN:-}" ]; then
              unset ML_TARGET_COLUMN
            fi

            echo "[CUSTOM-TRAINING] TARGET_COLUMN=${TARGET_COLUMN:-<not-set>}"
            echo "[CUSTOM-TRAINING] ML_TARGET_COLUMN=${ML_TARGET_COLUMN:-<not-set>}"

            RUNNER_PATH="/app/platform_runner.py"

            if [ ! -f "$RUNNER_PATH" ]; then
              RUNNER_PATH="/workspace/platform_runner.py"
            fi

            if [ ! -f "$RUNNER_PATH" ]; then
              echo "[CUSTOM-TRAINING] ERROR: platform_runner.py not found in /app or /workspace"
              ls -la /app || true
              ls -la /workspace || true
              exit 1
            fi

            # PREFLIGHT DATASET CHECK
            # Verify the shared uploads PVC is mounted and dataset is visible
            echo "[DATASET-PREFLIGHT] pvc=${MLOPS_UPLOADS_PVC_NAME:-backend-uploads-rwx-pvc}"
            echo "[DATASET-PREFLIGHT] mount=${MLOPS_UPLOADS_MOUNT_PATH:-/app/uploads}"
            DATASET_PATH_VISIBLE="${DATASET_PATH:-}"
            if [ -z "$DATASET_PATH_VISIBLE" ]; then
              echo "[DATASET-PREFLIGHT] dataset="
            else
              echo "[DATASET-PREFLIGHT] dataset=${DATASET_PATH_VISIBLE}"
            fi
            echo "[DATASET-PREFLIGHT] mountExists=${TEST_MOUNT_PATH:-true}"
            # Check if the mount path directory exists
            TEST_MOUNT_PATH=""
            if [ -d "${MLOPS_UPLOADS_MOUNT_PATH:-/app/uploads}" ]; then
              TEST_MOUNT_PATH="true"
            else
              TEST_MOUNT_PATH="false"
            fi
            echo "[DATASET-PREFLIGHT] mountExists=${TEST_MOUNT_PATH}"
            # Check if the dataset file exists
            TEST_DATASET_EXISTS="false"
            if [ -n "${DATASET_PATH_VISIBLE:-}" ] && [ -f "${DATASET_PATH_VISIBLE}" ]; then
              TEST_DATASET_EXISTS="true"
            fi
            echo "[DATASET-PREFLIGHT] datasetExists=${TEST_DATASET_EXISTS}"
            # Distinguish storage error from dataset error
            if [ "$TEST_MOUNT_PATH" = "false" ]; then
              echo "[STEP] STORAGE_MOUNT_MISSING"
              echo "[CUSTOM-TRAINING] The shared uploads PVC is not mounted at ${MLOPS_UPLOADS_MOUNT_PATH:-/app/uploads}"
              ls -la /app/uploads/projects/ || true
              exit 1
            fi
            if [ "$TEST_DATASET_EXISTS" = "false" ]; then
              echo "[STEP] DATASET_NOT_VISIBLE"
              echo "[CUSTOM-TRAINING] Dataset not visible at ${DATASET_PATH_VISIBLE:-<no path>}"
              # Show limited useful content without secrets
              if [ -d "/app/uploads/projects" ]; then
                echo "[CUSTOM-TRAINING] Available projects under /app/uploads/projects/:"
                ls /app/uploads/projects/ 2>/dev/null | head -5 || true
              fi
              exit 1
            fi

            SCRIPT_NAME="${USER_SCRIPT:-train.py}"
            SOURCE_SCRIPT=""

            if [ -f "/app/$SCRIPT_NAME" ]; then
              SOURCE_SCRIPT="/app/$SCRIPT_NAME"
            elif [ -f "/workspace/$SCRIPT_NAME" ]; then
              SOURCE_SCRIPT="/workspace/$SCRIPT_NAME"
            elif [ -f "$SCRIPT_NAME" ]; then
              SOURCE_SCRIPT="$SCRIPT_NAME"
            fi

            if [ -z "$SOURCE_SCRIPT" ]; then
              echo "[CUSTOM-TRAINING] ERROR: user script not found: $SCRIPT_NAME"
              echo "[CUSTOM-TRAINING] /app content:"
              ls -la /app || true
              echo "[CUSTOM-TRAINING] /workspace content:"
              ls -la /workspace || true
              exit 1
            fi

            WORKSPACE_RUNTIME="${WORKSPACE_DIR:-/workspace}"

            if [ -z "$WORKSPACE_RUNTIME" ]; then
              WORKSPACE_RUNTIME="/workspace"
            fi

            mkdir -p "$WORKSPACE_RUNTIME"
            mkdir -p "$WORKSPACE_RUNTIME/outputs"
            mkdir -p "$WORKSPACE_RUNTIME/artifacts"
            mkdir -p /opt/ml/model

            DEST_SCRIPT="$WORKSPACE_RUNTIME/train.py"

            SOURCE_REAL="$(readlink -f "$SOURCE_SCRIPT" 2>/dev/null || echo "$SOURCE_SCRIPT")"
            DEST_REAL="$(readlink -f "$DEST_SCRIPT" 2>/dev/null || echo "$DEST_SCRIPT")"

            echo "[CUSTOM-TRAINING] SOURCE_SCRIPT=$SOURCE_SCRIPT"
            echo "[CUSTOM-TRAINING] DEST_SCRIPT=$DEST_SCRIPT"
            echo "[CUSTOM-TRAINING] SOURCE_REAL=$SOURCE_REAL"
            echo "[CUSTOM-TRAINING] DEST_REAL=$DEST_REAL"

            if [ "$SOURCE_REAL" = "$DEST_REAL" ]; then
                echo "[CUSTOM-TRAINING] User script already exists in workspace. Skipping copy."
            else
                echo "[CUSTOM-TRAINING] Copying user script to workspace"
                cp "$SOURCE_SCRIPT" "$DEST_SCRIPT"
            fi

            if [ -d "/app/src" ]; then
              echo "[CUSTOM-TRAINING] Copying platform src package to workspace"
              rm -rf "$WORKSPACE_RUNTIME/src"
              cp -r /app/src "$WORKSPACE_RUNTIME/src"
            fi

            DATASET_ARG="${DATASET_PATH:-}"

            if [ -z "$DATASET_ARG" ]; then
              DATASET_ARG="${DATA_PATH:-}"
            fi

            echo "[CUSTOM-TRAINING] RUNNER_PATH=$RUNNER_PATH"
            echo "[CUSTOM-TRAINING] SOURCE_SCRIPT=$SOURCE_SCRIPT"
            echo "[CUSTOM-TRAINING] WORKSPACE_RUNTIME=$WORKSPACE_RUNTIME"
            echo "[CUSTOM-TRAINING] DATASET_ARG=$DATASET_ARG"
            echo "[CUSTOM-TRAINING] Workspace content:"
            ls -la "$WORKSPACE_RUNTIME" || true

            echo "[CUSTOM-TRAINING] Final command:"
            echo "python $RUNNER_PATH --script train.py --tracking-uri ${MLFLOW_TRACKING_URI:-http://mlflow-service:5000} --experiment-name ${MLFLOW_EXPERIMENT_NAME:-custom_training} --run-name ${RUN_NAME:-custom_run} --registered-model-name ${REGISTERED_MODEL_NAME:-custom_model} --dataset-path $DATASET_ARG --workspace $WORKSPACE_RUNTIME"

            cd "$WORKSPACE_RUNTIME"

            exec python "$RUNNER_PATH" --script "train.py" --tracking-uri "${MLFLOW_TRACKING_URI:-http://mlflow-service:5000}" --experiment-name "${MLFLOW_EXPERIMENT_NAME:-custom_training}" --run-name "${RUN_NAME:-custom_run}" --registered-model-name "${REGISTERED_MODEL_NAME:-custom_model}" --dataset-path "$DATASET_ARG" --workspace "$WORKSPACE_RUNTIME"
            """;
    }

    private String resolveImagePullPolicy(String imageUri) {
        if (!hasText(imageUri)) {
            return "IfNotPresent";
        }

        String clean = imageUri.trim();

        if (clean.endsWith(":latest")
                || clean.contains("/mlops-custom:")) {
            return "Always";
        }

        boolean looksLikeLocalImage =
                !clean.contains("/")
                        && clean.contains(":");

        if (looksLikeLocalImage) {
            return "Never";
        }

        return "IfNotPresent";
    }

    private boolean imagePullSecretExists(KubernetesClient client) {
        if (!hasText(imagePullSecret)) {
            return false;
        }

        try {
            return client.secrets()
                    .inNamespace(namespace)
                    .withName(imagePullSecret.trim())
                    .get() != null;
        } catch (Exception e) {
            System.out.println("[KubernetesJobService] Unable to validate image pull secret '"
                    + imagePullSecret + "': " + e.getMessage());
            return false;
        }
    }

    private boolean shouldAttachUploadsPvc() {
        return isUploadsPvcEnabled()
                && hasText(getUploadsPvcName())
                && hasText(getUploadsMountPath());
    }

    private void validateUploadsPvcExists(KubernetesClient client) {
        try {
            if (client.persistentVolumeClaims()
                    .inNamespace(namespace)
                    .withName(getUploadsPvcName().trim())
                    .get() == null) {
                throw new RuntimeException("Shared uploads PVC '" + getUploadsPvcName()
                        + "' not found in namespace '" + namespace + "'.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate shared uploads PVC '"
                    + getUploadsPvcName() + "': " + e.getMessage(), e);
        }
    }

    private List<EnvVar> buildEnvironmentVariables(MlProject project, MlExperiment experiment) {
        List<EnvVar> envVars = new ArrayList<>();

        String registeredModelName = firstNonBlank(
                safe(experiment.getRegisteredModelName()),
                safe(experiment.getModelName()),
                safe(experiment.getExperimentName())
        );

        addEnv(envVars, "MLFLOW_TRACKING_URI", mlflowTrackingUri);

        addEnv(envVars, "MLFLOW_EXPERIMENT_NAME", registeredModelName);
        addEnv(envVars, "REGISTERED_MODEL_NAME", registeredModelName);

        String platformRunName = safe(experiment.getExperimentName());
        addEnv(envVars, "RUN_NAME", platformRunName);
        addEnv(envVars, "MLFLOW_RUN_NAME", platformRunName);
        addEnv(envVars, "PROJECT_NAME", safe(project.getName()));
        addEnv(envVars, "PROJECT_ID", String.valueOf(project.getId()));
        addEnv(envVars, "SPRING_EXPERIMENT_ID", String.valueOf(experiment.getId()));
        addEnv(envVars, "MLFLOW_TAG_SPRING_EXPERIMENT_ID", String.valueOf(experiment.getId()));
        addEnv(envVars, "MLFLOW_TAG_PLATFORM_RUNNER", "true");
        addEnv(envVars, "MLFLOW_TAG_REGISTERED_MODEL_NAME", registeredModelName);

        addEnv(envVars, "PRIMARY_METRIC", safe(experiment.getPrimaryMetric()));
        addEnv(envVars, "PRIMARY_METRIC_1", safe(project.getPrimaryMetric1()));
        addEnv(envVars, "PRIMARY_METRIC_2", safe(project.getPrimaryMetric2()));
        addEnv(envVars, "PRIMARY_METRIC_3", safe(project.getPrimaryMetric3()));
        addEnv(envVars, "DIRECTION", safe(experiment.getDirection()));

        addEnv(envVars, "PROJECT_TYPE", safe(project.getProjectType()));
        addEnv(envVars, "PROBLEM_TYPE", safe(project.getProjectType()));

        /*
         * TARGET_COLUMN is optional.
         *
         * IMPORTANT:
         * Never inject TARGET_COLUMN="" into Kubernetes.
         * When no target is configured in the project/experiment, the variable
         * must be absent so that the user training script can use its own
         * default target column exactly as it does locally.
         */
        String targetColumn = firstNonBlank(
                experiment.getTargetColumn(),
                project.getTargetColumn()
        );

        addOptionalEnv(envVars, "TARGET_COLUMN", targetColumn);
        addOptionalEnv(envVars, "ML_TARGET_COLUMN", targetColumn);

        /*
         * DATASET_PATH / DATA_PATH.
         *
         * ARCHITECTURE FIX:
         * Le dataset n'est PLUS figé dans l'image Docker. Il est résolu ici,
         * par expérience, et injecté comme variable d'environnement du Pod au
         * moment du lancement du Job. C'est la SEULE source de vérité.
         *
         * Règles :
         *  - le chemin est résolu via DatasetFileResolverService (rôle-based) ;
         *  - un script Python n'est JAMAIS accepté comme dataset : on lève une
         *    exception claire avant de créer le Job ;
         *  - on n'injecte la variable QUE si le fichier existe réellement sur
         *    le PVC partagé /app/uploads (vérifié par le resolver).
         */
        String datasetPath = null;
        UploadedProjectFile resolvedDataset = null;

        Path workspaceDatasetPath = Path.of(
                uploadsStorageProperties.getMountPath(),
                "workspaces",
                String.valueOf(experiment.getId()),
                "dataset.csv"
        );
        if (Files.exists(workspaceDatasetPath)) {
            datasetPath = workspaceDatasetPath.toString();
            System.out.println("[DATASET_SELECTION] Using workspace dataset: " + datasetPath);
        } else {
            Path workspaceExtractedPath = Path.of(
                    uploadsStorageProperties.getMountPath(),
                    "workspaces",
                    String.valueOf(experiment.getId()),
                    "dataset-extracted"
            );
            if (Files.isDirectory(workspaceExtractedPath)) {
                try (java.util.stream.Stream<Path> files = Files.list(workspaceExtractedPath)) {
                    Path csvFile = files
                            .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                            .findFirst()
                            .orElse(null);
                    if (csvFile != null) {
                        datasetPath = csvFile.toString();
                        System.out.println("[DATASET_SELECTION] Using workspace extracted dataset: " + datasetPath);
                    }
                } catch (Exception e) {
                    System.out.println("[DATASET_SELECTION] Failed to list workspace extracted dir: " + e.getMessage());
                }
            }
        }

        if (datasetPath == null) {
            resolvedDataset = datasetFileResolverService.resolveDatasetFile(project, experiment);

            if (resolvedDataset != null && hasText(resolvedDataset.getFilePath())) {
                datasetPath = normalizeMountPath(resolvedDataset.getFilePath());
            }

            if (datasetPath != null && datasetPath.toLowerCase(Locale.ROOT).endsWith(".py")) {
                throw new IllegalStateException(
                        "Invalid dataset path: Python script selected as dataset: " + datasetPath
                                + " (file=" + resolvedDataset.getOriginalFileName()
                                + ", role=" + (resolvedDataset.getRole() == null ? "-" : resolvedDataset.getRole().name())
                                + "). La plateforme ne transmet jamais un script comme dataset."
                );
            }

            if (datasetPath != null) {
                System.out.println("[DATASET_SELECTION] Using resolved dataset: " + datasetPath
                        + ", file=" + resolvedDataset.getOriginalFileName()
                        + ", role=" + (resolvedDataset.getRole() == null ? "-" : resolvedDataset.getRole().name()));
            }
        }

        if (datasetPath != null) {
            Path dp = Path.of(datasetPath);
            boolean fileExists = Files.exists(dp);

            if (!fileExists) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] File NOT found at resolved path. "
                        + "Attempting dotless legacy fix. path=" + datasetPath);
                String fixedPath = attemptDotlessDatasetFix(datasetPath, experiment, resolvedDataset);
                if (fixedPath != null) {
                    datasetPath = fixedPath;
                }
                dp = Path.of(datasetPath);
                fileExists = Files.exists(dp);
            }

            String resolvedExt = "";
            String dpName = dp.getFileName().toString();
            int dotIdx = dpName.lastIndexOf(".");
            if (dotIdx > 0 && dotIdx < dpName.length() - 1) {
                resolvedExt = dpName.substring(dotIdx + 1);
            }

            if (fileExists && resolvedExt.isBlank()) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] File exists but has no dot extension. "
                        + "Attempting dotless legacy fix. path=" + datasetPath);
                String fixedPath = attemptDotlessDatasetFix(datasetPath, experiment, resolvedDataset);
                if (fixedPath != null) {
                    datasetPath = fixedPath;
                    dp = Path.of(datasetPath);
                    dpName = dp.getFileName().toString();
                    dotIdx = dpName.lastIndexOf(".");
                    if (dotIdx > 0 && dotIdx < dpName.length() - 1) {
                        resolvedExt = dpName.substring(dotIdx + 1);
                    }
                }
            }

            if (!fileExists) {
                throw new IllegalStateException(
                        "[RETRAINING_DATASET_PREFLIGHT] ABORT: Dataset file does not exist at path: "
                                + datasetPath
                                + ". projectId=" + project.getId()
                                + ", experimentId=" + experiment.getId()
                                + ". Cannot launch K8s Job without a valid dataset."
                );
            }

            if (resolvedExt.isBlank()) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] WARNING: No detectable extension. "
                        + "path=" + datasetPath + ". Job may fail with 'Unsupported dataset format'.");
            }

            addEnv(envVars, "DATASET_PATH", datasetPath);
            addEnv(envVars, "DATA_PATH", datasetPath);

            System.out.println("[RETRAINING_DATASET]"
                    + " projectId=" + project.getId()
                    + ", sourceExperimentId=" + experiment.getId()
                    + ", originalFilename=" + (experiment.getDatasetFile() != null
                        ? experiment.getDatasetFile().getOriginalFileName() : "null")
                    + ", storedFilename=" + (experiment.getDatasetFile() != null
                        ? experiment.getDatasetFile().getStoredFileName() : "null")
                    + ", databasePath=" + (experiment.getDatasetFile() != null
                        ? experiment.getDatasetFile().getFilePath() : "null")
                    + ", resolvedPath=" + datasetPath
                    + ", extension=" + resolvedExt
                    + ", exists=" + fileExists
                    + ", fallbackUsed=" + (datasetPath != null && !datasetPath.equals(
                        experiment.getDatasetFile() != null ? experiment.getDatasetFile().getFilePath() : ""))
            );
        }

        String trainingScriptPath = experiment.getModelFile() != null && hasText(experiment.getModelFile().getFilePath())
                ? normalizeMountPath(experiment.getModelFile().getFilePath())
                : "-";
        UploadedProjectFile preprocessingScript = datasetFileResolverService.findPreprocessingScript(project);
        String preprocessingScriptPath = preprocessingScript != null && hasText(preprocessingScript.getFilePath())
                ? normalizeMountPath(preprocessingScript.getFilePath())
                : "-";
        String requirementsPath = hasText(experiment.getRequirementsPath())
                ? normalizeMountPath(experiment.getRequirementsPath())
                : "-";
        String dockerfilePath = hasText(experiment.getDockerfilePath())
                ? normalizeMountPath(experiment.getDockerfilePath())
                : "-";

        System.out.println("[TRAINING-FILES] datasetPath=" + (datasetPath != null ? datasetPath : "-"));
        System.out.println("[TRAINING-FILES] trainingScriptPath=" + trainingScriptPath);
        System.out.println("[TRAINING-FILES] preprocessingScriptPath=" + preprocessingScriptPath);
        System.out.println("[TRAINING-FILES] requirementsPath=" + requirementsPath);
        System.out.println("[TRAINING-FILES] dockerfilePath=" + dockerfilePath);
        System.out.println("[TRAINING-FILES] trainingScriptRuntimeName=" + (experiment.getModelFile() != null
                ? UploadFileNameNormalizer.runtimeName(experiment.getModelFile().getOriginalFileName(), UploadedFileType.TRAINING_SCRIPT) : "-"));
        System.out.println("[TRAINING-FILES] datasetRuntimeName=" + (datasetPath != null
                ? datasetPath.substring(datasetPath.lastIndexOf('/') + 1) : "-"));
        System.out.println("[TRAINING-FILES] requirementsRuntimeName=" + UploadFileNameNormalizer.REQUIREMENTS_RUNTIME_NAME
                + ", dockerfileRuntimeName=" + UploadFileNameNormalizer.DOCKERFILE_RUNTIME_NAME);

        addEnv(envVars, "USER_SCRIPT", "train.py");
        addEnv(envVars, "WORKSPACE_DIR", "/workspace");

        addEnv(envVars, "RUNTIME_MODE", safe(experiment.getRuntimeMode()));
        addEnv(envVars, "MLOPS_CUSTOM_IMAGE_URI", safe(experiment.getCustomImageUri()));
        addEnv(envVars, "MLOPS_EXPERIMENT_NAME", safe(experiment.getExperimentName()));

        /*
         * Inform the platform runner and user scripts where shared uploads are mounted.
         */
        addEnv(envVars, "APP_UPLOAD_DIR", normalizeMountPath(getUploadsMountPath()));
        addEnv(envVars, "MLOPS_UPLOADS_MOUNT_PATH", normalizeMountPath(getUploadsMountPath()));
        addEnv(envVars, "MLOPS_UPLOADS_PVC_NAME", safe(getUploadsPvcName()));

        addEnv(envVars, "MLOPS_AUTO_MODEL_DISCOVERY", "true");
        addEnv(envVars, "MLOPS_DEFAULT_OUTPUT_DIR", "/workspace/outputs");

        addEnv(envVars, "MLOPS_ACCEPTED_MODEL_DIRS",
                "/workspace/outputs,"
                        + "/workspace/output,"
                        + "/workspace/artifacts,"
                        + "/workspace/artifact,"
                        + "/workspace/model,"
                        + "/workspace/models,"
                        + "/workspace/saved_model,"
                        + "/workspace/checkpoints,"
                        + "/workspace,"
                        + "/app/outputs,"
                        + "/app/output,"
                        + "/app/artifacts,"
                        + "/app/artifact,"
                        + "/app/model,"
                        + "/app/models,"
                        + "/app/saved_model,"
                        + "/app/checkpoints,"
                        + "/app"
        );

        addEnv(envVars, "MLOPS_ACCEPTED_MODEL_EXTENSIONS",
                ".pkl,.joblib,.sav,.cbm,.pt,.pth,.h5,.keras,.onnx"
        );

        addEnv(envVars, "MLOPS_PREFERRED_MODEL_FILENAMES",
                "MLmodel,best_model.pkl,final_model.pkl,model.pkl,"
                        + "best_model.joblib,final_model.joblib,model.joblib,"
                        + "best_model.cbm,final_model.cbm,model.cbm"
        );

        return envVars;
    }

    private void addEnv(List<EnvVar> envVars, String name, String value) {
        envVars.add(
                new EnvVarBuilder()
                        .withName(name)
                        .withValue(value != null ? value : "")
                        .build()
        );
    }

    /**
     * Adds an optional environment variable only when it contains a real value.
     *
     * This is required for TARGET_COLUMN:
     * - configured target -> inject it;
     * - absent/blank target -> do not create the variable;
     * - the user script can then apply its local default automatically.
     */
    private void addOptionalEnv(
            List<EnvVar> envVars,
            String name,
            String value
    ) {
        if (envVars == null
                || !hasText(name)
                || !hasText(value)) {
            return;
        }

        envVars.add(
                new EnvVarBuilder()
                        .withName(name.trim())
                        .withValue(value.trim())
                        .build()
        );
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

    private String buildJobName(String projectName, Long experimentId) {
        String projectSlug = slugify(projectName);

        String base = "mlops-custom-" + projectSlug + "-" + experimentId;

        if (base.length() > 63) {
            base = base.substring(0, 63);
        }

        base = base.replaceAll("-+$", "");

        if (base.isBlank()) {
            return "mlops-custom-" + experimentId;
        }

        return base;
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

    private String normalizeMountPath(String value) {
        String clean = firstNonBlank(value, "/app/uploads").replace("\\", "/").trim();

        if (clean.endsWith("/") && clean.length() > 1) {
            clean = clean.substring(0, clean.length() - 1);
        }

        return clean;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final java.util.Set<String> KNOWN_DATASET_EXTENSIONS = java.util.Set.of(
            "csv", "tsv", "json", "jsonl", "parquet", "xlsx", "xls",
            "zip", "tar", "gz", "tgz", "7z", "rar",
            "txt", "jpg", "jpeg", "png", "tif", "tiff"
    );

    private String attemptDotlessDatasetFix(String datasetPath, MlExperiment experiment,
                                            UploadedProjectFile resolvedDataset) {
        try {
            Path brokenPath = Path.of(datasetPath);
            String fileName = brokenPath.getFileName().toString();

            if (fileName.contains(".")) {
                return null;
            }

            String ext = null;

            // 1) Try from the resolved dataset returned by DatasetFileResolverService
            if (resolvedDataset != null) {
                ext = resolvedDataset.getFileExtension();
            }

            // 2) Fallback to experiment's own dataset file
            if (ext == null || ext.isBlank()) {
                if (experiment.getDatasetFile() != null) {
                    ext = experiment.getDatasetFile().getFileExtension();
                }
            }

            // 3) Try originalFileName from either source
            if (ext == null || ext.isBlank()) {
                String origName = null;
                if (resolvedDataset != null) {
                    origName = resolvedDataset.getOriginalFileName();
                }
                if (origName == null && experiment.getDatasetFile() != null) {
                    origName = experiment.getDatasetFile().getOriginalFileName();
                }
                if (origName != null && origName.contains(".")) {
                    ext = origName.substring(origName.lastIndexOf(".") + 1);
                }
            }

            if (ext == null || ext.isBlank()) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] Cannot determine extension for dotless fix. "
                        + "path=" + datasetPath
                        + ", resolvedDataset=" + (resolvedDataset != null ? resolvedDataset.getId() : "null")
                        + ", experimentDatasetFile=" + (experiment.getDatasetFile() != null
                            ? experiment.getDatasetFile().getId() : "null"));
                return null;
            }

            ext = ext.trim().toLowerCase(Locale.ROOT);
            if (ext.startsWith(".")) {
                ext = ext.substring(1);
            }

            if (!KNOWN_DATASET_EXTENSIONS.contains(ext)) {
                return null;
            }

            if (!Files.exists(brokenPath)) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] Source file also not found. "
                        + "path=" + brokenPath + ". Cannot rename.");
                return null;
            }

            String uuidPart = fileName.substring(0, fileName.length() - ext.length());
            String fixedName = uuidPart + "." + ext;

            Path parentDir = brokenPath.getParent();
            if (parentDir == null) {
                return null;
            }
            Path fixedPath = parentDir.resolve(fixedName);

            if (Files.exists(fixedPath)) {
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] Fixed file already exists. "
                        + "fixedPath=" + fixedPath);
            } else {
                try {
                    java.nio.file.Files.move(brokenPath, fixedPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    java.nio.file.Files.copy(brokenPath, fixedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    java.nio.file.Files.deleteIfExists(brokenPath);
                }
                System.out.println("[RETRAINING_DATASET_PREFLIGHT] Renamed dotless file on disk. "
                        + "oldPath=" + brokenPath
                        + ", newPath=" + fixedPath);
            }

            if (resolvedDataset != null) {
                resolvedDataset.setFilePath(fixedPath.toString());
                resolvedDataset.setStoredFileName(fixedName);
            }
            if (experiment.getDatasetFile() != null
                    && experiment.getDatasetFile().getId() != null
                    && resolvedDataset != null
                    && experiment.getDatasetFile().getId().equals(resolvedDataset.getId())) {
                experiment.getDatasetFile().setFilePath(fixedPath.toString());
                experiment.getDatasetFile().setStoredFileName(fixedName);
            }

            System.out.println("[RETRAINING_DATASET_PREFLIGHT] Dotless legacy fix applied. "
                    + "oldPath=" + brokenPath
                    + ", fixedPath=" + fixedPath
                    + ", extension=" + ext);

            return fixedPath.toString();

        } catch (Exception e) {
            System.out.println("[RETRAINING_DATASET_PREFLIGHT] Dotless fix failed. "
                    + "reason=" + e.getMessage());
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}