package com.mlops.platform.service;

import com.mlops.platform.dto.DeployModelRequestDto;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.dto.ModelDeploymentShareResponse;
import com.mlops.platform.dto.SearchPageResponse;
import com.mlops.platform.dto.ShareDeploymentRequest;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.ModelDeploymentShare;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Service
public class ModelDeploymentService {

    /*
     * Safety: MySQL columns like error_message are often limited.
     * Never store huge Docker/Kubernetes/MLflow logs directly in DB.
     */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    /*
     * Dependencies already expected in the generic serving image.
     * If the model only needs these packages, we avoid building a dedicated image.
     */
    private static final List<String> STANDARD_SERVING_PACKAGES = List.of(
            "pandas",
            "numpy",
            "scikit-learn",
            "sklearn",
            "scipy",
            "joblib",
            "cloudpickle",
            "mlflow",
            "pyarrow",
            "fastapi",
            "uvicorn",
            "python-multipart",
            "pydantic",
            "packaging"
    );

    /*
     * Dependencies that usually require a dedicated serving image.
     */
    private static final List<String> CUSTOM_SERVING_PACKAGES = List.of(
            "xgboost",
            "lightgbm",
            "catboost",
            "imbalanced-learn",
            "imblearn",
            "tensorflow",
            "keras",
            "torch",
            "torchvision",
            "torchaudio",
            "opencv-python",
            "opencv-contrib-python",
            "onnxruntime",
            "transformers",
            "sentence-transformers",
            "spacy",
            "nltk",
            "gensim",
            "statsmodels",
            "prophet",
            "pmdarima",
            "shap",
            "lime"
    );

    private final ModelDeploymentRepository modelDeploymentRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final KubernetesModelServingService kubernetesModelServingService;
    private final ModelServingImageBuildService modelServingImageBuildService;
    private final ModelDeploymentShareRepository modelDeploymentShareRepository;
    private final UserRepository userRepository;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final MlProjectRepository mlProjectRepository;
    private final ModelArtifactStandardizerService modelArtifactStandardizerService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final ModelApiKeyRepository modelApiKeyRepository;

    @Value("${custom.predict.api.url:http://localhost:8000}")
    private String predictApiUrl;

    @Value("${app.kubernetes.namespace:mlops}")
    private String namespaceName;

    @Value("${app.serving.dedicated-image.enabled:true}")
    private boolean dedicatedServingImageEnabled;

    @Value("${app.deployment.default-docker-image:acrmlopssalma.azurecr.io/mlops-generic-model-server:v6}")
    private String defaultDockerImage;

    @Value("${app.mlops.auto-deploy.enabled:true}")
    private boolean autoDeployEnabled;

    @Value("${app.kubernetes.rollback-window-ms:600000}")
    private long rollbackWindowMs;

    @Value("${app.kubernetes.prediction-smoke-test.enabled:true}")
    private boolean predictionSmokeTestEnabled;

    public ModelDeploymentService(
            ModelDeploymentRepository modelDeploymentRepository,
            MlExperimentRepository mlExperimentRepository,
            KubernetesModelServingService kubernetesModelServingService,
            ModelServingImageBuildService modelServingImageBuildService,
            ModelDeploymentShareRepository modelDeploymentShareRepository,
            UserRepository userRepository,
            MlflowTrackingClientService mlflowTrackingClientService,
            MlProjectRepository mlProjectRepository,
            ModelArtifactStandardizerService modelArtifactStandardizerService,
            NotificationService notificationService,
            PlatformTransactionManager transactionManager,
            ModelApiKeyRepository modelApiKeyRepository
    ) {
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.kubernetesModelServingService = kubernetesModelServingService;
        this.modelServingImageBuildService = modelServingImageBuildService;
        this.modelDeploymentShareRepository = modelDeploymentShareRepository;
        this.userRepository = userRepository;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.mlProjectRepository = mlProjectRepository;
        this.modelArtifactStandardizerService = modelArtifactStandardizerService;
        this.notificationService = notificationService;
        this.transactionManager = transactionManager;
        this.modelApiKeyRepository = modelApiKeyRepository;
    }

@Transactional
public ModelDeploymentResponse autoDeployExperimentIfNeeded(MlExperiment experiment) {
    if (!autoDeployEnabled) {
        return null;
    }

    if (experiment == null || experiment.getId() == null) {
        return null;
    }

    /*
     * Important:
     * The experiment passed by the production reconciler may be detached from Hibernate.
     * If we call experiment.getProject() directly, Hibernate can throw:
     * Could not initialize proxy [MlProject#...] - no session.
     *
     * So we reload the experiment inside this transactional method and use the managed entity.
     */
    MlExperiment managedExperiment = mlExperimentRepository.findById(experiment.getId())
            .orElse(null);

    if (managedExperiment == null) {
        System.out.println("[AUTO_DEPLOY] Experiment not found anymore. experimentId=" + experiment.getId());
        return null;
    }

    Long projectId = null;
    try {
        if (managedExperiment.getProject() != null) {
            projectId = managedExperiment.getProject().getId();
        }
    } catch (Exception e) {
        System.out.println("[AUTO_DEPLOY] Cannot read project from managed experiment. experimentId="
                + managedExperiment.getId()
                + ", reason="
                + e.getMessage());
        return null;
    }

    if (projectId == null) {
        System.out.println("[AUTO_DEPLOY] Experiment has no project. experimentId=" + managedExperiment.getId());
        return null;
    }

    if (!isValidProductionExperimentForDeployment(managedExperiment)) {
        System.out.println("[AUTO_DEPLOY] Skip deployment because experiment is not a valid scored Production candidate. "
                + "experimentId=" + managedExperiment.getId()
                + ", status=" + managedExperiment.getStatus()
                + ", modelVersion=" + managedExperiment.getModelVersion()
                + ", primaryMetricValue=" + managedExperiment.getPrimaryMetricValue()
                + ", globalScore=" + managedExperiment.getGlobalScore()
                + ", isProduction=" + managedExperiment.getProduction());
        return null;
    }

    String registeredModelName = firstNonBlank(
            managedExperiment.getRegisteredModelName(),
            managedExperiment.getModelName()
    );
    String modelVersion = firstNonBlank(managedExperiment.getModelVersion());
    String productionVersion = firstNonBlank(managedExperiment.getProductionVersion());

    if (!hasText(registeredModelName)) {
        return null;
    }

if (!hasText(modelVersion)) {
    System.out.println("[AUTO_DEPLOY] Skip deployment because experiment modelVersion is empty. "
            + "experimentId=" + managedExperiment.getId()
            + ", model=" + registeredModelName
            + ". This prevents overwriting historical runs with the MLflow production alias.");
    return null;
}

    registeredModelName = registeredModelName.trim();
    modelVersion = modelVersion.trim();

    boolean markedProduction = Boolean.TRUE.equals(managedExperiment.getProduction());
    boolean versionIsProduction = hasText(productionVersion)
            && productionVersion.trim().equalsIgnoreCase(modelVersion);

    if (!markedProduction && !versionIsProduction) {
    String runtimeMode = managedExperiment.getRuntimeMode() == null
            ? ""
            : managedExperiment.getRuntimeMode().trim().toUpperCase();

    boolean customDocker =
            "CUSTOM_DOCKERFILE".equals(runtimeMode)
                    || "CUSTOM_DOCKER".equals(runtimeMode)
                    || "CUSTOM_IMAGE".equals(runtimeMode)
                    || "CUSTOM_IMAGE_RUNTIME".equals(runtimeMode)
                    || "DOCKER_PERSONALIZED".equals(runtimeMode)
                    || "PERSONALIZED".equals(runtimeMode);

    if (customDocker) {
        try {
            String registryProductionVersion =
                    mlflowTrackingClientService.getProductionVersion(registeredModelName.trim());

            if (hasText(registryProductionVersion)) {
                productionVersion = registryProductionVersion.trim();

                managedExperiment.setRegisteredModelName(registeredModelName.trim());
                managedExperiment.setModelName(registeredModelName.trim());
                managedExperiment.setProductionVersion(productionVersion);
                managedExperiment.setProduction(productionVersion.equalsIgnoreCase(modelVersion.trim()));
                managedExperiment.setErrorMessage(null);

                managedExperiment = mlExperimentRepository.saveAndFlush(managedExperiment);

                if (!productionVersion.equalsIgnoreCase(modelVersion.trim())) {
                    System.out.println("[AUTO_DEPLOY][CUSTOM] Skip deployment because this run is not the MLflow production version. "
                            + "experimentId=" + managedExperiment.getId()
                            + ", model=" + registeredModelName
                            + ", runVersion=" + modelVersion
                            + ", productionVersion=" + productionVersion);
                    return null;
                }

                System.out.println("[AUTO_DEPLOY][CUSTOM] Production state confirmed from MLflow Registry. "
                        + "experimentId=" + managedExperiment.getId()
                        + ", model=" + registeredModelName
                        + ", version=" + modelVersion);
            } else {
                System.out.println("[AUTO_DEPLOY][CUSTOM] Skip deployment: no MLflow production alias found. "
                        + "experimentId=" + managedExperiment.getId()
                        + ", model=" + registeredModelName);
                return null;
            }

        } catch (Exception e) {
            System.out.println("[AUTO_DEPLOY][CUSTOM] Skip deployment: cannot read MLflow production alias. "
                    + "experimentId=" + managedExperiment.getId()
                    + ", model=" + registeredModelName
                    + ", reason=" + e.getMessage());
            return null;
        }
    } else {
        return null;
    }
}
    ModelDeployment existingSameVersion = findLatestDeploymentForModelVersion(
            registeredModelName,
            modelVersion
    );

    /*
     * Same model/version is already DEPLOYED and really ready in Kubernetes.
     * Nothing to rebuild or recreate.
     */
    if (existingSameVersion != null
            && DeploymentStatus.DEPLOYED.equals(existingSameVersion.getDeploymentStatus())
            && isDeploymentReallyReadyInKubernetes(existingSameVersion)) {

        ensureApiAccessEnabled(existingSameVersion);
        ModelDeployment saved = modelDeploymentRepository.saveAndFlush(existingSameVersion);
        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(saved));
    }

    /*
     * Same model/version is already DEPLOYING.
     * - If resources exist but are not ready, we wait.
     * - If resources are ready, we promote the candidate.
     * - If resources do not exist anymore, we retry using the same DB row.
     */
    if (existingSameVersion != null
            && DeploymentStatus.DEPLOYING.equals(existingSameVersion.getDeploymentStatus())) {

        boolean resourcesExist = false;
        try {
            resourcesExist = kubernetesModelServingService.modelServerResourcesExist(
                    existingSameVersion.getDeploymentName(),
                    existingSameVersion.getServiceName()
            );
        } catch (Exception e) {
            System.out.println("[AUTO_DEPLOY] Could not verify existing DEPLOYING Kubernetes resources. "
                    + "model=" + registeredModelName
                    + ", version=" + modelVersion
                    + ", reason=" + e.getMessage());
        }

        boolean ready = resourcesExist && isDeploymentReallyReadyInKubernetes(existingSameVersion);

        if (ready) {
            ModelDeployment deployed = promoteReadyDeployment(
                    existingSameVersion,
                    projectId,
                    resolveApiAccessConfigForDeployment(registeredModelName)
            );

            return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(deployed));
        }

        if (resourcesExist) {
            System.out.println("[AUTO_DEPLOY] Deployment already in progress. Skip duplicate auto-deploy. "
                    + "model=" + registeredModelName
                    + ", version=" + modelVersion
                    + ", deploymentId=" + existingSameVersion.getId());

            return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(existingSameVersion));
        }

        System.out.println("[AUTO_DEPLOY] Existing DEPLOYING row has no Kubernetes resources. Retrying deployment. "
                + "model=" + registeredModelName
                + ", version=" + modelVersion
                + ", deploymentId=" + existingSameVersion.getId());

        existingSameVersion.setDeploymentStatus(DeploymentStatus.PENDING);
        existingSameVersion.setActive(false);
        existingSameVersion.setErrorMessage(safeErrorMessage(
                "Previous DEPLOYING state had no Kubernetes resources. Auto-deploy will retry."
        ));
        modelDeploymentRepository.saveAndFlush(existingSameVersion);
    }

    /*
     * We do not permanently block FAILED candidates.
     * If Docker/Kubernetes/config was fixed, deployProductionModel will reuse the existing row.
     */
    System.out.println("[AUTO_DEPLOY] Deploying production model automatically: projectId="
            + projectId
            + ", experimentId=" + managedExperiment.getId()
            + ", model=" + registeredModelName
            + ", version=" + modelVersion);

    DeployModelRequestDto request = new DeployModelRequestDto();
    request.setProjectId(projectId);
    request.setExperimentId(managedExperiment.getId());
    request.setRegisteredModelName(registeredModelName);
    request.setModelVersion(modelVersion);

    return deployProductionModel(request);
}



    @Transactional(readOnly = true)
    public List<ModelDeploymentShareResponse> getDeploymentShares(Long deploymentId) {
        return modelDeploymentShareRepository.findByDeploymentId(deploymentId)
                .stream()
                .map(ModelDeploymentShareResponse::fromEntity)
                .toList();
    }

    @Transactional
    public List<ModelDeploymentShareResponse> shareDeploymentWithUsers(
            Long deploymentId,
            ShareDeploymentRequest request
    ) {
        ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.NOT_FOUND,
                        "MODEL_NOT_FOUND",
                        "Deploiement introuvable avec id=" + deploymentId
                ));

        deployment.setVisibility(DeploymentVisibility.SHARED);
        deployment.setApiEnabled(true);

        if (deployment.getApiKey() == null || deployment.getApiKey().isBlank()) {
            deployment.setApiKey(generateUniqueApiKey());
            deployment.setApiKeyCreatedAt(LocalDateTime.now());
            deployment.setApiKeyLastUsedAt(null);
        }

        modelDeploymentRepository.saveAndFlush(deployment);

        List<Long> newUserIds = request == null || request.getUserIds() == null
                ? List.of()
                : request.getUserIds().stream()
                    .filter(id -> id != null)
                    .distinct()
                    .toList();

        Set<Long> newUserSet = new HashSet<>(newUserIds);

        List<ModelDeploymentShare> existingShares =
                modelDeploymentShareRepository.findByDeploymentId(deploymentId);

        Set<Long> existingUserIds = existingShares.stream()
                .map(ModelDeploymentShare::getSharedWithUserId)
                .collect(Collectors.toSet());

        List<ModelDeploymentShare> toRemove = existingShares.stream()
                .filter(share -> !newUserSet.contains(share.getSharedWithUserId()))
                .toList();

        for (ModelDeploymentShare share : toRemove) {
            disableKeysForRemovedUser(deploymentId, share.getSharedWithUserId());
            modelDeploymentShareRepository.delete(share);
        }

        for (Long userId : newUserIds) {
            if (!existingUserIds.contains(userId)) {
                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new GatewayException(
                                HttpStatus.NOT_FOUND,
                                "USER_NOT_FOUND",
                                "Utilisateur introuvable avec id=" + userId
                        ));

                ModelDeploymentShare share = new ModelDeploymentShare();
                share.setDeploymentId(deploymentId);
                share.setSharedWithUserId(user.getId());
                share.setPermission(
                        request.getPermission() == null || request.getPermission().isBlank()
                                ? "PREDICT_ONLY"
                                : request.getPermission()
                );
                modelDeploymentShareRepository.save(share);
            }
        }

        modelDeploymentShareRepository.flush();
        return getDeploymentShares(deploymentId);
    }

    private void disableKeysForRemovedUser(Long deploymentId, Long userId) {
        modelApiKeyRepository.findByDeploymentIdAndOwnerId(deploymentId, userId)
                .ifPresent(key -> {
                    if (Boolean.TRUE.equals(key.getEnabled())) {
                        key.setEnabled(false);
                        modelApiKeyRepository.save(key);
                        System.out.println("[SHARED] API key disabled: keyId=" + key.getId()
                                + " userId=" + userId + " deploymentId=" + deploymentId);
                    }
                });
    }

    @Transactional
    public void removeDeploymentShare(Long deploymentId, Long userId) {
        disableKeysForRemovedUser(deploymentId, userId);
        modelDeploymentShareRepository.deleteByDeploymentIdAndSharedWithUserId(deploymentId, userId);
    }

    private static class ApiAccessConfig {
        private final boolean apiEnabled;
        private final DeploymentVisibility visibility;
        private final String apiKey;
        private final LocalDateTime apiKeyCreatedAt;
        private final LocalDateTime apiKeyLastUsedAt;

        private ApiAccessConfig(
                boolean apiEnabled,
                DeploymentVisibility visibility,
                String apiKey,
                LocalDateTime apiKeyCreatedAt,
                LocalDateTime apiKeyLastUsedAt
        ) {
            this.apiEnabled = apiEnabled;
            this.visibility = visibility;
            this.apiKey = apiKey;
            this.apiKeyCreatedAt = apiKeyCreatedAt;
            this.apiKeyLastUsedAt = apiKeyLastUsedAt;
        }
    }

    private ApiAccessConfig resolveApiAccessConfigForDeployment(String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return newPublicApiAccessConfig();
        }

        return modelDeploymentRepository
                .findTopByRegisteredModelNameAndApiEnabledTrueAndApiKeyIsNotNullOrderByUpdatedAtDesc(
                        registeredModelName.trim()
                )
                .map(existing -> new ApiAccessConfig(
                        existing.getApiEnabled() != null ? existing.getApiEnabled() : false,
                        existing.getVisibility() != null
                                ? existing.getVisibility()
                                : DeploymentVisibility.PRIVATE,
                        existing.getApiKey(),
                        existing.getApiKeyCreatedAt() != null
                                ? existing.getApiKeyCreatedAt()
                                : LocalDateTime.now(),
                        existing.getApiKeyLastUsedAt()
                ))
                .orElseGet(this::newPublicApiAccessConfig);
    }

    private ApiAccessConfig newPublicApiAccessConfig() {
        return new ApiAccessConfig(
                false,
                DeploymentVisibility.PRIVATE,
                generateUniqueApiKey(),
                LocalDateTime.now(),
                null
        );
    }

    private void applyApiAccessConfig(ModelDeployment deployment, ApiAccessConfig apiConfig) {
        if (deployment == null || apiConfig == null) {
            return;
        }

        deployment.setApiEnabled(apiConfig.apiEnabled);

        if (apiConfig.visibility != null) {
            deployment.setVisibility(apiConfig.visibility);
        } else if (deployment.getVisibility() == null) {
            deployment.setVisibility(DeploymentVisibility.PRIVATE);
        }

        /*
         * api_key is UNIQUE in MySQL. A blue/green candidate is initially a new
         * database row, so copying the active row's key causes Duplicate entry.
         * Existing rows keep their key; new candidates receive a unique key.
         * The stable external endpoint remains the Kubernetes Service, while the
         * active row becomes authoritative after readiness.
         */
        boolean isNewRow = deployment.getId() == null;
        boolean requestedKeyAlreadyOwned = apiConfig.apiKey != null
                && !apiConfig.apiKey.isBlank()
                && modelDeploymentRepository.existsByApiKey(apiConfig.apiKey);

        if (isNewRow && requestedKeyAlreadyOwned) {
            deployment.setApiKey(generateUniqueApiKey());
            deployment.setApiKeyCreatedAt(LocalDateTime.now());
            deployment.setApiKeyLastUsedAt(null);
        } else {
            deployment.setApiKey(apiConfig.apiKey);
            deployment.setApiKeyCreatedAt(apiConfig.apiKeyCreatedAt);
            deployment.setApiKeyLastUsedAt(apiConfig.apiKeyLastUsedAt);
        }
    }

    private boolean isNativeMlflowRegistryModelReadyForDeployment(MlExperiment experiment) {
    if (experiment == null || experiment.getId() == null) {
        return false;
    }

    String registeredModelName = firstNonBlank(
            experiment.getRegisteredModelName(),
            experiment.getModelName()
    );

    if (!hasText(registeredModelName)) {
        System.out.println("[MODEL_DEPLOYMENT] Native Registry check skipped: registeredModelName empty. "
                + "experimentId=" + experiment.getId());
        return false;
    }

    registeredModelName = registeredModelName.trim();

    String productionVersion = null;

    try {
        productionVersion = mlflowTrackingClientService.getProductionVersion(registeredModelName);
    } catch (Exception e) {
        System.out.println("[MODEL_DEPLOYMENT] Could not read MLflow production alias for "
                + registeredModelName
                + ". reason="
                + e.getMessage());
    }

    if (!hasText(productionVersion)) {
        System.out.println("[MODEL_DEPLOYMENT] Native Registry check failed: no production alias. "
                + "experimentId=" + experiment.getId()
                + ", registeredModelName=" + registeredModelName);
        return false;
    }

    productionVersion = productionVersion.trim();

    String modelVersion = firstNonBlank(experiment.getModelVersion());

    if (!hasText(modelVersion)) {
        experiment.setRegisteredModelName(registeredModelName);
        experiment.setModelName(registeredModelName);
        experiment.setProductionVersion(productionVersion);
        experiment.setProduction(false);
        mlExperimentRepository.saveAndFlush(experiment);

        System.out.println("[MODEL_DEPLOYMENT] Native Registry check skipped: experiment modelVersion is empty. "
                + "experimentId=" + experiment.getId()
                + ", registeredModelName=" + registeredModelName
                + ", productionVersion=" + productionVersion);
        return false;
    }

    modelVersion = modelVersion.trim();

    boolean thisRunIsProduction = modelVersion.equalsIgnoreCase(productionVersion);

    experiment.setRegisteredModelName(registeredModelName);
    experiment.setModelName(registeredModelName);
    experiment.setProductionVersion(productionVersion);
    experiment.setProduction(thisRunIsProduction);
    experiment.setErrorMessage(null);

    if (!thisRunIsProduction) {
        mlExperimentRepository.saveAndFlush(experiment);
        System.out.println("[MODEL_DEPLOYMENT] Native Registry check skipped: this run is not the production version. "
                + "experimentId=" + experiment.getId()
                + ", registeredModelName=" + registeredModelName
                + ", runVersion=" + modelVersion
                + ", productionVersion=" + productionVersion);
        return false;
    }

    experiment.setMessage(
            appendDeploymentMessage(
                    experiment.getMessage(),
                    "Native MLflow Registry model ready for deployment. "
                            + "Using models:/"
                            + registeredModelName
                            + "/"
                            + modelVersion
                            + "."
            )
    );

    mlExperimentRepository.saveAndFlush(experiment);

    System.out.println("[MODEL_DEPLOYMENT] Native MLflow Registry model is ready. "
            + "experimentId=" + experiment.getId()
            + ", registeredModelName=" + registeredModelName
            + ", modelVersion=" + modelVersion
            + ", productionVersion=" + productionVersion);

    return true;
}
    private void ensureApiAccessEnabled(ModelDeployment deployment) {
        if (deployment == null) {
            return;
        }

        if (Boolean.TRUE.equals(deployment.getApiEnabled())
                && deployment.getApiKey() != null
                && !deployment.getApiKey().isBlank()
                && deployment.getVisibility() != null
                && deployment.getVisibility() != DeploymentVisibility.PRIVATE) {
            return;
        }

        ApiAccessConfig apiAccessConfig = resolveApiAccessConfigForDeployment(deployment.getRegisteredModelName());
        applyApiAccessConfig(deployment, apiAccessConfig);
    }

@Transactional
public ModelDeploymentResponse deployProductionModel(DeployModelRequestDto request) {
    validateDeployRequest(request);

    MlExperiment experiment = mlExperimentRepository.findById(request.getExperimentId())
            .orElseThrow(() -> new RuntimeException(
                    "Expérience introuvable avec id = " + request.getExperimentId()
            ));

    if (experiment.getProject() == null || experiment.getProject().getId() == null) {
        throw new RuntimeException("L'expérience n'est liée à aucun projet.");
    }

    Long effectiveProjectId = request.getProjectId();

    if (effectiveProjectId == null) {
        effectiveProjectId = experiment.getProject().getId();
    }

    if (!experiment.getProject().getId().equals(effectiveProjectId)) {
        throw new RuntimeException("L'expérience ne correspond pas au projet demandé.");
    }

    String registeredModelName = firstNonBlank(
            request.getRegisteredModelName(),
            experiment.getRegisteredModelName()
    );

    String modelVersion = firstNonBlank(
            request.getModelVersion(),
            experiment.getModelVersion(),
            experiment.getProductionVersion()
    );

    if (!hasText(registeredModelName)) {
        throw new RuntimeException("registeredModelName est obligatoire.");
    }

    if (!hasText(modelVersion)) {
        throw new RuntimeException("modelVersion est obligatoire.");
    }

    registeredModelName = registeredModelName.trim();
    modelVersion = modelVersion.trim();

    boolean experimentMarkedProduction = Boolean.TRUE.equals(experiment.getProduction());

    boolean versionMatchesProduction =
            hasText(experiment.getProductionVersion())
                    && experiment.getProductionVersion().trim().equalsIgnoreCase(modelVersion);

    if (!experimentMarkedProduction && !versionMatchesProduction) {
        throw new RuntimeException(
                "Ce modèle n'est pas marqué comme production. "
                        + "Promouvez d'abord la bonne version dans le Model Registry."
        );
    }

    /*
     * Barrière obligatoire avant Kubernetes :
     * la version à déployer doit être un vrai modèle MLflow standardisé.
     */
    experiment = ensureExperimentArtifactReadyForDeployment(experiment);

    registeredModelName = firstNonBlank(
            experiment.getRegisteredModelName(),
            registeredModelName
    );

    modelVersion = firstNonBlank(
            experiment.getModelVersion(),
            experiment.getProductionVersion(),
            modelVersion
    );

    if (!hasText(registeredModelName)) {
        throw new RuntimeException("registeredModelName est obligatoire après standardisation.");
    }

    if (!hasText(modelVersion)) {
        throw new RuntimeException("modelVersion est obligatoire après standardisation.");
    }

    registeredModelName = registeredModelName.trim();
    modelVersion = modelVersion.trim();


    ApiAccessConfig apiAccessConfig = resolveApiAccessConfigForDeployment(registeredModelName);

    String safeModelName = sanitizeKubernetesName(registeredModelName);
    String safeVersion = sanitizeName(modelVersion);

    String deploymentName = safeModelName + "-v" + safeVersion + "-deployment";
    String serviceName = safeModelName + "-v" + safeVersion + "-service";

    /*
     * Blue/green :
     * - deploymentName/serviceName sont propres à la version (conservés pour
     *   la compatibilité du SMART MODEL GATEWAY et des endpoints ClusterIP).
     * - stableServiceName est le Service stable du modèle (ex. detection-spam-service),
     *   indépendant de la version, dont le selector bascule blue/green.
     */
    String stableServiceName = safeModelName + "-service";

    String candidateServingTrack = resolveCandidateServingTrack(registeredModelName);
    String stableServiceTargetTrack = resolveStableServiceTargetTrack(
            registeredModelName,
            candidateServingTrack
    );

    ModelDeployment existingSameVersion = findLatestDeploymentForModelVersion(
            registeredModelName,
            modelVersion
    );

    /*
     * Cas 1 : même version déjà DEPLOYED et prête.
     */
    if (existingSameVersion != null
            && DeploymentStatus.DEPLOYED.equals(existingSameVersion.getDeploymentStatus())
            && isDeploymentReallyReadyInKubernetes(existingSameVersion)) {

        ModelDeployment deployed = promoteReadyDeployment(
                existingSameVersion,
                effectiveProjectId,
                apiAccessConfig
        );

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(deployed));
    }

    /*
     * Cas 2 : même version déjà DEPLOYING.
     * On ne recrée pas la ligne et on ne relance pas Kubernetes.
     */
   if (existingSameVersion != null
            && (DeploymentStatus.DEPLOYING.equals(existingSameVersion.getDeploymentStatus())
            || DeploymentStatus.OUT_OF_SYNC.equals(existingSameVersion.getDeploymentStatus()))) {

    boolean resourcesExist = false;

    try {
        resourcesExist = kubernetesModelServingService.modelServerResourcesExist(
                existingSameVersion.getDeploymentName(),
                existingSameVersion.getServiceName()
        );
    } catch (Exception e) {
        System.out.println("[MODEL_DEPLOYMENT] Could not verify DEPLOYING/OUT_OF_SYNC Kubernetes resources. "
                + "model=" + registeredModelName
                + ", version=" + modelVersion
                + ", deploymentId=" + existingSameVersion.getId()
                + ", reason=" + e.getMessage());
    }

    boolean ready = resourcesExist && isDeploymentReallyReadyInKubernetes(existingSameVersion);

    if (ready) {
        ModelDeployment deployed = promoteReadyDeployment(
                existingSameVersion,
                effectiveProjectId,
                apiAccessConfig
        );

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(deployed));
    }

    /*
     * Correction importante :
     * Avant, si une ancienne ligne DEPLOYING existait mais sans ressources Kubernetes,
     * le code retournait directement et ne recréait jamais le deployment.
     *
     * Maintenant :
     * - si les ressources existent, on attend.
     * - si elles n'existent pas, on remet la ligne en PENDING et on relance le déploiement.
     */
    if (resourcesExist) {
        System.out.println("[MODEL_DEPLOYMENT] Deployment already in progress and resources exist. "
                + "Waiting for readiness. "
                + "model=" + registeredModelName
                + ", version=" + modelVersion
                + ", deploymentId=" + existingSameVersion.getId());

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(existingSameVersion));
    }

    System.out.println("[MODEL_DEPLOYMENT] Existing DEPLOYING row has no Kubernetes resources. "
            + "Retrying deployment now. "
            + "model=" + registeredModelName
            + ", version=" + modelVersion
            + ", deploymentId=" + existingSameVersion.getId());

    existingSameVersion.setDeploymentStatus(DeploymentStatus.PENDING);
    existingSameVersion.setActive(false);
    existingSameVersion.setErrorMessage(safeErrorMessage(
            "Previous DEPLOYING state had no Kubernetes resources. Deployment will be retried."
    ));

    modelDeploymentRepository.saveAndFlush(existingSameVersion);
}

    /*
     * Cas 3 : retry d'une candidate FAILED / OUT_OF_SYNC / PENDING.
     * On supprime uniquement les ressources Kubernetes de cette version candidate.
     * L'ancienne version active reste intacte.
     */
    boolean retryExistingCandidate = existingSameVersion != null
            && (
            DeploymentStatus.FAILED.equals(existingSameVersion.getDeploymentStatus())
                    || DeploymentStatus.OUT_OF_SYNC.equals(existingSameVersion.getDeploymentStatus())
                    || DeploymentStatus.PENDING.equals(existingSameVersion.getDeploymentStatus())
    );

    if (retryExistingCandidate) {
        try {
            kubernetesModelServingService.deleteModelServer(deploymentName, serviceName);

            System.out.println("[BLUE_GREEN] Deleted previous candidate Kubernetes resources before retry. "
                    + "deployment=" + deploymentName
                    + ", service=" + serviceName);
        } catch (Exception e) {
            System.out.println("[BLUE_GREEN] Candidate cleanup before retry ignored. "
                    + "deployment=" + deploymentName
                    + ", service=" + serviceName
                    + ", reason=" + e.getMessage());
        }
    }

    ModelDeployment deployment;

    if (existingSameVersion != null) {
        deployment = existingSameVersion;
    } else {
        deployment = new ModelDeployment();
        deployment.setRegisteredModelName(registeredModelName);
        deployment.setModelVersion(modelVersion);
    }

    deployment.setProjectId(effectiveProjectId);
    deployment.setExperimentId(experiment.getId());
    deployment.setMlflowRunId(experiment.getMlflowRunId());
    deployment.setKubeflowRunId(experiment.getKubeflowRunId());
    deployment.setDeploymentName(deploymentName);
    deployment.setServiceName(serviceName);
    deployment.setNamespaceName(namespaceName);
    deployment.setPredictionEndpoint(null);
    deployment.setDeploymentStatus(DeploymentStatus.DEPLOYING);
    deployment.setActive(false);
    deployment.setServingTrack(candidateServingTrack);
    deployment.setStableServiceName(stableServiceName);
    deployment.setErrorMessage(null);

    applyApiAccessConfig(deployment, apiAccessConfig);

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    notifyDeploymentStartedIfNeeded(saved);

    try {
        String dataType = resolveDeploymentDataType(experiment);

        /*
         * Nouvelle règle stricte pour éviter les crashs de serving :
         *
         * STANDARD / STANDARD_RUNTIME
         *      -> image standard uniquement.
         *
         * CUSTOM_DOCKERFILE / CUSTOM_DOCKER / CUSTOM_IMAGE
         *      -> image dédiée générée pour ce modèle uniquement.
         *
         * Important :
         * On ne compare plus les requirements avec l'image standard.
         * Même si les packages ont les mêmes noms, les versions peuvent être différentes.
         */
        String imageToDeploy = resolveServingImageForRuntime(
                experiment,
                registeredModelName,
                modelVersion,
                dataType
        );

        System.out.println("[MODEL_DEPLOYMENT] Final serving image selected: "
                + "experimentId=" + experiment.getId()
                + ", model=" + registeredModelName
                + ", version=" + modelVersion
                + ", runtimeMode=" + safeRuntimeMode(experiment)
                + ", image=" + imageToDeploy);

        saved.setDockerImage(imageToDeploy);
        saved = modelDeploymentRepository.saveAndFlush(saved);

        String serviceBaseUrl = kubernetesModelServingService.deployModelServer(
                deploymentName,
                serviceName,
                candidateServingTrack,
                imageToDeploy,
                registeredModelName,
                modelVersion,
                dataType
        );

        kubernetesModelServingService.ensureStableService(
                stableServiceName,
                registeredModelName,
                stableServiceTargetTrack
        );

        String finalPredictionEndpoint = buildEndpointForDataType(serviceBaseUrl, dataType);
        saved.setPredictionEndpoint(finalPredictionEndpoint);

        System.out.println("[BLUE_GREEN_START] Candidate deployment created. "
                + "candidateDeployment=" + deploymentName
                + ", candidateService=" + serviceName
                + ", stableService=" + stableServiceName
                + ", candidateTrack=" + candidateServingTrack
                + ", stableServiceTargetTrack=" + stableServiceTargetTrack
                + ", model=" + registeredModelName
                + ", version=" + modelVersion);

        boolean readyInKubernetes = kubernetesModelServingService.isModelServerReady(
                deploymentName,
                serviceName
        );

        if (!readyInKubernetes) {
            saved.setDeploymentStatus(DeploymentStatus.DEPLOYING);
            saved.setActive(false);
            saved.setErrorMessage(safeErrorMessage(
                    "Kubernetes resources created, but deployment/service are not ready yet. "
                            + "Old active deployment remains active."
            ));

            ModelDeployment pending = modelDeploymentRepository.saveAndFlush(saved);

            System.out.println("[BLUE_GREEN] Candidate deployment created but not ready yet. "
                    + "old active deployment remains active. "
                    + "candidateDeployment=" + deploymentName
                    + ", candidateService=" + serviceName
                    + ", candidateTrack=" + candidateServingTrack);

            return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(pending));
        }

        if (predictionSmokeTestEnabled) {
            boolean probeOk = kubernetesModelServingService.probePredictionEndpoint(finalPredictionEndpoint);

            if (!probeOk) {
                saved.setDeploymentStatus(DeploymentStatus.DEPLOYING);
                saved.setActive(false);
                saved.setErrorMessage(safeErrorMessage(
                        "Kubernetes is ready but the prediction endpoint did not answer. "
                                + "Candidate kept DEPLOYING, old active deployment remains active."
                ));

                ModelDeployment pending = modelDeploymentRepository.saveAndFlush(saved);

                System.out.println("[BLUE_GREEN] Candidate prediction smoke test failed. "
                        + "old active deployment remains active. "
                        + "candidateDeployment=" + deploymentName
                        + ", predictionEndpoint=" + finalPredictionEndpoint);

                return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(pending));
            }
        }

        System.out.println("[CANDIDATE_READY] Candidate is ready in Kubernetes. "
                + "Switching traffic and promoting. "
                + "candidateDeployment=" + deploymentName
                + ", candidateTrack=" + candidateServingTrack
                + ", stableService=" + stableServiceName);

        ModelDeployment deployed = promoteReadyDeployment(
                saved,
                effectiveProjectId,
                apiAccessConfig
        );

        System.out.println("[BLUE_GREEN] Switch completed. New active deployment="
                + deployed.getDeploymentName()
                + ", model=" + deployed.getRegisteredModelName()
                + ", version=" + deployed.getModelVersion()
                + ", image=" + deployed.getDockerImage());

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(deployed));

    } catch (Exception e) {
        try {
            kubernetesModelServingService.deleteModelServer(deploymentName, serviceName);
        } catch (Exception cleanupException) {
            System.out.println("[MODEL_DEPLOYMENT] Candidate Kubernetes cleanup failed: "
                    + cleanupException.getMessage());
        }

        saved.setDeploymentStatus(DeploymentStatus.FAILED);
        saved.setActive(false);
        saved.setErrorMessage(buildFullErrorMessage(e));

       ModelDeployment failed = modelDeploymentRepository.saveAndFlush(saved);

notifyDeploymentFailedIfNeeded(failed);

System.out.println("[BLUE_GREEN] Candidate deployment failed. Old active deployment remains active. "
        + "model=" + registeredModelName
        + ", version=" + modelVersion
        + ", reason=" + e.getMessage());

return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(failed));
    }
}


private MlExperiment ensureExperimentArtifactReadyForDeployment(MlExperiment experiment) {
    if (experiment == null || experiment.getId() == null) {
        throw new RuntimeException("Experiment is required before deployment.");
    }

    MlExperiment managedExperiment = mlExperimentRepository.findById(experiment.getId())
            .orElseThrow(() -> new RuntimeException(
                    "Expérience introuvable avec id = " + experiment.getId()
            ));

    /*
     * Cas Docker personnalisé :
     * Si le user script a déjà enregistré un vrai modèle MLflow dans le Registry,
     * on accepte ce modèle directement.
     * On ne cherche pas obligatoirement un model.pkl local.
     */
    if (isNativeMlflowRegistryModelReadyForDeployment(managedExperiment)) {
        MlExperiment saved = mlExperimentRepository.findById(managedExperiment.getId())
                .orElse(managedExperiment);

        saved.setMessage(
                appendDeploymentMessage(
                        saved.getMessage(),
                        "Native MLflow Registry model validated before deployment."
                )
        );
        saved.setErrorMessage(null);

        saved = mlExperimentRepository.saveAndFlush(saved);

        System.out.println("[MODEL_DEPLOYMENT] Native MLflow Registry model accepted without local artifact. "
                + "experimentId=" + saved.getId()
                + ", registeredModelName=" + saved.getRegisteredModelName()
                + ", modelVersion=" + saved.getModelVersion()
                + ", productionVersion=" + saved.getProductionVersion());

        return saved;
    }

    ModelArtifactStandardizerService.StandardizationResult result =
            modelArtifactStandardizerService.standardizeIfNeeded(managedExperiment);

    if (result == null) {
        if (isNativeMlflowRegistryModelReadyForDeployment(managedExperiment)) {
            return mlExperimentRepository.findById(managedExperiment.getId())
                    .orElse(managedExperiment);
        }

        throw new RuntimeException(
                "Model artifact validation failed: standardization returned null. "
                        + "Deployment blocked to avoid invalid MLflow model serving."
        );
    }

    System.out.println("[MODEL_DEPLOYMENT] Artifact readiness check:"
            + " experimentId=" + managedExperiment.getId()
            + ", success=" + result.isSuccess()
            + ", skipped=" + result.isSkipped()
            + ", standardized=" + result.isStandardized()
            + ", alreadyMlflowModel=" + result.isAlreadyMlflowModel()
            + ", newVersion=" + result.getNewVersion()
            + ", message=" + result.getMessage());

    if (result.isSuccess() && result.isAlreadyMlflowModel()) {
        MlExperiment refreshed = mlExperimentRepository.findById(managedExperiment.getId())
                .orElse(managedExperiment);

        refreshed.setMessage(
                appendDeploymentMessage(
                        refreshed.getMessage(),
                        "Existing MLflow pyfunc model validated before deployment."
                )
        );
        refreshed.setErrorMessage(null);

        return mlExperimentRepository.saveAndFlush(refreshed);
    }

    if (!result.isSuccess() || result.isSkipped()) {
        if (isNativeMlflowRegistryModelReadyForDeployment(managedExperiment)) {
            MlExperiment refreshed = mlExperimentRepository.findById(managedExperiment.getId())
                    .orElse(managedExperiment);

            refreshed.setMessage(
                    appendDeploymentMessage(
                            refreshed.getMessage(),
                            "Local artifact was not found, but MLflow Registry model is ready. Deployment allowed."
                    )
            );
            refreshed.setErrorMessage(null);

            return mlExperimentRepository.saveAndFlush(refreshed);
        }

        managedExperiment.setProduction(false);
        managedExperiment.setProductionVersion(null);
        managedExperiment.setErrorMessage(safeErrorMessage(
                "Model artifact not ready: " + result.getMessage()
        ));
        managedExperiment.setMessage(
                appendDeploymentMessage(
                        managedExperiment.getMessage(),
                        "Deployment blocked because model artifact is not ready: "
                                + result.getMessage()
                )
        );

        mlExperimentRepository.saveAndFlush(managedExperiment);

        throw new RuntimeException(
                "Deployment blocked: model artifact is not ready. "
                        + result.getMessage()
        );
    }

    MlExperiment refreshed = mlExperimentRepository.findById(managedExperiment.getId())
            .orElseThrow(() -> new RuntimeException(
                    "Expérience introuvable après standardisation avec id = "
                            + managedExperiment.getId()
            ));

    if (!hasText(refreshed.getRegisteredModelName())) {
        throw new RuntimeException(
                "Deployment blocked: registeredModelName is empty after standardization."
        );
    }

    if (!hasText(refreshed.getModelVersion())) {
        throw new RuntimeException(
                "Deployment blocked: modelVersion is empty after standardization."
        );
    }

    boolean markedProduction = Boolean.TRUE.equals(refreshed.getProduction());

    boolean versionMatchesProduction =
            hasText(refreshed.getProductionVersion())
                    && refreshed.getProductionVersion().trim()
                    .equalsIgnoreCase(refreshed.getModelVersion().trim());

    if (!markedProduction && !versionMatchesProduction) {
        throw new RuntimeException(
                "Deployment blocked: standardized model version is not marked as production."
        );
    }

    refreshed.setMessage(
            appendDeploymentMessage(
                    refreshed.getMessage(),
                    "Model artifact validated before deployment."
            )
    );
    refreshed.setErrorMessage(null);

    return mlExperimentRepository.saveAndFlush(refreshed);
}

private String appendDeploymentMessage(String oldMessage, String addition) {
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


public void reconcileDeploymentsWithKubernetes() {
    List<ModelDeployment> deployments = modelDeploymentRepository.findAll();

    if (deployments == null || deployments.isEmpty()) {
        return;
    }

    /*
     * Phase B : les vérifications Kubernetes se font HORS transaction
     * (une par déploiement, avec cache local du résultat).
     */
    Map<Long, Boolean> readyByDeploymentId = new LinkedHashMap<>();

    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        readyByDeploymentId.put(
                deployment.getId(),
                isDeploymentReallyReadyInKubernetes(deployment)
        );
    }

    /*
     * Phase C : chaque écriture est appliquée dans sa propre transaction
     * courte REQUIRES_NEW. Un déploiement ne bloque plus jamais les autres.
     */
    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        boolean readyInKubernetes =
                Boolean.TRUE.equals(readyByDeploymentId.get(deployment.getId()));
        DeploymentStatus status = deployment.getDeploymentStatus();

        if (DeploymentStatus.DEPLOYING.equals(status)
                || DeploymentStatus.OUT_OF_SYNC.equals(status)) {

            if (!readyInKubernetes) {
                if (DeploymentStatus.DEPLOYING.equals(status)) {
                    System.out.println("[DEPLOY_SYNC] Candidate deployment still not ready. "
                            + "model=" + deployment.getRegisteredModelName()
                            + ", version=" + deployment.getModelVersion()
                            + ", deploymentId=" + deployment.getId());
                }
                continue;
            }

            applyPromoteReadyInNewTransaction(deployment.getId());
            continue;
        }
    }

    /*
     * Phase D : rollback automatique si la Production active perd sa readiness.
     * Le reconciler rebascule le Service stable du modèle sur la dernière bonne
     * version (encore running grâce à la fenêtre de rollback). Sans candidate,
     * la ligne active passe en OUT_OF_SYNC.
     */
    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        boolean readyInKubernetes =
                Boolean.TRUE.equals(readyByDeploymentId.get(deployment.getId()));

        if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                && Boolean.TRUE.equals(deployment.getActive())
                && !readyInKubernetes) {

            attemptRollbackInNewTransaction(deployment.getId());
        }
    }

    /*
     * Phase E : nettoyage des versions obsolètes (DEPLOYED + inactive) dont les
     * ressources Kubernetes existent encore et dont la fenêtre de rollback est
     * dépassée. On ne supprime JAMAIS la Production active ni ses ressources.
     */
    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        boolean readyInKubernetes =
                Boolean.TRUE.equals(readyByDeploymentId.get(deployment.getId()));

        if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                && !Boolean.TRUE.equals(deployment.getActive())
                && readyInKubernetes
                && isStaleDeployment(deployment)) {

            cleanupStaleDeploymentInNewTransaction(deployment.getId());
        }
    }

    /*
     * Phase F : une seule Production active par modèle.
     * Si deux lignes sont actives pour le même modèle, on garde la plus récente.
     */
    enforceSingleActivePerModel(deployments);
}

private void applyPromoteReadyInNewTransaction(Long deploymentId) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(60);

    try {
        template.executeWithoutResult(status -> {
            ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                    .orElseThrow(() -> new RuntimeException(
                            "Deployment not found for promotion. deploymentId=" + deploymentId
                    ));

            ModelDeployment deployed = promoteReadyDeployment(
                    deployment,
                    deployment.getProjectId(),
                    resolveApiAccessConfigForDeployment(deployment.getRegisteredModelName())
            );

            System.out.println("[DEPLOY_SYNC] Candidate is now READY. Blue/green switch completed. "
                    + "model=" + deployed.getRegisteredModelName()
                    + ", version=" + deployed.getModelVersion()
                    + ", deploymentId=" + deployed.getId());
        });
    } catch (Exception e) {
        System.out.println("[DEPLOY_SYNC] Promotion failed. deploymentId=" + deploymentId
                + ", reason=" + e.getMessage());
    }
}

private void applyMarkOutOfSyncInNewTransaction(Long deploymentId, String message) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(30);

    try {
        template.executeWithoutResult(status -> {
            ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                    .orElseThrow(() -> new RuntimeException(
                            "Deployment not found for OUT_OF_SYNC. deploymentId=" + deploymentId
                    ));

            markDeploymentOutOfSync(deployment, message);

            System.out.println("[DEPLOY_SYNC] OUT_OF_SYNC: Kubernetes resources missing for "
                    + deployment.getRegisteredModelName()
                    + " v"
                    + deployment.getModelVersion()
                    + " deployment="
                    + deployment.getDeploymentName()
                    + " service="
                    + deployment.getServiceName());
        });
    } catch (Exception e) {
        System.out.println("[DEPLOY_SYNC] OUT_OF_SYNC failed. deploymentId=" + deploymentId
                + ", reason=" + e.getMessage());
    }
}

    /*
     * =========================================================
     * BLUE/GREEN — reconciler : rollback, stale cleanup, single active
     * =========================================================
     */

    private void attemptRollbackInNewTransaction(Long deploymentId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(60);

        try {
            template.executeWithoutResult(status -> {
                ModelDeployment current = modelDeploymentRepository.findById(deploymentId)
                        .orElseThrow(() -> new RuntimeException(
                                "Deployment not found for rollback. deploymentId=" + deploymentId
                        ));

                if (!DeploymentStatus.DEPLOYED.equals(current.getDeploymentStatus())
                        || !Boolean.TRUE.equals(current.getActive())) {
                    return;
                }

                ModelDeployment rollbackCandidate =
                        findRollbackCandidateForModel(current.getRegisteredModelName(), current.getId());

                if (rollbackCandidate == null) {
                    System.out.println("[ROLLBACK_STARTED] Active deployment lost readiness but no "
                            + "previous version is available. Marking OUT_OF_SYNC. "
                            + "model=" + current.getRegisteredModelName()
                            + ", version=" + current.getModelVersion()
                            + ", deploymentId=" + current.getId());

                    markDeploymentOutOfSync(
                            current,
                            "Active deployment lost Kubernetes readiness and no previous version is available for rollback."
                    );
                    return;
                }

                System.out.println("[ROLLBACK_STARTED] model=" + current.getRegisteredModelName()
                        + ", fromVersion=" + current.getModelVersion()
                        + ", toVersion=" + rollbackCandidate.getModelVersion()
                        + ", deploymentId=" + current.getId());

                if (hasText(rollbackCandidate.getStableServiceName())) {
                    String track = normalizeServingTrack(rollbackCandidate.getServingTrack());
                    kubernetesModelServingService.switchStableServiceTrack(
                            rollbackCandidate.getStableServiceName(),
                            rollbackCandidate.getRegisteredModelName(),
                            track
                    );
                }

                current.setActive(false);
                current.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
                current.setErrorMessage(safeErrorMessage(
                        "Rolled back because this deployment lost Kubernetes readiness."
                ));
                modelDeploymentRepository.save(current);

                rollbackCandidate.setActive(true);
                rollbackCandidate.setDeploymentStatus(DeploymentStatus.DEPLOYED);
                rollbackCandidate.setErrorMessage(null);
                modelDeploymentRepository.save(rollbackCandidate);

                modelDeploymentRepository.flush();

                System.out.println("[ROLLBACK_COMPLETED] model=" + current.getRegisteredModelName()
                        + ", productionRestored=" + rollbackCandidate.getModelVersion()
                        + ", deploymentId=" + rollbackCandidate.getId());
            });
        } catch (Exception e) {
            System.out.println("[ROLLBACK_STARTED] Rollback failed. deploymentId=" + deploymentId
                    + ", reason=" + e.getMessage());
        }
    }

    private ModelDeployment findRollbackCandidateForModel(String registeredModelName, Long excludeId) {
        if (!hasText(registeredModelName)) {
            return null;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(
                        registeredModelName.trim()
                );

        if (deployments == null || deployments.isEmpty()) {
            return null;
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (excludeId != null && deployment.getId().equals(excludeId)) {
                continue;
            }

            if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                    || Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (isDeploymentReallyReadyInKubernetes(deployment)) {
                return deployment;
            }
        }

        return null;
    }

    private boolean isStaleDeployment(ModelDeployment deployment) {
        if (deployment == null) {
            return false;
        }

        LocalDateTime base = deployment.getUpdatedAt() != null
                ? deployment.getUpdatedAt()
                : deployment.getDeployedAt();

        if (base == null) {
            return false;
        }

        return base.isBefore(
                LocalDateTime.now().minus(rollbackWindowMs, java.time.temporal.ChronoUnit.MILLIS)
        );
    }

    private void cleanupStaleDeploymentInNewTransaction(Long deploymentId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(60);

        try {
            template.executeWithoutResult(status -> {
                ModelDeployment stale = modelDeploymentRepository.findById(deploymentId)
                        .orElseThrow(() -> new RuntimeException(
                                "Deployment not found for stale cleanup. deploymentId=" + deploymentId
                        ));

                if (!DeploymentStatus.DEPLOYED.equals(stale.getDeploymentStatus())
                        || Boolean.TRUE.equals(stale.getActive())) {
                    return;
                }

                System.out.println("[STALE_DEPLOYMENT_DETECTED] model=" + stale.getRegisteredModelName()
                        + ", version=" + stale.getModelVersion()
                        + ", deployment=" + stale.getDeploymentName()
                        + ", service=" + stale.getServiceName()
                        + ", deploymentId=" + stale.getId());

                kubernetesModelServingService.deleteModelServer(
                        stale.getDeploymentName(),
                        stale.getServiceName()
                );

                stale.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
                stale.setErrorMessage(safeErrorMessage(
                        "Superseded by a newer production version and cleaned up after the rollback window."
                ));
                modelDeploymentRepository.saveAndFlush(stale);

                System.out.println("[OLD_DEPLOYMENT_CLEANED] model=" + stale.getRegisteredModelName()
                        + ", version=" + stale.getModelVersion()
                        + ", deployment=" + stale.getDeploymentName()
                        + ", service=" + stale.getServiceName());
            });
        } catch (Exception e) {
            System.out.println("[STALE_DEPLOYMENT_DETECTED] Cleanup failed. deploymentId=" + deploymentId
                    + ", reason=" + e.getMessage());
        }
    }

    private void enforceSingleActivePerModel(List<ModelDeployment> deployments) {
        if (deployments == null || deployments.isEmpty()) {
            return;
        }

        Map<String, ModelDeployment> activeByModel = new LinkedHashMap<>();
        List<Long> toDeactivateIds = new ArrayList<>();

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                    || !Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (!hasText(deployment.getRegisteredModelName())) {
                continue;
            }

            String key = deployment.getRegisteredModelName().trim().toLowerCase();
            ModelDeployment existing = activeByModel.get(key);

            if (existing == null) {
                activeByModel.put(key, deployment);
                continue;
            }

            ModelDeployment toKeep = isNewerDeployment(deployment, existing) ? deployment : existing;
            ModelDeployment toDeactivate = toKeep == deployment ? existing : deployment;

            System.out.println("[BLUE_GREEN] Duplicate active deployment detected for model="
                    + deployment.getRegisteredModelName()
                    + ". Keeping deploymentId=" + toKeep.getId()
                    + ", deactivating deploymentId=" + toDeactivate.getId());

            if (toDeactivate.getId() != null) {
                toDeactivateIds.add(toDeactivate.getId());
            }
        }

        for (Long id : toDeactivateIds) {
            applyDeactivateActiveInNewTransaction(id);
        }
    }

    private boolean isNewerDeployment(ModelDeployment a, ModelDeployment b) {
        LocalDateTime aTime = a.getDeployedAt() != null ? a.getDeployedAt() : a.getUpdatedAt();
        LocalDateTime bTime = b.getDeployedAt() != null ? b.getDeployedAt() : b.getUpdatedAt();

        if (aTime != null && bTime != null) {
            int cmp = aTime.compareTo(bTime);
            if (cmp != 0) {
                return cmp > 0;
            }
        } else if (aTime != null) {
            return true;
        } else if (bTime != null) {
            return false;
        }

        Long aId = a.getId();
        Long bId = b.getId();

        return aId != null && bId != null && aId > bId;
    }

    private void applyDeactivateActiveInNewTransaction(Long deploymentId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);

        try {
            template.executeWithoutResult(status -> {
                ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                        .orElse(null);

                if (deployment == null || !Boolean.TRUE.equals(deployment.getActive())) {
                    return;
                }

                deployment.setActive(false);
                modelDeploymentRepository.saveAndFlush(deployment);
            });
        } catch (Exception e) {
            System.out.println("[BLUE_GREEN] Failed to deactivate duplicate active deployment. "
                    + "deploymentId=" + deploymentId
                    + ", reason=" + e.getMessage());
        }
    }

@Transactional
public ModelDeploymentResponse createDeploymentRecord(
        Long projectId,
        Long experimentId,
        String registeredModelName,
        String modelVersion,
        String mlflowRunId,
        String kubeflowRunId
) {
    if (projectId == null) {
        throw new IllegalArgumentException("projectId est obligatoire");
    }

    if (experimentId == null) {
        throw new IllegalArgumentException("experimentId est obligatoire");
    }

    if (!hasText(registeredModelName)) {
        throw new IllegalArgumentException("registeredModelName est obligatoire");
    }

    if (!hasText(modelVersion)) {
        throw new IllegalArgumentException("modelVersion est obligatoire");
    }

    registeredModelName = registeredModelName.trim();
    modelVersion = modelVersion.trim();

    ModelDeployment existingSameVersion = findLatestDeploymentForModelVersion(
            registeredModelName,
            modelVersion
    );

    if (existingSameVersion != null) {
        ensureApiAccessEnabled(existingSameVersion);
        ModelDeployment savedExisting = modelDeploymentRepository.saveAndFlush(existingSameVersion);

        System.out.println("[MODEL_DEPLOYMENT] createDeploymentRecord skipped duplicate record. "
                + "model=" + registeredModelName
                + ", version=" + modelVersion
                + ", existingDeploymentId=" + savedExisting.getId());

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(savedExisting));
    }

    ApiAccessConfig apiAccessConfig = resolveApiAccessConfigForDeployment(registeredModelName);

    String safeModelName = sanitizeKubernetesName(registeredModelName);
    String safeVersion = sanitizeName(modelVersion);

    String deploymentName = safeModelName + "-v" + safeVersion + "-deployment";
    String serviceName = safeModelName + "-v" + safeVersion + "-service";

    ModelDeployment deployment = new ModelDeployment();
    deployment.setProjectId(projectId);
    deployment.setExperimentId(experimentId);
    deployment.setRegisteredModelName(registeredModelName);
    deployment.setModelVersion(modelVersion);
    deployment.setMlflowRunId(cleanText(mlflowRunId));
    deployment.setKubeflowRunId(cleanText(kubeflowRunId));
    deployment.setDeploymentStatus(DeploymentStatus.PENDING);
    deployment.setPredictionEndpoint(null);
    deployment.setDeploymentName(deploymentName);
    deployment.setServiceName(serviceName);
    deployment.setNamespaceName(namespaceName);
    deployment.setDockerImage(defaultDockerImage);
    deployment.setActive(false);
    deployment.setErrorMessage(null);

    applyApiAccessConfig(deployment, apiAccessConfig);

    try {
        ModelDeployment savedDeployment = modelDeploymentRepository.saveAndFlush(deployment);

        return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(savedDeployment));

    } catch (DataIntegrityViolationException duplicateViolation) {
        /*
         * Deux schedulers peuvent créer la même ligne en même temps.
         * La contrainte UNIQUE uk_deployment_model_version a empêché
         * le doublon : on retourne la ligne existante.
         */
        ModelDeployment raced = findLatestDeploymentForModelVersion(
                registeredModelName,
                modelVersion
        );

        if (raced != null) {
            ensureApiAccessEnabled(raced);
            ModelDeployment savedRaced =
                    modelDeploymentRepository.saveAndFlush(raced);

            System.out.println("[MODEL_DEPLOYMENT] createDeploymentRecord "
                    + "recovered from unique-constraint race. "
                    + "model=" + registeredModelName
                    + ", version=" + modelVersion
                    + ", existingDeploymentId=" + savedRaced.getId());

            return enrichDeploymentResponse(
                    ModelDeploymentResponse.fromEntity(savedRaced)
            );
        }

        throw duplicateViolation;
    }
}


    @Transactional
    public ModelDeploymentResponse getActiveDeploymentByProject(Long projectId) {
        if (projectId == null) {
            throw new RuntimeException("projectId est obligatoire.");
        }

        List<ModelDeployment> deployments = modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            System.out.println("[MODEL_DEPLOYMENT] No deployments found for project id=" + projectId);
            return null;
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
                continue;
            }

            if (!isDeploymentReallyReadyInKubernetes(deployment)) {
                markDeploymentOutOfSync(
                        deployment,
                        "Active deployment is marked DEPLOYED in MySQL, but Kubernetes resources are missing or not ready."
                );
                continue;
            }

            ModelDeploymentResponse response = ModelDeploymentResponse.fromEntity(deployment);

            if (!hasText(response.getMlflowProductionVersion())) {
                response.setMlflowProductionVersion(deployment.getModelVersion());
            }

            if (!hasText(response.getKubernetesActiveVersion())) {
                response.setKubernetesActiveVersion(deployment.getModelVersion());
            }

            return enrichDeploymentResponse(response);
        }

        System.out.println("[MODEL_DEPLOYMENT] No active deployment found for project id=" + projectId);
        return null;
    }

    @Transactional
    public List<ModelDeploymentResponse> getDeploymentsByProject(Long projectId) {
        List<ModelDeployment> deployments = modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        return filterReadyDeploymentsForDisplay(deployments)
                .stream()
                .map(ModelDeploymentResponse::fromEntity)
                .map(this::enrichDeploymentResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<ModelDeploymentResponse> getDeploymentsByExperiment(Long experimentId) {
        List<ModelDeployment> deployments = modelDeploymentRepository.findByExperimentIdOrderByCreatedAtDesc(experimentId);

        return filterReadyDeploymentsForDisplay(deployments)
                .stream()
                .map(ModelDeploymentResponse::fromEntity)
                .map(this::enrichDeploymentResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ModelDeploymentResponse markAsDeploying(Long deploymentId) {
        ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

        deployment.setDeploymentStatus(DeploymentStatus.DEPLOYING);
        deployment.setActive(false);
        deployment.setErrorMessage(null);

        return ModelDeploymentResponse.fromEntity(modelDeploymentRepository.saveAndFlush(deployment));
    }

    @Transactional
public ModelDeploymentResponse markAsDeployed(Long deploymentId) {
    ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

    /*
     * Blue/green : on bascule le trafic vers ce déploiement puis on désactive
     * les anciennes versions du modèle (ressources conservées pour rollback).
     */
    if (hasText(deployment.getStableServiceName())) {
        String track = normalizeServingTrack(deployment.getServingTrack());

        try {
            kubernetesModelServingService.switchStableServiceTrack(
                    deployment.getStableServiceName(),
                    deployment.getRegisteredModelName(),
                    track
            );
            System.out.println("[TRAFFIC_SWITCH] Manual promotion switched traffic to track="
                    + track
                    + ", model=" + deployment.getRegisteredModelName()
                    + ", version=" + deployment.getModelVersion());
        } catch (Exception e) {
            System.out.println("[TRAFFIC_SWITCH] Failed to switch stable service on manual promotion. "
                    + "reason=" + e.getMessage());
        }
    }

    deactivatePreviousActiveDeploymentsForModel(
            deployment.getRegisteredModelName(),
            deployment.getId()
    );

    deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
    deployment.setActive(true);
    deployment.setDeployedAt(LocalDateTime.now());
    deployment.setErrorMessage(null);

    ensureApiAccessEnabled(deployment);

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    notifyDeploymentDeployedIfNeeded(saved);

    System.out.println("[PRODUCTION_PROMOTED] Manual promotion. model="
            + saved.getRegisteredModelName()
            + ", version=" + saved.getModelVersion()
            + ", deploymentId=" + saved.getId());

    return ModelDeploymentResponse.fromEntity(saved);
}

   @Transactional
public ModelDeploymentResponse markAsFailed(Long deploymentId, String errorMessage) {
    ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

    deployment.setDeploymentStatus(DeploymentStatus.FAILED);
    deployment.setActive(false);
    deployment.setErrorMessage(safeErrorMessage(
            errorMessage != null && !errorMessage.isBlank()
                    ? errorMessage
                    : "Deployment failed."
    ));

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    notifyDeploymentFailedIfNeeded(saved);

    return ModelDeploymentResponse.fromEntity(saved);
}

    @Transactional
    public ModelDeploymentResponse stopDeployment(Long deploymentId) {
        ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

        deployment.setActive(false);
        deployment.setErrorMessage(null);

        try {
            kubernetesModelServingService.deleteModelServer(
                    deployment.getDeploymentName(),
                    deployment.getServiceName()
            );
        } catch (Exception e) {
            System.out.println("[MODEL_DEPLOYMENT] Failed to delete Kubernetes resources: "
                    + e.getMessage());
        }

        return ModelDeploymentResponse.fromEntity(modelDeploymentRepository.saveAndFlush(deployment));
    }

    @Transactional
    public ModelDeploymentResponse enableApiAccess(Long deploymentId) {
        ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new RuntimeException("Deployment introuvable avec id=" + deploymentId));

        DeploymentVisibility oldVisibility = deployment.getVisibility();

        if (oldVisibility == null) {
            oldVisibility = DeploymentVisibility.PRIVATE;
        }

        deployment.setVisibility(DeploymentVisibility.PUBLIC_API);
        deployment.setApiEnabled(true);

        if (oldVisibility != DeploymentVisibility.PUBLIC_API
                || deployment.getApiKey() == null
                || deployment.getApiKey().isBlank()) {
            deployment.setApiKey(generateUniqueApiKey());
            deployment.setApiKeyCreatedAt(LocalDateTime.now());
            deployment.setApiKeyLastUsedAt(null);
        }

        ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);
        return ModelDeploymentResponse.fromEntity(saved);
    }

    @Transactional
    public ModelDeploymentResponse disableApiAccess(Long deploymentId) {
        ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new RuntimeException("Deployment introuvable avec id=" + deploymentId));

        deployment.setVisibility(DeploymentVisibility.PRIVATE);
        deployment.setApiEnabled(false);
        deployment.setApiKey(null);
        deployment.setApiKeyCreatedAt(null);
        deployment.setApiKeyLastUsedAt(null);

        ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);
        return ModelDeploymentResponse.fromEntity(saved);
    }

    @Transactional
    public ModelDeploymentResponse regenerateApiKey(Long deploymentId) {
        ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

        if (deployment.getRegisteredModelName() == null || deployment.getRegisteredModelName().isBlank()) {
            throw new RuntimeException("registeredModelName manquant.");
        }

        String registeredModelName = deployment.getRegisteredModelName().trim();
        String newApiKey = generateUniqueApiKey();
        LocalDateTime now = LocalDateTime.now();

        DeploymentVisibility currentVisibility = deployment.getVisibility();

        if (currentVisibility == null) {
            currentVisibility = DeploymentVisibility.PRIVATE;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(registeredModelName);

        for (ModelDeployment item : deployments) {
            if (item == null) {
                continue;
            }

            item.setApiEnabled(true);
            item.setVisibility(currentVisibility);
            item.setApiKey(newApiKey);
            item.setApiKeyCreatedAt(now);
            item.setApiKeyLastUsedAt(null);

            modelDeploymentRepository.save(item);
        }

        modelDeploymentRepository.flush();

        ModelDeployment refreshed = findDeploymentOrThrow(deploymentId);
        return ModelDeploymentResponse.fromEntity(refreshed);
    }

    /*
     * Ancienne signature gardée pour ne pas casser un ancien code.
     * Attention : en mode SHARED, cette méthode échouera car X-USER-EMAIL est obligatoire.
     */
    @Transactional
    public ModelDeployment findDeploymentForExternalApiOrThrow(
            String registeredModelName,
            String apiKey
    ) {
        return findDeploymentForExternalApiOrThrow(registeredModelName, apiKey, null);
    }

    /*
     * Nouvelle méthode utilisée par ExternalPredictionController.
     *
     * PUBLIC_API :
     * - clé API valide suffit.
     *
     * SHARED :
     * - clé API valide obligatoire.
     * - X-USER-EMAIL obligatoire.
     * - l'utilisateur correspondant à cet email doit être dans model_deployment_shares.
     *
     * PRIVATE :
     * - accès externe refusé.
     */
    @Transactional
    public ModelDeployment findDeploymentForExternalApiOrThrow(
            String registeredModelName,
            String apiKey,
            String userEmail
    ) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new RuntimeException("registeredModelName est obligatoire.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("API key manquante.");
        }

        String cleanModelName = registeredModelName.trim();
        String cleanApiKey = apiKey.trim();

        ModelDeployment apiOwner = modelDeploymentRepository
                .findTopByRegisteredModelNameAndApiKeyAndApiEnabledTrueOrderByUpdatedAtDesc(
                        cleanModelName,
                        cleanApiKey
                )
                .orElseThrow(() -> new RuntimeException("API key invalide pour ce modèle."));

        DeploymentVisibility visibility = apiOwner.getVisibility();

        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            throw new RuntimeException("Ce modèle est privé et ne peut pas être utilisé via l'API externe.");
        }

        if (!Boolean.TRUE.equals(apiOwner.getApiEnabled())) {
            throw new RuntimeException("L'accès API de ce modèle est désactivé.");
        }

        if (DeploymentVisibility.SHARED.equals(visibility)) {
            if (userEmail == null || userEmail.isBlank()) {
                throw new RuntimeException("X-USER-EMAIL est obligatoire pour utiliser un modèle partagé.");
            }

            String cleanEmail = userEmail.trim();
            User user = findUserByEmailIgnoreCaseOrThrow(cleanEmail);

            boolean authorizedOnApiOwner =
                    modelDeploymentShareRepository.findByDeploymentIdAndSharedWithUserId(
                            apiOwner.getId(),
                            user.getId()
                    ).isPresent();

            boolean authorizedOnAnySameModelDeployment = false;

            if (!authorizedOnApiOwner) {
                List<ModelDeployment> sameModelDeployments =
                        modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(cleanModelName);

                if (sameModelDeployments != null) {
                    for (ModelDeployment deployment : sameModelDeployments) {
                        if (deployment == null || deployment.getId() == null) {
                            continue;
                        }

                        boolean found =
                                modelDeploymentShareRepository.findByDeploymentIdAndSharedWithUserId(
                                        deployment.getId(),
                                        user.getId()
                                ).isPresent();

                        if (found) {
                            authorizedOnAnySameModelDeployment = true;
                            break;
                        }
                    }
                }
            }

            if (!authorizedOnApiOwner && !authorizedOnAnySameModelDeployment) {
                throw new RuntimeException("Cet utilisateur n'est pas autorisé à utiliser ce modèle partagé.");
            }
        }

        ModelDeployment activeDeployment = findKubernetesReadyActiveDeployment(cleanModelName)
                .orElseThrow(() -> new RuntimeException(
                        "Aucun déploiement Kubernetes actif et prêt trouvé pour ce modèle."
                ));

        activeDeployment.setApiEnabled(apiOwner.getApiEnabled());
        activeDeployment.setVisibility(visibility);
        activeDeployment.setApiKey(apiOwner.getApiKey());
        activeDeployment.setApiKeyCreatedAt(apiOwner.getApiKeyCreatedAt());
        activeDeployment.setApiKeyLastUsedAt(LocalDateTime.now());

        return modelDeploymentRepository.saveAndFlush(activeDeployment);
    }

    private User findUserByEmailIgnoreCaseOrThrow(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email utilisateur obligatoire.");
        }

        String cleanEmail = email.trim();

        return userRepository.findAll()
                .stream()
                .filter(user -> user != null
                        && user.getEmail() != null
                        && user.getEmail().equalsIgnoreCase(cleanEmail))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé pour l'email : " + cleanEmail));
    }

    private Optional<ModelDeployment> findFailedDeploymentForSameModelVersion(
            String registeredModelName,
            String modelVersion
    ) {
        if (registeredModelName == null || registeredModelName.isBlank()
                || modelVersion == null || modelVersion.isBlank()) {
            return Optional.empty();
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
                        registeredModelName.trim(),
                        modelVersion.trim()
                );

        if (deployments == null || deployments.isEmpty()) {
            return Optional.empty();
        }

        return deployments.stream()
                .filter(item -> item != null && DeploymentStatus.FAILED.equals(item.getDeploymentStatus()))
                .findFirst();
    }

    private ModelDeployment findActiveDeploymentByProjectSafely(Long projectId) {
        if (projectId == null) {
            return null;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            return null;
        }

        ModelDeployment activeToKeep = null;

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
                continue;
            }

            if (!isDeploymentReallyReadyInKubernetes(deployment)) {
                deployment.setDeploymentStatus(DeploymentStatus.DEPLOYING);
                deployment.setActive(false);
                deployment.setErrorMessage(safeErrorMessage(
                        "Active deployment is not ready in Kubernetes anymore."
                ));
                modelDeploymentRepository.save(deployment);
                continue;
            }

            if (activeToKeep == null) {
                activeToKeep = deployment;
                continue;
            }

            deployment.setActive(false);
            modelDeploymentRepository.save(deployment);

            try {
                kubernetesModelServingService.deleteModelServer(
                        deployment.getDeploymentName(),
                        deployment.getServiceName()
                );
            } catch (Exception e) {
                System.out.println("[MODEL_DEPLOYMENT] Failed to delete duplicate active deployment: "
                        + e.getMessage());
            }
        }

        modelDeploymentRepository.flush();

        if (activeToKeep != null) {
            return activeToKeep;
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (isDeploymentReallyReadyInKubernetes(deployment)) {
                return deployment;
            }
        }

        return null;
    }

    private ModelDeployment findActiveSameVersionForProjectSafely(
            Long projectId,
            String registeredModelName,
            String modelVersion
    ) {
        if (projectId == null || registeredModelName == null || modelVersion == null) {
            return null;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            return null;
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null) {
                continue;
            }

            if (!Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (deployment.getRegisteredModelName() == null || deployment.getModelVersion() == null) {
                continue;
            }

            boolean sameModel = deployment.getRegisteredModelName()
                    .trim()
                    .equalsIgnoreCase(registeredModelName.trim());

            boolean sameVersion = deployment.getModelVersion()
                    .trim()
                    .equalsIgnoreCase(modelVersion.trim());

            if (sameModel && sameVersion) {
                return deployment;
            }
        }

        return null;
    }

    private void deactivateActiveDeploymentsForProjectExcept(Long projectId, Long deploymentIdToKeep) {
        if (projectId == null) {
            return;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            return;
        }

        for (ModelDeployment oldDeployment : deployments) {
            if (oldDeployment == null || oldDeployment.getId() == null) {
                continue;
            }

            if (!Boolean.TRUE.equals(oldDeployment.getActive())) {
                continue;
            }

            if (deploymentIdToKeep != null && oldDeployment.getId().equals(deploymentIdToKeep)) {
                continue;
            }

            oldDeployment.setActive(false);
            modelDeploymentRepository.save(oldDeployment);

            try {
                kubernetesModelServingService.deleteModelServer(
                        oldDeployment.getDeploymentName(),
                        oldDeployment.getServiceName()
                );
            } catch (Exception e) {
                System.out.println("[MODEL_DEPLOYMENT] Failed to delete old project deployment: "
                        + e.getMessage());
            }
        }

        modelDeploymentRepository.flush();
    }

    /*
     * =========================================================
     * BLUE/GREEN — helpers
     * =========================================================
     */

    private ModelDeployment findCurrentActiveDeploymentForModel(String registeredModelName) {
        return findKubernetesReadyActiveDeployment(registeredModelName).orElse(null);
    }

    /*
     * La candidate prend la couleur opposée à la Production courante.
     * Sans Production active, la première version démarre sur "blue".
     */
    private String resolveCandidateServingTrack(String registeredModelName) {
        ModelDeployment active = findCurrentActiveDeploymentForModel(registeredModelName);

        if (active != null) {
            String track = normalizeServingTrack(active.getServingTrack());
            return "green".equals(track) ? "blue" : "green";
        }

        return "blue";
    }

    /*
     * Cible du Service stable pendant la création de la candidate :
     * on garde le trafic sur la Production courante tant qu'elle existe et
     * possède un Service stable ; sinon (première version / legacy sans Service
     * stable) on pointe vers la candidate.
     */
    private String resolveStableServiceTargetTrack(
            String registeredModelName,
            String candidateTrack
    ) {
        ModelDeployment active = findCurrentActiveDeploymentForModel(registeredModelName);

        if (active != null && hasText(active.getStableServiceName())) {
            return normalizeServingTrack(active.getServingTrack());
        }

        return candidateTrack;
    }

    private String normalizeServingTrack(String track) {
        if (track != null && "green".equalsIgnoreCase(track.trim())) {
            return "green";
        }

        return "blue";
    }

    /*
     * Désactive les anciennes versions actives du MÊME modèle (et non du même
     * projet : le modèle est l'unité de Production), sans supprimer leurs
     * ressources Kubernetes : elles restent disponibles pour un rollback
     * pendant la fenêtre de rollback, puis le reconciler les nettoie.
     */
    private void deactivatePreviousActiveDeploymentsForModel(String registeredModelName, Long keepId) {
        if (!hasText(registeredModelName)) {
            return;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(
                        registeredModelName.trim()
                );

        if (deployments == null || deployments.isEmpty()) {
            return;
        }

        for (ModelDeployment oldDeployment : deployments) {
            if (oldDeployment == null || oldDeployment.getId() == null) {
                continue;
            }

            if (keepId != null && oldDeployment.getId().equals(keepId)) {
                continue;
            }

            if (!Boolean.TRUE.equals(oldDeployment.getActive())) {
                continue;
            }

            oldDeployment.setActive(false);
            modelDeploymentRepository.save(oldDeployment);

            System.out.println("[BLUE_GREEN] Previous active deployment deactivated. "
                    + "Kubernetes resources kept for rollback window. "
                    + "model=" + registeredModelName.trim()
                    + ", version=" + oldDeployment.getModelVersion()
                    + ", deploymentId=" + oldDeployment.getId());
        }

        modelDeploymentRepository.flush();
    }

private String resolveServingImageForRuntime(
        MlExperiment experiment,
        String registeredModelName,
        String modelVersion,
        String dataType
) {
    if (experiment == null || experiment.getId() == null) {
        throw new RuntimeException("Experiment is required to resolve serving image.");
    }

    if (!hasText(registeredModelName)) {
        throw new RuntimeException("registeredModelName is required to resolve serving image. experimentId="
                + experiment.getId());
    }

    if (!hasText(modelVersion)) {
        throw new RuntimeException("modelVersion is required to resolve serving image. experimentId="
                + experiment.getId());
    }

    String runtimeMode = safeRuntimeMode(experiment);

    String normalizedDataType = hasText(dataType)
            ? dataType.trim().toUpperCase()
            : "TABULAR";

    if (!hasText(defaultDockerImage)) {
        throw new RuntimeException(
                "Default serving image is empty. Please configure app.deployment.default-docker-image "
                        + "or APP_DEPLOYMENT_DEFAULT_DOCKER_IMAGE."
        );
    }

    String genericServingImage = defaultDockerImage.trim();

    /*
     * STANDARD_RUNTIME :
     * on utilise toujours l'image générique.
     */
    if (isStandardRuntimeMode(runtimeMode)) {
        System.out.println("[SERVING_IMAGE_RESOLVER] STANDARD runtime detected. "
                + "Using generic model server. "
                + "experimentId=" + experiment.getId()
                + ", runtimeMode=" + runtimeMode
                + ", model=" + registeredModelName
                + ", version=" + modelVersion
                + ", dataType=" + normalizedDataType
                + ", image=" + genericServingImage);

        return genericServingImage;
    }

    /*
     * CUSTOM_DOCKERFILE :
     * on ne doit PAS utiliser l'image générique.
     *
     * Pourquoi ?
     * Le modèle custom peut avoir des dépendances différentes :
     * numpy, pandas, mlflow, torch, tensorflow, xgboost...
     *
     * Donc on construit une image de serving dédiée avec Kaniko,
     * en se basant sur l'image custom training quand elle existe.
     */
    if (isCustomDockerRuntimeMode(runtimeMode)) {
        if (!dedicatedServingImageEnabled) {
            throw new RuntimeException(
                    "CUSTOM_DOCKERFILE requires a dedicated serving image. "
                            + "Generic model server cannot safely load custom model dependencies. "
                            + "Please set APP_SERVING_DEDICATED_IMAGE_ENABLED=true. "
                            + "experimentId=" + experiment.getId()
            );
        }

        String modelUriForServing =
                "models:/" + registeredModelName.trim() + "/" + modelVersion.trim();

        System.out.println("[SERVING_IMAGE_RESOLVER] CUSTOM_DOCKERFILE runtime detected. "
                + "Building dedicated serving image with Kaniko. "
                + "experimentId=" + experiment.getId()
                + ", runtimeMode=" + runtimeMode
                + ", modelUri=" + modelUriForServing
                + ", dataType=" + normalizedDataType
                + ", customTrainingImage=" + experiment.getCustomImageUri());

        String dedicatedImage = modelServingImageBuildService.buildServingImageForModel(
                experiment,
                registeredModelName.trim(),
                modelVersion.trim(),
                modelUriForServing,
                normalizedDataType
        );

        if (!hasText(dedicatedImage)) {
            throw new RuntimeException(
                    "Dedicated serving image build returned empty image URI. "
                            + "experimentId=" + experiment.getId()
            );
        }

        System.out.println("[SERVING_IMAGE_RESOLVER] Dedicated serving image selected. "
                + "experimentId=" + experiment.getId()
                + ", runtimeMode=" + runtimeMode
                + ", model=" + registeredModelName
                + ", version=" + modelVersion
                + ", image=" + dedicatedImage.trim());

        return dedicatedImage.trim();
    }

    throw new RuntimeException(
            "Unsupported runtimeMode=" + runtimeMode
                    + " for experimentId=" + experiment.getId()
    );
}
private String safeRuntimeMode(MlExperiment experiment) {
    if (experiment == null || !hasText(experiment.getRuntimeMode())) {
        return "STANDARD_RUNTIME";
    }

    return experiment.getRuntimeMode().trim().toUpperCase();
}

private boolean isStandardRuntimeMode(String runtimeMode) {
    if (!hasText(runtimeMode)) {
        return true;
    }

    String clean = runtimeMode.trim().toUpperCase();

    return clean.equals("STANDARD")
            || clean.equals("STANDARD_RUNTIME")
            || clean.equals("STANDARD_IMAGE")
            || clean.equals("DEFAULT");
}


private boolean isCustomDockerRuntimeMode(String runtimeMode) {
    if (!hasText(runtimeMode)) {
        return false;
    }

    String clean = runtimeMode.trim().toUpperCase();

    return clean.equals("CUSTOM_DOCKERFILE")
            || clean.equals("CUSTOM_DOCKER")
            || clean.equals("DOCKER_PERSONALIZED")
            || clean.equals("PERSONALIZED")
            || clean.equals("CUSTOM_IMAGE")
            || clean.equals("CUSTOM_IMAGE_RUNTIME");
}


    private boolean modelRequiresDedicatedServingImage(MlExperiment experiment) {
        /*
         * Hybrid strategy:
         * - Use generic serving image for common dependencies.
         * - Build dedicated serving image only when the model needs custom dependencies.
         *
         * This avoids useless Docker builds for simple sklearn models while still supporting
         * xgboost/lightgbm/catboost/tensorflow/torch/opencv/etc.
         */
        String requirementsContent = readExperimentRequirementsContent(experiment);

        if (!hasText(requirementsContent)) {
            System.out.println("[MODEL_DEPLOYMENT] No requirements file found for serving decision. Generic image selected.");
            return false;
        }

        List<String> normalizedPackages = parseRequirementPackageNames(requirementsContent);

        if (normalizedPackages.isEmpty()) {
            System.out.println("[MODEL_DEPLOYMENT] Empty requirements file for serving decision. Generic image selected.");
            return false;
        }

        for (String packageName : normalizedPackages) {
            if (CUSTOM_SERVING_PACKAGES.contains(packageName)) {
                System.out.println("[MODEL_DEPLOYMENT] Dedicated serving image required. Detected custom dependency: "
                        + packageName);
                return true;
            }
        }

        for (String packageName : normalizedPackages) {
            if (!STANDARD_SERVING_PACKAGES.contains(packageName)) {
                System.out.println("[MODEL_DEPLOYMENT] Dedicated serving image required. Dependency is not in generic whitelist: "
                        + packageName);
                return true;
            }
        }

        System.out.println("[MODEL_DEPLOYMENT] Generic serving image is enough. Detected requirements="
                + normalizedPackages);

        return false;
    }

    private String readExperimentRequirementsContent(MlExperiment experiment) {
        if (experiment == null) {
            return null;
        }

        String requirementsPath = firstNonBlank(experiment.getRequirementsPath());

        if (!hasText(requirementsPath)) {
            return null;
        }

        try {
            Path path = Paths.get(requirementsPath.trim());

            if (!Files.exists(path)) {
                System.out.println("[MODEL_DEPLOYMENT] requirementsPath not found: " + requirementsPath);
                return null;
            }

            return Files.readString(path);

        } catch (Exception e) {
            System.out.println("[MODEL_DEPLOYMENT] Could not read requirementsPath="
                    + requirementsPath
                    + " reason="
                    + e.getMessage());
            return null;
        }
    }

    private List<String> parseRequirementPackageNames(String requirementsContent) {
        if (!hasText(requirementsContent)) {
            return List.of();
        }

        List<String> packages = new ArrayList<>();

        String[] lines = requirementsContent.split("\\R");

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }

            String line = rawLine.trim().toLowerCase();

            if (line.isBlank()
                    || line.startsWith("#")
                    || line.startsWith("-r ")
                    || line.startsWith("--")
                    || line.startsWith("git+")
                    || line.startsWith("http://")
                    || line.startsWith("https://")) {
                continue;
            }

            int commentIndex = line.indexOf("#");
            if (commentIndex >= 0) {
                line = line.substring(0, commentIndex).trim();
            }

            String packageName = line
                    .replaceAll("\\[.*?\\]", "")
                    .split("==|>=|<=|~=|!=|>|<|;")[0]
                    .trim()
                    .replace("_", "-");

            if (!packageName.isBlank() && !packages.contains(packageName)) {
                packages.add(packageName);
            }
        }

        return packages;
    }

    private String resolveDeploymentDataType(MlExperiment experiment) {
        String projectDataType = null;

        if (experiment != null && experiment.getProject() != null) {
            projectDataType = experiment.getProject().getDataType();
        }

        String experimentDataType = experiment != null ? experiment.getDataType() : null;

        String dataType = firstNonBlank(
                projectDataType,
                experimentDataType,
                "TABULAR"
        );

        dataType = dataType.trim().toUpperCase();

        if (dataType.equals("NLP")) {
            return "TEXT";
        }

        if (dataType.equals("TEXT") || dataType.equals("IMAGE") || dataType.equals("TABULAR")) {
            return dataType;
        }

        return "TABULAR";
    }

    private String buildEndpointForDataType(String serviceBaseUrl, String dataType) {
        String baseUrl = normalizeBaseUrl(serviceBaseUrl);

        if (dataType == null || dataType.isBlank()) {
            return baseUrl + "/predict";
        }

        String clean = dataType.trim().toUpperCase();

        if (clean.equals("TEXT") || clean.equals("NLP")) {
            return baseUrl + "/predict-text";
        }

        if (clean.equals("IMAGE")) {
            return baseUrl + "/predict-image";
        }

        return baseUrl + "/predict";
    }

    private void validateDeployRequest(DeployModelRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("La requête de déploiement est obligatoire.");
        }

        if (request.getExperimentId() == null) {
            throw new IllegalArgumentException("experimentId est obligatoire.");
        }
    }

    private ModelDeployment findDeploymentOrThrow(Long deploymentId) {
        return modelDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new RuntimeException("Déploiement introuvable avec id = " + deploymentId));
    }

    /*
     * Variante publique utilisée par le SMART MODEL GATEWAY.
     */
    public ModelDeployment findDeploymentOrThrowPublic(Long deploymentId) {
        return findDeploymentOrThrow(deploymentId);
    }

    private String sanitizeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        return value
                .trim()
                .toLowerCase()
                .replace("_", "-")
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-", "")
                .replaceAll("-$", "");
    }

    private String sanitizeKubernetesName(String value) {
        String clean = sanitizeName(value);

        if (clean == null || clean.isBlank()) {
            clean = "model";
        }

        if (!clean.matches("^[a-z].*")) {
            clean = "model-" + clean;
        }

        if (clean.length() > 40) {
            clean = clean.substring(0, 40);
            clean = clean.replaceAll("-$", "");
        }

        if (clean.isBlank()) {
            clean = "model";
        }

        return clean;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8000";
        }

        String clean = value.trim();

        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        return clean;
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return null;
    }

    private String buildFullErrorMessage(Throwable e) {
        if (e == null) {
            return safeErrorMessage("Unknown deployment error.");
        }

        StringBuilder message = new StringBuilder();
        Throwable current = e;
        int depth = 0;

        while (current != null && depth < 6) {
            if (depth > 0) {
                message.append(" | Caused by ");
            }

            message.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage());

            current = current.getCause();
            depth++;
        }

        return safeErrorMessage(message.toString());
    }

    private String safeErrorMessage(String value) {
        return limitForDatabase(value, ERROR_MESSAGE_MAX_LENGTH);
    }

    private String limitForDatabase(String value, int maxLength) {
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

        int suffixLength = 16;
        int end = Math.max(0, maxLength - suffixLength);
        return cleaned.substring(0, end) + "... [truncated]";
    }

    private String generateUniqueApiKey() {
        String apiKey;

        do {
            apiKey = generateApiKey();
        } while (modelDeploymentRepository.existsByApiKey(apiKey));

        return apiKey;
    }

    private String generateApiKey() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        return "mlops_" + token;
    }

    @Transactional
    public ModelDeploymentResponse updateDeploymentVisibility(Long deploymentId, String visibilityValue) {
        ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new RuntimeException("Deployment introuvable avec id=" + deploymentId));

        DeploymentVisibility oldVisibility = deployment.getVisibility();

        if (oldVisibility == null) {
            oldVisibility = DeploymentVisibility.PRIVATE;
        }

        String cleanVisibility = visibilityValue == null
                ? "PRIVATE"
                : visibilityValue.trim().toUpperCase();

        DeploymentVisibility newVisibility;

        try {
            newVisibility = DeploymentVisibility.valueOf(cleanVisibility);
        } catch (Exception e) {
            throw new RuntimeException("Visibility invalide : " + visibilityValue);
        }

        boolean visibilityChanged = oldVisibility != newVisibility;

        if (newVisibility == DeploymentVisibility.PRIVATE) {
            deployment.setVisibility(DeploymentVisibility.PRIVATE);
            deployment.setApiEnabled(false);

            if (visibilityChanged) {
                deployment.setApiKey(null);
                deployment.setApiKeyCreatedAt(null);
                deployment.setApiKeyLastUsedAt(null);
            }

        } else if (newVisibility == DeploymentVisibility.PUBLIC_API) {
            deployment.setVisibility(DeploymentVisibility.PUBLIC_API);
            deployment.setApiEnabled(true);

            if (visibilityChanged || deployment.getApiKey() == null || deployment.getApiKey().isBlank()) {
                deployment.setApiKey(generateUniqueApiKey());
                deployment.setApiKeyCreatedAt(LocalDateTime.now());
                deployment.setApiKeyLastUsedAt(null);
            }

        } else if (newVisibility == DeploymentVisibility.SHARED) {
            deployment.setVisibility(DeploymentVisibility.SHARED);
            deployment.setApiEnabled(true);

            if (visibilityChanged || deployment.getApiKey() == null || deployment.getApiKey().isBlank()) {
                deployment.setApiKey(generateUniqueApiKey());
                deployment.setApiKeyCreatedAt(LocalDateTime.now());
                deployment.setApiKeyLastUsedAt(null);
            }
        }

        ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);
        return ModelDeploymentResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
public boolean isDeploymentReallyReadyInKubernetes(ModelDeployment deployment) {
    if (deployment == null) {
        return false;
    }

    if (deployment.getDeploymentName() == null || deployment.getDeploymentName().isBlank()) {
        return false;
    }

    if (deployment.getServiceName() == null || deployment.getServiceName().isBlank()) {
        return false;
    }

    try {
        return kubernetesModelServingService.isModelServerReady(
                deployment.getDeploymentName(),
                deployment.getServiceName()
        );
    } catch (Exception e) {
        System.out.println("[MODEL_DEPLOYMENT] Kubernetes readiness check failed for "
                + deployment.getDeploymentName()
                + " / "
                + deployment.getServiceName()
                + " : "
                + e.getMessage());
        return false;
    }
}

    private ModelDeploymentResponse enrichDeploymentResponse(ModelDeploymentResponse response) {
        if (response == null) {
            return null;
        }

        String registeredModelName = response.getRegisteredModelName();

        if (!hasText(registeredModelName)) {
            return response;
        }

        try {
            String mlflowProductionVersion = mlflowTrackingClientService.getProductionVersion(
                    registeredModelName.trim()
            );

            if (hasText(mlflowProductionVersion)) {
                response.setMlflowProductionVersion(mlflowProductionVersion);
            } else if (hasText(response.getModelVersion())) {
                response.setMlflowProductionVersion(response.getModelVersion());
            }
        } catch (Exception e) {
            System.out.println("[MODEL_DEPLOYMENT] Could not read MLflow production version for "
                    + registeredModelName
                    + " : "
                    + e.getMessage());

            if (hasText(response.getModelVersion())) {
                response.setMlflowProductionVersion(response.getModelVersion());
            }
        }

        try {
            String kubernetesActiveVersion = findKubernetesActiveVersion(registeredModelName.trim());

            if (hasText(kubernetesActiveVersion)) {
                response.setKubernetesActiveVersion(kubernetesActiveVersion);
            } else if (hasText(response.getModelVersion())) {
                response.setKubernetesActiveVersion(response.getModelVersion());
            }
        } catch (Exception e) {
            System.out.println("[MODEL_DEPLOYMENT] Could not read Kubernetes active version for "
                    + registeredModelName
                    + " : "
                    + e.getMessage());

            if (hasText(response.getModelVersion())) {
                response.setKubernetesActiveVersion(response.getModelVersion());
            }
        }

        return response;
    }

    private String findKubernetesActiveVersion(String registeredModelName) {
        Optional<ModelDeployment> activeDeployment = findKubernetesReadyActiveDeployment(registeredModelName);
        return activeDeployment.map(ModelDeployment::getModelVersion).orElse(null);
    }

    private Optional<ModelDeployment> findKubernetesReadyActiveDeployment(String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return Optional.empty();
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(
                        registeredModelName.trim()
                );

        if (deployments == null || deployments.isEmpty()) {
            return Optional.empty();
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null) {
                continue;
            }

            if (!Boolean.TRUE.equals(deployment.getActive())) {
                continue;
            }

            if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
                continue;
            }

            if (isDeploymentReallyReadyInKubernetes(deployment)) {
                return Optional.of(deployment);
            }
        }

        return Optional.empty();
    }

    private ModelDeployment findLatestDeploymentForModelVersion(
            String registeredModelName,
            String modelVersion
    ) {
        if (registeredModelName == null || registeredModelName.isBlank()
                || modelVersion == null || modelVersion.isBlank()) {
            return null;
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
                        registeredModelName.trim(),
                        modelVersion.trim()
                );

        if (deployments == null || deployments.isEmpty()) {
            return null;
        }

        return deployments.get(0);
    }

@Transactional
public List<ModelDeploymentResponse> getVisibleDeploymentsForUser(User currentUser) {
    if (currentUser == null || currentUser.getId() == null) {
        throw new RuntimeException("Utilisateur connecté introuvable.");
    }

    List<ModelDeployment> deployments =
            modelDeploymentRepository.findAllByOrderByCreatedAtDesc();

    if (deployments == null || deployments.isEmpty()) {
        return List.of();
    }

    List<ModelDeployment> visibleDeployments = new ArrayList<>();

    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        /*
         * Sécurité principale :
         * On n'affiche jamais un modèle si :
         * - MySQL ne le marque pas DEPLOYED
         * - active != true
         * - Kubernetes ne confirme pas Deployment + Service ready
         *
         * Si Kubernetes ne confirme pas, isDeploymentReadyForUserInterface()
         * passe automatiquement le déploiement en OUT_OF_SYNC.
         */
        if (!isDeploymentReadyForUserInterface(deployment)) {
            continue;
        }

        DeploymentVisibility visibility = deployment.getVisibility();

        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        /*
         * PUBLIC_API :
         * visible par tous les utilisateurs connectés.
         */
        if (DeploymentVisibility.PUBLIC_API.equals(visibility)) {
            visibleDeployments.add(deployment);
            continue;
        }

        /*
         * PRIVATE :
         * visible seulement par le propriétaire du projet.
         */
        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            if (isDeploymentOwner(deployment, currentUser)) {
                visibleDeployments.add(deployment);
            }
            continue;
        }

        /*
         * SHARED :
         * visible par :
         * - le propriétaire du projet
         * - les utilisateurs explicitement autorisés
         *
         * C'était l'erreur chez toi :
         * le propriétaire n'était pas inclus dans ce cas.
         */
        if (DeploymentVisibility.SHARED.equals(visibility)) {
            if (isDeploymentOwner(deployment, currentUser)
                    || isDeploymentSharedWithUser(deployment, currentUser)) {
                visibleDeployments.add(deployment);
            }
        }
    }

    return deduplicateVisibleDeploymentsByModel(visibleDeployments)
            .stream()
            .map(ModelDeploymentResponse::fromEntity)
            .map(this::enrichDeploymentResponse)
            .toList();
}



    @Transactional
    public List<ModelDeploymentResponse> getOwnedDeploymentsForUser(User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new RuntimeException("Utilisateur connecté introuvable.");
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findAllByOrderByCreatedAtDesc();

        if (deployments == null || deployments.isEmpty()) {
            return List.of();
        }

        List<ModelDeployment> ownedDeployments = new ArrayList<>();

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!isDeploymentReadyForUserInterface(deployment)) {
                continue;
            }

            if (isDeploymentOwner(deployment, currentUser)) {
                ownedDeployments.add(deployment);
            }
        }

        return deduplicateVisibleDeploymentsByModel(ownedDeployments)
                .stream()
                .map(ModelDeploymentResponse::fromEntity)
                .map(this::enrichDeploymentResponse)
                .toList();
    }

    @Transactional
    public List<ModelDeploymentResponse> getAccessibleDeploymentsForUser(User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new RuntimeException("Utilisateur connecté introuvable.");
        }

        List<ModelDeployment> deployments =
                modelDeploymentRepository.findAllByOrderByCreatedAtDesc();

        if (deployments == null || deployments.isEmpty()) {
            return List.of();
        }

        List<ModelDeployment> accessibleDeployments = new ArrayList<>();

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!isDeploymentReadyForUserInterface(deployment)) {
                continue;
            }

            if (isDeploymentOwner(deployment, currentUser)) {
                continue;
            }

            DeploymentVisibility visibility = deployment.getVisibility();

            if (visibility == null) {
                visibility = DeploymentVisibility.PRIVATE;
            }

            if (DeploymentVisibility.PUBLIC_API.equals(visibility)) {
                accessibleDeployments.add(deployment);
                continue;
            }

            if (DeploymentVisibility.SHARED.equals(visibility)
                    && isDeploymentSharedWithUser(deployment, currentUser)) {
                accessibleDeployments.add(deployment);
            }
        }

        return deduplicateVisibleDeploymentsByModel(accessibleDeployments)
                .stream()
                .map(ModelDeploymentResponse::fromEntity)
                .map(this::enrichDeploymentResponse)
                .toList();
    }

    /**
     * Recherche dynamique des déploiements avec filtres et pagination.
     *
     * @param scope "owned" pour la page Déploiements, "accessible" pour la page Modèles accessibles.
     *
     * Pas de @Transactional : le filtrage appelle Kubernetes
     * (isDeploymentReadyForUserInterface) et peut marquer des déploiements
     * OUT_OF_SYNC (écriture) ; on ne garde jamais une connexion DB ouverte
     * pendant des appels réseau.
     */
    public SearchPageResponse<ModelDeploymentResponse> searchDeploymentsForUser(
            User currentUser,
            String scope,
            String q,
            String projectType,
            String dataType,
            int page,
            int size
    ) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new RuntimeException("Utilisateur connecté introuvable.");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        boolean ownedOnly = "owned".equalsIgnoreCase(scope);

        Specification<MlProject> projectSpec = deploymentProjectScopeSpecification(
                currentUser, ownedOnly, projectType, dataType);

        List<MlProject> candidateProjects = mlProjectRepository.findAll(projectSpec);

        if (candidateProjects == null || candidateProjects.isEmpty()) {
            return new SearchPageResponse<>(List.of(), 0, safePage, safeSize, false);
        }

        Map<Long, MlProject> projectById = new LinkedHashMap<>();
        List<Long> projectIds = new ArrayList<>();

        for (MlProject project : candidateProjects) {
            if (project == null || project.getId() == null) {
                continue;
            }
            projectById.put(project.getId(), project);
            projectIds.add(project.getId());
        }

        if (projectIds.isEmpty()) {
            return new SearchPageResponse<>(List.of(), 0, safePage, safeSize, false);
        }

        List<ModelDeployment> candidates =
                modelDeploymentRepository.findByProjectIdInOrderByCreatedAtDesc(projectIds);

        String normalizedQ = q == null ? "" : q.trim().toLowerCase();

        List<ScoredDeployment> scored = new ArrayList<>();

        for (ModelDeployment deployment : candidates) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (!isDeploymentReadyForUserInterface(deployment)) {
                continue;
            }

            if (!ownedOnly) {
                DeploymentVisibility visibility = deployment.getVisibility();
                if (visibility == null) {
                    visibility = DeploymentVisibility.PRIVATE;
                }

                boolean accessible = DeploymentVisibility.PUBLIC_API.equals(visibility)
                        || (DeploymentVisibility.SHARED.equals(visibility)
                        && isDeploymentSharedWithUser(deployment, currentUser));

                if (!accessible) {
                    continue;
                }
            }

            int relevance = deploymentRelevanceScore(deployment, projectById, normalizedQ);

            if (!normalizedQ.isEmpty() && relevance <= 0) {
                continue;
            }

            scored.add(new ScoredDeployment(deployment, relevance));
        }

        List<ModelDeployment> ordered = scored.stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.relevance, a.relevance);
                    if (cmp != 0) {
                        return cmp;
                    }
                    LocalDateTime ad = a.deployment.getCreatedAt();
                    LocalDateTime bd = b.deployment.getCreatedAt();
                    if (ad == null) {
                        return 1;
                    }
                    if (bd == null) {
                        return -1;
                    }
                    return bd.compareTo(ad);
                })
                .map(s -> s.deployment)
                .toList();

        List<ModelDeployment> deduped = deduplicateVisibleDeploymentsByModel(ordered);

        List<ModelDeploymentResponse> all = deduped.stream()
                .map(ModelDeploymentResponse::fromEntity)
                .map(this::enrichDeploymentResponse)
                .toList();

        long total = all.size();
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<ModelDeploymentResponse> items = from >= all.size()
                ? List.of()
                : all.subList(from, to);

        return new SearchPageResponse<>(items, total, safePage, safeSize, to < all.size());
    }

    private Specification<MlProject> deploymentProjectScopeSpecification(
            User currentUser,
            boolean ownedOnly,
            String projectType,
            String dataType
    ) {
        return (Root<MlProject> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNotNull(root.get("owner")));

            if (ownedOnly) {
                predicates.add(cb.equal(root.get("owner"), currentUser));
            } else {
                predicates.add(cb.notEqual(root.get("owner"), currentUser));
            }

            if (projectType != null && !projectType.isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(root.get("projectType")),
                        projectType.trim().toLowerCase()
                ));
            }

            if (dataType != null && !dataType.isBlank()) {
                predicates.add(cb.equal(
                        cb.upper(root.get("dataType")),
                        dataType.trim().toUpperCase()
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private int deploymentRelevanceScore(
            ModelDeployment deployment,
            Map<Long, MlProject> projectById,
            String q
    ) {
        if (q == null || q.isEmpty()) {
            return 0;
        }

        int score = 0;

        String registered = deployment.getRegisteredModelName();
        if (registered != null) {
            String lower = registered.toLowerCase();
            if (lower.equals(q)) {
                score += 100;
            } else if (lower.startsWith(q)) {
                score += 80;
            } else if (lower.contains(q)) {
                score += 60;
            }
        }

        String name = deployment.getDeploymentName();
        if (name != null && name.toLowerCase().contains(q)) {
            score += 50;
        }

        String version = deployment.getModelVersion();
        if (version != null && version.toLowerCase().contains(q)) {
            score += 20;
        }

        MlProject project = projectById.get(deployment.getProjectId());
        if (project != null) {
            if (project.getName() != null && project.getName().toLowerCase().contains(q)) {
                score += 40;
            }
            if (project.getDescription() != null && project.getDescription().toLowerCase().contains(q)) {
                score += 30;
            }
        }

        return score;
    }

    private static class ScoredDeployment {
        private final ModelDeployment deployment;
        private final int relevance;

        private ScoredDeployment(ModelDeployment deployment, int relevance) {
            this.deployment = deployment;
            this.relevance = relevance;
        }
    }

    @Transactional
    public void deleteDeploymentsForProject(Long projectId) {
        if (projectId == null) {
            return;
        }

        List<ModelDeployment> deployments = modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            System.out.println("[DELETE PROJECT DEPLOYMENT] No deployments found for projectId=" + projectId);
            return;
        }

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            try {
                if (hasText(deployment.getDeploymentName()) && hasText(deployment.getServiceName())) {
                    kubernetesModelServingService.deleteModelServer(deployment.getDeploymentName(), deployment.getServiceName());

                    System.out.println("[DELETE PROJECT DEPLOYMENT] Deleted Kubernetes deployment/service: "
                            + deployment.getDeploymentName()
                            + " / "
                            + deployment.getServiceName());
                }
            } catch (Exception e) {
                System.out.println("[DELETE PROJECT DEPLOYMENT] Failed to delete Kubernetes resources for deploymentId="
                        + deployment.getId()
                        + " reason="
                        + e.getMessage());
            }

            try {
                modelDeploymentShareRepository.deleteByDeploymentId(deployment.getId());
                modelDeploymentShareRepository.flush();
                modelDeploymentRepository.delete(deployment);
                modelDeploymentRepository.flush();

                System.out.println("[DELETE PROJECT DEPLOYMENT] Deleted DB deployment id=" + deployment.getId());

            } catch (Exception e) {
                System.out.println("[DELETE PROJECT DEPLOYMENT] Failed to delete DB deploymentId="
                        + deployment.getId()
                        + " reason="
                        + e.getMessage());

                throw new RuntimeException("Impossible de supprimer le déploiement id=" + deployment.getId(), e);
            }
        }
    }

    private List<ModelDeployment> filterReadyDeploymentsForDisplay(List<ModelDeployment> deployments) {
        if (deployments == null || deployments.isEmpty()) {
            return List.of();
        }

        List<ModelDeployment> readyDeployments = new ArrayList<>();

        for (ModelDeployment deployment : deployments) {
            if (isDeploymentReadyForUserInterface(deployment)) {
                readyDeployments.add(deployment);
            }
        }

        return deduplicateForDisplay(readyDeployments);
    }

    private boolean isDeploymentReadyForUserInterface(ModelDeployment deployment) {
        if (deployment == null || deployment.getId() == null) {
            return false;
        }
        if (!DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
            return false;
        }
        if (!Boolean.TRUE.equals(deployment.getActive())) {
            return false;
        }
        if (!isDeploymentReallyReadyInKubernetes(deployment)) {
            markDeploymentOutOfSync(deployment, "Deployment hidden because Kubernetes Deployment/Service are missing or not ready.");
            System.out.println("[VISIBLE_DEPLOYMENTS] Hidden OUT_OF_SYNC deployment: "
                    + deployment.getRegisteredModelName()
                    + " v"
                    + deployment.getModelVersion());
            return false;
        }
        return true;
    }

    private void markDeploymentOutOfSync(ModelDeployment deployment, String message) {
    if (deployment == null || deployment.getId() == null) {
        return;
    }

    deployment.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
    deployment.setActive(false);
    deployment.setErrorMessage(safeErrorMessage(
            hasText(message)
                    ? message
                    : "Deployment is out of sync with Kubernetes."
    ));

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    notifyDeploymentOutOfSyncIfNeeded(saved);
}

    private boolean isDeploymentOwner(ModelDeployment deployment, User currentUser) {
        if (deployment == null || currentUser == null || currentUser.getId() == null || deployment.getProjectId() == null) {
            return false;
        }
        Optional<MlProject> projectOptional = mlProjectRepository.findById(deployment.getProjectId());
        if (projectOptional.isEmpty()) {
            return false;
        }
        MlProject project = projectOptional.get();
        return project.getOwner() != null
                && project.getOwner().getId() != null
                && project.getOwner().getId().equals(currentUser.getId());
    }

    /*
     * Vérifie que l'utilisateur est le propriétaire du déploiement.
     * Utilisé par le SMART MODEL GATEWAY pour les opérations owner-only.
     */
    public boolean isDeploymentOwnedByUser(Long deploymentId, User currentUser) {
        ModelDeployment deployment = findDeploymentOrThrow(deploymentId);
        return isDeploymentOwner(deployment, currentUser);
    }

    /*
     * Résout le déploiement Kubernetes actif et prêt pour une clé SMART.
     *
     * Règles :
     * - PRIVATE : accès externe refusé (403).
     * - SHARED / PUBLIC_API : une clé API valide suffit à l'appel externe.
     *   Le partage reste une notion interne à la plateforme (X-USER-EMAIL supprimé).
     *
     * Réutilise la logique existante de résolution du déploiement actif.
     */
    public ModelDeployment findGatewayDeploymentForExternalApiOrThrow(
            Long deploymentId
    ) {
        ModelDeployment deployment = findDeploymentOrThrow(deploymentId);

        DeploymentVisibility visibility = deployment.getVisibility();

        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "EXTERNAL_ACCESS_DISABLED",
                    "Ce modèle est privé et ne peut pas être utilisé via l'API externe."
            );
        }

        if (!hasText(deployment.getRegisteredModelName())) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modèle introuvable pour cette clé API."
            );
        }

        ModelDeployment activeDeployment = findKubernetesReadyActiveDeployment(
                        deployment.getRegisteredModelName().trim()
                )
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "MODEL_NOT_DEPLOYED",
                        "Aucun déploiement Kubernetes actif et prêt trouvé pour ce modèle."
                ));

        return activeDeployment;
    }

    private boolean isDeploymentSharedWithUser(ModelDeployment deployment, User currentUser) {
        if (deployment == null || currentUser == null || currentUser.getId() == null) {
            return false;
        }
        if (deployment.getId() != null
                && modelDeploymentShareRepository.existsByDeploymentIdAndSharedWithUserId(deployment.getId(), currentUser.getId())) {
            return true;
        }
        if (!hasText(deployment.getRegisteredModelName())) {
            return false;
        }
        List<ModelDeployment> sameModelDeployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(deployment.getRegisteredModelName().trim());
        if (sameModelDeployments == null || sameModelDeployments.isEmpty()) {
            return false;
        }
        for (ModelDeployment sameModelDeployment : sameModelDeployments) {
            if (sameModelDeployment == null || sameModelDeployment.getId() == null) {
                continue;
            }
            if (modelDeploymentShareRepository.existsByDeploymentIdAndSharedWithUserId(sameModelDeployment.getId(), currentUser.getId())) {
                return true;
            }
        }
        return false;
    }

    private List<ModelDeployment> deduplicateVisibleDeploymentsByModel(List<ModelDeployment> deployments) {
        if (deployments == null || deployments.isEmpty()) {
            return List.of();
        }
        Map<String, ModelDeployment> bestByModel = new LinkedHashMap<>();
        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getRegisteredModelName() == null) {
                continue;
            }
            String key = deployment.getRegisteredModelName().trim().toLowerCase();
            ModelDeployment currentBest = bestByModel.get(key);
            if (isBetterVisibleDeployment(deployment, currentBest)) {
                bestByModel.put(key, deployment);
            }
        }
        return new ArrayList<>(bestByModel.values());
    }

    private boolean isBetterVisibleDeployment(ModelDeployment candidate, ModelDeployment currentBest) {
        if (candidate == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }
        int candidateScore = visibleDeploymentScore(candidate);
        int currentScore = visibleDeploymentScore(currentBest);
        if (candidateScore != currentScore) {
            return candidateScore > currentScore;
        }
        Integer candidateVersion = parseVersionNumber(candidate.getModelVersion());
        Integer currentVersion = parseVersionNumber(currentBest.getModelVersion());
        if (candidateVersion != null && currentVersion != null) {
            int cmp = candidateVersion.compareTo(currentVersion);
            if (cmp != 0) {
                return cmp > 0;
            }
        }
        LocalDateTime candidateUpdatedAt = candidate.getUpdatedAt();
        LocalDateTime currentUpdatedAt = currentBest.getUpdatedAt();
        if (candidateUpdatedAt != null && currentUpdatedAt != null) {
            int cmp = candidateUpdatedAt.compareTo(currentUpdatedAt);
            if (cmp != 0) {
                return cmp > 0;
            }
        }
        Long candidateId = candidate.getId();
        Long currentId = currentBest.getId();
        return candidateId != null && currentId != null && candidateId > currentId;
    }

    private int visibleDeploymentScore(ModelDeployment deployment) {
        if (deployment == null) {
            return 0;
        }
        int score = 0;
        if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
            score += 100;
        }
        if (Boolean.TRUE.equals(deployment.getActive())) {
            score += 50;
        }
        if (DeploymentVisibility.PUBLIC_API.equals(deployment.getVisibility())) {
            score += 10;
        }
        if (DeploymentVisibility.SHARED.equals(deployment.getVisibility())) {
            score += 10;
        }
        return score;
    }

    private Integer parseVersionNumber(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(version.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private List<ModelDeployment> deduplicateForDisplay(List<ModelDeployment> deployments) {
    if (deployments == null || deployments.isEmpty()) {
        return List.of();
    }

    Map<String, ModelDeployment> bestByModelVersion = new LinkedHashMap<>();

    for (ModelDeployment deployment : deployments) {
        if (deployment == null) {
            continue;
        }

        String key = deploymentKey(
                deployment.getRegisteredModelName(),
                deployment.getModelVersion()
        );

        ModelDeployment currentBest = bestByModelVersion.get(key);

        if (isBetterDeploymentForDisplay(deployment, currentBest)) {
            bestByModelVersion.put(key, deployment);
        }
    }

    return new ArrayList<>(bestByModelVersion.values());
}

private boolean isBetterDeploymentForDisplay(
        ModelDeployment candidate,
        ModelDeployment currentBest
) {
    if (candidate == null) {
        return false;
    }

    if (currentBest == null) {
        return true;
    }

    int candidateScore = deploymentDisplayScore(candidate);
    int currentScore = deploymentDisplayScore(currentBest);

    if (candidateScore != currentScore) {
        return candidateScore > currentScore;
    }

    LocalDateTime candidateUpdatedAt = candidate.getUpdatedAt();
    LocalDateTime currentUpdatedAt = currentBest.getUpdatedAt();

    if (candidateUpdatedAt != null && currentUpdatedAt != null) {
        int compare = candidateUpdatedAt.compareTo(currentUpdatedAt);

        if (compare != 0) {
            return compare > 0;
        }
    }

    Long candidateId = candidate.getId();
    Long currentId = currentBest.getId();

    return candidateId != null
            && currentId != null
            && candidateId > currentId;
}

private int deploymentDisplayScore(ModelDeployment deployment) {
    if (deployment == null) {
        return 0;
    }

    if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
            && Boolean.TRUE.equals(deployment.getActive())) {
        return 100;
    }

    if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
        return 90;
    }

    if (DeploymentStatus.DEPLOYING.equals(deployment.getDeploymentStatus())) {
        return 60;
    }

    if (DeploymentStatus.PENDING.equals(deployment.getDeploymentStatus())) {
        return 40;
    }

    if (DeploymentStatus.OUT_OF_SYNC.equals(deployment.getDeploymentStatus())) {
        return 30;
    }

    if (DeploymentStatus.FAILED.equals(deployment.getDeploymentStatus())) {
        return 10;
    }

    return 0;
}

private String deploymentKey(String registeredModelName, String modelVersion) {
    String model = registeredModelName == null
            ? "-"
            : registeredModelName.trim().toLowerCase();

    String version = modelVersion == null
            ? "-"
            : modelVersion.trim().toLowerCase();

    return model + "::" + version;
}

private void cleanupDuplicateDeploymentsForSameModelVersion(
        String registeredModelName,
        String modelVersion,
        Long deploymentIdToKeep
) {
    if (registeredModelName == null || registeredModelName.isBlank()
            || modelVersion == null || modelVersion.isBlank()
            || deploymentIdToKeep == null) {
        return;
    }

    List<ModelDeployment> deployments =
            modelDeploymentRepository.findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
                    registeredModelName.trim(),
                    modelVersion.trim()
            );

    if (deployments == null || deployments.isEmpty()) {
        return;
    }

    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        if (deployment.getId().equals(deploymentIdToKeep)) {
            continue;
        }

        deployment.setActive(false);

        if (DeploymentStatus.DEPLOYING.equals(deployment.getDeploymentStatus())
                || DeploymentStatus.PENDING.equals(deployment.getDeploymentStatus())) {
            deployment.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
            deployment.setErrorMessage(safeErrorMessage(
                    "Duplicate deployment ignored because deploymentId="
                            + deploymentIdToKeep
                            + " is the authoritative deployment for this model/version."
            ));
        }

        modelDeploymentRepository.save(deployment);
    }

    modelDeploymentRepository.flush();
}

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ModelDeployment promoteReadyDeployment(
        ModelDeployment deployment,
        Long projectId,
        ApiAccessConfig apiAccessConfig
) {
    if (deployment == null || deployment.getId() == null) {
        throw new RuntimeException("Deployment introuvable pour promotion.");
    }

    if (!isDeploymentReallyReadyInKubernetes(deployment)) {
        throw new RuntimeException("Impossible de promouvoir : Kubernetes n'est pas prêt.");
    }

    deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
    deployment.setActive(true);
    deployment.setDeployedAt(LocalDateTime.now());
    deployment.setErrorMessage(null);

    applyApiAccessConfig(deployment, apiAccessConfig);

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    /*
     * Blue/green : on ne bascule le trafic qu'après readiness Kubernetes.
     * Le Service stable du modèle bascule son selector vers le track candidat :
     * opération atomique côté Kubernetes, aucun pod ne redémarre.
     */
    if (hasText(saved.getStableServiceName())) {
        String track = normalizeServingTrack(saved.getServingTrack());

        try {
            kubernetesModelServingService.switchStableServiceTrack(
                    saved.getStableServiceName(),
                    saved.getRegisteredModelName(),
                    track
            );

            System.out.println("[TRAFFIC_SWITCH] Traffic switched to track="
                    + track
                    + ", model=" + saved.getRegisteredModelName()
                    + ", version=" + saved.getModelVersion()
                    + ", stableService=" + saved.getStableServiceName());
        } catch (Exception e) {
            System.out.println("[TRAFFIC_SWITCH] Failed to switch stable service. "
                    + "model=" + saved.getRegisteredModelName()
                    + ", version=" + saved.getModelVersion()
                    + ", stableService=" + saved.getStableServiceName()
                    + ", reason=" + e.getMessage());
        }
    }

    /*
     * Blue/green : les anciennes versions actives sont désactivées (active=false)
     * MAIS leurs ressources Kubernetes restent en place pendant la fenêtre de
     * rollback. Le reconciler les nettoie ensuite (STALE_DEPLOYMENT).
     */
    deactivatePreviousActiveDeploymentsForModel(
            saved.getRegisteredModelName(),
            saved.getId()
    );

    cleanupDuplicateDeploymentsForSameModelVersion(
            saved.getRegisteredModelName(),
            saved.getModelVersion(),
            saved.getId()
    );

    ModelDeployment finalSaved = modelDeploymentRepository.saveAndFlush(saved);

    notifyDeploymentDeployedIfNeeded(finalSaved);

    System.out.println("[PRODUCTION_PROMOTED] model="
            + finalSaved.getRegisteredModelName()
            + ", version=" + finalSaved.getModelVersion()
            + ", deploymentId=" + finalSaved.getId()
            + ", track=" + normalizeServingTrack(finalSaved.getServingTrack())
            + ", active=" + finalSaved.getActive());

    return finalSaved;
}

private void notifyDeploymentDeployedIfNeeded(ModelDeployment deployment) {
    if (deployment == null || deployment.getId() == null) {
        return;
    }

    try {
        notificationService.notifyDeploymentDeployed(
                deployment.getId(),
                resolveDeploymentOwner(deployment),
                buildDeploymentDisplayName(deployment)
        );
    } catch (Exception e) {
        System.out.println("[NOTIFICATION][DEPLOYMENT] Could not notify DEPLOYED for deploymentId="
                + deployment.getId()
                + ", reason="
                + e.getMessage());
    }
}

private void notifyDeploymentStartedIfNeeded(ModelDeployment deployment) {
    if (deployment == null || deployment.getId() == null) {
        return;
    }

    try {
        notificationService.notifyDeploymentStarted(
                deployment.getId(),
                resolveDeploymentOwner(deployment),
                buildDeploymentDisplayName(deployment)
        );
    } catch (Exception e) {
        System.out.println("[NOTIFICATION][DEPLOYMENT] Could not notify STARTED for deploymentId="
                + deployment.getId()
                + ", reason="
                + e.getMessage());
    }
}

private void notifyDeploymentFailedIfNeeded(ModelDeployment deployment) {
    if (deployment == null || deployment.getId() == null) {
        return;
    }

    try {
        notificationService.notifyDeploymentFailed(
                deployment.getId(),
                resolveDeploymentOwner(deployment),
                buildDeploymentDisplayName(deployment),
                deployment.getErrorMessage()
        );
    } catch (Exception e) {
        System.out.println("[NOTIFICATION][DEPLOYMENT] Could not notify FAILED for deploymentId="
                + deployment.getId()
                + ", reason="
                + e.getMessage());
    }
}

private void notifyDeploymentOutOfSyncIfNeeded(ModelDeployment deployment) {
    if (deployment == null || deployment.getId() == null) {
        return;
    }

    try {
        notificationService.notifyDeploymentOutOfSync(
                deployment.getId(),
                resolveDeploymentOwner(deployment),
                buildDeploymentDisplayName(deployment)
        );
    } catch (Exception e) {
        System.out.println("[NOTIFICATION][DEPLOYMENT] Could not notify OUT_OF_SYNC for deploymentId="
                + deployment.getId()
                + ", reason="
                + e.getMessage());
    }
}

private User resolveDeploymentOwner(ModelDeployment deployment) {
    if (deployment == null || deployment.getProjectId() == null) {
        return null;
    }

    try {
        return mlProjectRepository.findById(deployment.getProjectId())
                .map(MlProject::getOwner)
                .orElse(null);
    } catch (Exception e) {
        System.out.println("[NOTIFICATION][DEPLOYMENT] Could not resolve owner for deploymentId="
                + deployment.getId()
                + ", projectId="
                + deployment.getProjectId()
                + ", reason="
                + e.getMessage());
        return null;
    }
}

private String buildDeploymentDisplayName(ModelDeployment deployment) {
    if (deployment == null) {
        return "model deployment";
    }

    if (hasText(deployment.getDeploymentName())) {
        return deployment.getDeploymentName();
    }

    String model = hasText(deployment.getRegisteredModelName())
            ? deployment.getRegisteredModelName().trim()
            : "model";

    String version = hasText(deployment.getModelVersion())
            ? deployment.getModelVersion().trim()
            : "unknown";

    return model + " v" + version;
}
    


private boolean isValidProductionExperimentForDeployment(MlExperiment experiment) {
    if (experiment == null) {
        return false;
    }

    return Boolean.TRUE.equals(experiment.getProduction())
            && "COMPLETED".equalsIgnoreCase(String.valueOf(experiment.getStatus()))
            && experiment.getMlflowRunId() != null
            && !experiment.getMlflowRunId().isBlank()
            && experiment.getRegisteredModelName() != null
            && !experiment.getRegisteredModelName().isBlank()
            && experiment.getModelVersion() != null
            && !experiment.getModelVersion().isBlank()
            && experiment.getProductionVersion() != null
            && !experiment.getProductionVersion().isBlank()
            && experiment.getProductionVersion().trim().equalsIgnoreCase(experiment.getModelVersion().trim())
            && experiment.getPrimaryMetricValue() != null
            && experiment.getGlobalScore() != null;
}

}
