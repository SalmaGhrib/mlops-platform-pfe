package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.PipelineGatewayResponse;
import com.mlops.platform.entity.UploadedProjectFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class PipelineClientService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.pipeline.gateway-url:http://127.0.0.1:8001/upload-and-run-custom-model}")
    private String pipelineGatewayUrl;

    @Value("${app.kubeflow.api-url:http://localhost:8889}")
    private String kubeflowApiUrl;

    public PipelineClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public static class KubeflowRunInfo {
        private String runId;
        private String runName;
        private String status;
        private String runUrl;

        public KubeflowRunInfo() {
        }

        public KubeflowRunInfo(String runId, String runName, String status, String runUrl) {
            this.runId = runId;
            this.runName = runName;
            this.status = status;
            this.runUrl = runUrl;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getRunName() {
            return runName;
        }

        public void setRunName(String runName) {
            this.runName = runName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getRunUrl() {
            return runUrl;
        }

        public void setRunUrl(String runUrl) {
            this.runUrl = runUrl;
        }
    }

    /*
     * Nouvelle signature principale.
     * Elle reçoit springExperimentId pour permettre une synchronisation fiable :
     * MySQL experiment id -> tag MLflow spring_experiment_id.
     */
    public PipelineGatewayResponse launchCustomModelPipeline(
            Long springExperimentId,
            Long projectId,
            String projectName,
            String modelName,
            String registeredModelName,
            String runName,
            String targetColumn,
            String primaryMetric,
            String direction,
            String projectType,
            String runtimeMode,
            UploadedProjectFile modelFile,
            UploadedProjectFile datasetFile
    ) {
        try {
            if (modelFile == null || modelFile.getFilePath() == null || modelFile.getFilePath().isBlank()) {
                throw new RuntimeException("Model/script file is required to launch Kubeflow pipeline.");
            }

            Path modelPath = Paths.get(modelFile.getFilePath()).toAbsolutePath().normalize();

            if (!Files.exists(modelPath)) {
                throw new RuntimeException("Model/script file does not exist: " + modelPath);
            }

            Path datasetPath = null;

            if (datasetFile != null && datasetFile.getFilePath() != null && !datasetFile.getFilePath().isBlank()) {
                datasetPath = Paths.get(datasetFile.getFilePath()).toAbsolutePath().normalize();

                if (!Files.exists(datasetPath)) {
                    throw new RuntimeException("Dataset file does not exist: " + datasetPath);
                }
            }

            String cleanProjectName = cleanOrDefault(projectName, "default_project");
            String cleanModelName = cleanOrDefault(modelName, "custom_model");

            String cleanRegisteredModelName = cleanOrDefault(
                    registeredModelName,
                    buildFallbackRegisteredModelName(cleanProjectName)
            );

            String cleanRunName = cleanOrDefault(runName, "custom_run_" + System.currentTimeMillis());
            String cleanTargetColumn = targetColumn != null ? targetColumn.trim() : "";
            String cleanPrimaryMetric = cleanOrDefault(primaryMetric, "accuracy");
            String cleanDirection = cleanOrDefault(direction, inferDirection(cleanPrimaryMetric));
            String cleanProjectType = cleanOrDefault(projectType, "classification").toLowerCase();
            String cleanRuntimeMode = cleanOrDefault(runtimeMode, "STANDARD_RUNTIME").toUpperCase();

            cleanRegisteredModelName = normalizeRegisteredModelName(
                    cleanRegisteredModelName,
                    cleanProjectName
            );

            /*
             * Sécurité :
             * CUSTOM_DOCKERFILE est lancé par KubernetesJobService,
             * pas par ce client pipeline standard.
             */
            if ("CUSTOM_DOCKERFILE".equalsIgnoreCase(cleanRuntimeMode)) {
                PipelineGatewayResponse gatewayResponse = new PipelineGatewayResponse();
                gatewayResponse.setStatus("FAILED");
                gatewayResponse.setKubeflowRunName(cleanRunName);
                gatewayResponse.setMlflowExperimentName(cleanProjectName);
                gatewayResponse.setRegisteredModelName(cleanRegisteredModelName);
                gatewayResponse.setMessage(
                        "CUSTOM_DOCKERFILE must be launched by KubernetesJobService, not PipelineClientService."
                );
                return gatewayResponse;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            /*
             * Champs principaux snake_case côté FastAPI.
             */
            body.add("project_id", projectId != null ? String.valueOf(projectId) : "");
            body.add("project_name", cleanProjectName);

            body.add("model_name", cleanModelName);
            body.add("registered_model_name", cleanRegisteredModelName);

            body.add("run_name", cleanRunName);
            body.add("target_column", cleanTargetColumn);
            body.add("primary_metric", cleanPrimaryMetric);
            body.add("direction", cleanDirection);
            body.add("project_type", cleanProjectType);
            body.add("runtime_mode", cleanRuntimeMode);

            /*
             * Champs de synchronisation.
             * Très important : le gateway / custom runner doit recevoir cet ID
             * pour le mettre comme tag MLflow.
             */
            if (springExperimentId != null) {
                body.add("spring_experiment_id", String.valueOf(springExperimentId));
                body.add("springExperimentId", String.valueOf(springExperimentId));
                body.add("mysql_experiment_id", String.valueOf(springExperimentId));
                body.add("experiment_db_id", String.valueOf(springExperimentId));
            }

            /*
             * Champs camelCase pour compatibilité avec les anciennes versions du gateway.
             */
            body.add("projectId", projectId != null ? String.valueOf(projectId) : "");
            body.add("projectName", cleanProjectName);
            body.add("modelName", cleanModelName);
            body.add("registeredModelName", cleanRegisteredModelName);
            body.add("runName", cleanRunName);
            body.add("targetColumn", cleanTargetColumn);
            body.add("primaryMetric", cleanPrimaryMetric);

            /*
             * Champs MLflow explicites.
             */
            body.add("mlflow_experiment_name", cleanProjectName);
            body.add("mlflowExperimentName", cleanProjectName);
            body.add("mlflow_registered_model_name", cleanRegisteredModelName);
            body.add("mlflowRegisteredModelName", cleanRegisteredModelName);
            body.add("production_alias", "production");
            body.add("model_alias", "production");

            /*
             * Fichier Python.
             * On garde model_file et script_file pour compatibilité.
             */
            FileSystemResource scriptResource = new FileSystemResource(modelPath);
            body.add("model_file", scriptResource);
            body.add("script_file", scriptResource);

            /*
             * Dataset optionnel.
             */
            if (datasetPath != null) {
                FileSystemResource datasetResource = new FileSystemResource(datasetPath);
                body.add("dataset_file", datasetResource);
                body.add("dataset", datasetResource);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            String url = normalizeGatewayUrl(pipelineGatewayUrl);

            System.out.println("[PipelineClientService] Launching custom model pipeline");
            System.out.println("[PipelineClientService] URL = " + url);
            System.out.println("[PipelineClientService] spring_experiment_id = " + springExperimentId);
            System.out.println("[PipelineClientService] project_id = " + projectId);
            System.out.println("[PipelineClientService] project_name = " + cleanProjectName);
            System.out.println("[PipelineClientService] model_name = " + cleanModelName);
            System.out.println("[PipelineClientService] registered_model_name = " + cleanRegisteredModelName);
            System.out.println("[PipelineClientService] run_name = " + cleanRunName);
            System.out.println("[PipelineClientService] target_column = " + cleanTargetColumn);
            System.out.println("[PipelineClientService] primary_metric = " + cleanPrimaryMetric);
            System.out.println("[PipelineClientService] direction = " + cleanDirection);
            System.out.println("[PipelineClientService] project_type = " + cleanProjectType);
            System.out.println("[PipelineClientService] runtime_mode = " + cleanRuntimeMode);
            System.out.println("[PipelineClientService] model_file = " + modelPath);
            System.out.println("[PipelineClientService] dataset_file = " + datasetPath);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    requestEntity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException(
                        "Kubeflow pipeline launch failed with status: " + response.getStatusCode()
                );
            }

            String responseBody = response.getBody();

            return buildGatewayResponse(
                    responseBody,
                    cleanProjectName,
                    cleanRunName,
                    cleanRegisteredModelName
            );

        } catch (Exception e) {
            throw new RuntimeException("Kubeflow pipeline launch failed: " + e.getMessage(), e);
        }
    }

    /*
     * Signature standard conservée.
     * Elle délègue à la nouvelle signature principale avec springExperimentId = null.
     * Cela évite de casser les anciens appels.
     */
    public PipelineGatewayResponse launchCustomModelPipeline(
            Long projectId,
            String projectName,
            String modelName,
            String registeredModelName,
            String runName,
            String targetColumn,
            String primaryMetric,
            String direction,
            String projectType,
            String runtimeMode,
            UploadedProjectFile modelFile,
            UploadedProjectFile datasetFile
    ) {
        return launchCustomModelPipeline(
                null,
                projectId,
                projectName,
                modelName,
                registeredModelName,
                runName,
                targetColumn,
                primaryMetric,
                direction,
                projectType,
                runtimeMode,
                modelFile,
                datasetFile
        );
    }

    /*
     * Ancienne signature conservée pour compatibilité.
     * Si un ancien service appelle encore sans registeredModelName,
     * on construit un nom stable à partir du projet.
     */
    public PipelineGatewayResponse launchCustomModelPipeline(
            Long projectId,
            String projectName,
            String modelName,
            String runName,
            String targetColumn,
            String primaryMetric,
            String direction,
            String projectType,
            String runtimeMode,
            UploadedProjectFile modelFile,
            UploadedProjectFile datasetFile
    ) {
        String fallbackRegisteredModelName = buildFallbackRegisteredModelName(projectName);

        return launchCustomModelPipeline(
                null,
                projectId,
                projectName,
                modelName,
                fallbackRegisteredModelName,
                runName,
                targetColumn,
                primaryMetric,
                direction,
                projectType,
                runtimeMode,
                modelFile,
                datasetFile
        );
    }

    /*
     * Ancienne surcharge conservée pour ne pas casser l'existant.
     * CUSTOM_DOCKERFILE reste bloqué ici car il doit passer par KubernetesJobService.
     */
    public PipelineGatewayResponse launchCustomModelPipeline(
            Long springExperimentId,
            String projectName,
            String projectType,
            String modelName,
            String runName,
            String registeredModelName,
            String primaryMetric,
            String primaryMode,
            String algorithmReplacements,
            UploadedProjectFile modelFile,
            UploadedProjectFile datasetFile,
            String runtimeMode,
            String customImageUri,
            String dockerfilePath,
            String requirementsPath
    ) {
        String mode = runtimeMode == null || runtimeMode.isBlank()
                ? "STANDARD_RUNTIME"
                : runtimeMode.trim().toUpperCase();

        String cleanProjectName = cleanOrDefault(projectName, "default_project");

        String cleanRegisteredModelName = normalizeRegisteredModelName(
                cleanOrDefault(registeredModelName, buildFallbackRegisteredModelName(cleanProjectName)),
                cleanProjectName
        );

        if ("CUSTOM_DOCKERFILE".equals(mode)) {
            PipelineGatewayResponse gatewayResponse = new PipelineGatewayResponse();
            gatewayResponse.setStatus("FAILED");
            gatewayResponse.setKubeflowRunName(runName);
            gatewayResponse.setMlflowExperimentName(cleanProjectName);
            gatewayResponse.setRegisteredModelName(cleanRegisteredModelName);
            gatewayResponse.setMessage(
                    "CUSTOM_DOCKERFILE must be launched by KubernetesJobService, not PipelineClientService."
            );
            return gatewayResponse;
        }

        /*
         * Ici, l'ancien springExperimentId est transmis comme spring_experiment_id.
         * projectId n'existe pas dans cette ancienne signature, donc on met null.
         */
        return launchCustomModelPipeline(
                springExperimentId,
                null,
                cleanProjectName,
                modelName,
                cleanRegisteredModelName,
                runName,
                "",
                primaryMetric,
                primaryMode,
                projectType,
                mode,
                modelFile,
                datasetFile
        );
    }

    private PipelineGatewayResponse buildGatewayResponse(
            String responseBody,
            String projectName,
            String runName,
            String registeredModelName
    ) {
        PipelineGatewayResponse gatewayResponse = new PipelineGatewayResponse();

        gatewayResponse.setStatus("RUNNING");
        gatewayResponse.setKubeflowRunName(runName);
        gatewayResponse.setMlflowExperimentName(projectName);
        gatewayResponse.setRegisteredModelName(registeredModelName);
        gatewayResponse.setMessage(
                responseBody == null || responseBody.isBlank()
                        ? "Pipeline launch request sent successfully."
                        : responseBody
        );

        if (responseBody == null || responseBody.isBlank()) {
            return gatewayResponse;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode runNode = root.has("run") ? root.get("run") : root;
            JsonNode dataNode = root.has("data") ? root.get("data") : root;

            String status = firstNotBlank(
                    getText(root, "status", "state"),
                    getText(dataNode, "status", "state"),
                    getText(runNode, "status", "state")
            );

            if (status != null && !status.isBlank()) {
                gatewayResponse.setStatus(status);
            }

            String message = firstNotBlank(
                    getText(root, "message", "detail", "error"),
                    getText(dataNode, "message", "detail", "error"),
                    responseBody
            );

            gatewayResponse.setMessage(message);

            String kubeflowRunId = firstNotBlank(
                    getText(root, "kubeflowRunId", "kubeflow_run_id", "runId", "run_id", "id"),
                    getText(dataNode, "kubeflowRunId", "kubeflow_run_id", "runId", "run_id", "id"),
                    getText(runNode, "kubeflowRunId", "kubeflow_run_id", "runId", "run_id", "id")
            );

            if (kubeflowRunId != null && !kubeflowRunId.isBlank()) {
                gatewayResponse.setKubeflowRunId(kubeflowRunId);
            }

            String kubeflowRunName = firstNotBlank(
                    getText(root, "kubeflowRunName", "kubeflow_run_name", "runName", "run_name", "name"),
                    getText(dataNode, "kubeflowRunName", "kubeflow_run_name", "runName", "run_name", "name"),
                    getText(runNode, "kubeflowRunName", "kubeflow_run_name", "runName", "run_name", "name")
            );

            if (kubeflowRunName != null && !kubeflowRunName.isBlank()) {
                gatewayResponse.setKubeflowRunName(kubeflowRunName);
            }

            String mlflowRunId = firstNotBlank(
                    getText(root, "mlflowRunId", "mlflow_run_id"),
                    getText(dataNode, "mlflowRunId", "mlflow_run_id"),
                    getText(runNode, "mlflowRunId", "mlflow_run_id")
            );

            if (mlflowRunId != null && !mlflowRunId.isBlank()) {
                gatewayResponse.setMlflowRunId(mlflowRunId);
            }

            String mlflowExperimentName = firstNotBlank(
                    getText(root, "mlflowExperimentName", "mlflow_experiment_name"),
                    getText(dataNode, "mlflowExperimentName", "mlflow_experiment_name"),
                    projectName
            );

            gatewayResponse.setMlflowExperimentName(mlflowExperimentName);

            String responseRegisteredModelName = firstNotBlank(
                    getText(root, "registeredModelName", "registered_model_name"),
                    getText(dataNode, "registeredModelName", "registered_model_name"),
                    getText(root, "mlflowRegisteredModelName", "mlflow_registered_model_name"),
                    getText(dataNode, "mlflowRegisteredModelName", "mlflow_registered_model_name"),
                    registeredModelName
            );

            responseRegisteredModelName = normalizeRegisteredModelName(
                    responseRegisteredModelName,
                    projectName
            );

            gatewayResponse.setRegisteredModelName(responseRegisteredModelName);

        } catch (Exception e) {
            gatewayResponse.setMessage(responseBody);
        }

        return gatewayResponse;
    }

    public KubeflowRunInfo getKubeflowRunInfo(String kubeflowRunId) {
        if (kubeflowRunId == null || kubeflowRunId.isBlank()) {
            return null;
        }

        String cleanRunId = kubeflowRunId.trim();

        List<String> candidateBaseUrls = List.of(
                stripTrailingSlash(kubeflowApiUrl),
                "http://ml-pipeline.kubeflow.svc.cluster.local:8888",
                "http://localhost:8889"
        );

        List<String> candidatePaths = List.of(
                "/apis/v2beta1/runs/" + cleanRunId,
                "/apis/v1beta1/runs/" + cleanRunId
        );

        for (String baseUrl : candidateBaseUrls) {
            if (baseUrl == null || baseUrl.isBlank()) {
                continue;
            }

            for (String path : candidatePaths) {
                String url = stripTrailingSlash(baseUrl) + path;

                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));

                    HttpEntity<Void> entity = new HttpEntity<>(headers);

                    ResponseEntity<String> response = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            String.class
                    );

                    if (!response.getStatusCode().is2xxSuccessful()) {
                        continue;
                    }

                    String body = response.getBody();

                    if (body == null || body.isBlank()) {
                        continue;
                    }

                    KubeflowRunInfo info = parseKubeflowRunInfo(body, url, cleanRunId);

                    if (info != null) {
                        return info;
                    }

                } catch (Exception e) {
                    System.out.println("[SYNC][KUBEFLOW] failed to fetch run info from " + url + ": " + e.getMessage());
                }
            }
        }

        return null;
    }

    private KubeflowRunInfo parseKubeflowRunInfo(String body, String url, String runId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode runNode = root.has("run") ? root.path("run") : root;

            String status = firstNotBlank(
                    runNode.path("status").asText(null),
                    root.path("status").asText(null),
                    root.path("state").asText(null)
            );

            String runName = firstNotBlank(
                    runNode.path("name").asText(null),
                    runNode.path("run_name").asText(null),
                    runNode.path("display_name").asText(null),
                    root.path("run_name").asText(null),
                    root.path("name").asText(null)
            );

            if (status == null && runName == null) {
                return null;
            }

            KubeflowRunInfo info = new KubeflowRunInfo();
            info.setRunId(runId);
            info.setRunName(runName);
            info.setStatus(status);
            info.setRunUrl(url);

            return info;

        } catch (Exception e) {
            return null;
        }
    }

    public boolean deleteKubeflowRun(String kubeflowRunId) {
        if (kubeflowRunId == null || kubeflowRunId.isBlank()) {
            System.out.println("deleteKubeflowRun ignored: kubeflowRunId is empty.");
            return false;
        }

        String cleanRunId = kubeflowRunId.trim();

        List<String> candidateBaseUrls = List.of(
                stripTrailingSlash(kubeflowApiUrl),
                "http://ml-pipeline.kubeflow.svc.cluster.local:8888",
                "http://localhost:8889"
        );

        List<String> candidatePaths = List.of(
                "/apis/v2beta1/runs/" + cleanRunId,
                "/apis/v1beta1/runs/" + cleanRunId
        );

        for (String baseUrl : candidateBaseUrls) {
            if (baseUrl == null || baseUrl.isBlank()) {
                continue;
            }

            for (String path : candidatePaths) {
                String url = stripTrailingSlash(baseUrl) + path;

                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));

                    HttpEntity<Void> entity = new HttpEntity<>(headers);

                    ResponseEntity<String> response = restTemplate.exchange(
                            url,
                            HttpMethod.DELETE,
                            entity,
                            String.class
                    );

                    if (response.getStatusCode().is2xxSuccessful()) {
                        return true;
                    }

                } catch (Exception e) {
                    System.out.println("Kubeflow delete failed for URL=" + url);
                    System.out.println("Reason=" + e.getMessage());
                }
            }
        }

        return false;
    }

    private String normalizeGatewayUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:8001/upload-and-run-custom-model";
        }

        String clean = value.trim();

        if (!clean.endsWith("/upload-and-run-custom-model")
                && !clean.endsWith("/launch-custom-model")) {
            clean = stripTrailingSlash(clean) + "/upload-and-run-custom-model";
        }

        return clean;
    }

    private String inferDirection(String metric) {
        if (metric == null || metric.isBlank()) {
            return "max";
        }

        String normalized = metric.trim().toLowerCase();

        if (normalized.contains("loss")
                || normalized.contains("error")
                || normalized.equals("rmse")
                || normalized.equals("mse")
                || normalized.equals("mae")) {
            return "min";
        }

        return "max";
    }

    private String normalizeRegisteredModelName(String registeredModelName, String projectName) {
        String base = cleanOrDefault(
                registeredModelName,
                buildFallbackRegisteredModelName(projectName)
        );

        String cleanName = base
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = buildFallbackRegisteredModelName(projectName);
        }

        while (cleanName.endsWith("_model_model")) {
            cleanName = cleanName.substring(0, cleanName.length() - "_model".length());
        }

        if (cleanName.endsWith("_model")) {
            return cleanName;
        }

        return cleanName + "_model";
    }

    private String buildFallbackRegisteredModelName(String projectName) {
        String baseName = cleanOrDefault(projectName, "project");

        String cleanName = baseName
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = "project";
        }

        if (cleanName.endsWith("_model")) {
            return cleanName;
        }

        return cleanName + "_model";
    }

    private String cleanOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }

        String clean = value.trim();

        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        return clean;
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

    private String getText(JsonNode root, String... names) {
        if (root == null || names == null) {
            return null;
        }

        for (String name : names) {
            if (root.hasNonNull(name)) {
                return root.get(name).asText();
            }
        }

        return null;
    }
}