package com.mlops.platform.service;

import com.mlops.platform.dto.PipelineStatusResponse;
import com.mlops.platform.dto.RunPipelineStepDto;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.RunPipelineStep;
import com.mlops.platform.repository.RunPipelineStepRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistance et progression des étapes de pipeline d'un run.
 *
 * - Chaque étape est identifiée par (experimentId, stepCode) : contrainte unique en base.
 * - Les transitions sont monotones : PENDING -> RUNNING -> SUCCEEDED/FAILED.
 *   Une étape déjà SUCCEEDED/FAILED/SKIPPED ne change jamais d'état (idempotence).
 * - La progression globale est calculée à partir des étapes réellement applicables
 *   (les étapes SKIPPED ne pénalisent pas) et jamais codée en dur.
 */
@Service
public class RunPipelineStepService {

    // Codes communs
    public static final String SUBMITTED = "SUBMITTED";
    public static final String METRICS_EXTRACTED = "METRICS_EXTRACTED";
    public static final String MODEL_ARTIFACT_DETECTED = "MODEL_ARTIFACT_DETECTED";
    public static final String MLFLOW_REGISTERED = "MLFLOW_REGISTERED";
    public static final String PRODUCTION_PROMOTED = "PRODUCTION_PROMOTED";
    public static final String DEPLOYMENT_READY = "DEPLOYMENT_READY";

    // Standard / Kubeflow
    public static final String KFP_PIPELINE_CREATED = "KFP_PIPELINE_CREATED";
    public static final String KFP_RUN_SUBMITTED = "KFP_RUN_SUBMITTED";
    public static final String DATA_PREPARED = "DATA_PREPARED";
    public static final String KFP_TRAINING_RUNNING = "KFP_TRAINING_RUNNING";

    // Custom Docker / Kubernetes
    public static final String FILES_VALIDATED = "FILES_VALIDATED";
    public static final String IMAGE_BUILD_STARTED = "IMAGE_BUILD_STARTED";
    public static final String IMAGE_BUILD_COMPLETED = "IMAGE_BUILD_COMPLETED";
    public static final String ACR_PUSH_COMPLETED = "ACR_PUSH_COMPLETED";
    public static final String TRAINING_JOB_CREATED = "TRAINING_JOB_CREATED";
    public static final String TRAINING_RUNNING = "TRAINING_RUNNING";
    public static final String USER_SCRIPT_COMPLETED = "USER_SCRIPT_COMPLETED";

    public static final String RUNTIME_STANDARD = "STANDARD_RUNTIME";
    public static final String RUNTIME_CUSTOM = "CUSTOM_DOCKERFILE";

    private static final DateTimeFormatter ISO_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RunPipelineStepRepository stepRepository;

    public RunPipelineStepService(RunPipelineStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    /**
     * Ordre et libellés par défaut de chaque pipeline (le frontend traduit aussi par code).
     */
    public static Map<String, String> standardStepDefinitions() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(SUBMITTED, "Soumission");
        defs.put(KFP_PIPELINE_CREATED, "Pipeline Kubeflow créée");
        defs.put(KFP_RUN_SUBMITTED, "Run Kubeflow soumis");
        defs.put(DATA_PREPARED, "Préparation et validation des données");
        defs.put(KFP_TRAINING_RUNNING, "Entraînement Kubeflow");
        defs.put(METRICS_EXTRACTED, "Évaluation des métriques");
        defs.put(MODEL_ARTIFACT_DETECTED, "Artefact modèle");
        defs.put(MLFLOW_REGISTERED, "Registry MLflow");
        defs.put(PRODUCTION_PROMOTED, "Promotion Production");
        defs.put(DEPLOYMENT_READY, "Déploiement");
        return defs;
    }

    public static Map<String, String> customStepDefinitions() {
        Map<String, String> defs = new LinkedHashMap<>();
        defs.put(FILES_VALIDATED, "Validation des fichiers");
        defs.put(IMAGE_BUILD_STARTED, "Build image (Kaniko)");
        defs.put(IMAGE_BUILD_COMPLETED, "Image construite");
        defs.put(ACR_PUSH_COMPLETED, "Push image ACR");
        defs.put(TRAINING_JOB_CREATED, "Création Job Kubernetes");
        defs.put(TRAINING_RUNNING, "Exécution platform_runner");
        defs.put(USER_SCRIPT_COMPLETED, "Exécution train.py");
        defs.put(METRICS_EXTRACTED, "Extraction des métriques");
        defs.put(MODEL_ARTIFACT_DETECTED, "Détection de l'artefact");
        defs.put(MLFLOW_REGISTERED, "Enregistrement MLflow");
        defs.put(PRODUCTION_PROMOTED, "Promotion Production");
        defs.put(DEPLOYMENT_READY, "Déploiement");
        return defs;
    }

    public static List<String> standardStepCodes() {
        return List.copyOf(standardStepDefinitions().keySet());
    }

    public static List<String> customStepCodes() {
        return List.copyOf(customStepDefinitions().keySet());
    }

    public static List<String> stepCodesForMode(String runtimeMode) {
        return isCustomMode(runtimeMode) ? customStepCodes() : standardStepCodes();
    }

    public static boolean isCustomMode(String runtimeMode) {
        return runtimeMode != null && RUNTIME_CUSTOM.equalsIgnoreCase(runtimeMode.trim());
    }

    /**
     * Crée (si absente) toutes les étapes de la pipeline du mode du run, dans l'ordre.
     * Idempotent : un refresh ou un redémarrage ne recrée pas d'étapes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initializeForExperiment(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        Map<String, String> definitions = isCustomMode(mode)
                ? customStepDefinitions()
                : standardStepDefinitions();

        int order = 0;
        for (Map.Entry<String, String> entry : definitions.entrySet()) {
            ensureStep(
                    experiment.getId(),
                    entry.getKey(),
                    mode,
                    order,
                    entry.getValue(),
                    experiment.getExperimentName()
            );
            order++;
        }
    }

    /**
     * Ouvre une étape (PENDING -> RUNNING). Sans effet si l'étape est déjà terminale.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(MlExperiment experiment, String stepCode, String message) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (isTerminal(step.getStatus())) {
            appendMessageAndSave(step, message);
            return;
        }

        if (hasBlockedPrerequisite(experiment.getId(), mode, stepCode)) {
            appendMessageAndSave(step, message);
            return;
        }

        LocalDateTime now = now();
        if (!RunPipelineStep.STATUS_RUNNING.equals(step.getStatus())) {
            step.setStatus(RunPipelineStep.STATUS_RUNNING);
            step.setStartedAt(now);
            step.setProgress(50);
        }

        appendMessageAndSave(step, message);
    }

    /**
     * Marque une étape comme réussie (-> SUCCEEDED). Idempotent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(MlExperiment experiment, String stepCode, String message) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (RunPipelineStep.STATUS_FAILED.equals(step.getStatus())
                || RunPipelineStep.STATUS_SKIPPED.equals(step.getStatus())) {
            return;
        }

        if (hasBlockedPrerequisite(experiment.getId(), mode, stepCode)) {
            appendMessageAndSave(step, message);
            return;
        }

        step.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
        step.setProgress(100);
        if (step.getStartedAt() == null) {
            step.setStartedAt(now());
        }
        step.setCompletedAt(now());
        step.setErrorCode(null);
        step.setErrorDetails(null);
        appendMessageAndSave(step, message);
    }

    /**
     * Marque une étape comme échouée (-> FAILED) et skip toutes les étapes suivantes.
     * Une étape déjà FAILED ne peut pas être écrasée (idempotence).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(
            MlExperiment experiment,
            String stepCode,
            String errorCode,
            String errorDetails,
            String message,
            String logs
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (RunPipelineStep.STATUS_FAILED.equals(step.getStatus())) {
            return;
        }

        step.setStatus(RunPipelineStep.STATUS_FAILED);
        step.setProgress(0);
        if (step.getStartedAt() == null) {
            step.setStartedAt(now());
        }
        step.setCompletedAt(now());
        step.setErrorCode(errorCode);
        step.setErrorDetails(errorDetails);
        step.setMessage(message != null && !message.isBlank() ? message : step.getMessage());
        if (logs != null && !logs.isBlank()) {
            step.setLogs(mergeLogs(step.getLogs(), logs));
        }

        stepRepository.saveAndFlush(step);

        skipDownstream(experiment, stepCode, mode);
    }

    /**
     * Marque une étape comme non applicable (-> SKIPPED). Idempotent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(MlExperiment experiment, String stepCode, String message) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (isTerminal(step.getStatus())) {
            return;
        }

        step.setStatus(RunPipelineStep.STATUS_SKIPPED);
        step.setProgress(0);
        if (step.getStartedAt() == null) {
            step.setStartedAt(now());
        }
        step.setCompletedAt(now());
        appendMessageAndSave(step, message);

        skipDownstream(experiment, stepCode, mode);
    }

    /**
     * Appende des logs à une étape (sans changer son statut).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendLogs(MlExperiment experiment, String stepCode, String logs) {
        if (experiment == null || experiment.getId() == null || logs == null || logs.isBlank()) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        step.setLogs(mergeLogs(step.getLogs(), logs));
        stepRepository.saveAndFlush(step);
    }

    /**
     * Associe les identifiants Kubernetes/MLflow à une étape (job, pod, externalId).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateJobDetails(
            MlExperiment experiment,
            String stepCode,
            String jobName,
            String podName,
            String externalId
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (jobName != null && !jobName.isBlank()) {
            step.setJobName(jobName);
        }
        if (podName != null && !podName.isBlank()) {
            step.setPodName(podName);
        }
        if (externalId != null && !externalId.isBlank()) {
            step.setExternalId(externalId);
        }

        stepRepository.saveAndFlush(step);
    }

    /**
     * État complet du pipeline pour le frontend (GET /api/experiments/{id}/pipeline-status).
     * Peut écrire (backfill) pour initialiser les étapes d'un run sans événements.
     */
    @Transactional
    public PipelineStatusResponse getPipelineStatus(MlExperiment experiment) {
        PipelineStatusResponse response = new PipelineStatusResponse();

        if (experiment == null || experiment.getId() == null) {
            return response;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        response.setExperimentId(experiment.getId());
        response.setRuntimeMode(mode);
        response.setRunStatus(experiment.getStatus() != null ? experiment.getStatus().name() : null);
        response.setKubeflowStatus(experiment.getKubeflowStatus());
        response.setBuildStatus(experiment.getBuildStatus());

        List<RunPipelineStep> steps =
                stepRepository.findByExperimentIdOrderByOrderIndexAsc(experiment.getId());

        if (steps.isEmpty()) {
            steps = backfillSteps(experiment);
        }

        int globalProgress = computeGlobalProgress(steps, experiment);

        for (RunPipelineStep step : steps) {
            response.getSteps().add(toDto(step));
        }

        response.setGlobalProgress(globalProgress);
        response.setUpdatedAt(now().format(ISO_FORMAT));

        return response;
    }

    public String getStepLogs(Long experimentId, String stepCode) {
        return stepRepository.findByExperimentIdAndStepCode(experimentId, stepCode)
                .map(RunPipelineStep::getLogs)
                .orElse("");
    }

    /**
     * Correctif Promotion Production : SUCCEEDED même si l'étape avait été
     * provisoirement marquée SKIPPED par une décision antérieure. La promotion
     * réelle observée (alias @production / flag production) prime sur une
     * décision de non-promotion prise trop tôt. FAILED reste bloquant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProductionPromoted(MlExperiment experiment, String message) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                PRODUCTION_PROMOTED,
                mode,
                orderOf(mode, PRODUCTION_PROMOTED),
                labelOf(mode, PRODUCTION_PROMOTED),
                experiment.getExperimentName()
        );

        if (RunPipelineStep.STATUS_FAILED.equals(step.getStatus())) {
            return;
        }

        if (!RunPipelineStep.STATUS_SUCCEEDED.equals(step.getStatus())) {
            step.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
            step.setProgress(100);
            if (step.getStartedAt() == null) {
                step.setStartedAt(now());
            }
            step.setCompletedAt(now());
            step.setErrorCode(null);
            step.setErrorDetails(null);
        }

        appendMessageAndSave(step, message);
    }

    /**
     * Correctif Déploiement : SUCCEEDED quand un déploiement actif existe,
     * y compris si l'étape avait été provisoirement marquée SKIPPED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeploymentReady(MlExperiment experiment, String message) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                DEPLOYMENT_READY,
                mode,
                orderOf(mode, DEPLOYMENT_READY),
                labelOf(mode, DEPLOYMENT_READY),
                experiment.getExperimentName()
        );

        if (RunPipelineStep.STATUS_FAILED.equals(step.getStatus())) {
            return;
        }

        if (!RunPipelineStep.STATUS_SUCCEEDED.equals(step.getStatus())) {
            step.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
            step.setProgress(100);
            if (step.getStartedAt() == null) {
                step.setStartedAt(now());
            }
            step.setCompletedAt(now());
            step.setErrorCode(null);
            step.setErrorDetails(null);
        }

        appendMessageAndSave(step, message);
    }

        /**
     * Correctif Enregistrement MLflow : SUCCEEDED même si les étapes
     * Artefact/Enregistrement avaient été provisoirement marquées SKIPPED par
     * une décision antérieure. Une version effectivement enregistrée (promue ou
     * alignée sur l'alias @production) prime sur une décision trop précoce.
     * FAILED reste bloquant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markModelRegistered(
            MlExperiment experiment,
            String modelName,
            String modelVersion,
            String message
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String mode = normalizeMode(experiment.getRuntimeMode());

        upgradeStepIfNotFailed(experiment, mode, MODEL_ARTIFACT_DETECTED,
                "Artefact modèle détecté (version " + modelVersion + ").");
        upgradeStepIfNotFailed(experiment, mode, MLFLOW_REGISTERED,
                message != null && !message.isBlank()
                        ? message
                        : "Modèle " + modelName + " enregistré dans le Registry MLflow "
                        + "(version " + modelVersion + ").");
    }

    private void upgradeStepIfNotFailed(
            MlExperiment experiment,
            String mode,
            String stepCode,
            String message
    ) {
        RunPipelineStep step = ensureStep(
                experiment.getId(),
                stepCode,
                mode,
                orderOf(mode, stepCode),
                labelOf(mode, stepCode),
                experiment.getExperimentName()
        );

        if (RunPipelineStep.STATUS_FAILED.equals(step.getStatus())) {
            return;
        }

        if (!RunPipelineStep.STATUS_SUCCEEDED.equals(step.getStatus())) {
            step.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
            step.setProgress(100);
            if (step.getStartedAt() == null) {
                step.setStartedAt(now());
            }
            step.setCompletedAt(now());
            step.setErrorCode(null);
            step.setErrorDetails(null);
        }

        appendMessageAndSave(step, message);
    }

    /**
     * Résout les étapes restantes d'un run terminal et définitivement résolu
     * (échec confirmé ou non-promotion confirmée) :
     * - COMPLETED : une étape RUNNING est considérée réussie (le run s'est terminé),
     *   une étape PENDING est non applicable -> SKIPPED.
     * - FAILED/CANCELLED : toute étape non terminale -> SKIPPED.
     * Garantit qu'aucune étape ne reste PENDING/RUNNING indéfiniment sur un run fini.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveTerminalSteps(MlExperiment experiment) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        String runStatus = experiment.getStatus() == null
                ? ""
                : experiment.getStatus().name();

        List<RunPipelineStep> steps =
                stepRepository.findByExperimentIdOrderByOrderIndexAsc(experiment.getId());

        if (steps.isEmpty()) {
            return;
        }

        boolean completed = "COMPLETED".equalsIgnoreCase(runStatus)
                || "SUCCEEDED".equalsIgnoreCase(runStatus);

        for (RunPipelineStep step : steps) {
            if (isTerminal(step.getStatus())) {
                continue;
            }

            if (RunPipelineStep.STATUS_RUNNING.equals(step.getStatus()) && completed) {
                step.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
                step.setProgress(100);
                step.setCompletedAt(now());
                step.setMessage("Étape finalisée (le run s'est terminé avec succès).");
            } else {
                step.setStatus(RunPipelineStep.STATUS_SKIPPED);
                step.setProgress(0);
                if (step.getStartedAt() == null) {
                    step.setStartedAt(now());
                }
                step.setCompletedAt(now());
                step.setMessage("Étape non applicable à la fin du run.");
            }

            stepRepository.save(step);
        }
    }

    // ------------------------------------------------------------------
    // Mécanique interne
    // ------------------------------------------------------------------

    private RunPipelineStep ensureStep(
            Long experimentId,
            String stepCode,
            String mode,
            int order,
            String label,
            String runId
    ) {
        return stepRepository.findByExperimentIdAndStepCode(experimentId, stepCode)
                .orElseGet(() -> {
                    RunPipelineStep step = new RunPipelineStep();
                    step.setExperimentId(experimentId);
                    step.setStepCode(stepCode);
                    step.setLabel(label);
                    step.setExecutionMode(mode);
                    step.setOrderIndex(order);
                    step.setStatus(RunPipelineStep.STATUS_PENDING);
                    step.setProgress(0);
                    step.setRunId(runId);

                    try {
                        return stepRepository.saveAndFlush(step);
                    } catch (DataIntegrityViolationException duplicate) {
                        return stepRepository
                                .findByExperimentIdAndStepCode(experimentId, stepCode)
                                .orElse(step);
                    }
                });
    }

    private void skipDownstream(MlExperiment experiment, String failedStepCode, String mode) {
        List<String> codes = stepCodesForMode(mode);
        int failedIndex = codes.indexOf(failedStepCode);

        if (failedIndex < 0) {
            return;
        }

        List<RunPipelineStep> steps =
                stepRepository.findByExperimentIdOrderByOrderIndexAsc(experiment.getId());

        for (RunPipelineStep step : steps) {
            if (step.getOrderIndex() > failedIndex && !isTerminal(step.getStatus())) {
                step.setStatus(RunPipelineStep.STATUS_SKIPPED);
                step.setProgress(0);
                step.setCompletedAt(now());
                step.setMessage("Étape non exécutée suite à l'étape précédente (échec ou non applicable).");
                stepRepository.save(step);
            }
        }
    }

    /**
     * Garde-fous d'ordre :
     * - Les étapes Métriques / Artefact / Registry MLflow ne peuvent pas avancer
     *   tant que l'entraînement (gate du runtime) n'est pas terminal.
     * - Promotion Production / Déploiement ne peuvent pas avancer tant que le
     *   modèle n'est pas enregistré dans le Registry MLflow (étape terminale).
     * Une traversée bloquée est silencieuse : l'étape reste PENDING et sera
     * appliquée au tick suivant (idempotence + convergence).
     */
    private boolean hasBlockedPrerequisite(Long experimentId, String mode, String stepCode) {
        boolean isMetricsBlock = METRICS_EXTRACTED.equals(stepCode)
                || MODEL_ARTIFACT_DETECTED.equals(stepCode)
                || MLFLOW_REGISTERED.equals(stepCode);

        boolean isFinalBlock = PRODUCTION_PROMOTED.equals(stepCode)
                || DEPLOYMENT_READY.equals(stepCode);

        if (!isMetricsBlock && !isFinalBlock) {
            return false;
        }

        List<RunPipelineStep> steps =
                stepRepository.findByExperimentIdOrderByOrderIndexAsc(experimentId);

        if (steps.isEmpty()) {
            return false;
        }

        if (isMetricsBlock) {
            String gate = isCustomMode(mode) ? TRAINING_RUNNING : KFP_TRAINING_RUNNING;
            if (!isStepTerminal(steps, gate)) {
                return true;
            }
        }

        if (isFinalBlock && !isStepTerminal(steps, MLFLOW_REGISTERED)) {
            return true;
        }

        return false;
    }

    private boolean isStepTerminal(List<RunPipelineStep> steps, String stepCode) {
        return steps.stream()
                .filter(step -> stepCode.equals(step.getStepCode()))
                .findFirst()
                .map(step -> isTerminal(step.getStatus()))
                .orElse(false);
    }

    private int computeGlobalProgress(List<RunPipelineStep> steps, MlExperiment experiment) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }

        int total = steps.size();
        int succeeded = 0;
        int running = 0;
        int pending = 0;
        int failed = 0;
        int skipped = 0;

        for (RunPipelineStep step : steps) {
            switch (step.getStatus()) {
                case RunPipelineStep.STATUS_SUCCEEDED -> succeeded++;
                case RunPipelineStep.STATUS_RUNNING -> running++;
                case RunPipelineStep.STATUS_PENDING -> pending++;
                case RunPipelineStep.STATUS_FAILED -> failed++;
                case RunPipelineStep.STATUS_SKIPPED -> skipped++;
            }
        }

        /*
         * 100 % uniquement quand plus aucune étape n'est PENDING/RUNNING :
         * toutes les étapes sont alors résolues (SUCCEEDED, SKIPPED ou FAILED).
         * Tant qu'une étape est en attente ou en cours, le 100 % est impossible,
         * même si l'expérience est déjà terminale côté statut global.
         */
        if (running + pending == 0) {
            if (failed > 0) {
                return Math.round((succeeded * 100.0f) / total);
            }
            return 100;
        }

        /*
         * Pipeline encore active : le progrès ne compte que les étapes SUCCEEDED
         * (les SKIPPED ne pénalisent pas le dénominateur). Une étape en échec
         * ne contribue jamais au 100 %.
         */
        if (failed > 0) {
            return Math.round((succeeded * 100.0f) / total);
        }

        int denominator = total - skipped;
        if (denominator <= 0) {
            return 0;
        }

        double score = succeeded + (running * 0.5);
        return (int) Math.round((score * 100.0f) / denominator);
    }

    /**
     * Backfill conservateur pour les runs lancés AVANT l'arrivée des événements d'étapes :
     * on ne marque comme réelles que les étapes dont on a une trace concrète (statut de build
     * / job réellement enregistré par le watcher, ou run Kubeflow soumis). Jamais de SUCCEEDED
     * inventé pour des étapes non vérifiées.
     */
    private List<RunPipelineStep> backfillSteps(MlExperiment experiment) {
        String mode = normalizeMode(experiment.getRuntimeMode());
        Map<String, String> definitions = isCustomMode(mode)
                ? customStepDefinitions()
                : standardStepDefinitions();

        int order = 0;
        for (Map.Entry<String, String> entry : definitions.entrySet()) {
            ensureStep(
                    experiment.getId(),
                    entry.getKey(),
                    mode,
                    order,
                    entry.getValue(),
                    experiment.getExperimentName()
            );
            order++;
        }

        List<RunPipelineStep> steps =
                stepRepository.findByExperimentIdOrderByOrderIndexAsc(experiment.getId());

        RunPipelineStep submitStep = findStep(steps, isCustomMode(mode) ? FILES_VALIDATED : SUBMITTED);
        if (submitStep != null) {
            submitStep.setStatus(RunPipelineStep.STATUS_SUCCEEDED);
            submitStep.setProgress(100);
            submitStep.setCompletedAt(now());
            stepRepository.save(submitStep);
        }

        if (isCustomMode(mode)) {
            backfillCustomFromBuildStatus(steps, experiment.getBuildStatus());
        } else {
            backfillStandardFromKubeflowStatus(steps, experiment.getKubeflowStatus(),
                    experiment.getMlflowRunId(), experiment.getModelVersion());
        }

        return stepRepository.findByExperimentIdOrderByOrderIndexAsc(experiment.getId());
    }

    private void backfillCustomFromBuildStatus(List<RunPipelineStep> steps, String buildStatus) {
        String status = buildStatus == null ? "" : buildStatus.trim().toUpperCase();

        if (status.startsWith("IMAGE_BUILD_RUNNING") || status.startsWith("WAITING_FOR_IMAGE_BUILD")) {
            markStep(steps, IMAGE_BUILD_STARTED, RunPipelineStep.STATUS_RUNNING);
        } else if (status.startsWith("IMAGE_BUILD_COMPLETED")
                || status.startsWith("CUSTOM_IMAGE_JOB") && status.contains("COMPLETED")
                || status.equals("CUSTOM_IMAGE_JOB_RUNNING")
                || status.startsWith("CUSTOM_IMAGE_JOB_STARTING")
                || status.equals("TRAINING_COMPLETED")) {
            markStep(steps, IMAGE_BUILD_STARTED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, IMAGE_BUILD_COMPLETED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, ACR_PUSH_COMPLETED, RunPipelineStep.STATUS_SUCCEEDED);
            if (status.equals("TRAINING_COMPLETED")
                    || status.startsWith("CUSTOM_IMAGE_JOB") && status.contains("COMPLETED")) {
                markStep(steps, TRAINING_JOB_CREATED, RunPipelineStep.STATUS_SUCCEEDED);
                markStep(steps, TRAINING_RUNNING, RunPipelineStep.STATUS_SUCCEEDED);
            } else {
                markStep(steps, TRAINING_JOB_CREATED, RunPipelineStep.STATUS_RUNNING);
                markStep(steps, TRAINING_RUNNING, RunPipelineStep.STATUS_RUNNING);
            }
        }
    }

    private void backfillStandardFromKubeflowStatus(
            List<RunPipelineStep> steps,
            String kubeflowStatus,
            String mlflowRunId,
            String modelVersion
    ) {
        String status = kubeflowStatus == null ? "" : kubeflowStatus.trim().toUpperCase();

        if (status.equals("COMPLETED") || status.equals("SUCCEEDED")) {
            markStep(steps, KFP_PIPELINE_CREATED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, KFP_RUN_SUBMITTED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, DATA_PREPARED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, KFP_TRAINING_RUNNING, RunPipelineStep.STATUS_SUCCEEDED);

            if (mlflowRunId != null && !mlflowRunId.isBlank()) {
                markStep(steps, METRICS_EXTRACTED, RunPipelineStep.STATUS_SUCCEEDED);
                markStep(steps, MODEL_ARTIFACT_DETECTED, RunPipelineStep.STATUS_SUCCEEDED);
                markStep(steps, MLFLOW_REGISTERED, RunPipelineStep.STATUS_SUCCEEDED);
            }

            if (modelVersion != null && !modelVersion.isBlank()) {
                markStep(steps, PRODUCTION_PROMOTED, RunPipelineStep.STATUS_SUCCEEDED);
            }
        } else if (status.equals("RUNNING")) {
            markStep(steps, KFP_PIPELINE_CREATED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, KFP_RUN_SUBMITTED, RunPipelineStep.STATUS_SUCCEEDED);
            markStep(steps, KFP_TRAINING_RUNNING, RunPipelineStep.STATUS_RUNNING);
        }
    }

    private void markStep(List<RunPipelineStep> steps, String code, String status) {
        RunPipelineStep step = findStep(steps, code);
        if (step == null) {
            return;
        }

        if (isTerminal(step.getStatus())) {
            return;
        }

        step.setStatus(status);
        step.setProgress(status.equals(RunPipelineStep.STATUS_SUCCEEDED) ? 100 : 50);
        step.setStartedAt(now());
        if (status.equals(RunPipelineStep.STATUS_SUCCEEDED)) {
            step.setCompletedAt(now());
        }
        stepRepository.save(step);
    }

    private RunPipelineStep findStep(List<RunPipelineStep> steps, String code) {
        return steps.stream()
                .filter(step -> code.equals(step.getStepCode()))
                .findFirst()
                .orElse(null);
    }

    private void appendMessageAndSave(RunPipelineStep step, String message) {
        if (message != null && !message.isBlank()) {
            step.setMessage(message);
        }
        stepRepository.saveAndFlush(step);
    }

    private RunPipelineStepDto toDto(RunPipelineStep step) {
        RunPipelineStepDto dto = new RunPipelineStepDto();
        dto.setStepCode(step.getStepCode());
        dto.setLabel(step.getLabel());
        dto.setExecutionMode(step.getExecutionMode());
        dto.setStatus(step.getStatus());
        dto.setProgress(step.getProgress());
        dto.setOrderIndex(step.getOrderIndex());
        dto.setStartedAt(formatDateTime(step.getStartedAt()));
        dto.setCompletedAt(formatDateTime(step.getCompletedAt()));
        dto.setMessage(step.getMessage());
        dto.setErrorCode(step.getErrorCode());
        dto.setErrorDetails(step.getErrorDetails());
        dto.setJobName(step.getJobName());
        dto.setPodName(step.getPodName());
        dto.setExternalId(step.getExternalId());
        dto.setUpdatedAt(formatDateTime(step.getUpdatedAt()));
        dto.setHasLogs(step.getLogs() != null && !step.getLogs().isBlank());
        return dto;
    }

    private int orderOf(String mode, String stepCode) {
        return stepCodesForMode(mode).indexOf(stepCode);
    }

    private String labelOf(String mode, String stepCode) {
        Map<String, String> definitions = isCustomMode(mode)
                ? customStepDefinitions()
                : standardStepDefinitions();
        return definitions.getOrDefault(stepCode, stepCode);
    }

    private String normalizeMode(String runtimeMode) {
        if (runtimeMode == null || runtimeMode.isBlank()) {
            return RUNTIME_STANDARD;
        }
        String upper = runtimeMode.trim().toUpperCase();
        return isCustomMode(upper) ? RUNTIME_CUSTOM : RUNTIME_STANDARD;
    }

    private boolean isTerminal(String status) {
        return RunPipelineStep.STATUS_SUCCEEDED.equals(status)
                || RunPipelineStep.STATUS_FAILED.equals(status)
                || RunPipelineStep.STATUS_SKIPPED.equals(status);
    }

    private String mergeLogs(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        if (existing.contains(incoming)) {
            return existing;
        }
        return existing + "\n" + incoming;
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(ISO_FORMAT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC());
    }
}
