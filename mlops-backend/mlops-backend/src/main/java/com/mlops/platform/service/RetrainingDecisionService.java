package com.mlops.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.PredictionLog;
import com.mlops.platform.entity.RetrainingConfig;
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

            if (failedRuns > 0) {
                RetrainingDecisionResult run = RetrainingDecisionResult.run(
                        RetrainingTriggerType.FAILED_RUNS,
                        "Monitoring : " + failedRuns + " run(s) échoué(s) sur les " + lookbackHours + " dernières heures."
                );
                run.setRecentFailedRuns(failedRuns);
                return run;
            }
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

    private long safeCount(Long value) {
        return value != null ? value : 0L;
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
