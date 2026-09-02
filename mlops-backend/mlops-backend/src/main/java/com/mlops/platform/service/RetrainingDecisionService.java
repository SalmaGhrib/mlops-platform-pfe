package com.mlops.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.PredictionLog;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.PredictionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RetrainingDecisionService {

    private static final Logger log =
            LoggerFactory.getLogger(RetrainingDecisionService.class);

    private final PredictionLogRepository predictionLogRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final ObjectMapper objectMapper;

    public RetrainingDecisionService(
            PredictionLogRepository predictionLogRepository,
            MlExperimentRepository mlExperimentRepository,
            ObjectMapper objectMapper
    ) {
        this.predictionLogRepository = predictionLogRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Décision centralisée de réentraînement.
     *
     * Applique la stratégie de la configuration et intègre le monitoring,
     * la sévérité et la classification sécurité.
     */
    public RetrainingDecision buildDecision(
            RetrainingConfig config,
            LocalDateTime now,
            boolean sourceReady
    ) {
        if (config == null || config.getProjectId() == null) {
            return RetrainingDecision.skip(
                    RetrainingSeverity.NONE,
                    "Configuration de réentraînement invalide."
            );
        }

        config.normalizeMonitoringSettings();

        /*
         * Compatibilité avec l'ancien mécanisme manuel.
         * Conservé pour ne pas casser les autres fonctionnalités.
         */
        if (config.isManualTriggerRequested()) {
            RetrainingDecision manual = RetrainingDecision.run(
                    RetrainingTriggerType.MANUAL,
                    RetrainingSeverity.HIGH,
                    "Réentraînement demandé manuellement par l'utilisateur."
            );
            manual.addReasonCode("MANUAL_REQUEST");
            return manual;
        }

        if (!sourceReady) {
            RetrainingDecision blocked = RetrainingDecision.skip(
                    RetrainingSeverity.LOW,
                    "Aucune expérience Production valide : le réentraînement "
                            + "est différé jusqu'à la disponibilité d'une source."
            );

            blocked.setSecurityLevel(
                    RetrainingSecurityLevel.SECURITY_ALERT_ONLY
            );

            blocked.addReasonCode("NO_VALID_SOURCE");
            blocked.addReasonCode("DEFERRED");

            return blocked;
        }

        RetrainingDecision decision =
                evaluateMonitoring(config, now);

        return applySecurityClassification(decision);
    }

    /**
     * Classification sécurité.
     *
     * Un ERROR_RATE_EXTREME peut rester bloquant.
     *
     * En revanche, un DATA_DRIFT élevé ne doit pas annuler le retraining :
     * c'est précisément un signal qui justifie le réentraînement.
     */
    private RetrainingDecision applySecurityClassification(
            RetrainingDecision decision
    ) {
        if (decision == null) {
            return RetrainingDecision.skip(
                    RetrainingSeverity.LOW,
                    "Décision indisponible."
            );
        }

        List<String> reasonCodes =
                decision.getReasonCodes() != null
                        ? decision.getReasonCodes()
                        : Collections.emptyList();

        boolean extremeErrorRate =
                reasonCodes.contains("ERROR_RATE_EXTREME");

        boolean extremeDrift =
                reasonCodes.contains("DRIFT_EXTREME");

        /*
         * Les erreurs extrêmes explicitement considérées suspectes
         * restent bloquantes.
         */
        if (extremeErrorRate) {
            boolean blockModel =
                    reasonCodes.contains("ERROR_RATE_BLOCK");

            decision.setShouldRetrain(false);

            decision.setTriggerType(
                    blockModel
                            ? RetrainingTriggerType.BLOCKED_BY_SECURITY
                            : RetrainingTriggerType.SKIPPED_SUSPECT_DATA
            );

            decision.setSecurityLevel(
                    blockModel
                            ? RetrainingSecurityLevel.BLOCK_MODEL
                            : RetrainingSecurityLevel.SECURITY_ALERT_ONLY
            );

            decision.setSeverity(
                    RetrainingSeverity.CRITICAL
            );

            decision.addReasonCode("SUSPECT_DATA");
            decision.addReasonCode("BLOCKED_FOR_SECURITY");

            decision.setReason(
                    "[SECURITE] Donnees suspectes detectees : le retraining "
                            + "est bloque pour eviter de corrompre le modele. "
                            + safeMessage(decision.getReason())
            );

            return decision;
        }

        /*
         * Un drift très important reste un DATA_DRIFT valide.
         * On augmente simplement la sévérité.
         */
        if (extremeDrift && decision.isShouldRetrain()) {
            decision.setSeverity(
                    RetrainingSeverity.CRITICAL
            );

            decision.setSecurityLevel(
                    RetrainingSecurityLevel.RETRAIN_RECOMMENDED
            );
        }

        if (decision.isShouldRetrain()
                && decision.getSecurityLevel()
                == RetrainingSecurityLevel.NONE) {

            decision.setSecurityLevel(
                    RetrainingSecurityLevel.RETRAIN_RECOMMENDED
            );
        }

        return decision;
    }

    /**
     * Évalue les signaux actifs :
     *
     * - PERFORMANCE_DROP
     * - DATA_DRIFT
     */
    private RetrainingDecision evaluateMonitoring(
            RetrainingConfig config,
            LocalDateTime now
    ) {
        Long projectId = config.getProjectId();

        int lookbackHours =
                safeInt(
                        config.getMonitoringLookbackHours(),
                        24
                );

        LocalDateTime recentStart =
                now.minusHours(lookbackHours);

        long minPredictions =
                safeLong(
                        config.getMinPredictionsForMonitoring(),
                        20
                );

        RetrainingDecision result =
                RetrainingDecision.skip(
                        RetrainingSeverity.NONE,
                        "Aucun signal de monitoring ne justifie un réentraînement."
                );

        long recentPredictionCount =
                safeCount(
                        predictionLogRepository
                                .countByProjectIdAndCreatedAtAfter(
                                        projectId,
                                        recentStart
                                )
                );

        result.addObservedMetric(
                "recentPredictionCount",
                (double) recentPredictionCount
        );

        if (recentPredictionCount < minPredictions) {
            result.addReasonCode("INSUFFICIENT_DATA");

            result.setReason(
                    "Pas assez de prédictions pour évaluer le monitoring."
            );

            logMonitoringResult(
                    projectId,
                    recentPredictionCount,
                    result
            );

            return result;
        }

        /*
         * =========================================================
         * PERFORMANCE DROP
         * =========================================================
         */

        double performanceDrop =
                evaluatePerformanceDrop(projectId);

        double dropThreshold =
                safeDouble(
                        config.getPerformanceDropThresholdPercent(),
                        10.0
                );

        result.addObservedMetric(
                "performanceDropPercent",
                roundTwo(performanceDrop)
        );

        result.addThreshold(
                "performanceDropThresholdPercent",
                dropThreshold
        );

        if (performanceDrop >= dropThreshold) {
            RetrainingDecision run =
                    RetrainingDecision.run(
                            RetrainingTriggerType.PERFORMANCE_DROP,
                            performanceDrop >= dropThreshold * 2
                                    ? RetrainingSeverity.CRITICAL
                                    : RetrainingSeverity.HIGH,
                            "Monitoring : baisse de performance du modèle en production. "
                                    + "Score global="
                                    + roundTwo(currentProductionScore(projectId))
                                    + ", baisse="
                                    + roundTwo(performanceDrop)
                                    + "%"
                                    + ", seuil="
                                    + dropThreshold
                                    + "%."
                    );

            run.addReasonCode("PERFORMANCE_DROP");

            run.addObservedMetric(
                    "recentPredictionCount",
                    (double) recentPredictionCount
            );

            run.addObservedMetric(
                    "performanceDropPercent",
                    roundTwo(performanceDrop)
            );

            run.addThreshold(
                    "performanceDropThresholdPercent",
                    dropThreshold
            );

            logMonitoringResult(
                    projectId,
                    recentPredictionCount,
                    run
            );

            return run;
        }

        /*
         * =========================================================
         * DATA DRIFT
         * =========================================================
         *
         * Ancienne logique supprimée :
         *
         * baseline = -48h -> -24h
         * recent   = -24h -> maintenant
         *
         * Cette logique donnait baseline=0 sur un nouveau modèle.
         *
         * Nouvelle logique :
         *
         * uniquement les prédictions réussies de l'expérience
         * actuellement en Production.
         *
         * premières N prédictions = baseline
         * dernières N prédictions = recent
         */

        DriftWindows driftWindows =
                resolveDriftWindows(
                        projectId,
                        minPredictions
                );

        double driftThreshold =
                safeDouble(
                        config.getDataDriftThresholdPercent(),
                        5.0
                );

        result.addThreshold(
                "dataDriftThresholdPercent",
                driftThreshold
        );

        if (!driftWindows.isReady(minPredictions)) {
            result.addObservedMetric(
                    "dataDriftScorePercent",
                    0.0
            );

            result.addReasonCode(
                    "INSUFFICIENT_DRIFT_DATA"
            );

            result.setReason(
                    "Pas encore assez de prédictions Production "
                            + "pour construire une baseline et une fenêtre récente. "
                            + "Disponible="
                            + driftWindows.getTotalPredictions()
                            + ", requis="
                            + (minPredictions * 2)
                            + "."
            );

            logMonitoringResult(
                    projectId,
                    driftWindows.getTotalPredictions(),
                    result
            );

            return result;
        }

        List<PredictionLog> baseline =
                driftWindows.getBaseline();

        List<PredictionLog> recent =
                driftWindows.getRecent();

        double driftScore =
                calculateSimpleDataDriftScore(
                        baseline,
                        recent
                );

        result.addObservedMetric(
                "dataDriftScorePercent",
                roundTwo(driftScore)
        );

        if (driftScore >= driftThreshold) {
            RetrainingDecision run =
                    RetrainingDecision.run(
                            RetrainingTriggerType.DATA_DRIFT,
                            driftScore >= driftThreshold * 2
                                    ? RetrainingSeverity.CRITICAL
                                    : RetrainingSeverity.HIGH,
                            "Data drift détecté sur les inputs de prédiction. "
                                    + "Score="
                                    + roundTwo(driftScore)
                                    + "%"
                                    + ", seuil="
                                    + driftThreshold
                                    + "%"
                                    + ", baseline="
                                    + baseline.size()
                                    + ", récent="
                                    + recent.size()
                                    + ", experimentId="
                                    + driftWindows.getProductionExperimentId()
                                    + "."
                    );

            run.addReasonCode("DATA_DRIFT");

            run.addObservedMetric(
                    "recentPredictionCount",
                    (double) recent.size()
            );

            run.addObservedMetric(
                    "performanceDropPercent",
                    roundTwo(performanceDrop)
            );

            run.addObservedMetric(
                    "dataDriftScorePercent",
                    roundTwo(driftScore)
            );

            run.addThreshold(
                    "performanceDropThresholdPercent",
                    dropThreshold
            );

            run.addThreshold(
                    "dataDriftThresholdPercent",
                    driftThreshold
            );

            /*
             * Un drift très élevé reste un signal de retraining.
             *
             * Il n'est plus transformé en BLOCK_MODEL.
             */
            if (driftScore >= driftThreshold * 2) {
                run.setSeverity(
                        RetrainingSeverity.CRITICAL
                );

                run.setSecurityLevel(
                        RetrainingSecurityLevel.RETRAIN_RECOMMENDED
                );

                run.addReasonCode(
                        "DRIFT_EXTREME"
                );
            }

            logMonitoringResult(
                    projectId,
                    recent.size(),
                    run
            );

            return run;
        }

        String reason =
                "Monitoring OK : "
                        + "prédictions récentes="
                        + recentPredictionCount
                        + ", drift="
                        + roundTwo(driftScore)
                        + "%.";

        result.setReason(reason);
        result.addReasonCode("MONITORING_OK");

        result.setSeverity(
                RetrainingSeverity.NONE
        );

        logMonitoringResult(
                projectId,
                recentPredictionCount,
                result
        );

        return result;
    }

    /**
     * Construit les fenêtres de drift à partir des prédictions
     * réussies de l'expérience actuellement en Production.
     *
     * Exemple :
     *
     * minPredictions = 5
     * total = 17
     *
     * baseline = prédictions 1..5
     * recent   = prédictions 13..17
     */
    private DriftWindows resolveDriftWindows(
            Long projectId,
            long minPredictions
    ) {
        MlExperiment production =
                mlExperimentRepository
                        .findCurrentProductionExperimentForProject(
                                projectId
                        )
                        .orElse(null);

        if (production == null
                || production.getId() == null) {

            log.info(
                    "[DATA_DRIFT] projectId={} aucune expérience Production trouvée",
                    projectId
            );

            return new DriftWindows(
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0
            );
        }

        List<PredictionLog> predictions =
                predictionLogRepository
                        .findSuccessfulPredictionsForProduction(
                                projectId,
                                production.getId()
                        );

        if (predictions == null) {
            predictions =
                    Collections.emptyList();
        }

        int windowSize =
                (int) Math.max(
                        1L,
                        minPredictions
                );

        int required =
                windowSize * 2;

        if (predictions.size() < required) {
            log.info(
                    "[DATA_DRIFT] projectId={} experimentId={} "
                            + "données insuffisantes total={} required={}",
                    projectId,
                    production.getId(),
                    predictions.size(),
                    required
            );

            return new DriftWindows(
                    production.getId(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    predictions.size()
            );
        }

        List<PredictionLog> baseline =
                new ArrayList<>(
                        predictions.subList(
                                0,
                                windowSize
                        )
                );

        List<PredictionLog> recent =
                new ArrayList<>(
                        predictions.subList(
                                predictions.size() - windowSize,
                                predictions.size()
                        )
                );

        log.info(
                "[DATA_DRIFT] projectId={} experimentId={} "
                        + "baseline={} recent={} total={}",
                projectId,
                production.getId(),
                baseline.size(),
                recent.size(),
                predictions.size()
        );

        return new DriftWindows(
                production.getId(),
                baseline,
                recent,
                predictions.size()
        );
    }

    private void logMonitoringResult(
            Long projectId,
            long predictions,
            RetrainingDecision decision
    ) {
        double performanceDrop =
                decision.getObservedMetrics()
                        .getOrDefault(
                                "performanceDropPercent",
                                0.0
                        );

        double drift =
                decision.getObservedMetrics()
                        .getOrDefault(
                                "dataDriftScorePercent",
                                0.0
                        );

        double performanceThreshold =
                decision.getThresholds()
                        .getOrDefault(
                                "performanceDropThresholdPercent",
                                0.0
                        );

        double driftThreshold =
                decision.getThresholds()
                        .getOrDefault(
                                "dataDriftThresholdPercent",
                                0.0
                        );

        log.info(
                "[MONITORING_RETRAINING] projectId={} predictions={} "
                        + "performanceDrop={}% drift={}% "
                        + "performanceThreshold={}% driftThreshold={}% "
                        + "shouldRetrain={} reasonCodes={}",
                projectId,
                predictions,
                performanceDrop,
                drift,
                performanceThreshold,
                driftThreshold,
                decision.isShouldRetrain(),
                decision.getReasonCodes()
        );
    }

    /**
     * Conservé pour compatibilité.
     */
    private void copyObservedMetrics(
            RetrainingDecision source,
            RetrainingDecision target
    ) {
        if (source == null || target == null) {
            return;
        }

        target.getObservedMetrics()
                .putAll(
                        source.getObservedMetrics()
                );
    }

    /**
     * Pré-validation avant lancement effectif du retraining.
     */
    public RetrainingDecisionResult evaluateBeforeRetraining(
            RetrainingConfig config
    ) {
        if (config == null
                || config.getProjectId() == null) {

            return RetrainingDecisionResult.skip(
                    "Configuration de réentraînement invalide."
            );
        }

        config.normalizeMonitoringSettings();

        if (config.isManualTriggerRequested()) {
            return RetrainingDecisionResult.run(
                    RetrainingTriggerType.MANUAL,
                    "Réentraînement demandé manuellement par l'utilisateur."
            );
        }

        return evaluateMonitoringSignals(config);
    }

    /**
     * Même logique de monitoring utilisée juste avant
     * le lancement du retraining.
     *
     * Important : utilise désormais la même baseline de drift
     * que evaluateMonitoring().
     */
    public RetrainingDecisionResult evaluateMonitoringSignals(
            RetrainingConfig config
    ) {
        if (config == null
                || config.getProjectId() == null) {

            return RetrainingDecisionResult.skip(
                    "Configuration de monitoring invalide."
            );
        }

        config.normalizeMonitoringSettings();

        Long projectId =
                config.getProjectId();

        int lookbackHours =
                safeInt(
                        config.getMonitoringLookbackHours(),
                        24
                );

        LocalDateTime now =
                LocalDateTime.now(
                        Clock.systemUTC()
                );

        LocalDateTime recentStart =
                now.minusHours(
                        lookbackHours
                );

        long minPredictions =
                safeLong(
                        config.getMinPredictionsForMonitoring(),
                        20
                );

        RetrainingDecisionResult result =
                RetrainingDecisionResult.skip(
                        "Aucun signal de monitoring "
                                + "ne justifie un réentraînement."
                );

        long recentPredictionCount =
                safeCount(
                        predictionLogRepository
                                .countByProjectIdAndCreatedAtAfter(
                                        projectId,
                                        recentStart
                                )
                );

        result.setRecentPredictionCount(
                recentPredictionCount
        );

        if (recentPredictionCount < minPredictions) {
            result.setReason(
                    "Pas assez de prédictions pour évaluer le monitoring."
            );

            return result;
        }

        /*
         * =========================================================
         * PERFORMANCE DROP
         * =========================================================
         */

        double performanceDrop =
                evaluatePerformanceDrop(
                        projectId
                );

        double dropThreshold =
                safeDouble(
                        config.getPerformanceDropThresholdPercent(),
                        10.0
                );

        result.setPerformanceDropPercent(
                roundTwo(performanceDrop)
        );

        if (performanceDrop >= dropThreshold) {
            RetrainingDecisionResult run =
                    RetrainingDecisionResult.run(
                            RetrainingTriggerType.PERFORMANCE_DROP,
                            "Monitoring : baisse de performance "
                                    + "du modèle en production. "
                                    + "Score global="
                                    + roundTwo(
                                            currentProductionScore(
                                                    projectId
                                            )
                                    )
                                    + ", baisse="
                                    + roundTwo(performanceDrop)
                                    + "%"
                                    + ", seuil="
                                    + dropThreshold
                                    + "%."
                    );

            run.setRecentPredictionCount(
                    recentPredictionCount
            );

            run.setPerformanceDropPercent(
                    roundTwo(performanceDrop)
            );

            return run;
        }

        /*
         * =========================================================
         * DATA DRIFT
         * =========================================================
         */

        DriftWindows driftWindows =
                resolveDriftWindows(
                        projectId,
                        minPredictions
                );

        if (!driftWindows.isReady(minPredictions)) {
            result.setRecentPredictionCount(
                    driftWindows.getTotalPredictions()
            );

            result.setDataDriftScorePercent(
                    0.0
            );

            result.setReason(
                    "Pas encore assez de prédictions Production "
                            + "pour évaluer le data drift. "
                            + "Disponible="
                            + driftWindows.getTotalPredictions()
                            + ", requis="
                            + (minPredictions * 2)
                            + "."
            );

            return result;
        }

        List<PredictionLog> baseline =
                driftWindows.getBaseline();

        List<PredictionLog> recent =
                driftWindows.getRecent();

        double driftScore =
                calculateSimpleDataDriftScore(
                        baseline,
                        recent
                );

        result.setDataDriftScorePercent(
                roundTwo(driftScore)
        );

        double driftThreshold =
                safeDouble(
                        config.getDataDriftThresholdPercent(),
                        5.0
                );

        if (driftScore >= driftThreshold) {
            RetrainingDecisionResult run =
                    RetrainingDecisionResult.run(
                            RetrainingTriggerType.DATA_DRIFT,
                            "Data drift détecté sur les inputs de prédiction. "
                                    + "Score="
                                    + roundTwo(driftScore)
                                    + "%"
                                    + ", seuil="
                                    + driftThreshold
                                    + "%"
                                    + ", baseline="
                                    + baseline.size()
                                    + ", récent="
                                    + recent.size()
                                    + ", experimentId="
                                    + driftWindows.getProductionExperimentId()
                                    + "."
                    );

            run.setRecentPredictionCount(
                    recent.size()
            );

            run.setDataDriftScorePercent(
                    roundTwo(driftScore)
            );

            return run;
        }

        String reason =
                "Monitoring OK : "
                        + "prédictions récentes="
                        + recentPredictionCount
                        + ", drift="
                        + roundTwo(driftScore)
                        + "%.";

        result.setReason(reason);

        return result;
    }

    /**
     * Score global du modèle actuellement en production.
     */
    private double currentProductionScore(
            Long projectId
    ) {
        MlExperiment production =
                mlExperimentRepository
                        .findCurrentProductionExperimentForProject(
                                projectId
                        )
                        .orElse(null);

        if (production == null
                || production.getGlobalScore() == null) {

            return 0.0;
        }

        return production.getGlobalScore();
    }

    /**
     * Compare le modèle Production au meilleur score historique
     * du même registeredModelName.
     */
    private double evaluatePerformanceDrop(
            Long projectId
    ) {
        if (projectId == null) {
            return 0.0;
        }

        MlExperiment production =
                mlExperimentRepository
                        .findCurrentProductionExperimentForProject(
                                projectId
                        )
                        .orElse(null);

        if (production == null
                || production.getGlobalScore() == null) {

            return 0.0;
        }

        String registeredModelName =
                production.getRegisteredModelName();

        if (registeredModelName == null
                || registeredModelName.isBlank()) {

            return 0.0;
        }

        List<MlExperiment> candidates =
                mlExperimentRepository
                        .findEligibleProductionCandidates(
                                registeredModelName
                        );

        double bestScore =
                production.getGlobalScore();

        if (candidates != null) {
            for (MlExperiment candidate : candidates) {
                if (candidate == null
                        || candidate.getGlobalScore() == null) {
                    continue;
                }

                bestScore =
                        Math.max(
                                bestScore,
                                candidate.getGlobalScore()
                        );
            }
        }

        if (bestScore <= 0.0) {
            return 0.0;
        }

        double drop =
                (
                        (
                                bestScore
                                        - production.getGlobalScore()
                        )
                                / bestScore
                )
                        * 100.0;

        return Math.max(
                0.0,
                roundTwo(drop)
        );
    }

    /**
     * Calcule un score simple de data drift à partir
     * des inputs enregistrés dans prediction_logs.
     */
    private double calculateSimpleDataDriftScore(
            List<PredictionLog> baseline,
            List<PredictionLog> recent
    ) {
        if (baseline == null
                || recent == null
                || baseline.isEmpty()
                || recent.isEmpty()) {

            return 0.0;
        }

        Map<String, FeatureStats> baselineStats =
                buildFeatureStats(baseline);

        Map<String, FeatureStats> recentStats =
                buildFeatureStats(recent);

        if (baselineStats.isEmpty()
                || recentStats.isEmpty()) {

            log.warn(
                    "[DATA_DRIFT] Impossible de calculer le drift : "
                            + "baselineStats={} recentStats={}",
                    baselineStats.size(),
                    recentStats.size()
            );

            return 0.0;
        }

        Set<String> features =
                new LinkedHashSet<>();

        features.addAll(
                baselineStats.keySet()
        );

        features.addAll(
                recentStats.keySet()
        );

        double total = 0.0;
        int compared = 0;

        for (String feature : features) {
            FeatureStats b =
                    baselineStats.get(feature);

            FeatureStats r =
                    recentStats.get(feature);

            if (b == null || r == null) {
                total += 100.0;
                compared++;
                continue;
            }

            /*
             * Feature numérique.
             */
            if (b.numericCount > 0
                    && r.numericCount > 0) {

                double bMean =
                        b.numericSum
                                / Math.max(
                                        1,
                                        b.numericCount
                                );

                double rMean =
                        r.numericSum
                                / Math.max(
                                        1,
                                        r.numericCount
                                );

                double denominator =
                        Math.max(
                                1.0,
                                Math.abs(bMean)
                        );

                double score =
                        Math.min(
                                100.0,
                                Math.abs(
                                        rMean - bMean
                                )
                                        / denominator
                                        * 100.0
                        );

                total += score;
                compared++;
                continue;
            }

            /*
             * Feature catégorielle.
             */
            if (!b.categories.isEmpty()
                    || !r.categories.isEmpty()) {

                Set<String> union =
                        new HashSet<>();

                union.addAll(
                        b.categories
                );

                union.addAll(
                        r.categories
                );

                Set<String> intersection =
                        new HashSet<>(
                                b.categories
                        );

                intersection.retainAll(
                        r.categories
                );

                double similarity =
                        union.isEmpty()
                                ? 1.0
                                : (
                                        (double) intersection.size()
                                                / union.size()
                                );

                total +=
                        (1.0 - similarity)
                                * 100.0;

                compared++;
            }
        }

        if (compared == 0) {
            return 0.0;
        }

        return total / compared;
    }

    /**
     * Construit les statistiques de chaque feature.
     */
    private Map<String, FeatureStats> buildFeatureStats(
            List<PredictionLog> logs
    ) {
        Map<String, FeatureStats> stats =
                new LinkedHashMap<>();

        if (logs == null) {
            return stats;
        }

        for (PredictionLog predictionLog : logs) {
            if (predictionLog == null
                    || predictionLog.getInputJson() == null
                    || predictionLog.getInputJson().isBlank()) {

                continue;
            }

            Map<String, Object> input =
                    parseInputJson(
                            predictionLog.getInputJson()
                    );

            flattenInput(input)
                    .forEach(
                            (feature, value) -> {
                                FeatureStats featureStats =
                                        stats.computeIfAbsent(
                                                feature,
                                                ignored ->
                                                        new FeatureStats()
                                        );

                                featureStats.accept(
                                        value
                                );
                            }
                    );
        }

        return stats;
    }

    /**
     * Parse le JSON enregistré pour la prédiction.
     */
    private Map<String, Object> parseInputJson(
            String json
    ) {
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

        } catch (Exception e) {
            log.warn(
                    "[DATA_DRIFT] JSON de prédiction invalide : {}",
                    e.getMessage()
            );

            return Collections.emptyMap();
        }
    }

    /**
     * Supporte :
     *
     * {
     *   "amount": 10,
     *   ...
     * }
     *
     * ainsi que :
     *
     * {
     *   "rows": [
     *      { ... }
     *   ]
     * }
     */
    private Map<String, Object> flattenInput(
            Map<String, Object> input
    ) {
        Map<String, Object> flattened =
                new LinkedHashMap<>();

        if (input == null
                || input.isEmpty()) {

            return flattened;
        }

        Object rows =
                input.get("rows");

        if (rows instanceof List<?> list
                && !list.isEmpty()
                && list.get(0)
                instanceof Map<?, ?> firstRow) {

            for (Map.Entry<?, ?> entry :
                    firstRow.entrySet()) {

                if (entry.getKey() != null) {
                    flattened.put(
                            String.valueOf(
                                    entry.getKey()
                            ),
                            entry.getValue()
                    );
                }
            }

            return flattened;
        }

        for (Map.Entry<String, Object> entry :
                input.entrySet()) {

            if (entry.getValue() == null) {
                continue;
            }

            /*
             * Métadonnées techniques ignorées pour le drift.
             */
            if ("model_name".equalsIgnoreCase(
                    entry.getKey()
            )
                    || "model_alias".equalsIgnoreCase(
                    entry.getKey()
            )
                    || "modelName".equalsIgnoreCase(
                    entry.getKey()
            )
                    || "modelAlias".equalsIgnoreCase(
                    entry.getKey()
            )) {

                continue;
            }

            flattened.put(
                    entry.getKey(),
                    entry.getValue()
            );
        }

        return flattened;
    }

    /**
     * Conservé pour compatibilité.
     */
    private void copyScores(
            RetrainingDecisionResult source,
            RetrainingDecisionResult target
    ) {
        if (source == null || target == null) {
            return;
        }

        target.setPredictionErrorRatePercent(
                source.getPredictionErrorRatePercent()
        );

        target.setDataDriftScorePercent(
                source.getDataDriftScorePercent()
        );

        target.setRecentPredictionCount(
                source.getRecentPredictionCount()
        );

        target.setRecentFailedRuns(
                source.getRecentFailedRuns()
        );
    }

    private double calculatePercent(
            long value,
            long total
    ) {
        if (total <= 0) {
            return 0.0;
        }

        return ((double) value / total)
                * 100.0;
    }

    private double toDouble(
            LocalDateTime value
    ) {
        if (value == null) {
            return 0.0;
        }

        return (double) value
                .atZone(
                        java.time.ZoneOffset.UTC
                )
                .toInstant()
                .toEpochMilli();
    }

    private long safeCount(
            Long value
    ) {
        return value != null
                ? value
                : 0L;
    }

    private long safeCount(
            Double value
    ) {
        return value != null
                ? Math.round(value)
                : 0L;
    }

    private int safeInt(
            Integer value,
            int fallback
    ) {
        return value != null
                && value > 0
                ? value
                : fallback;
    }

    private long safeLong(
            Integer value,
            long fallback
    ) {
        return value != null
                && value > 0
                ? value
                : fallback;
    }

    private double safeDouble(
            Double value,
            double fallback
    ) {
        return value != null
                && value > 0
                ? value
                : fallback;
    }

    private double roundTwo(
            double value
    ) {
        if (Double.isNaN(value)
                || Double.isInfinite(value)) {

            return 0.0;
        }

        return Math.round(
                value * 100.0
        )
                / 100.0;
    }

    private String safeMessage(
            String message
    ) {
        if (message == null
                || message.isBlank()) {

            return "Aucune raison fournie";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }

    /**
     * Représente les deux fenêtres utilisées pour le drift.
     */
    private static final class DriftWindows {

        private final Long productionExperimentId;
        private final List<PredictionLog> baseline;
        private final List<PredictionLog> recent;
        private final int totalPredictions;

        private DriftWindows(
                Long productionExperimentId,
                List<PredictionLog> baseline,
                List<PredictionLog> recent,
                int totalPredictions
        ) {
            this.productionExperimentId =
                    productionExperimentId;

            this.baseline =
                    baseline != null
                            ? baseline
                            : Collections.emptyList();

            this.recent =
                    recent != null
                            ? recent
                            : Collections.emptyList();

            this.totalPredictions =
                    Math.max(
                            0,
                            totalPredictions
                    );
        }

        private Long getProductionExperimentId() {
            return productionExperimentId;
        }

        private List<PredictionLog> getBaseline() {
            return baseline;
        }

        private List<PredictionLog> getRecent() {
            return recent;
        }

        private int getTotalPredictions() {
            return totalPredictions;
        }

        private boolean isReady(
                long minPredictions
        ) {
            return baseline.size()
                    >= minPredictions
                    && recent.size()
                    >= minPredictions;
        }
    }

    /**
     * Statistiques d'une feature.
     */
    private static class FeatureStats {

        private long numericCount;
        private double numericSum;

        private final Set<String> categories =
                new HashSet<>();

        void accept(
                Object value
        ) {
            if (value == null) {
                categories.add(
                        "__NULL__"
                );
                return;
            }

            if (value instanceof Number number) {
                numericCount++;
                numericSum +=
                        number.doubleValue();
                return;
            }

            String text =
                    String.valueOf(value)
                            .trim();

            if (text.isBlank()) {
                categories.add(
                        "__BLANK__"
                );
                return;
            }

            try {
                double numeric =
                        Double.parseDouble(
                                text
                        );

                numericCount++;
                numericSum += numeric;

            } catch (NumberFormatException ignored) {
                categories.add(
                        text.toLowerCase(
                                Locale.ROOT
                        )
                );
            }
        }
    }
}