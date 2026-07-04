package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /*
     * Shared uploads PVC.
     *
     * This is required for scalable custom Docker runs:
     * - datasets are NOT copied into the Kaniko ConfigMap
     * - datasets remain under /app/uploads/...
     * - the custom training job must mount the same PVC at /app/uploads
     */
    @Value("${app.uploads.pvc-enabled:true}")
    private boolean uploadsPvcEnabled;

    @Value("${app.uploads.pvc-name:mlops-uploads-pvc}")
    private String uploadsPvcName;

    @Value("${app.uploads.mount-path:/app/uploads}")
    private String uploadsMountPath;

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
                        + "pvc=" + uploadsPvcName
                        + ", mountPath=" + uploadsMountPath
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
                        .withMountPath(normalizeMountPath(uploadsMountPath))
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
                            .withClaimName(uploadsPvcName.trim())
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

                RUNNER_PATH="/app/platform_runner.py"

                if [ ! -f "$RUNNER_PATH" ]; then
                  RUNNER_PATH="/workspace/platform_runner.py"
                fi

                if [ ! -f "$RUNNER_PATH" ]; then
                  echo "[CUSTOM-TRAINING] ERROR: platform_runner.py not found in /app or /workspace"
                  echo "[CUSTOM-TRAINING] /app content:"
                  ls -la /app || true
                  echo "[CUSTOM-TRAINING] /workspace content:"
                  ls -la /workspace || true
                  exit 1
                fi

                SCRIPT_NAME="${USER_SCRIPT:-train.py}"
                SCRIPT_PATH="$SCRIPT_NAME"

                if [ -f "/app/$SCRIPT_NAME" ]; then
                  SCRIPT_PATH="/app/$SCRIPT_NAME"
                elif [ -f "/workspace/$SCRIPT_NAME" ]; then
                  SCRIPT_PATH="/workspace/$SCRIPT_NAME"
                fi

                if [ ! -f "$SCRIPT_PATH" ]; then
                  echo "[CUSTOM-TRAINING] ERROR: user script not found: $SCRIPT_NAME"
                  echo "[CUSTOM-TRAINING] Checked:"
                  echo "  - $SCRIPT_PATH"
                  echo "  - /app/$SCRIPT_NAME"
                  echo "  - /workspace/$SCRIPT_NAME"
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

                DATASET_ARG="${DATASET_PATH:-}"

                if [ -z "$DATASET_ARG" ]; then
                  DATASET_ARG="${DATA_PATH:-}"
                fi

                echo "[CUSTOM-TRAINING] RUNNER_PATH=$RUNNER_PATH"
                echo "[CUSTOM-TRAINING] SCRIPT_PATH=$SCRIPT_PATH"
                echo "[CUSTOM-TRAINING] WORKSPACE_RUNTIME=$WORKSPACE_RUNTIME"
                echo "[CUSTOM-TRAINING] DATASET_ARG=$DATASET_ARG"
                echo "[CUSTOM-TRAINING] Final command:"
                echo "python $RUNNER_PATH --script $SCRIPT_PATH --tracking-uri ${MLFLOW_TRACKING_URI:-http://mlflow-service:5000} --experiment-name ${MLFLOW_EXPERIMENT_NAME:-custom_training} --run-name ${RUN_NAME:-custom_run} --registered-model-name ${REGISTERED_MODEL_NAME:-custom_model} --dataset-path $DATASET_ARG --workspace $WORKSPACE_RUNTIME"

                cd "$WORKSPACE_RUNTIME"

                exec python "$RUNNER_PATH" --script "$SCRIPT_PATH" --tracking-uri "${MLFLOW_TRACKING_URI:-http://mlflow-service:5000}" --experiment-name "${MLFLOW_EXPERIMENT_NAME:-custom_training}" --run-name "${RUN_NAME:-custom_run}" --registered-model-name "${REGISTERED_MODEL_NAME:-custom_model}" --dataset-path "$DATASET_ARG" --workspace "$WORKSPACE_RUNTIME"
                """;
    }

    private String resolveImagePullPolicy(String imageUri) {
        if (!hasText(imageUri)) {
            return "IfNotPresent";
        }

        String clean = imageUri.trim();

        if (clean.endsWith(":latest")) {
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
        return uploadsPvcEnabled
                && hasText(uploadsPvcName)
                && hasText(uploadsMountPath);
    }

    private void validateUploadsPvcExists(KubernetesClient client) {
        try {
            if (client.persistentVolumeClaims()
                    .inNamespace(namespace)
                    .withName(uploadsPvcName.trim())
                    .get() == null) {
                throw new RuntimeException("Shared uploads PVC '" + uploadsPvcName
                        + "' not found in namespace '" + namespace + "'.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate shared uploads PVC '"
                    + uploadsPvcName + "': " + e.getMessage(), e);
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

        addEnv(envVars, "RUN_NAME", safe(experiment.getExperimentName()));
        addEnv(envVars, "PROJECT_NAME", safe(project.getName()));
        addEnv(envVars, "PROJECT_ID", String.valueOf(project.getId()));
        addEnv(envVars, "SPRING_EXPERIMENT_ID", String.valueOf(experiment.getId()));

        addEnv(envVars, "PRIMARY_METRIC", safe(experiment.getPrimaryMetric()));
        addEnv(envVars, "PRIMARY_METRIC_1", safe(project.getPrimaryMetric1()));
        addEnv(envVars, "PRIMARY_METRIC_2", safe(project.getPrimaryMetric2()));
        addEnv(envVars, "PRIMARY_METRIC_3", safe(project.getPrimaryMetric3()));
        addEnv(envVars, "DIRECTION", safe(experiment.getDirection()));

        addEnv(envVars, "PROJECT_TYPE", safe(project.getProjectType()));
        addEnv(envVars, "PROBLEM_TYPE", safe(project.getProjectType()));

        String targetColumn = firstNonBlank(
                safe(experiment.getTargetColumn()),
                safe(project.getTargetColumn())
        );

        addEnv(envVars, "TARGET_COLUMN", targetColumn);
        addEnv(envVars, "ML_TARGET_COLUMN", targetColumn);

        /*
         * Do not set DATASET_PATH here.
         *
         * DATASET_PATH is injected into the generated Docker image by KubernetesBuildService.
         * If we set DATASET_PATH here with an empty value, Kubernetes would override the image ENV
         * and the dataset path would be lost.
         */
        addEnv(envVars, "USER_SCRIPT", "train.py");
        addEnv(envVars, "WORKSPACE_DIR", "/workspace");

        addEnv(envVars, "RUNTIME_MODE", safe(experiment.getRuntimeMode()));
        addEnv(envVars, "MLOPS_CUSTOM_IMAGE_URI", safe(experiment.getCustomImageUri()));
        addEnv(envVars, "MLOPS_EXPERIMENT_NAME", safe(experiment.getExperimentName()));

        /*
         * Inform the platform runner and user scripts where shared uploads are mounted.
         */
        addEnv(envVars, "APP_UPLOAD_DIR", normalizeMountPath(uploadsMountPath));
        addEnv(envVars, "MLOPS_UPLOADS_MOUNT_PATH", normalizeMountPath(uploadsMountPath));
        addEnv(envVars, "MLOPS_UPLOADS_PVC_NAME", safe(uploadsPvcName));

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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}