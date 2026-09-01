package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AutoProductionDeploymentReconcilerService {

    private static final String PRODUCTION_ALIAS = "production";
    private static final String STANDARDIZED_MARKER_PREFIX =
            "[MODEL_ARTIFACT_STANDARDIZED_VERSION=";
    private static final long STALE_PROMOTION_MINUTES = 15;

    private final MlExperimentRepository mlExperimentRepository;
    private final ModelDeploymentService modelDeploymentService;
    private final ModelArtifactStandardizerService modelArtifactStandardizerService;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final PlatformTransactionManager transactionManager;
    private final RunPipelineStepService runPipelineStepService;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final ModelQualityService modelQualityService;
    private final NotificationService notificationService;
    private final MlProjectRepository mlProjectRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.mlops.auto-deploy.enabled:true}")
    private boolean autoDeployEnabled;

    @Value("${app.mlops.production-reconciler.enabled:true}")
    private boolean reconcilerEnabled;

    @Value("${app.mlflow.tracking-uri:${mlflow.tracking.uri:http://mlflow-service:5000}}")
    private String localMlflowTrackingUri;

    public AutoProductionDeploymentReconcilerService(
            MlExperimentRepository mlExperimentRepository,
            ModelDeploymentService modelDeploymentService,
            ModelArtifactStandardizerService modelArtifactStandardizerService,
            MlflowTrackingClientService mlflowTrackingClientService,
            PlatformTransactionManager transactionManager,
            RunPipelineStepService runPipelineStepService,
            ModelDeploymentRepository modelDeploymentRepository,
            ModelQualityService modelQualityService,
            NotificationService notificationService,
            MlProjectRepository mlProjectRepository
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.modelDeploymentService = modelDeploymentService;
        this.modelArtifactStandardizerService = modelArtifactStandardizerService;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.transactionManager = transactionManager;
        this.runPipelineStepService = runPipelineStepService;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.modelQualityService = modelQualityService;
        this.notificationService = notificationService;
        this.mlProjectRepository = mlProjectRepository;
    }

    @Scheduled(
            fixedDelayString = "${app.mlops.production-reconciler.delay-ms:30000}",
            initialDelayString = "${app.mlops.production-reconciler.initial-delay-ms:20000}"
    )
    @SchedulerLock(
            name = "autoProductionDeploymentReconciler",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT2S"
    )
    public void reconcileProductionDeployments() {
        if (!running.compareAndSet(false, true)) {
            log("Previous reconciliation still running. Skip.");
            return;
        }

        try {
            /*
             * Finalisation des étapes Production/Déploiement du tracker :
             * indépendante des flags auto-deploy/reconciler, pour que le statut
             * des étapes reste toujours cohérent.
             */
            reconcilePipelineSteps();

            if (!autoDeployEnabled || !reconcilerEnabled) {
                return;
            }

            log("===================================");
            log("Start Production -> Deployment check");
            log("===================================");

            reconcileDeploymentStatuses();

            List<MlExperiment> experiments =
                    mlExperimentRepository.findAllForProductionReconciliation();

            if (experiments == null || experiments.isEmpty()) {
                log("No experiments found.");
                return;
            }

            /*
             * Une seule candidate gagnante par projet + modèle.
             * La comparaison se fait sur globalScore (plus grand = meilleur),
             * puis sur la date et l'id pour rendre le choix déterministe.
             */
            Map<String, MlExperiment> bestCandidates = new LinkedHashMap<>();

            for (MlExperiment experiment : experiments) {
                if (!isEligibleScoredCandidate(experiment)) {
                    continue;
                }

                String key = buildModelKey(experiment);
                MlExperiment currentBest = bestCandidates.get(key);

                if (currentBest == null
                        || compareScoredCandidates(experiment, currentBest) > 0) {
                    bestCandidates.put(key, experiment);
                }
            }

            if (bestCandidates.isEmpty()) {
                log("No scored COMPLETED candidates found.");
                return;
            }

            for (MlExperiment bestCandidate : bestCandidates.values()) {
                MlExperiment promoted = promoteBestCandidate(bestCandidate);

                if (promoted == null) {
                    continue;
                }

                deployCandidate(promoted);
            }

            log("Finished Production -> Deployment check.");

        } catch (Exception e) {
            log("Global failure. Reason: " + safeMessage(e));
            e.printStackTrace();

        } finally {
            running.set(false);
        }
    }

    private void reconcileDeploymentStatuses() {
        try {
            modelDeploymentService.reconcileDeploymentsWithKubernetes();
            log("Deployment status reconciliation completed before candidate evaluation.");
        } catch (Exception e) {
            log("Deployment reconciliation failed before candidate evaluation: "
                    + safeMessage(e));
        }
    }

    /**
     * Finalise les étapes PRODUCTION_PROMOTED / DEPLOYMENT_READY de tous les runs.
     *
     * Règles :
     * - Run promu (production=true, version alignée sur l'alias @production) :
     *   PRODUCTION_PROMOTED -> SUCCEEDED ; DEPLOYMENT_READY -> SUCCEEDED si un
     *   déploiement actif existe, RUNNING si un déploiement est en cours.
     * - Run terminal non promu : PRODUCTION_PROMOTED/DEPLOYMENT_READY -> SKIPPED
     *   avec une raison claire, uniquement quand la décision est définitive
     *   (évite de "skippar" un futur vainqueur encore en cours de scoring).
     * - Run actif : on laisse PENDING (rien n'est décidé).
     */
    private void reconcilePipelineSteps() {
        List<MlExperiment> experiments;

        try {
            experiments = mlExperimentRepository.findAllForProductionReconciliation();
        } catch (Exception e) {
            log("Pipeline step reconciliation query failed. reason=" + safeMessage(e));
            return;
        }

        if (experiments == null || experiments.isEmpty()) {
            return;
        }

        Map<String, MlExperiment> bestCandidates = new LinkedHashMap<>();

        for (MlExperiment experiment : experiments) {
            if (!isEligibleScoredCandidate(experiment)) {
                continue;
            }

            String key = buildModelKey(experiment);
            MlExperiment currentBest = bestCandidates.get(key);

            if (currentBest == null
                    || compareScoredCandidates(experiment, currentBest) > 0) {
                bestCandidates.put(key, experiment);
            }
        }

        Set<Long> contenderIds = bestCandidates.values().stream()
                .map(MlExperiment::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        for (MlExperiment experiment : experiments) {
            try {
                finalizeExperimentPipelineSteps(experiment, contenderIds);
            } catch (Exception e) {
                log("Pipeline step finalization failed. experimentId="
                        + safeExperimentId(experiment)
                        + ", reason=" + safeMessage(e));
            }
        }
    }

    private void finalizeExperimentPipelineSteps(
            MlExperiment experiment,
            Set<Long> contenderIds
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        /*
         * On utilise directement l'entité chargée par findAllForProductionReconciliation
         * (JOIN FETCH project) : ses champs scalaires sont initialisés même après
         * la clôture de la session, contrairement à un reload() qui renverrait un
         * proxy Lazy de project. Les faits Promotion/Déploiement proviennent du
         * Registry MLflow (lecture HTTP fraîche) et non du cache d'entité.
         */
        MlExperiment current = experiment;

        boolean terminal = current.getStatus() == ExperimentStatus.COMPLETED
                || current.getStatus() == ExperimentStatus.FAILED
                || current.getStatus() == ExperimentStatus.CANCELLED;

        String modelName = resolveRegisteredModelName(current);
        String modelVersion = trimToNull(current.getModelVersion());
        boolean hasVersion = hasText(modelName) && hasText(modelVersion);

        String registryVersion = hasVersion
                ? getProductionVersionFromRegistryAlias(modelName)
                : null;

        boolean isPromoted = Boolean.TRUE.equals(current.getProduction())
                && hasText(current.getProductionVersion())
                && hasVersion
                && current.getProductionVersion().equalsIgnoreCase(modelVersion);

        if (isPromoted) {
            resolveTrailingStepsIfCompleted(current);
            runPipelineStepService.markProductionPromoted(
                    current,
                    "Version " + modelVersion + " promue en Production."
            );
            markDeploymentStepFromState(current, modelVersion);
            runPipelineStepService.resolveTerminalSteps(current);
            return;
        }

        /*
         * Alias @production == version de ce run : PROMU. On exige aussi que le
         * flag production de la ligne soit vrai : une ligne non-production dont la
         * version coïnciderait par hasard avec l'alias ne décrit PAS la version
         * promue, on la traite donc comme non-promue.
         */
        if (hasVersion
                && registryVersion != null
                && registryVersion.equalsIgnoreCase(modelVersion)
                && Boolean.TRUE.equals(current.getProduction())) {
            resolveTrailingStepsIfCompleted(current);
            runPipelineStepService.markProductionPromoted(
                    current,
                    "Version " + modelVersion + " alignée sur l'alias @production."
            );
            markDeploymentStepFromState(current, modelVersion);
            runPipelineStepService.resolveTerminalSteps(current);
            return;
        }

        /*
         * Non promu : on ne marque SKIPPED que si c'est définitif.
         */
        if (!terminal) {
            return;
        }

        if (current.getStatus() == ExperimentStatus.FAILED
                || current.getStatus() == ExperimentStatus.CANCELLED) {
            markProductionSkipped(
                    current,
                    "Run " + current.getStatus()
                            + " : version non promue en Production."
            );
            return;
        }

        boolean autoPromoteOff =
                current.getProject() != null
                        && Boolean.FALSE.equals(
                        current.getProject().getAutoPromoteBestModel());

        if (autoPromoteOff) {
            markProductionSkipped(
                    current,
                    "Promotion automatique désactivée pour ce projet : "
                            + "version non promue en Production."
            );
            return;
        }

        /*
         * Le Registry est la seule preuve irréversible de non-promotion : si
         * l'alias @production pointe vers une AUTRE version de ce modèle, ce run
         * ne sera jamais promu. Un candidat encore en course (contender) est
         * laissé PENDING : il peut être promu à ce tick ou au suivant.
         */
        if (modelVersion != null
                && registryVersion != null
                && !registryVersion.equalsIgnoreCase(modelVersion)
                && !contenderIds.contains(current.getId())) {
            markProductionSkipped(
                    current,
                    "Version " + modelVersion
                            + " non promue en Production : l'alias @production "
                            + "pointe vers la version " + registryVersion + "."
            );
            return;
        }

        boolean scored = current.getGlobalScore() != null
                && current.getPrimaryMetricValue() != null;

        LocalDateTime deadlineBase = current.getFinishedAt() != null
                ? current.getFinishedAt()
                : (current.getUpdatedAt() != null
                ? current.getUpdatedAt()
                : current.getCreatedAt());

        boolean stale = deadlineBase != null
                && deadlineBase.isBefore(
                LocalDateTime.now().minusMinutes(STALE_PROMOTION_MINUTES));

        /*
         * Non-promotion définitive par renoncement : le mécanisme de promotion a
         * eu amplement le temps de promouvoir ce run s'il était gagnant. Après le
         * délai sans alias @production correspondant, il ne sera jamais promu.
         */
        if (scored && stale && !contenderIds.contains(current.getId())) {
            markProductionSkipped(
                    current,
                    "Version non promue en Production : aucune promotion après "
                            + STALE_PROMOTION_MINUTES + " minutes."
            );
            return;
        }

        /*
         * Sinon la décision est encore possible (scoring en cours, enregistrement
         * en cours, promotion à retenter au prochain tick) : on laisse PENDING.
         */
    }

    private void resolveTrailingStepsIfCompleted(MlExperiment experiment) {
        if (experiment == null
                || experiment.getId() == null
                || experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return;
        }

        boolean hasMetrics = experiment.getPrimaryMetricValue() != null
                || experiment.getGlobalScore() != null;

        String modelVersion = trimToNull(experiment.getModelVersion());
        boolean hasVersion = hasText(modelVersion);

        if (hasMetrics) {
            runPipelineStepService.markSucceeded(
                    experiment,
                    RunPipelineStepService.METRICS_EXTRACTED,
                    "Métriques extraites du run."
            );
        } else {
            runPipelineStepService.markSkipped(
                    experiment,
                    RunPipelineStepService.METRICS_EXTRACTED,
                    "Aucune métrique détectée pour ce run."
            );
        }

        if (hasVersion) {
            runPipelineStepService.markModelRegistered(
                    experiment,
                    resolveRegisteredModelName(experiment),
                    modelVersion,
                    "Modèle enregistré dans le Registry MLflow (version "
                            + modelVersion + ")."
            );
        } else {
            /*
             * MLFLOW_REGISTERED (index > MODEL_ARTIFACT_DETECTED) marqué en premier :
             * la cascade de markSkipped(MODEL_ARTIFACT_DETECTED) est alors sans effet
             * et le message spécifique de MLFLOW_REGISTERED est conservé.
             */
            runPipelineStepService.markSkipped(
                    experiment,
                    RunPipelineStepService.MLFLOW_REGISTERED,
                    "Aucun modèle enregistré dans le Registry MLflow."
            );
            runPipelineStepService.markSkipped(
                    experiment,
                    RunPipelineStepService.MODEL_ARTIFACT_DETECTED,
                    "Aucun artefact modèle pour ce run."
            );
        }
    }

    private void markProductionSkipped(MlExperiment experiment, String reason) {
        resolveTrailingStepsIfCompleted(experiment);

        /*
         * Ordre important : DEPLOYMENT_READY est marqué AVANT PRODUCTION_PROMOTED.
         * markSkipped cascade en aval ; en marquant d'abord la dernière étape avec
         * un message spécifique, la cascade de PRODUCTION_PROMOTED devient sans
         * effet (étape terminale) et le message spécifique est conservé.
         */
        runPipelineStepService.markSkipped(
                experiment,
                RunPipelineStepService.DEPLOYMENT_READY,
                "Aucun déploiement : version non promue en Production."
        );

        runPipelineStepService.markSkipped(
                experiment,
                RunPipelineStepService.PRODUCTION_PROMOTED,
                reason
        );

        runPipelineStepService.resolveTerminalSteps(experiment);
    }

    private void markDeploymentStepFromState(
            MlExperiment experiment,
            String modelVersion
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        Optional<ModelDeployment> activeDeployment =
                modelDeploymentRepository.findByExperimentIdAndActiveTrue(experiment.getId());

        if (activeDeployment.isPresent()) {
            runPipelineStepService.markDeploymentReady(
                    experiment,
                    "Déploiement actif en Production (version "
                            + modelVersion + ")."
            );
            return;
        }

        boolean deployingExists = modelDeploymentRepository
                .findByExperimentIdOrderByCreatedAtDesc(experiment.getId())
                .stream()
                .anyMatch(deployment -> DeploymentStatus.DEPLOYING.equals(
                        deployment.getDeploymentStatus()));

        if (deployingExists) {
            runPipelineStepService.markRunning(
                    experiment,
                    RunPipelineStepService.DEPLOYMENT_READY,
                    "Déploiement en cours (version " + modelVersion + ")."
            );
        }
    }

    private boolean isEligibleScoredCandidate(MlExperiment experiment) {
        if (experiment == null
                || experiment.getId() == null
                || experiment.getProject() == null
                || experiment.getProject().getId() == null) {
            return false;
        }

        if (Boolean.FALSE.equals(
                experiment.getProject().getAutoPromoteBestModel())) {
            return false;
        }

        return experiment.getStatus() == ExperimentStatus.COMPLETED
                && hasText(experiment.getMlflowRunId())
                && hasText(resolveRegisteredModelName(experiment))
                && hasText(experiment.getModelVersion())
                && experiment.getPrimaryMetricValue() != null
                && experiment.getGlobalScore() != null
                && Double.isFinite(experiment.getGlobalScore());
    }

    private String buildModelKey(MlExperiment experiment) {
        Long projectId = experiment.getProject().getId();
        return projectId + "|" + clean(resolveRegisteredModelName(experiment));
    }

    private int compareScoredCandidates(
            MlExperiment left,
            MlExperiment right
    ) {
        int scoreComparison = Double.compare(
                left.getGlobalScore(),
                right.getGlobalScore()
        );

        if (scoreComparison != 0) {
            return scoreComparison;
        }

        LocalDateTime leftDate = left.getFinishedAt() != null
                ? left.getFinishedAt()
                : (left.getUpdatedAt() != null
                ? left.getUpdatedAt()
                : left.getCreatedAt());

        LocalDateTime rightDate = right.getFinishedAt() != null
                ? right.getFinishedAt()
                : (right.getUpdatedAt() != null
                ? right.getUpdatedAt()
                : right.getCreatedAt());

        int dateComparison = Comparator.nullsFirst(
                Comparator.<LocalDateTime>naturalOrder()
        ).compare(leftDate, rightDate);

        if (dateComparison != 0) {
            return dateComparison;
        }

        return Comparator.nullsFirst(Long::compareTo)
                .compare(left.getId(), right.getId());
    }

    /**
     * Positionne l'alias MLflow sur la meilleure version, vérifie le Registry,
     * puis synchronise toutes les lignes MySQL du même projet/modèle.
     */
    private MlExperiment promoteBestCandidate(MlExperiment candidate) {
        try {
            String modelName = resolveRegisteredModelName(candidate);
            String version = trimToNull(candidate.getModelVersion());

            if (!hasText(modelName) || version == null) {
                return null;
            }

            /*
             * GATE QUALITÉ :
             * Le modèle ne peut pas être promu si son score global normalisé
             * est inférieur au seuil minimal configuré pour son type de projet.
             */
            ModelQualityService.QualityEvaluation quality =
                    modelQualityService.evaluate(candidate.getProject(), candidate);

            if (quality != null && !quality.conforming()) {
                log("Quality gate REJECTED candidate. experimentId="
                        + candidate.getId()
                        + ", model=" + modelName
                        + ", version=" + version
                        + ", globalScore=" + candidate.getGlobalScore()
                        + ", threshold=" + quality.threshold()
                        + ", reason=" + quality.reason());
                modelQualityService.raiseQualityAlertIfNeeded(candidate.getProject());
                return null;
            }

            String currentAliasVersion =
                    getProductionVersionFromRegistryAlias(modelName);

            if (!version.equalsIgnoreCase(
                    trimToNull(currentAliasVersion) == null
                            ? ""
                            : currentAliasVersion.trim())) {
                setRegisteredModelAlias(
                        modelName,
                        PRODUCTION_ALIAS,
                        version
                );
            }

            String confirmedVersion =
                    getProductionVersionFromRegistryAlias(modelName);

            if (!version.equalsIgnoreCase(
                    trimToNull(confirmedVersion) == null
                            ? ""
                            : confirmedVersion.trim())) {
                throw new IllegalStateException(
                        "MLflow alias verification failed. expected="
                                + version
                                + ", actual="
                                + confirmedVersion
                );
            }

            synchronizeModelProductionState(
                    candidate,
                    modelName,
                    version
            );

            MlExperiment refreshed = reload(candidate.getId());

            log("Best candidate promoted automatically. projectId="
                    + candidate.getProject().getId()
                    + ", experimentId=" + candidate.getId()
                    + ", model=" + modelName
                    + ", version=" + version
                    + ", globalScore=" + candidate.getGlobalScore());

            notifyProductionPromoted(candidate, modelName, version);

            return refreshed;

        } catch (Exception e) {
            log("Automatic best-model promotion failed. experimentId="
                    + safeExperimentId(candidate)
                    + ", reason=" + safeMessage(e));
            return null;
        }
    }

    private void synchronizeModelProductionState(
            MlExperiment winner,
            String registeredModelName,
            String productionVersion
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);

        template.executeWithoutResult(status -> {
            List<MlExperiment> sameModelExperiments =
                    mlExperimentRepository
                            .findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                                    winner.getProject(),
                                    registeredModelName
                            );

            for (MlExperiment experiment : sameModelExperiments) {
                boolean selected = experiment.getId() != null
                        && experiment.getId().equals(winner.getId());

                experiment.setRegisteredModelName(registeredModelName);
                experiment.setModelName(registeredModelName);
                experiment.setProduction(selected);
                experiment.setProductionVersion(
                        selected ? productionVersion : null
                );

                if (selected) {
                    experiment.setErrorMessage(null);
                    experiment.setMessage(
                            appendSafeMessage(
                                    experiment.getMessage(),
                                    "Automatically promoted as best global-score "
                                            + "Production model. version="
                                            + productionVersion
                            )
                    );
                }
            }

            mlExperimentRepository.saveAll(sameModelExperiments);
            mlExperimentRepository.flush();
        });
    }

    private void deployCandidate(MlExperiment candidate) {
        try {
            MlExperiment readyCandidate = prepareCandidateForDeployment(candidate);

            if (readyCandidate == null) {
                log("Candidate skipped because model artifact is not ready. experimentId="
                        + safeExperimentId(candidate));
                return;
            }

            Long projectId = readyCandidate.getProject() == null
                    ? null
                    : readyCandidate.getProject().getId();

            log("Candidate ready:"
                    + " projectId=" + projectId
                    + ", experimentId=" + readyCandidate.getId()
                    + ", model=" + readyCandidate.getRegisteredModelName()
                    + ", version=" + readyCandidate.getModelVersion()
                    + ", productionVersion=" + readyCandidate.getProductionVersion());

            ModelDeploymentResponse response =
                    modelDeploymentService.autoDeployExperimentIfNeeded(readyCandidate);

            if (response == null) {
                log("No deployment action needed for experimentId="
                        + readyCandidate.getId());
                return;
            }

            log("Deployment response:"
                    + " deploymentId=" + response.getId()
                    + ", model=" + response.getRegisteredModelName()
                    + ", version=" + response.getModelVersion()
                    + ", status=" + response.getDeploymentStatus()
                    + ", active=" + response.getActive());

        } catch (Exception e) {
            log("Failed to deploy candidate experimentId="
                    + safeExperimentId(candidate)
                    + ", reason=" + safeMessage(e));
            e.printStackTrace();
        }
    }

    /**
     * Prépare une seule fois l'artefact du modèle.
     *
     * Règles importantes :
     * 1. Si la version courante est déjà alignée sur @production et marquée
     *    comme standardisée, aucune nouvelle version MLflow n'est créée.
     * 2. Si le standardizer crée une nouvelle version, l'alias @production
     *    est immédiatement déplacé vers cette nouvelle version.
     * 3. La base est synchronisée avec la même version afin d'éviter la boucle
     *    v1 -> v2 -> v3 -> ...
     */
    private MlExperiment prepareCandidateForDeployment(MlExperiment candidate) {
        if (candidate == null || candidate.getId() == null) {
            return null;
        }

        MlExperiment managedExperiment = reload(candidate.getId());

        if (managedExperiment == null) {
            log("Candidate not found anymore. experimentId=" + candidate.getId());
            return null;
        }

        String modelName = resolveRegisteredModelName(managedExperiment);
        String currentVersion = trimToNull(managedExperiment.getModelVersion());
        String aliasVersion = getProductionVersionFromRegistryAlias(modelName);

        syncProductionState(managedExperiment, modelName, currentVersion, aliasVersion);

        if (isAlreadyPrepared(managedExperiment, aliasVersion)) {
            log("Model already standardized and aligned with @production. "
                    + "Skipping standardization. experimentId="
                    + managedExperiment.getId()
                    + ", version=" + managedExperiment.getModelVersion());
            return managedExperiment;
        }

        try {
            ModelArtifactStandardizerService.StandardizationResult result =
                    modelArtifactStandardizerService.standardizeIfNeeded(managedExperiment);

            if (result == null) {
                return fallbackOrBlock(
                        managedExperiment,
                        "Model artifact standardization returned null."
                );
            }

            log("Standardization check result:"
                    + " experimentId=" + managedExperiment.getId()
                    + ", success=" + result.isSuccess()
                    + ", skipped=" + result.isSkipped()
                    + ", standardized=" + result.isStandardized()
                    + ", alreadyMlflowModel=" + result.isAlreadyMlflowModel()
                    + ", newVersion=" + result.getNewVersion()
                    + ", message=" + result.getMessage());

            if (!result.isSuccess()) {
                return fallbackOrBlock(
                        managedExperiment,
                        "Model artifact standardization failed: " + result.getMessage()
                );
            }

            String newVersion = trimToNull(result.getNewVersion());

            if (newVersion == null
                    && (result.isAlreadyMlflowModel() || result.isSkipped())
                    && currentVersion != null) {
                managedExperiment.setMessage(
                        appendSafeMessage(
                                managedExperiment.getMessage(),
                                buildStandardizedMarker(currentVersion)
                        )
                );
                managedExperiment.setErrorMessage(null);
                mlExperimentRepository.saveAndFlush(managedExperiment);
            }

            /*
             * Cas 1 : une nouvelle version standardisée a été créée.
             * On déplace immédiatement l'alias production et on aligne la base.
             */
            if (newVersion != null) {
                promoteVersionAndSynchronize(
                        managedExperiment,
                        modelName,
                        newVersion,
                        true
                );

                MlExperiment refreshed = reload(managedExperiment.getId());

                if (refreshed == null) {
                    return null;
                }

                log("Standardized version promoted and synchronized. "
                        + "experimentId=" + refreshed.getId()
                        + ", modelVersion=" + refreshed.getModelVersion()
                        + ", productionVersion=" + refreshed.getProductionVersion());

                return isEligibleProductionExperiment(refreshed)
                        ? refreshed
                        : null;
            }

            /*
             * Cas 2 : aucun nouveau numéro de version.
             * Cela signifie généralement que le modèle est déjà au format MLflow
             * ou que la standardisation a été ignorée.
             */
            MlExperiment refreshed = reload(managedExperiment.getId());

            if (refreshed == null) {
                return null;
            }

            String refreshedModelName = resolveRegisteredModelName(refreshed);
            String refreshedModelVersion = trimToNull(refreshed.getModelVersion());
            String refreshedAliasVersion =
                    getProductionVersionFromRegistryAlias(refreshedModelName);

            if (refreshedAliasVersion == null && refreshedModelVersion != null) {
                /*
                 * Première version : l'absence de l'alias n'est pas une erreur.
                 * Le backend crée @production sur la version candidate.
                 */
                promoteVersionAndSynchronize(
                        refreshed,
                        refreshedModelName,
                        refreshedModelVersion,
                        result.isAlreadyMlflowModel() || result.isSkipped()
                );
                refreshed = reload(refreshed.getId());
            } else {
                syncProductionState(
                        refreshed,
                        refreshedModelName,
                        refreshedModelVersion,
                        refreshedAliasVersion
                );
            }

            if (refreshed != null && isEligibleProductionExperiment(refreshed)) {
                return refreshed;
            }

            return fallbackOrBlock(
                    managedExperiment,
                    "Candidate is no longer eligible after standardization."
            );

        } catch (Exception e) {
            return fallbackOrBlock(
                    managedExperiment,
                    "Model artifact preparation failed: " + safeMessage(e)
            );
        }
    }

    private MlExperiment fallbackOrBlock(
            MlExperiment experiment,
            String reason
    ) {
        boolean nativeReady =
                acceptNativeMlflowRegistryModelIfAvailable(experiment, reason);

        if (nativeReady) {
            return reloadIfStillEligible(experiment.getId());
        }

        markArtifactNotReady(experiment, reason);
        return null;
    }

    /**
     * Déplace l'alias MLflow puis synchronise atomiquement la ligne MySQL.
     */
    private void promoteVersionAndSynchronize(
            MlExperiment experiment,
            String registeredModelName,
            String version,
            boolean markStandardized
    ) {
        if (experiment == null || experiment.getId() == null) {
            throw new IllegalArgumentException("Experiment is required.");
        }

        String cleanModelName = trimToNull(registeredModelName);
        String cleanVersion = trimToNull(version);

        if (cleanModelName == null) {
            throw new IllegalStateException("Registered model name is missing.");
        }

        if (cleanVersion == null) {
            throw new IllegalStateException("Model version is missing.");
        }

        setRegisteredModelAlias(
                cleanModelName,
                PRODUCTION_ALIAS,
                cleanVersion
        );

        String confirmedVersion =
                getProductionVersionFromRegistryAlias(cleanModelName);

        if (!cleanVersion.equals(confirmedVersion)) {
            throw new IllegalStateException(
                    "MLflow alias verification failed. expected="
                            + cleanVersion
                            + ", actual="
                            + confirmedVersion
            );
        }

        experiment.setRegisteredModelName(cleanModelName);
        experiment.setModelName(cleanModelName);
        experiment.setModelVersion(cleanVersion);
        experiment.setProductionVersion(cleanVersion);
        experiment.setProduction(true);
        experiment.setErrorMessage(null);

        if (markStandardized) {
            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            buildStandardizedMarker(cleanVersion)
                    )
            );
        }

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "MLflow alias @production synchronized with version "
                                + cleanVersion + "."
                )
        );

        mlExperimentRepository.saveAndFlush(experiment);

        log("Production alias assigned and database synchronized. "
                + "experimentId=" + experiment.getId()
                + ", model=" + cleanModelName
                + ", version=" + cleanVersion);
    }

    private void setRegisteredModelAlias(
            String registeredModelName,
            String alias,
            String version
    ) {
        String cleanName = trimToNull(registeredModelName);
        String cleanAlias = trimToNull(alias);
        String cleanVersion = trimToNull(version);

        if (cleanName == null || cleanAlias == null || cleanVersion == null) {
            throw new IllegalArgumentException(
                    "Model name, alias and version are required."
            );
        }

        String requestUrl = normalizeMlflowBaseUrl()
                + "/api/2.0/mlflow/registered-models/alias";

        Map<String, String> body = new LinkedHashMap<>();
        body.put("name", cleanName);
        body.put("alias", cleanAlias);
        body.put("version", cleanVersion);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Map<String, String>> entity =
                new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                requestUrl,
                HttpMethod.POST,
                entity,
                String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Unable to set MLflow alias. status="
                            + response.getStatusCode()
            );
        }
    }

    private void syncProductionState(
            MlExperiment experiment,
            String registeredModelName,
            String modelVersion,
            String productionVersion
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String cleanName = trimToNull(registeredModelName);
        String cleanModelVersion = trimToNull(modelVersion);
        String cleanProductionVersion = trimToNull(productionVersion);

        if (cleanName != null) {
            experiment.setRegisteredModelName(cleanName);
            experiment.setModelName(cleanName);
        }

        if (cleanModelVersion != null) {
            experiment.setModelVersion(cleanModelVersion);
        }

        experiment.setProductionVersion(cleanProductionVersion);

        boolean isProduction =
                cleanModelVersion != null
                        && cleanProductionVersion != null
                        && cleanModelVersion.equalsIgnoreCase(
                                cleanProductionVersion
                        );

        experiment.setProduction(isProduction);

        if (isProduction) {
            experiment.setErrorMessage(null);
        }

        mlExperimentRepository.saveAndFlush(experiment);
    }

    /**
     * Une expérience est considérée comme "déjà préparée" uniquement si :
     * 1. sa version est alignée sur l'alias @production du Registry, ET
     * 2. le run possède réellement cette version enregistrée dans MLflow.
     * La vérification du Registry remplace l'ancien marqueur stocké dans le
     * message (fragile : écrasé par les synchronisations et tronqué).
     */
    private boolean isAlreadyPrepared(
            MlExperiment experiment,
            String registryProductionVersion
    ) {
        if (experiment == null) {
            return false;
        }

        String modelVersion = trimToNull(experiment.getModelVersion());
        String productionVersion = trimToNull(registryProductionVersion);

        if (modelVersion == null || productionVersion == null) {
            return false;
        }

        boolean versionsAligned =
                modelVersion.equalsIgnoreCase(productionVersion);

        if (!versionsAligned) {
            return false;
        }

        String registeredModelName = resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)
                || !hasText(experiment.getMlflowRunId())) {
            return false;
        }

        try {
            String registeredVersion =
                    mlflowTrackingClientService.findModelVersionByRunId(
                            registeredModelName,
                            experiment.getMlflowRunId()
                    );

            return hasText(registeredVersion)
                    && modelVersion.equalsIgnoreCase(registeredVersion.trim());
        } catch (Exception e) {
            log("isAlreadyPrepared registry check failed. experimentId="
                    + experiment.getId()
                    + ", reason=" + safeMessage(e));
            return false;
        }
    }

    private String buildStandardizedMarker(String version) {
        return STANDARDIZED_MARKER_PREFIX + version + "]";
    }

    private MlExperiment reloadIfStillEligible(Long experimentId) {
        MlExperiment refreshed = reload(experimentId);

        if (refreshed == null) {
            return null;
        }

        if (!isEligibleProductionExperiment(refreshed)) {
            log("Candidate not eligible after MLflow Registry fallback. "
                    + "experimentId=" + refreshed.getId()
                    + ", model=" + refreshed.getRegisteredModelName()
                    + ", modelVersion=" + refreshed.getModelVersion()
                    + ", productionVersion=" + refreshed.getProductionVersion()
                    + ", production=" + refreshed.getProduction());
            return null;
        }

        return refreshed;
    }

    private MlExperiment reload(Long experimentId) {
        if (experimentId == null) {
            return null;
        }

        return mlExperimentRepository.findById(experimentId).orElse(null);
    }

    private boolean acceptNativeMlflowRegistryModelIfAvailable(
            MlExperiment experiment,
            String reason
    ) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        String registeredModelName = resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)) {
            log("Native MLflow Registry fallback skipped. experimentId="
                    + experiment.getId()
                    + ", reason=no registered model name");
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            log("Native MLflow Registry fallback skipped because experiment "
                    + "is not COMPLETED. experimentId="
                    + experiment.getId()
                    + ", status=" + experiment.getStatus());
            return false;
        }

        String runVersion = trimToNull(experiment.getModelVersion());
        String productionVersion =
                getProductionVersionFromRegistryAlias(registeredModelName);

        /*
         * Première version sans alias : on la promeut au lieu de la rejeter.
         */
        if (productionVersion == null && runVersion != null) {
            try {
                promoteVersionAndSynchronize(
                        experiment,
                        registeredModelName,
                        runVersion,
                        false
                );
                return true;
            } catch (Exception e) {
                log("Native MLflow Registry first-version promotion failed. "
                        + "experimentId=" + experiment.getId()
                        + ", reason=" + safeMessage(e));
                return false;
            }
        }

        if (productionVersion == null) {
            log("Native MLflow Registry fallback failed. experimentId="
                    + experiment.getId()
                    + ", registeredModelName=" + registeredModelName
                    + ", reason=" + reason);
            return false;
        }

        boolean thisRunIsProduction =
                runVersion != null
                        && productionVersion.equalsIgnoreCase(runVersion);

        experiment.setRegisteredModelName(registeredModelName);
        experiment.setModelName(registeredModelName);
        experiment.setProductionVersion(productionVersion);
        experiment.setProduction(thisRunIsProduction);
        experiment.setErrorMessage(null);

        if (!thisRunIsProduction) {
            mlExperimentRepository.saveAndFlush(experiment);
            log("Native MLflow Registry fallback skipped because this run is "
                    + "not the production version. experimentId="
                    + experiment.getId()
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

        log("No local artifact found, but MLflow Registry production model "
                + "is ready. Continuing deployment for experimentId="
                + experiment.getId()
                + ", registeredModelName=" + registeredModelName
                + ", productionVersion=" + productionVersion);

        return true;
    }

    private String getProductionVersionFromRegistryAlias(
            String registeredModelName
    ) {
        String cleanName = trimToNull(registeredModelName);

        if (cleanName == null) {
            return null;
        }

        try {
            String requestUrl = normalizeMlflowBaseUrl()
                    + "/api/2.0/mlflow/registered-models/alias?name="
                    + URLEncoder.encode(cleanName, StandardCharsets.UTF_8)
                    + "&alias="
                    + URLEncoder.encode(
                            PRODUCTION_ALIAS,
                            StandardCharsets.UTF_8
                    );

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null
                    || body.isBlank()) {
                log("Registry alias check failed. registeredModelName="
                        + cleanName
                        + ", status=" + response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            String version = root.path("model_version")
                    .path("version")
                    .asText(null);

            log("Registry alias check. registeredModelName="
                    + cleanName
                    + ", alias=" + PRODUCTION_ALIAS
                    + ", version=" + version
                    + ", ready=" + hasText(version));

            return trimToNull(version);

        } catch (HttpClientErrorException.BadRequest e) {
            /*
             * Pour MLflow, un alias absent peut être retourné en HTTP 400.
             * Ce cas est normal pour la première version.
             */
            String responseBody = e.getResponseBodyAsString();

            if (responseBody != null
                    && responseBody.contains("alias production not found")) {
                log("Registry has no @production alias yet. model="
                        + cleanName);
                return null;
            }

            log("Registry alias check failed. registeredModelName="
                    + cleanName
                    + ", reason=" + safeMessage(e));
            return null;

        } catch (Exception e) {
            log("Registry alias check failed. registeredModelName="
                    + cleanName
                    + ", reason=" + safeMessage(e));
            return null;
        }
    }

    private boolean isEligibleProductionExperiment(
            MlExperiment experiment
    ) {
        if (experiment == null || experiment.getId() == null) {
            return false;
        }

        if (experiment.getProject() == null
                || experiment.getProject().getId() == null) {
            log("Experiment ignored: no project. experimentId="
                    + experiment.getId());
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (!hasText(experiment.getMlflowRunId())) {
            log("Experiment ignored: missing mlflowRunId. experimentId="
                    + experiment.getId());
            return false;
        }

        String registeredModelName =
                resolveRegisteredModelName(experiment);

        if (!hasText(registeredModelName)) {
            log("Experiment ignored: missing registered model name. "
                    + "experimentId=" + experiment.getId());
            return false;
        }

        String modelVersion = trimToNull(experiment.getModelVersion());
        String productionVersion =
                trimToNull(experiment.getProductionVersion());

        boolean markedProduction =
                Boolean.TRUE.equals(experiment.getProduction());

        boolean versionMatchesProduction =
                modelVersion != null
                        && productionVersion != null
                        && productionVersion.equalsIgnoreCase(modelVersion);

        if (markedProduction || versionMatchesProduction) {
            logEligible(experiment, registeredModelName);
            return true;
        }

        String runtimeMode = normalizeRuntimeMode(experiment);

        if (isCustomDockerRuntime(runtimeMode)) {
            String registryProductionVersion =
                    getProductionVersionFromRegistryAlias(
                            registeredModelName
                    );

            /*
             * Première version : au lieu d'ignorer le candidat, on crée
             * l'alias production sur sa version.
             */
            if (registryProductionVersion == null
                    && modelVersion != null) {
                try {
                    promoteVersionAndSynchronize(
                            experiment,
                            registeredModelName,
                            modelVersion,
                            false
                    );
                    log("[CUSTOM] First production alias created. "
                            + "experimentId=" + experiment.getId()
                            + ", version=" + modelVersion);
                    return true;
                } catch (Exception e) {
                    log("[CUSTOM] First production promotion failed. "
                            + "experimentId=" + experiment.getId()
                            + ", reason=" + safeMessage(e));
                    return false;
                }
            }

            if (registryProductionVersion == null) {
                log("[CUSTOM] Experiment ignored: no model version and no "
                        + "MLflow production alias. experimentId="
                        + experiment.getId());
                return false;
            }

            boolean thisRunIsProduction =
                    modelVersion != null
                            && registryProductionVersion
                            .equalsIgnoreCase(modelVersion);

            experiment.setRegisteredModelName(registeredModelName);
            experiment.setModelName(registeredModelName);
            experiment.setProductionVersion(
                    registryProductionVersion
            );
            experiment.setProduction(thisRunIsProduction);
            experiment.setErrorMessage(null);

            mlExperimentRepository.saveAndFlush(experiment);

            if (!thisRunIsProduction) {
                log("[CUSTOM] Experiment ignored because this run is not "
                        + "the MLflow production version. experimentId="
                        + experiment.getId()
                        + ", runVersion=" + modelVersion
                        + ", productionVersion="
                        + registryProductionVersion);
                return false;
            }

            experiment.setMessage(
                    appendSafeMessage(
                            experiment.getMessage(),
                            "CUSTOM_DOCKERFILE production state recovered "
                                    + "from MLflow Registry. Using models:/"
                                    + registeredModelName
                                    + "@production for automatic deployment."
                    )
            );

            mlExperimentRepository.saveAndFlush(experiment);

            log("[CUSTOM] Eligible custom docker experiment from MLflow "
                    + "Registry. experimentId=" + experiment.getId()
                    + ", model=" + registeredModelName
                    + ", productionVersion="
                    + registryProductionVersion);

            return true;
        }

        log("Experiment ignored: not production candidate. experimentId="
                + experiment.getId()
                + ", model=" + registeredModelName
                + ", modelVersion=" + modelVersion
                + ", productionVersion=" + productionVersion
                + ", production=" + experiment.getProduction()
                + ", runtimeMode=" + runtimeMode);

        return false;
    }

    private void logEligible(
            MlExperiment experiment,
            String registeredModelName
    ) {
        log("Eligible production experiment. experimentId="
                + experiment.getId()
                + ", model=" + registeredModelName
                + ", modelVersion=" + experiment.getModelVersion()
                + ", productionVersion="
                + experiment.getProductionVersion()
                + ", runtimeMode="
                + normalizeRuntimeMode(experiment));
    }

    private String normalizeRuntimeMode(MlExperiment experiment) {
        return experiment.getRuntimeMode() == null
                ? ""
                : experiment.getRuntimeMode().trim().toUpperCase();
    }

    private boolean isCustomDockerRuntime(String runtimeMode) {
        return "CUSTOM_DOCKERFILE".equals(runtimeMode)
                || "CUSTOM_DOCKER".equals(runtimeMode)
                || "CUSTOM_IMAGE".equals(runtimeMode)
                || "CUSTOM_IMAGE_RUNTIME".equals(runtimeMode)
                || "DOCKER_PERSONALIZED".equals(runtimeMode)
                || "PERSONALIZED".equals(runtimeMode);
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

        return projectId
                + "|"
                + clean(modelName)
                + "|"
                + clean(version);
    }

    private boolean isNewer(
            MlExperiment left,
            MlExperiment right
    ) {
        LocalDateTime leftDate = left.getUpdatedAt() != null
                ? left.getUpdatedAt()
                : left.getCreatedAt();

        LocalDateTime rightDate = right.getUpdatedAt() != null
                ? right.getUpdatedAt()
                : right.getCreatedAt();

        if (leftDate == null && rightDate == null) {
            return left.getId() != null
                    && right.getId() != null
                    && left.getId() > right.getId();
        }

        if (leftDate != null && rightDate == null) {
            return true;
        }

        if (leftDate == null) {
            return false;
        }

        return leftDate.isAfter(rightDate);
    }

    private String resolveRegisteredModelName(
            MlExperiment experiment
    ) {
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

    private void markArtifactNotReady(
            MlExperiment experiment,
            String reason
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        boolean nativeReady =
                acceptNativeMlflowRegistryModelIfAvailable(
                        experiment,
                        reason
                );

        if (nativeReady) {
            log("Artifact fallback succeeded. Deployment will not be "
                    + "blocked. experimentId=" + experiment.getId());
            return;
        }

        experiment.setMessage(
                appendSafeMessage(
                        experiment.getMessage(),
                        "Model artifact not ready for deployment. Reason: "
                                + reason
                )
        );

        /*
         * Ne jamais effacer aveuglément un alias production réel.
         * On relit d'abord le Registry.
         */
        String registryProductionVersion =
                getProductionVersionFromRegistryAlias(
                        resolveRegisteredModelName(experiment)
                );

        String modelVersion =
                trimToNull(experiment.getModelVersion());

        boolean remainsProduction =
                registryProductionVersion != null
                        && modelVersion != null
                        && registryProductionVersion
                        .equalsIgnoreCase(modelVersion);

        experiment.setProductionVersion(
                registryProductionVersion
        );
        experiment.setProduction(remainsProduction);

        mlExperimentRepository.saveAndFlush(experiment);

        log("Artifact not ready. Deployment blocked. experimentId="
                + experiment.getId()
                + ", reason=" + reason);
    }

    private String normalizeMlflowBaseUrl() {
        String url = trimToNull(localMlflowTrackingUri);

        if (url == null) {
            url = "http://mlflow-service:5000";
        }

        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return url;
    }

    private String safeExperimentId(MlExperiment experiment) {
        return experiment == null || experiment.getId() == null
                ? "null"
                : String.valueOf(experiment.getId());
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase();
    }

    private String appendSafeMessage(
            String oldMessage,
            String addition
    ) {
        String base = oldMessage == null
                ? ""
                : oldMessage.trim();

        if (!hasText(addition)) {
            return limit(base, 3000);
        }

        if (base.contains(addition)) {
            return limit(base, 3000);
        }

        String result = base.isBlank()
                ? addition
                : base + " " + addition;

        return limit(result, 3000);
    }

    private String safeMessage(Exception e) {
        if (e == null) {
            return "unknown error";
        }

        return hasText(e.getMessage())
                ? limit(e.getMessage(), 1000)
                : e.getClass().getSimpleName();
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

        return cleaned.substring(
                0,
                Math.max(0, maxLength - 16)
        ) + "... [truncated]";
    }

    private void log(String message) {
        System.out.println(
                "[PRODUCTION_RECONCILER] " + message
        );
    }

    private void notifyProductionPromoted(
            MlExperiment candidate,
            String modelName,
            String version
    ) {
        try {
            if (candidate.getProject() == null
                    || candidate.getProject().getOwner() == null) {
                return;
            }

            User owner = candidate.getProject().getOwner();

            notificationService.notifyProductionPromoted(
                    candidate.getId(),
                    owner,
                    modelName,
                    version
            );

            System.out.println(
                    "[NOTIFICATION] created type=NEW_PRODUCTION_VERSION"
                            + " experimentId=" + candidate.getId()
                            + " userId=" + owner.getId()
                            + " model=" + modelName
                            + " version=" + version
            );

        } catch (Exception e) {
            System.out.println(
                    "[NOTIFICATION][PRODUCTION] Could not notify NEW_PRODUCTION_VERSION. "
                            + "experimentId=" + safeExperimentId(candidate)
                            + ", reason=" + safeMessage(e)
            );
        }
    }
}