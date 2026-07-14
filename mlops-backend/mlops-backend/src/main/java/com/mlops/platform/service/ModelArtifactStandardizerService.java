package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class ModelArtifactStandardizerService {

    private final MlExperimentRepository mlExperimentRepository;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.model-standardizer.enabled:true}")
    private boolean standardizerEnabled;

    @Value("${app.model-standardizer.python-command:python}")
    private String pythonCommand;

    @Value("${app.model-standardizer.script-path:scripts/standardize_model_artifact.py}")
    private String standardizerScriptPath;

    @Value("${app.model-standardizer.target-artifact-path:standardized_model}")
    private String targetArtifactPath;

    @Value("${mlflow.tracking.uri:http://mlflow-service.mlops.svc.cluster.local:5000}")
    private String mlflowTrackingUri;

    public ModelArtifactStandardizerService(
            MlExperimentRepository mlExperimentRepository,
            MlflowTrackingClientService mlflowTrackingClientService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
    }

    public StandardizationResult standardizeIfNeeded(MlExperiment experiment) {
        if (!standardizerEnabled) {
            return StandardizationResult.skipped("Model artifact standardizer disabled.");
        }

        if (experiment == null || experiment.getId() == null) {
            return StandardizationResult.skipped("Experiment is null.");
        }

        if (!hasText(experiment.getMlflowRunId())) {
            return StandardizationResult.skipped(
                    "Experiment has no MLflow run id yet. Standardization will be skipped for now."
            );
        }

        if (!hasText(experiment.getRegisteredModelName())) {
            return StandardizationResult.skipped(
                    "Experiment has no registeredModelName yet. Standardization will be skipped for now."
            );
        }

        File scriptFile = new File(standardizerScriptPath);

        if (!scriptFile.exists() || !scriptFile.isFile()) {
            return StandardizationResult.failed(
                    "Standardizer script not found: " + standardizerScriptPath
            );
        }

        try {
            List<String> command = new ArrayList<>();

            command.add(pythonCommand);
            command.add(scriptFile.getAbsolutePath());

            command.add("--mlflow_tracking_uri");
            command.add(mlflowTrackingUri);

            command.add("--run_id");
            command.add(experiment.getMlflowRunId().trim());

            command.add("--registered_model_name");
            command.add(experiment.getRegisteredModelName().trim());

            /*
             * On essaie d'abord artifact_path=model.
             * Le script Python fait lui-même un fallback vers tout le run
             * si le dossier "model" n'existe pas.
             */
            command.add("--artifact_path");
            command.add("model");

            command.add("--target_artifact_path");
            command.add(targetArtifactPath);

            command.add("--set_production_alias");
            command.add("false");

            System.out.println("[MODEL_STANDARDIZER] Starting standardization for experimentId="
                    + experiment.getId()
                    + ", runId="
                    + experiment.getMlflowRunId()
                    + ", registeredModelName="
                    + experiment.getRegisteredModelName());

            System.out.println("[MODEL_STANDARDIZER] Command=" + String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    System.out.println("[MODEL_STANDARDIZER] " + line);
                }
            }

            int exitCode = process.waitFor();
            String outputText = output.toString();

            JsonNode json = extractLastJsonObject(outputText);

            if (json == null) {
                return StandardizationResult.failed(
                        "Standardizer finished but no JSON result was detected. "
                                + "exitCode=" + exitCode
                                + ", output=" + trim(outputText)
                );
            }

            boolean success = json.path("success").asBoolean(false);
            boolean alreadyMlflowModel = json.path("already_mlflow_model").asBoolean(false);
            boolean standardized = json.path("standardized").asBoolean(false);

            String newVersion = textOrNull(json, "new_version");
            String standardizationType = textOrNull(json, "standardization_type");
            String sourceModelFile = textOrNull(json, "source_model_file");
            String message = textOrNull(json, "message");

            if (exitCode != 0 || !success) {
                String finalMessage = hasText(message)
                        ? message
                        : "Standardizer failed with exitCode=" + exitCode;

                experiment.setMessage(
                        appendMessage(
                                experiment.getMessage(),
                                "Model artifact standardization failed: " + finalMessage
                        )
                );

                mlExperimentRepository.saveAndFlush(experiment);

                return StandardizationResult.failed(finalMessage);
            }

            if (hasText(newVersion)) {
                experiment.setModelVersion(newVersion);
                // Standardization creates/updates an artifact; it must not promote it.
                experiment.setProduction(false);

                experiment.setMessage(
                        appendMessage(
                                experiment.getMessage(),
                                "Model artifact standardized automatically. "
                                        + "type=" + safe(standardizationType)
                                        + ", newVersion=" + newVersion
                        )
                );

                experiment.setErrorMessage(null);

                mlExperimentRepository.saveAndFlush(experiment);

            } else if (alreadyMlflowModel) {
                experiment.setMessage(
                        appendMessage(
                                experiment.getMessage(),
                                "Existing valid MLflow model detected. No standardization needed."
                        )
                );

                experiment.setErrorMessage(null);

                mlExperimentRepository.saveAndFlush(experiment);

            } else if (standardized) {
                experiment.setMessage(
                        appendMessage(
                                experiment.getMessage(),
                                "Model artifact standardized automatically, but new MLflow version was not detected."
                        )
                );

                experiment.setErrorMessage(null);

                mlExperimentRepository.saveAndFlush(experiment);
            }

            return StandardizationResult.success(
                    hasText(message) ? message : "Model artifact standardization completed.",
                    standardized,
                    alreadyMlflowModel,
                    newVersion,
                    standardizationType,
                    sourceModelFile
            );

        } catch (Exception e) {
            String error = "Standardizer exception: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage();

            experiment.setMessage(
                    appendMessage(
                            experiment.getMessage(),
                            "Model artifact standardization failed: " + error
                    )
            );

            mlExperimentRepository.saveAndFlush(experiment);

            return StandardizationResult.failed(error);
        }
    }

    private JsonNode extractLastJsonObject(String text) {
        if (!hasText(text)) {
            return null;
        }

        int lastEnd = text.lastIndexOf("}");

        if (lastEnd < 0) {
            return null;
        }

        /*
         * On essaie de parser depuis chaque "{" possible jusqu'au dernier "}".
         * Plus robuste que lastIndexOf("{"), car les logs peuvent contenir d'autres accolades.
         */
        for (int start = text.indexOf("{"); start >= 0 && start < lastEnd; start = text.indexOf("{", start + 1)) {
            String candidate = text.substring(start, lastEnd + 1).trim();

            try {
                return objectMapper.readTree(candidate);
            } catch (Exception ignored) {
                // On essaie l'accolade suivante.
            }
        }

        return null;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }

        JsonNode value = node.path(fieldName);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        String text = value.asText(null);

        if (!hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }

        return text.trim();
    }

    private String appendMessage(String oldMessage, String addition) {
        if (!hasText(oldMessage)) {
            return addition;
        }

        if (!hasText(addition)) {
            return oldMessage;
        }

        if (oldMessage.contains(addition)) {
            return oldMessage;
        }

        return oldMessage + " " + addition;
    }

    private String trim(String value) {
        if (value == null) {
            return "";
        }

        if (value.length() <= 3000) {
            return value;
        }

        return value.substring(0, 3000);
    }

    private String safe(String value) {
        return hasText(value) ? value : "-";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class StandardizationResult {

        private final boolean success;
        private final boolean skipped;
        private final boolean standardized;
        private final boolean alreadyMlflowModel;
        private final String newVersion;
        private final String standardizationType;
        private final String sourceModelFile;
        private final String message;

        private StandardizationResult(
                boolean success,
                boolean skipped,
                boolean standardized,
                boolean alreadyMlflowModel,
                String newVersion,
                String standardizationType,
                String sourceModelFile,
                String message
        ) {
            this.success = success;
            this.skipped = skipped;
            this.standardized = standardized;
            this.alreadyMlflowModel = alreadyMlflowModel;
            this.newVersion = newVersion;
            this.standardizationType = standardizationType;
            this.sourceModelFile = sourceModelFile;
            this.message = message;
        }

        public static StandardizationResult success(
                String message,
                boolean standardized,
                boolean alreadyMlflowModel,
                String newVersion,
                String standardizationType,
                String sourceModelFile
        ) {
            return new StandardizationResult(
                    true,
                    false,
                    standardized,
                    alreadyMlflowModel,
                    newVersion,
                    standardizationType,
                    sourceModelFile,
                    message
            );
        }

        public static StandardizationResult skipped(String message) {
            return new StandardizationResult(
                    true,
                    true,
                    false,
                    false,
                    null,
                    null,
                    null,
                    message
            );
        }

        public static StandardizationResult failed(String message) {
            return new StandardizationResult(
                    false,
                    false,
                    false,
                    false,
                    null,
                    null,
                    null,
                    message
            );
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public boolean isStandardized() {
            return standardized;
        }

        public boolean isAlreadyMlflowModel() {
            return alreadyMlflowModel;
        }

        public String getNewVersion() {
            return newVersion;
        }

        public String getStandardizationType() {
            return standardizationType;
        }

        public String getSourceModelFile() {
            return sourceModelFile;
        }

        public String getMessage() {
            return message;
        }
    }
}