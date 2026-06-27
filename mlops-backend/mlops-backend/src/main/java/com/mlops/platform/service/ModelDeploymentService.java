package com.mlops.platform.service;

import com.mlops.platform.dto.DeployModelRequestDto;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.dto.ModelDeploymentShareResponse;
import com.mlops.platform.dto.ShareDeploymentRequest;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.ModelDeploymentShare;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Value("${custom.predict.api.url:http://localhost:8000}")
    private String predictApiUrl;

    @Value("${app.kubernetes.namespace:mlops}")
    private String namespaceName;

    @Value("${app.serving.dedicated-image.enabled:true}")
    private boolean dedicatedServingImageEnabled;

    @Value("${app.deployment.default-docker-image:docker.io/salmaghrib2003/mlops-generic-model-server:latest}")
    private String defaultDockerImage;

    @Value("${app.mlops.auto-deploy.enabled:true}")
    private boolean autoDeployEnabled;

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
            NotificationService notificationService
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

    String registeredModelName = firstNonBlank(managedExperiment.getRegisteredModelName());
    String modelVersion = firstNonBlank(managedExperiment.getModelVersion());
    String productionVersion = firstNonBlank(managedExperiment.getProductionVersion());

   if (!hasText(registeredModelName)) {
    return null;
}

if (!hasText(modelVersion)) {
    try {
        String registryProductionVersion = mlflowTrackingClientService.getProductionVersion(registeredModelName.trim());

        if (hasText(registryProductionVersion)) {
            modelVersion = registryProductionVersion.trim();

            managedExperiment.setModelVersion(modelVersion);
managedExperiment.setProductionVersion(modelVersion);
managedExperiment.setProduction(true);
managedExperiment.setErrorMessage(null);

productionVersion = modelVersion;

managedExperiment = mlExperimentRepository.saveAndFlush(managedExperiment);

            System.out.println("[AUTO_DEPLOY] modelVersion was empty, recovered from MLflow production alias. "
                    + "experimentId=" + managedExperiment.getId()
                    + ", model=" + registeredModelName
                    + ", version=" + modelVersion);
        }
    } catch (Exception e) {
        System.out.println("[AUTO_DEPLOY] Could not recover modelVersion from MLflow production alias. "
                + "model=" + registeredModelName
                + ", reason=" + e.getMessage());
    }
}

if (!hasText(modelVersion)) {
    return null;
}

    registeredModelName = registeredModelName.trim();
    modelVersion = modelVersion.trim();

    boolean markedProduction = Boolean.TRUE.equals(managedExperiment.getProduction());
    boolean versionIsProduction = hasText(productionVersion)
            && productionVersion.trim().equalsIgnoreCase(modelVersion);

    if (!markedProduction && !versionIsProduction) {
        return null;
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
                .orElseThrow(() -> new RuntimeException("Deployment introuvable avec id=" + deploymentId));

        deployment.setVisibility(DeploymentVisibility.SHARED);
        deployment.setApiEnabled(true);

        if (deployment.getApiKey() == null || deployment.getApiKey().isBlank()) {
            deployment.setApiKey(generateUniqueApiKey());
            deployment.setApiKeyCreatedAt(LocalDateTime.now());
            deployment.setApiKeyLastUsedAt(null);
        }

        modelDeploymentRepository.saveAndFlush(deployment);

        List<Long> userIds = request == null || request.getUserIds() == null
                ? List.of()
                : request.getUserIds();

        modelDeploymentShareRepository.deleteByDeploymentId(deploymentId);

        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("Utilisateur introuvable avec id=" + userId));

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

        modelDeploymentShareRepository.flush();

        return getDeploymentShares(deploymentId);
    }

    @Transactional
    public void removeDeploymentShare(Long deploymentId, Long userId) {
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
                        true,
                        existing.getVisibility() != null
                                ? existing.getVisibility()
                                : DeploymentVisibility.PUBLIC_API,
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
                true,
                DeploymentVisibility.PUBLIC_API,
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
        deployment.setVisibility(
                apiConfig.visibility != null
                        ? apiConfig.visibility
                        : DeploymentVisibility.PUBLIC_API
        );
        deployment.setApiKey(apiConfig.apiKey);
        deployment.setApiKeyCreatedAt(apiConfig.apiKeyCreatedAt);
        deployment.setApiKeyLastUsedAt(apiConfig.apiKeyLastUsedAt);
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

    String modelVersion = firstNonBlank(
            experiment.getModelVersion(),
            experiment.getProductionVersion(),
            productionVersion
    );

    if (!hasText(modelVersion)) {
        modelVersion = productionVersion;
    }

    modelVersion = modelVersion.trim();

    /*
     * Correction importante :
     * Si l'expérience a modelVersion=null ou une ancienne valeur,
     * mais que MLflow Registry possède bien alias production,
     * on force la version à la version production MLflow.
     */
    if (!modelVersion.equalsIgnoreCase(productionVersion)) {
        System.out.println("[MODEL_DEPLOYMENT] Native Registry version corrected from experiment value to production alias. "
                + "experimentId=" + experiment.getId()
                + ", registeredModelName=" + registeredModelName
                + ", oldModelVersion=" + modelVersion
                + ", productionVersion=" + productionVersion);

        modelVersion = productionVersion;
    }

    experiment.setRegisteredModelName(registeredModelName);
    experiment.setModelName(registeredModelName);
    experiment.setModelVersion(modelVersion);
    experiment.setProductionVersion(productionVersion);
    experiment.setProduction(true);
    experiment.setErrorMessage(null);

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
        && DeploymentStatus.DEPLOYING.equals(existingSameVersion.getDeploymentStatus())) {

    boolean resourcesExist = false;

    try {
        resourcesExist = kubernetesModelServingService.modelServerResourcesExist(
                existingSameVersion.getDeploymentName(),
                existingSameVersion.getServiceName()
        );
    } catch (Exception e) {
        System.out.println("[MODEL_DEPLOYMENT] Could not verify DEPLOYING Kubernetes resources. "
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
    deployment.setErrorMessage(null);

    applyApiAccessConfig(deployment, apiAccessConfig);

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

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
                imageToDeploy,
                registeredModelName,
                modelVersion,
                dataType
        );

        String finalPredictionEndpoint = buildEndpointForDataType(serviceBaseUrl, dataType);
        saved.setPredictionEndpoint(finalPredictionEndpoint);

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
                    + ", candidateService=" + serviceName);

            return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(pending));
        }

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


@Transactional
public void reconcileDeploymentsWithKubernetes() {
    List<ModelDeployment> deployments = modelDeploymentRepository.findAll();

    if (deployments == null || deployments.isEmpty()) {
        return;
    }

    for (ModelDeployment deployment : deployments) {
        if (deployment == null || deployment.getId() == null) {
            continue;
        }

        /*
         * Candidate DEPLOYING devenue prête : switch blue/green ici.
         */
        if (DeploymentStatus.DEPLOYING.equals(deployment.getDeploymentStatus())) {
            boolean readyInKubernetes = isDeploymentReallyReadyInKubernetes(deployment);

            if (!readyInKubernetes) {
                continue;
            }

            ModelDeployment deployed = promoteReadyDeployment(
                    deployment,
                    deployment.getProjectId(),
                    resolveApiAccessConfigForDeployment(deployment.getRegisteredModelName())
            );

            System.out.println("[DEPLOY_SYNC] Candidate is now READY. Blue/green switch completed. "
                    + "model=" + deployed.getRegisteredModelName()
                    + ", version=" + deployed.getModelVersion()
                    + ", deploymentId=" + deployed.getId());

            continue;
        }

        /*
         * Version active marquée DEPLOYED mais plus prête dans Kubernetes.
         */
        if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                && Boolean.TRUE.equals(deployment.getActive())) {

            boolean readyInKubernetes = isDeploymentReallyReadyInKubernetes(deployment);

            if (!readyInKubernetes) {
                markDeploymentOutOfSync(
                        deployment,
                        "MySQL marked this model as DEPLOYED, but Kubernetes Deployment/Service are missing or not ready."
                );

                System.out.println("[DEPLOY_SYNC] OUT_OF_SYNC: Kubernetes resources missing for "
                        + deployment.getRegisteredModelName()
                        + " v"
                        + deployment.getModelVersion()
                        + " deployment="
                        + deployment.getDeploymentName()
                        + " service="
                        + deployment.getServiceName());
            }
        }
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

    ModelDeployment savedDeployment = modelDeploymentRepository.saveAndFlush(deployment);

    return enrichDeploymentResponse(ModelDeploymentResponse.fromEntity(savedDeployment));
}


    @Transactional
    public ModelDeploymentResponse getActiveDeploymentByProject(Long projectId) {
        if (projectId == null) {
            throw new RuntimeException("projectId est obligatoire.");
        }

        List<ModelDeployment> deployments = modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        if (deployments == null || deployments.isEmpty()) {
            throw new RuntimeException("Aucun déploiement trouvé pour ce projet id=" + projectId);
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

        throw new RuntimeException("Aucun déploiement actif et réellement prêt dans Kubernetes trouvé pour le projet id=" + projectId);
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

    deactivateActiveDeploymentsForProjectExcept(
            deployment.getProjectId(),
            deployment.getId()
    );

    deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
    deployment.setActive(true);
    deployment.setDeployedAt(LocalDateTime.now());
    deployment.setErrorMessage(null);

    ensureApiAccessEnabled(deployment);

    ModelDeployment saved = modelDeploymentRepository.saveAndFlush(deployment);

    notifyDeploymentDeployedIfNeeded(saved);

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

        if (currentVisibility == null || currentVisibility == DeploymentVisibility.PRIVATE) {
            currentVisibility = DeploymentVisibility.PUBLIC_API;
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
            throw new RuntimeException("registeredModelName is required to resolve serving image.");
        }

        if (!hasText(modelVersion)) {
            throw new RuntimeException("modelVersion is required to resolve serving image.");
        }

        String runtimeMode = safeRuntimeMode(experiment);

        /*
         * Règle demandée :
         * - run standard       -> image standard
         * - run Docker custom  -> image dédiée générée pour le modèle
         */
        if (isStandardRuntimeMode(runtimeMode)) {
            if (!hasText(defaultDockerImage)) {
                throw new RuntimeException("Default standard serving image is empty.");
            }

            System.out.println("[SERVING_IMAGE_RESOLVER] STANDARD runtime detected. "
                    + "Using standard serving image. "
                    + "experimentId=" + experiment.getId()
                    + ", runtimeMode=" + runtimeMode
                    + ", image=" + defaultDockerImage);

            return defaultDockerImage.trim();
        }

        if (isCustomDockerRuntimeMode(runtimeMode)) {
            if (!dedicatedServingImageEnabled) {
                throw new RuntimeException(
                        "Custom Docker runtime requires a dedicated serving image, "
                                + "but app.serving.dedicated-image.enabled=false. "
                                + "experimentId=" + experiment.getId()
                );
            }

            String modelUriForDependencies =
                    "models:/" + registeredModelName.trim() + "/" + modelVersion.trim();

            System.out.println("[SERVING_IMAGE_RESOLVER] CUSTOM DOCKER runtime detected. "
                    + "Building dedicated serving image. "
                    + "experimentId=" + experiment.getId()
                    + ", runtimeMode=" + runtimeMode
                    + ", modelUri=" + modelUriForDependencies
                    + ", dataType=" + dataType);

            String dedicatedImage = modelServingImageBuildService.buildServingImageForModel(
                    registeredModelName.trim(),
                    modelVersion.trim(),
                    modelUriForDependencies,
                    dataType
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
                    + ", image=" + dedicatedImage);

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
     * Blue/green : on désactive les anciennes versions seulement après readiness Kubernetes.
     */
    deactivateActiveDeploymentsForProjectExcept(projectId, saved.getId());

    cleanupDuplicateDeploymentsForSameModelVersion(
            saved.getRegisteredModelName(),
            saved.getModelVersion(),
            saved.getId()
    );

    ModelDeployment finalSaved = modelDeploymentRepository.saveAndFlush(saved);

    notifyDeploymentDeployedIfNeeded(finalSaved);

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
    

}