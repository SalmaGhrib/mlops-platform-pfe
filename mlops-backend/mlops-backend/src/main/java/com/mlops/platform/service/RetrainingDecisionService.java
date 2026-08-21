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
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.PredictionLogRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RetrainingDecisionService {

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
     * la sévérité et la classification sécurité. C'est le point d'entrée
     * unique utilisé par AutoRetrainingService et le monitoring.
     *
     * @param config       configuration à évaluer
     * @param now          horodatage UTC courant
     * @param sourceReady  false si aucune Production valide n'a été résolue
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
            blocked.setSecurityLevel(RetrainingSecurityLevel.SECURITY_ALERT_ONLY);
            blocked.addReasonCode("NO_VALID_SOURCE");
            blocked.addReasonCode("DEFERRED");
            return blocked;
        }

        boolean scheduleDue = config.isScheduleDue(now);

        RetrainingDecision decision = switch (config.getStrategy()) {
            case SCHEDULED_ONLY -> buildScheduledOnly(config, scheduleDue);
            case SCHEDULED_WITH_MONITORING ->
                    buildScheduledWithMonitoring(config, scheduleDue, now);
            case SMART_MONITORING_ONLY -> buildSmartMonitoringOnly(config, now);
            case FULL_SMART -> buildFullSmart(config, scheduleDue, now);
        };

        return applySecurityClassification(decision);
    }

    /**
     * Règle de sécurité : on ne réentraîne JAMAIS sur des données suspectes.
     *
     * Un signal extrême (taux d'erreur ou drift >= 2x le seuil) rend les
     * données non fiables : le réentraînement est bloqué et transformé en
     * alerte. Un signal normal est classé RETRAIN_RECOMMENDED.
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

        List<String> reasonCodes = decision.getReasonCodes();
        boolean extremeErrorRate = reasonCodes.contains("ERROR_RATE_EXTREME");
        boolean extremeDrift = reasonCodes.contains("DRIFT_EXTREME");

        if (extremeErrorRate || extremeDrift) {
            boolean blockModel = reasonCodes.contains("ERROR_RATE_BLOCK")
                    || reasonCodes.contains("DRIFT_BLOCK");

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
            decision.setSeverity(RetrainingSeverity.CRITICAL);
            decision.addReasonCode("SUSPECT_DATA");
            decision.addReasonCode("BLOCKED_FOR_SECURITY");
            decision.setReason(
                    "[SECURITE] Donnees suspectes detectees : le retraining "
                            + "est bloque pour eviter de corrompre le modele. "
                            + safeMessage(decision.getReason())
            );
            return decision;
        }

        if (decision.isShouldRetrain()
                && decision.getSecurityLevel() == RetrainingSecurityLevel.NONE) {
            decision.setSecurityLevel(RetrainingSecurityLevel.RETRAIN_RECOMMENDED);
        }

        return decision;
    }

    private RetrainingDecision buildScheduledOnly(
            RetrainingConfig config,
            boolean scheduleDue
    ) {
        if (!scheduleDue) {
            return RetrainingDecision.skip(
                    RetrainingSeverity.NONE,
                    "Échéance planifiée non atteinte."
            );
        }

        RetrainingDecision run = RetrainingDecision.run(
                RetrainingTriggerType.SCHEDULED,
                RetrainingSeverity.MEDIUM,
                "Stratégie SCHEDULED_ONLY : échéance planifiée atteinte."
        );
        run.addReasonCode("SCHEDULE_DUE");
        run.addThreshold("scheduleWindow", toDouble(config.getNextRetrainingAt()));
        return run;
    }

    private RetrainingDecision buildScheduledWithMonitoring(
            RetrainingConfig config,
            boolean scheduleDue,
            LocalDateTime now
    ) {
        if (!scheduleDue) {
            return RetrainingDecision.skip(
                    RetrainingSeverity.NONE,
                    "Échéance planifiée non atteinte."
            );
        }

        RetrainingDecision monitoring = evaluateMonitoring(config, now);

        if (monitoring.isShouldRetrain()) {
            return monitoring;
        }

        long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);
        long recentCount = safeCount(monitoring.getObservedMetrics().get("recentPredictionCount"));

        if (recentCount < minPredictions) {
            RetrainingDecision scheduled = RetrainingDecision.run(
                    RetrainingTriggerType.SCHEDULED,
                    RetrainingSeverity.MEDIUM,
                    "Pas assez de prédictions pour conclure sur le drift. "
                            + "Le réentraînement planifié est conservé. "
                            + safeMessage(monitoring.getReason())
            );
            scheduled.addReasonCode("SCHEDULE_DUE");
            scheduled.addReasonCode("INSUFFICIENT_DATA");
            scheduled.addThreshold("scheduleWindow", toDouble(config.getNextRetrainingAt()));
            copyObservedMetrics(monitoring, scheduled);
            return scheduled;
        }

        RetrainingDecision skipped = RetrainingDecision.skip(
                RetrainingSeverity.LOW,
                "Mode SCHEDULED_WITH_MONITORING : le monitoring n'autorise pas "
                        + "le réentraînement. " + safeMessage(monitoring.getReason())
        );
        skipped.addReasonCode("MONITORING_OK");
        copyObservedMetrics(monitoring, skipped);
        return skipped;
    }

    private RetrainingDecision buildSmartMonitoringOnly(
            RetrainingConfig config,
            LocalDateTime now
    ) {
        return evaluateMonitoring(config, now);
    }

    private RetrainingDecision buildFullSmart(
            RetrainingConfig config,
            boolean scheduleDue,
            LocalDateTime now
    ) {
        RetrainingDecision monitoring = evaluateMonitoring(config, now);

        if (monitoring.isShouldRetrain()) {
            return monitoring;
        }

        if (scheduleDue) {
            RetrainingDecision scheduled = RetrainingDecision.run(
                    RetrainingTriggerType.SCHEDULED,
                    RetrainingSeverity.MEDIUM,
                    "Mode FULL_SMART : échéance planifiée atteinte. "
                            + safeMessage(monitoring.getReason())
            );
            scheduled.addReasonCode("SCHEDULE_DUE");
            scheduled.addThreshold("scheduleWindow", toDouble(config.getNextRetrainingAt()));
            copyObservedMetrics(monitoring, scheduled);
            return scheduled;
        }

        RetrainingDecision skipped = RetrainingDecision.skip(
                RetrainingSeverity.NONE,
                "Aucun signal de monitoring et échéance non atteinte."
        );
        skipped.addReasonCode("MONITORING_OK");
        copyObservedMetrics(monitoring, skipped);
        return skipped;
    }

    /**
     * Évalue les signaux de monitoring (runs échoués, erreurs de prédiction,
     * data drift) et classe la sévérité + le niveau sécurité.
     */
    private RetrainingDecision evaluateMonitoring(
            RetrainingConfig config,
            LocalDateTime now
    ) {
        Long projectId = config.getProjectId();
        int lookbackHours = safeInt(config.getMonitoringLookbackHours(), 24);
        LocalDateTime recentStart = now.minusHours(lookbackHours);
        LocalDateTime baselineStart = now.minusHours(lookbackHours * 2L);

        RetrainingDecision result = RetrainingDecision.skip(
                RetrainingSeverity.NONE,
                "Aucun signal de monitoring ne justifie un réentraînement."
        );

        boolean anySignal = false;

        if (config.isFailureMonitoringEnabled()) {
            long failedRuns = safeCount(
                    mlExperimentRepository.countFailedRunsForProjectSince(projectId, lookbackHours)
            );
            anySignal = anySignal || failedRuns > 0;
            result.addObservedMetric("recentFailedRuns", (double) failedRuns);
            result.addThreshold("failedRunsThreshold", 1.0);

            /*
             * Les runs échoués sont un signal TECHNIQUE (entraînement,
             * Job Docker, MLflow, déploiement). On alerte mais on ne
             * déclenche JAMAIS de réentraînement : ré-entraîner sur des
             * données ou un pipeline corrompus aggraverait le problème.
             */
            if (failedRuns > 0) {
                result.addReasonCode("FAILED_RUNS_TECHNICAL");
            }
        }

        long recentPredictionCount = safeCount(
                predictionLogRepository.countByProjectIdAndCreatedAtAfter(projectId, recentStart)
        );
        result.addObservedMetric("recentPredictionCount", (double) recentPredictionCount);

        if (config.isPerformanceMonitoringEnabled()) {
            long predictionErrors = safeCount(
                    predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(projectId, recentStart)
            );
            double errorRate = calculatePercent(predictionErrors, recentPredictionCount);
            double maxErrorRate = safeDouble(config.getMaxPredictionErrorRatePercent(), 20.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);

            result.addObservedMetric("predictionErrorRatePercent", roundTwo(errorRate));
            result.addThreshold("maxPredictionErrorRatePercent", maxErrorRate);
            anySignal = anySignal || recentPredictionCount >= minPredictions;

            if (recentPredictionCount >= minPredictions && errorRate >= maxErrorRate) {
                RetrainingDecision run = RetrainingDecision.run(
                        RetrainingTriggerType.PREDICTION_ERRORS,
                        errorRate >= maxErrorRate * 2
                                ? RetrainingSeverity.CRITICAL
                                : RetrainingSeverity.HIGH,
                        "Monitoring : taux d'erreur prédiction élevé. "
                                + "Erreurs=" + predictionErrors
                                + ", prédictions=" + recentPredictionCount
                                + ", taux=" + roundTwo(errorRate) + "%"
                                + ", seuil=" + maxErrorRate + "%."
                );
                run.addReasonCode("PREDICTION_ERRORS");
                run.addObservedMetric("recentPredictionCount", (double) recentPredictionCount);
                run.addObservedMetric("predictionErrorRatePercent", roundTwo(errorRate));

                if (errorRate >= maxErrorRate * 3) {
                    run.setSecurityLevel(RetrainingSecurityLevel.BLOCK_MODEL);
                    run.addReasonCode("ERROR_RATE_BLOCK");
                    run.addReasonCode("ERROR_RATE_EXTREME");
                } else if (errorRate >= maxErrorRate * 2) {
                    run.setSecurityLevel(RetrainingSecurityLevel.SECURITY_ALERT_ONLY);
                    run.addReasonCode("ERROR_RATE_EXTREME");
                }

                return run;
            }
        }

        if (config.isPerformanceMonitoringEnabled()) {
            double performanceDrop = evaluatePerformanceDrop(projectId);
            double dropThreshold = safeDouble(config.getPerformanceDropThresholdPercent(), 5.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);

            result.addObservedMetric("performanceDropPercent", roundTwo(performanceDrop));
            result.addThreshold("performanceDropThresholdPercent", dropThreshold);

            if (performanceDrop >= dropThreshold && recentPredictionCount >= minPredictions) {
                RetrainingDecision run = RetrainingDecision.run(
                        RetrainingTriggerType.PERFORMANCE_DROP,
                        performanceDrop >= dropThreshold * 2
                                ? RetrainingSeverity.CRITICAL
                                : RetrainingSeverity.HIGH,
                        "Monitoring : baisse de performance du modèle en production. "
                                + "Score global=" + roundTwo(currentProductionScore(projectId))
                                + ", baisse=" + roundTwo(performanceDrop) + "%"
                                + ", seuil=" + dropThreshold + "%."
                );
                run.addReasonCode("PERFORMANCE_DROP");
                run.addObservedMetric("recentPredictionCount", (double) recentPredictionCount);
                run.addObservedMetric("performanceDropPercent", roundTwo(performanceDrop));
                return run;
            }
        }

        if (config.isDataDriftMonitoringEnabled()) {
            List<PredictionLog> baseline = predictionLogRepository
                    .findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(projectId, baselineStart, recentStart);
            List<PredictionLog> recent = predictionLogRepository
                    .findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(projectId, recentStart, now);

            double driftScore = calculateSimpleDataDriftScore(baseline, recent);
            double driftThreshold = safeDouble(config.getDataDriftThresholdPercent(), 25.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);

            result.addObservedMetric("dataDriftScorePercent", roundTwo(driftScore));
            result.addThreshold("dataDriftThresholdPercent", driftThreshold);

            if (baseline.size() >= minPredictions && recent.size() >= minPredictions && driftScore >= driftThreshold) {
                RetrainingDecision run = RetrainingDecision.run(
                        RetrainingTriggerType.DATA_DRIFT,
                        driftScore >= driftThreshold * 2
                                ? RetrainingSeverity.CRITICAL
                                : RetrainingSeverity.HIGH,
                        "Data drift détecté sur les inputs de prédiction. "
                                + "Score=" + roundTwo(driftScore) + "%"
                                + ", seuil=" + driftThreshold + "%"
                                + ", baseline=" + baseline.size()
                                + ", récent=" + recent.size() + "."
                );
                run.addReasonCode("DATA_DRIFT");
                run.addObservedMetric("recentPredictionCount", (double) recent.size());
                run.addObservedMetric("dataDriftScorePercent", roundTwo(driftScore));

                if (driftScore >= driftThreshold * 3) {
                    run.setSecurityLevel(RetrainingSecurityLevel.BLOCK_MODEL);
                    run.addReasonCode("DRIFT_BLOCK");
                    run.addReasonCode("DRIFT_EXTREME");
                } else if (driftScore >= driftThreshold * 2) {
                    run.setSecurityLevel(RetrainingSecurityLevel.SECURITY_ALERT_ONLY);
                    run.addReasonCode("DRIFT_EXTREME");
                }

                return run;
            }
        }

        String reason = "Monitoring OK : "
                + "prédictions récentes=" + recentPredictionCount
                + ", taux erreur=" + result.getObservedMetrics()
                        .getOrDefault("predictionErrorRatePercent", 0.0) + "%"
                + ", drift=" + result.getObservedMetrics()
                        .getOrDefault("dataDriftScorePercent", 0.0) + "%"
                + ", runs échoués=" + result.getObservedMetrics()
                        .getOrDefault("recentFailedRuns", 0.0) + ".";

        result.setReason(reason);
        result.addReasonCode("MONITORING_OK");
        if (!anySignal) {
            result.setSeverity(RetrainingSeverity.NONE);
        } else {
            result.setSeverity(RetrainingSeverity.LOW);
        }
        return result;
    }

    private void copyObservedMetrics(
            RetrainingDecision source,
            RetrainingDecision target
    ) {
        if (source == null || target == null) {
            return;
        }
        target.getObservedMetrics().putAll(source.getObservedMetrics());
    }

    public RetrainingDecisionResult evaluateBeforeRetraining(RetrainingConfig config) {
        if (config == null || config.getProjectId() == null) {
            return RetrainingDecisionResult.skip("Configuration de réentraînement invalide.");
        }

        config.normalizeMonitoringSettings();

        if (config.isManualTriggerRequested()) {
            return RetrainingDecisionResult.run(
                    RetrainingTriggerType.MANUAL,
                    "Réentraînement demandé manuellement par l'utilisateur."
            );
        }

        RetrainingStrategy strategy = config.getStrategy() != null
                ? config.getStrategy()
                : RetrainingStrategy.SCHEDULED_WITH_MONITORING;

        if (strategy == RetrainingStrategy.SCHEDULED_ONLY) {
            return RetrainingDecisionResult.run(
                    RetrainingTriggerType.SCHEDULED,
                    "Stratégie SCHEDULED_ONLY : réentraînement lancé à chaque échéance."
            );
        }

        RetrainingDecisionResult monitoringDecision = evaluateMonitoringSignals(config);

        if (monitoringDecision.isShouldRetrain()) {
            return monitoringDecision;
        }

        if (strategy == RetrainingStrategy.SCHEDULED_WITH_MONITORING) {
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);
            if (monitoringDecision.getRecentPredictionCount() < minPredictions) {
                RetrainingDecisionResult result = RetrainingDecisionResult.run(
                        RetrainingTriggerType.SCHEDULED,
                        "Pas assez de prédictions pour conclure sur le drift. Le réentraînement planifié est conservé. "
                                + monitoringDecision.getReason()
                );
                copyScores(monitoringDecision, result);
                return result;
            }
        }

        return monitoringDecision;
    }

    public RetrainingDecisionResult evaluateMonitoringSignals(RetrainingConfig config) {
        if (config == null || config.getProjectId() == null) {
            return RetrainingDecisionResult.skip("Configuration de monitoring invalide.");
        }

        Long projectId = config.getProjectId();
        int lookbackHours = safeInt(config.getMonitoringLookbackHours(), 24);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        LocalDateTime recentStart = now.minusHours(lookbackHours);
        LocalDateTime baselineStart = now.minusHours(lookbackHours * 2L);

        RetrainingDecisionResult result = RetrainingDecisionResult.skip("Aucun signal de monitoring ne justifie un réentraînement.");

        if (config.isFailureMonitoringEnabled()) {
            long failedRuns = safeCount(
                    mlExperimentRepository.countFailedRunsForProjectSince(projectId, lookbackHours)
            );
            result.setRecentFailedRuns(failedRuns);
        }

        long recentPredictionCount = safeCount(
                predictionLogRepository.countByProjectIdAndCreatedAtAfter(projectId, recentStart)
        );
        result.setRecentPredictionCount(recentPredictionCount);

        if (config.isPerformanceMonitoringEnabled()) {
            long predictionErrors = safeCount(
                    predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(projectId, recentStart)
            );
            double errorRate = calculatePercent(predictionErrors, recentPredictionCount);
            result.setPredictionErrorRatePercent(roundTwo(errorRate));

            double maxErrorRate = safeDouble(config.getMaxPredictionErrorRatePercent(), 20.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);

            if (recentPredictionCount >= minPredictions && errorRate >= maxErrorRate) {
                RetrainingDecisionResult run = RetrainingDecisionResult.run(
                        RetrainingTriggerType.PREDICTION_ERRORS,
                        "Monitoring : taux d'erreur prédiction élevé. "
                                + "Erreurs=" + predictionErrors
                                + ", prédictions=" + recentPredictionCount
                                + ", taux=" + roundTwo(errorRate) + "%"
                                + ", seuil=" + maxErrorRate + "%."
                );
                run.setRecentPredictionCount(recentPredictionCount);
                run.setPredictionErrorRatePercent(roundTwo(errorRate));
                return run;
            }
        }

        if (config.isPerformanceMonitoringEnabled()) {
            double performanceDrop = evaluatePerformanceDrop(projectId);
            double dropThreshold = safeDouble(config.getPerformanceDropThresholdPercent(), 5.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);
            result.setPerformanceDropPercent(roundTwo(performanceDrop));

            if (performanceDrop >= dropThreshold && recentPredictionCount >= minPredictions) {
                RetrainingDecisionResult run = RetrainingDecisionResult.run(
                        RetrainingTriggerType.PERFORMANCE_DROP,
                        "Monitoring : baisse de performance du modèle en production. "
                                + "Score global=" + roundTwo(currentProductionScore(projectId))
                                + ", baisse=" + roundTwo(performanceDrop) + "%"
                                + ", seuil=" + dropThreshold + "%."
                );
                run.setRecentPredictionCount(recentPredictionCount);
                run.setPerformanceDropPercent(roundTwo(performanceDrop));
                return run;
            }
        }

        if (config.isDataDriftMonitoringEnabled()) {
            List<PredictionLog> baseline = predictionLogRepository
                    .findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(projectId, baselineStart, recentStart);
            List<PredictionLog> recent = predictionLogRepository
                    .findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(projectId, recentStart, now);

            double driftScore = calculateSimpleDataDriftScore(baseline, recent);
            result.setDataDriftScorePercent(roundTwo(driftScore));

            double driftThreshold = safeDouble(config.getDataDriftThresholdPercent(), 25.0);
            long minPredictions = safeLong(config.getMinPredictionsForMonitoring(), 30);

            if (baseline.size() >= minPredictions && recent.size() >= minPredictions && driftScore >= driftThreshold) {
                RetrainingDecisionResult run = RetrainingDecisionResult.run(
                        RetrainingTriggerType.DATA_DRIFT,
                        "Data drift détecté sur les inputs de prédiction. "
                                + "Score=" + roundTwo(driftScore) + "%"
                                + ", seuil=" + driftThreshold + "%"
                                + ", baseline=" + baseline.size()
                                + ", récent=" + recent.size() + "."
                );
                run.setRecentPredictionCount(recent.size());
                run.setDataDriftScorePercent(roundTwo(driftScore));
                return run;
            }
        }

        String reason = "Monitoring OK : "
                + "prédictions récentes=" + recentPredictionCount
                + ", taux erreur=" + result.getPredictionErrorRatePercent() + "%"
                + ", drift=" + result.getDataDriftScorePercent() + "%"
                + ", runs échoués=" + result.getRecentFailedRuns() + ".";

        result.setReason(reason);
        return result;
    }

    /**
     * Score global du modèle actuellement en production pour un projet.
     */
    private double currentProductionScore(Long projectId) {
        MlExperiment production = mlExperimentRepository
                .findCurrentProductionExperimentForProject(projectId)
                .orElse(null);

        if (production == null || production.getGlobalScore() == null) {
            return 0.0;
        }

        return production.getGlobalScore();
    }

    /**
     * Baisse de performance du modèle en production, en pourcentage.
     *
     * Compare le score global de la Production courante au meilleur score
     * historique du même registeredModelName. Une baisse positive indique
     * que le modèle en production a régressé par rapport à son meilleur état.
     *
     * L'absence de Production (ou de score) est traitée comme une baisse
     * nulle : on ne déclenche jamais un réentraînement sur une information
     * technique incomplète.
     */
    private double evaluatePerformanceDrop(Long projectId) {
        if (projectId == null) {
            return 0.0;
        }

        MlExperiment production = mlExperimentRepository
                .findCurrentProductionExperimentForProject(projectId)
                .orElse(null);

        if (production == null || production.getGlobalScore() == null) {
            return 0.0;
        }

        String registeredModelName = production.getRegisteredModelName();
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return 0.0;
        }

        List<MlExperiment> candidates = mlExperimentRepository
                .findEligibleProductionCandidates(registeredModelName);

        double bestScore = production.getGlobalScore();
        for (MlExperiment candidate : candidates) {
            if (candidate == null || candidate.getGlobalScore() == null) {
                continue;
            }
            bestScore = Math.max(bestScore, candidate.getGlobalScore());
        }

        if (bestScore <= 0.0) {
            return 0.0;
        }

        double drop = ((bestScore - production.getGlobalScore()) / bestScore) * 100.0;
        return Math.max(0.0, roundTwo(drop));
    }

    private double calculateSimpleDataDriftScore(List<PredictionLog> baseline, List<PredictionLog> recent) {
        if (baseline == null || recent == null || baseline.isEmpty() || recent.isEmpty()) {
            return 0.0;
        }

        Map<String, FeatureStats> baselineStats = buildFeatureStats(baseline);
        Map<String, FeatureStats> recentStats = buildFeatureStats(recent);

        if (baselineStats.isEmpty() || recentStats.isEmpty()) {
            return 0.0;
        }

        Set<String> features = new LinkedHashSet<>();
        features.addAll(baselineStats.keySet());
        features.addAll(recentStats.keySet());

        double total = 0.0;
        int compared = 0;

        for (String feature : features) {
            FeatureStats b = baselineStats.get(feature);
            FeatureStats r = recentStats.get(feature);

            if (b == null || r == null) {
                total += 100.0;
                compared++;
                continue;
            }

            if (b.numericCount > 0 && r.numericCount > 0) {
                double bMean = b.numericSum / Math.max(1, b.numericCount);
                double rMean = r.numericSum / Math.max(1, r.numericCount);
                double denominator = Math.max(1.0, Math.abs(bMean));
                double score = Math.min(100.0, Math.abs(rMean - bMean) / denominator * 100.0);
                total += score;
                compared++;
            } else if (!b.categories.isEmpty() || !r.categories.isEmpty()) {
                Set<String> union = new HashSet<>();
                union.addAll(b.categories);
                union.addAll(r.categories);

                Set<String> intersection = new HashSet<>(b.categories);
                intersection.retainAll(r.categories);

                double similarity = union.isEmpty() ? 1.0 : ((double) intersection.size() / union.size());
                total += (1.0 - similarity) * 100.0;
                compared++;
            }
        }

        if (compared == 0) {
            return 0.0;
        }

        return total / compared;
    }

    private Map<String, FeatureStats> buildFeatureStats(List<PredictionLog> logs) {
        Map<String, FeatureStats> stats = new LinkedHashMap<>();

        for (PredictionLog log : logs) {
            if (log == null || log.getInputJson() == null || log.getInputJson().isBlank()) {
                continue;
            }

            Map<String, Object> input = parseInputJson(log.getInputJson());
            flattenInput(input).forEach((feature, value) -> {
                FeatureStats featureStats = stats.computeIfAbsent(feature, ignored -> new FeatureStats());
                featureStats.accept(value);
            });
        }

        return stats;
    }

    private Map<String, Object> parseInputJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> flattenInput(Map<String, Object> input) {
        Map<String, Object> flattened = new LinkedHashMap<>();

        if (input == null || input.isEmpty()) {
            return flattened;
        }

        Object rows = input.get("rows");
        if (rows instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> firstRow) {
            for (Map.Entry<?, ?> entry : firstRow.entrySet()) {
                if (entry.getKey() != null) {
                    flattened.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return flattened;
        }

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if ("model_name".equalsIgnoreCase(entry.getKey()) || "model_alias".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            flattened.put(entry.getKey(), entry.getValue());
        }

        return flattened;
    }

    private void copyScores(RetrainingDecisionResult source, RetrainingDecisionResult target) {
        target.setPredictionErrorRatePercent(source.getPredictionErrorRatePercent());
        target.setDataDriftScorePercent(source.getDataDriftScorePercent());
        target.setRecentPredictionCount(source.getRecentPredictionCount());
        target.setRecentFailedRuns(source.getRecentFailedRuns());
    }

    private double calculatePercent(long value, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return ((double) value / total) * 100.0;
    }

    private double toDouble(LocalDateTime value) {
        if (value == null) {
            return 0.0;
        }
        return (double) value.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private long safeCount(Long value) {
        return value != null ? value : 0L;
    }

    private long safeCount(Double value) {
        return value != null ? Math.round(value) : 0L;
    }

    private int safeInt(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private long safeLong(Integer value, long fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private double safeDouble(Double value, double fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private double roundTwo(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Aucune raison fournie";
        }
        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }

    private static class FeatureStats {
        private long numericCount;
        private double numericSum;
        private final Set<String> categories = new HashSet<>();

        void accept(Object value) {
            if (value == null) {
                categories.add("__NULL__");
                return;
            }

            if (value instanceof Number number) {
                numericCount++;
                numericSum += number.doubleValue();
                return;
            }

            String text = String.valueOf(value).trim();
            if (text.isBlank()) {
                categories.add("__BLANK__");
                return;
            }

            try {
                double numeric = Double.parseDouble(text);
                numericCount++;
                numericSum += numeric;
            } catch (NumberFormatException ignored) {
                categories.add(text.toLowerCase(Locale.ROOT));
            }
        }
    }
}
