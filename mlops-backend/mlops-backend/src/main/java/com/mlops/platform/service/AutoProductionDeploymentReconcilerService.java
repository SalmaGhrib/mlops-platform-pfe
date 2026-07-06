package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoProductionDeploymentReconcilerService {

    private final MlExperimentRepository mlExperimentRepository;
    private final ModelDeploymentService modelDeploymentService;
    private final ModelArtifactStandardizerService modelArtifactStandardizerService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.mlops.auto-deploy.enabled:true}")
    private boolean autoDeployEnabled;

    @Value("${app.mlops.production-reconciler.enabled:true}")
    private boolean reconcilerEnabled;

    @Value("${app.mlflow.tracking-uri:${mlflow.tracking.uri:http://mlflow-service:5000}}")
    private String localMlflowTrackingUri;

    public AutoProductionDeploymentReconcilerService(
            MlExperimentRepository mlExperimentRepository,
            ModelDeploymentService modelDeploymentService,
            ModelArtifactStandardizerService modelArtifactStandardizerService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.modelDeploymentService = modelDeploymentService;
        this.modelArtifactStandardizerService = modelArtifactStandardizerService;
    }

    @Scheduled(
            fixedDelayString = "${app.mlops.production-reconciler.delay-ms:30000}",
            initialDelayString = "${app.mlops.production-reconciler.initial-delay-ms:20000}"
    )
    public void reconcileProductionDeployments() {
        if (!autoDeployEnabled || !reconcilerEnabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            System.out.println("[PRODUCTION_RECONCILER] Previous reconciliation still running. Skip.");
            return;
        }

        try {
            System.out.println("[PRODUCTION_RECONCILER] ===================================");
            System.out.println("[PRODUCTION_RECONCILER] Start Production -> Deployment check");
            System.out.println("[PRODUCTION_RECONCILER] ===================================");

            try {
                modelDeploymentService.reconcileDeploymentsWithKubernetes();
                System.out.println("[PRODUCTION_RECONCILER] Deployment status reconciliation completed before candidate evaluation.");
            } catch (Exception e) {
                System.out.println("[PRODUCTION_RECONCILER] Deployment reconciliation failed before candidate evaluation: " + e.getMessage());
            }

            List<MlExperiment> experiments = mlExperimentRepository.findAll();

            if (experiments == null || experiments.isEmpty()) {
                System.out.println("[PRODUCTION_RECONCILER] No experiments found.");
                return;
            }

            Map<String, MlExperiment> productionCandidates = new LinkedHashMap<>();

            for (MlExperiment experiment : experiments) {
                if (!isEligibleProductionExperiment(experiment)) {
                    continue;
                }

                String key = buildCandidateKey(experiment);
                MlExperiment current = productionCandidates.get(key);

                if (current == null || isNewer(experiment, current)) {
                    productionCandidates.put(key, experiment);
                }
            }

            if (productionCandidates.isEmpty()) {
                System.out.println("[PRODUCTION_RECONCILER] No production candidates found.");
                return;
            }

            for (MlExperiment candidate : productionCandidates.values()) {
                try {
                    MlExperiment readyCandidate = prepareCandidateForDeployment(candidate);

                    if (readyCandidate == null) {
                        System.out.println("[PRODUCTION_RECONCILER] Candidate skipped because model artifact is not ready. "
                                + "experimentId=" + safeExperimentId(candidate));
                        continue;
                    }

                    System.out.println("[PRODUCTION_RECONCILER] Candidate ready:"
                            + " projectId=" + readyCandidate.getProject().getId()
                            + ", experimentId=" + readyCandidate.getId()
                            + ", model=" + readyCandidate.getRegisteredModelName()
                            + ", version=" + readyCandidate.getModelVersion()
                            + ", productionVersion=" + readyCandidate.getProductionVersion()
                    );

                    ModelDeploymentResponse response =
                            modelDeploymentService.autoDeployExperimentIfNeeded(readyCandidate);

                    if (response == null) {
                        System.out.println("[PRODUCTION_RECONCILER] No deployment action needed for experimentId="
                                + readyCandidate.getId());
                    } else {
                        System.out.println("[PRODUCTION_RECONCILER] Deployment response:"
                                + " deploymentId=" + response.getId()
                                + ", model=" + response.getRegisteredModelName()
                                + ", version=" + response.getModelVersion()
                                + ", status=" + response.getDeploymentStatus()
                                + ", active=" + response.getActive()
                        );
                    }

                } catch (Exception e) {
                    System.out.println("[PRODUCTION_RECONCILER] Failed to deploy candidate experimentId="
                            + safeExperimentId(candidate));
                    System.out.println("[PRODUCTION_RECONCILER] Reason: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            System.out.println("[PRODUCTION_RECONCILER] Finished Production -> Deployment check.");

        } catch (Exception e) {
            System.out.println("[PRODUCTION_RECONCILER] Global failure.");
            System.out.println("[PRODUCTION_RECONCILER] Reason: " + e.getMessage());
            e.printStackTrace();

        } finally {
            running.set(false);
        }
    }

    private MlExperiment prepareCandidateForDeployment(MlExperiment candidate) {
        if (candidate == null || candidate.getId() == null) {
            return null;
        }

        MlExperiment managedExperiment = mlExperimentRepository.findById(candidate.getId())
                .orElse(null);

        if (managedExperiment == null) {
            System.out.println("[PRODUCTION_RECONCILER] Candidate not found anymore. experimentId="
                    + candidate.getId());
            return null;
        }

        try {
            ModelArtifactStandardizerService.StandardizationResult result =
                    modelArtifactStandardizerService.standardizeIfNeeded(managedExperiment);

            if (result == null) {
                boolean nativeReady = acceptNativeMlflowRegistryModelIfAvailable(
                        managedExperiment,
                        "Model artifact standardization returned null."
                );

                if (!nativeReady) {
                    markArtifactNotReady(
                            managedExperiment,
                            "Model artifact standardization returned null."
                    );
                    return null;
                }

                return reloadIfStillEligible(managedExperiment.getId());
            }

            System.out.println("[PRODUCTION_RECONCILER] Standardization check result:"
                    + " experimentId=" + managedExperiment.getId()
                    + ", success=" + result.isSuccess()
                    + ", skipped=" + result.isSkipped()
                    + ", standardized=" + result.isStandardized()
                    + ", alreadyMlflowModel=" + result.isAlreadyMlflowModel()
                    + ", newVersion=" + result.getNewVersion()
                    + ", message=" + result.getMessage());

            if (result.isSuccess()) {
                MlExperiment refreshed = mlExperimentRepository.findById(managedExperiment.getId())
                        .orElse(null);

                if (refreshed == null) {
                    return null;
                }

                if (!isEligibleProductionExperiment(refreshed)) {
                    boolean nativeReady = acceptNativeMlflowRegistryModelIfAvailable(
                            refreshed,
                            "Candidate is no longer eligible after standardization."
                    );

                    if (!nativeReady) {
                        System.out.println("[PRODUCTION_RECONCILER] Candidate is no longer eligible after standardization. "
                                + "experimentId=" + refreshed.getId()
                                + ", modelVersion=" + refreshed.getModelVersion()
                                + ", productionVersion=" + refreshed.getProductionVersion()
                                + ", production=" + refreshed.getProduction());
                        return null;
                    }

                    return reloadIfStillEligible(refreshed.getId());
                }

                return refreshed;
            }

            boolean nativeReady = acceptNativeMlflowRegistryModelIfAvailable(
                    managedExperiment,
                    "Model artifact standardization failed: " + result.getMessage()
            );

            if (!nativeReady) {
                markArtifactNotReady(
                        managedExperiment,
                        "Model artifact standardization failed: " + result.getMessage()
                );
                return null;
            }

            return reloadIfStillEligible(managedExperiment.getId());

        } catch (Exception e) {
            boolean nativeReady = acceptNativeMlflowRegistryModelIfAvailable(
                    managedExperiment,
                    "Model artifact preparation failed: " + e.getMessage()
            );

            if (!nativeReady) {
                markArtifactNotReady(
                        managedExperiment,
                        "Model artifact preparation failed: " + e.getMessage()
                );
                return null;
            }

            return reloadIfStillEligible(managedExperiment.getId());
        }
    }

    private MlExperiment reloadIfStillEligible(Long experimentId) {
        if (experimentId == null) {
            return null;
        }

        MlExperiment refreshed = mlExperimentRepository.findById(experimentId)
                .orElse(null);

        if (refreshed == null) {
            return null;
        }

        if (!isEligibleProductionExperiment(refreshed)) {
            System.out.println("[PRODUCTION_RECONCILER] Candidate not eligible after MLflow Registry fallback. "
                    + "experimentId=" + refreshed.getId()
                    + ", model=" + refreshed.getRegisteredModelName()
                    + ", modelVersion=" + refreshed.getModelVersion()
                    + ", productionVersion=" + refreshed.getProductionVersion()
                    + ", production=" + refreshed.getProduction());
            return null;
        }

        return refreshed;
    }

    private boolean acceptNativeMlflowRegistryModelIfAvailable(MlExperiment experiment, String reason) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        String registeredModelName = resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)) {
            System.out.println("[PRODUCTION_RECONCILER] Native MLflow Registry fallback skipped. "
                    + "experimentId=" + experiment.getId()
                    + ", reason=no registered model name");
            return false;
        }

        registeredModelName = registeredModelName.trim();

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            System.out.println("[PRODUCTION_RECONCILER] Native MLflow Registry fallback skipped because experiment is not COMPLETED. "
                    + "experimentId=" + experiment.getId()
                    + ", status=" + experiment.getStatus());
            return false;
        }

        String productionVersion = getProductionVersionFromRegistryAlias(registeredModelName);

        if (!hasText(productionVersion)) {
            System.out.println("[PRODUCTION_RECONCILER] Native MLflow Registry fallback failed. "
                    + "experimentId=" + experiment.getId()
                    + ", registeredModelName=" + registeredModelName
                    + ", reason=" + reason);
            return false;
        }

        productionVersion = productionVersion.trim();

        String runVersion = firstNonBlank(experiment.getModelVersion());
        boolean thisRunIsProduction = hasText(runVersion)
                && productionVersion.equalsIgnoreCase(runVersion.trim());

        experiment.setRegisteredModelName(registeredModelName);
        experiment.setModelName(registeredModelName);
        experiment.setProductionVersion(productionVersion);
        experiment.setProduction(thisRunIsProduction);
        experiment.setErrorMessage(null);

        if (!thisRunIsProduction) {
            mlExperimentRepository.saveAndFlush(experiment);
            System.out.println("[PRODUCTION_RECONCILER] Native MLflow Registry fallback skipped because this run is not the production version. "
                    + "experimentId=" + experiment.getId()
                    + ", runVersion=" + runVersion
                    + ", productionVersion=" + productionVersion);
            return false;
        }

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "Native MLflow Registry model ready for deployment. "
                                + "Using models:/"
                                + registeredModelName
                                + "@production."
                )
        );

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "Model artifact ready for deployment."
                )
        );

        mlExperimentRepository.saveAndFlush(experiment);

        System.out.println("[PRODUCTION_RECONCILER] No local artifact found, "
                + "but MLflow Registry production model is ready. "
                + "Continuing deployment for experimentId="
                + experiment.getId()
                + ", registeredModelName="
                + registeredModelName
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

            if (!hasText(url)) {
                url = "http://mlflow-service:5000";
            }

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            String requestUrl = url
                    + "/api/2.0/mlflow/registered-models/alias?name="
                    + URLEncoder.encode(cleanName, StandardCharsets.UTF_8)
                    + "&alias="
                    + URLEncoder.encode("production", StandardCharsets.UTF_8);

            org.springframework.web.client.RestTemplate restTemplate =
                    new org.springframework.web.client.RestTemplate();

            org.springframework.http.HttpHeaders headers =
                    new org.springframework.http.HttpHeaders();

            headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));

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
                System.out.println("[PRODUCTION_RECONCILER] Registry alias check failed. "
                        + "registeredModelName=" + cleanName
                        + ", status=" + response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode modelVersion = root.path("model_version");

            String version = modelVersion.path("version").asText(null);

            System.out.println("[PRODUCTION_RECONCILER] Registry alias check. "
                    + "registeredModelName=" + cleanName
                    + ", alias=production"
                    + ", version=" + version
                    + ", ready=" + hasText(version));

            return hasText(version) ? version.trim() : null;

        } catch (Exception e) {
            System.out.println("[PRODUCTION_RECONCILER] Registry alias check failed. "
                    + "registeredModelName=" + cleanName
                    + ", reason=" + e.getMessage());

            return null;
        }
    }

    private boolean isEligibleProductionExperiment(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        if (experiment.getProject() == null || experiment.getProject().getId() == null) {
            System.out.println("[PRODUCTION_RECONCILER] Experiment ignored: no project. "
                    + "experimentId=" + experiment.getId());
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (!hasText(experiment.getMlflowRunId())) {
            System.out.println("[PRODUCTION_RECONCILER] Experiment ignored: missing mlflowRunId. "
                    + "experimentId=" + experiment.getId());
            return false;
        }

        String registeredModelName = resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)) {
            System.out.println("[PRODUCTION_RECONCILER] Experiment ignored: missing registered model name. "
                    + "experimentId=" + experiment.getId());
            return false;
        }

        registeredModelName = registeredModelName.trim();

        String runtimeMode = experiment.getRuntimeMode() == null
                ? ""
                : experiment.getRuntimeMode().trim().toUpperCase();

        String modelVersion = experiment.getModelVersion();
        String productionVersion = experiment.getProductionVersion();

        boolean markedProduction = Boolean.TRUE.equals(experiment.getProduction());

        boolean versionMatchesProduction =
                hasText(modelVersion)
                        && hasText(productionVersion)
                        && productionVersion.trim().equalsIgnoreCase(modelVersion.trim());

        if (markedProduction || versionMatchesProduction) {
            System.out.println("[PRODUCTION_RECONCILER] Eligible production experiment. "
                    + "experimentId=" + experiment.getId()
                    + ", model=" + registeredModelName
                    + ", modelVersion=" + modelVersion
                    + ", productionVersion=" + productionVersion
                    + ", runtimeMode=" + runtimeMode);

            return true;
        }

        boolean customDockerRuntime =
                "CUSTOM_DOCKERFILE".equals(runtimeMode)
                        || "CUSTOM_DOCKER".equals(runtimeMode)
                        || "CUSTOM_IMAGE".equals(runtimeMode)
                        || "CUSTOM_IMAGE_RUNTIME".equals(runtimeMode)
                        || "DOCKER_PERSONALIZED".equals(runtimeMode)
                        || "PERSONALIZED".equals(runtimeMode);

        if (customDockerRuntime) {
            String registryProductionVersion =
                    getProductionVersionFromRegistryAlias(registeredModelName);

            if (!hasText(registryProductionVersion)) {
                System.out.println("[PRODUCTION_RECONCILER][CUSTOM] Custom experiment ignored: no MLflow production alias. "
                        + "experimentId=" + experiment.getId()
                        + ", model=" + registeredModelName
                        + ", modelVersion=" + modelVersion
                        + ", productionVersion=" + productionVersion);

                return false;
            }

            registryProductionVersion = registryProductionVersion.trim();

            boolean thisRunIsProduction = hasText(modelVersion)
                    && registryProductionVersion.equalsIgnoreCase(modelVersion.trim());

            experiment.setRegisteredModelName(registeredModelName);
            experiment.setModelName(registeredModelName);
            experiment.setProductionVersion(registryProductionVersion);
            experiment.setProduction(thisRunIsProduction);
            experiment.setErrorMessage(null);

            if (!thisRunIsProduction) {
                mlExperimentRepository.saveAndFlush(experiment);
                System.out.println("[PRODUCTION_RECONCILER][CUSTOM] Custom experiment ignored because this run is not the MLflow production version. "
                        + "experimentId=" + experiment.getId()
                        + ", model=" + registeredModelName
                        + ", runVersion=" + modelVersion
                        + ", productionVersion=" + registryProductionVersion);
                return false;
            }

            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            "CUSTOM_DOCKERFILE production state recovered from MLflow Registry. "
                                    + "Using models:/"
                                    + registeredModelName
                                    + "/"
                                    + registryProductionVersion
                                    + " for automatic deployment."
                    )
            );

            mlExperimentRepository.saveAndFlush(experiment);

            System.out.println("[PRODUCTION_RECONCILER][CUSTOM] Eligible custom docker experiment from MLflow Registry. "
                    + "experimentId=" + experiment.getId()
                    + ", model=" + registeredModelName
                    + ", productionVersion=" + registryProductionVersion
                    + ", mlflowRunId=" + experiment.getMlflowRunId());

            return true;
        }

        System.out.println("[PRODUCTION_RECONCILER] Experiment ignored: not production candidate. "
                + "experimentId=" + experiment.getId()
                + ", model=" + registeredModelName
                + ", modelVersion=" + modelVersion
                + ", productionVersion=" + productionVersion
                + ", production=" + experiment.getProduction()
                + ", runtimeMode=" + runtimeMode);

        return false;
    }

    private String buildCandidateKey(MlExperiment experiment) {
        if (experiment == null) {
            return "null|null|null";
        }

        Long projectId = experiment.getProject() != null
                ? experiment.getProject().getId()
                : null;

        String modelName = resolveRegisteredModelName(experiment);

        String version = hasText(experiment.getProductionVersion())
                ? experiment.getProductionVersion()
                : experiment.getModelVersion();

        return String.valueOf(projectId)
                + "|"
                + clean(modelName)
                + "|"
                + clean(version);
    }

    private boolean isNewer(MlExperiment left, MlExperiment right) {
        LocalDateTime leftDate = left.getUpdatedAt() != null ? left.getUpdatedAt() : left.getCreatedAt();
        LocalDateTime rightDate = right.getUpdatedAt() != null ? right.getUpdatedAt() : right.getCreatedAt();

        if (leftDate == null && rightDate == null) {
            return left.getId() != null && right.getId() != null && left.getId() > right.getId();
        }

        if (leftDate != null && rightDate == null) {
            return true;
        }

        if (leftDate == null) {
            return false;
        }

        return leftDate.isAfter(rightDate);
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

    private void markArtifactNotReady(MlExperiment experiment, String reason) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        boolean nativeReady = acceptNativeMlflowRegistryModelIfAvailable(experiment, reason);

        if (nativeReady) {
            System.out.println("[PRODUCTION_RECONCILER] Artifact fallback succeeded. Deployment will not be blocked. "
                    + "experimentId=" + experiment.getId());
            return;
        }

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "Model artifact not ready for deployment. Reason: " + reason
                )
        );

        experiment.setProduction(false);
        experiment.setProductionVersion(null);

        mlExperimentRepository.saveAndFlush(experiment);

        System.out.println("[PRODUCTION_RECONCILER] Artifact not ready. Deployment blocked. "
                + "experimentId=" + experiment.getId()
                + ", reason=" + reason);
    }

    private String safeExperimentId(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return "null";
        }

        return String.valueOf(experiment.getId());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }

        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String appendSafeMessage(String oldMessage, String addition) {
        String base = oldMessage == null ? "" : oldMessage.trim();

        if (addition == null || addition.isBlank()) {
            return limit(base, 3000);
        }

        if (base.contains(addition)) {
            return limit(base, 3000);
        }

        String result = base.isBlank() ? addition : base + " " + addition;

        return limit(result, 3000);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String cleaned = value
                .replace("\u0000", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();

        if (cleaned.length() <= maxLength) {
            return cleaned;
        }

        return cleaned.substring(0, maxLength - 16) + "... [truncated]";
    }
}