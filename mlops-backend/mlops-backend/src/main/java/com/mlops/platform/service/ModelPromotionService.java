package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelPromotionService {

    private final MlExperimentRepository mlExperimentRepository;
    private final MlExperimentMetricRepository mlExperimentMetricRepository;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final RetrainingSourcePersistenceService
            retrainingSourcePersistenceService;

    public ModelPromotionService(
            MlExperimentRepository mlExperimentRepository,
            MlExperimentMetricRepository mlExperimentMetricRepository,
            MlflowTrackingClientService mlflowTrackingClientService,
            RetrainingSourcePersistenceService
                    retrainingSourcePersistenceService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlExperimentMetricRepository =
                mlExperimentMetricRepository;
        this.mlflowTrackingClientService =
                mlflowTrackingClientService;
        this.retrainingSourcePersistenceService =
                retrainingSourcePersistenceService;
    }

    /**
     * Recalcule la meilleure expérience d'un projet selon l'ordre de priorité
     * des métriques, puis met une seule expérience en production.
     *
     * Règles :
     * - primaryMetric1 = priorité 1 ;
     * - primaryMetric2 = priorité 2 ;
     * - primaryMetric3 = priorité 3 ;
     * - modelVersion reste la version MLflow réelle du run ;
     * - productionVersion désigne la version actuellement promue ;
     * - une seule expérience exacte peut avoir production=true.
     */
    public synchronized MlExperiment recalculateProductionForProject(
            MlProject project,
            String registeredModelName
    ) {
        if (project == null) {
            throw new IllegalArgumentException("Project is required");
        }

        if (registeredModelName == null
                || registeredModelName.isBlank()) {
            throw new IllegalArgumentException(
                    "registeredModelName is required"
            );
        }

        String cleanRegisteredModelName =
                registeredModelName.trim();

        List<String> selectedMetrics =
                getSelectedProjectMetrics(project);

        if (selectedMetrics.isEmpty()) {
            throw new IllegalStateException(
                    "Project must have at least one priority metric"
            );
        }

        List<MlExperiment> experiments =
                mlExperimentRepository
                        .findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                                project,
                                cleanRegisteredModelName
                        );

        if (experiments == null || experiments.isEmpty()) {
            throw new IllegalStateException(
                    "No experiments found for registeredModelName="
                            + cleanRegisteredModelName
            );
        }

        MlExperiment bestExperiment = null;

        /*
         * Étape 1 :
         * recalcul des scores pour chaque expérience éligible.
         */
        for (MlExperiment experiment : experiments) {
            if (!isEligibleExperiment(experiment)) {
                continue;
            }

            Map<String, Double> metricValues =
                    loadMetricValues(experiment);

            if (metricValues.isEmpty()) {
                continue;
            }

            updateExperimentMainMetricAndScore(
                    project,
                    experiment,
                    selectedMetrics,
                    metricValues
            );

            MlExperiment savedExperiment =
                    mlExperimentRepository.saveAndFlush(experiment);

            updateMetricsGlobalScore(
                    savedExperiment,
                    savedExperiment.getGlobalScore()
            );

            if (compareExperimentsByPriority(
                    project,
                    savedExperiment,
                    bestExperiment,
                    selectedMetrics
            ) > 0) {
                bestExperiment = savedExperiment;
            }
        }

        if (bestExperiment == null) {
            throw new IllegalStateException(
                    "No eligible completed experiment found "
                            + "for production promotion"
            );
        }

        String bestVersion = bestExperiment.getModelVersion();

        if (bestVersion == null || bestVersion.isBlank()) {
            throw new IllegalStateException(
                    "Best experiment has no model version"
            );
        }

        /*
         * MLflow reçoit l'alias production sur la version gagnante.
         *
         * Remarque :
         * cette méthode effectue un appel externe. Pour une architecture encore
         * plus stricte, elle pourra ensuite être séparée dans un orchestrateur
         * non transactionnel. Le timeout limite néanmoins la transaction.
         */
        mlflowTrackingClientService.setProductionAlias(
                cleanRegisteredModelName,
                bestVersion
        );

        /*
         * Étape 2 :
         * une seule ligne MySQL reçoit production=true.
         */
        Long bestExperimentId = bestExperiment.getId();

        mlExperimentRepository.clearProductionStateByRegisteredModelName(
                cleanRegisteredModelName
        );

        for (MlExperiment experiment : experiments) {
            if (experiment == null || experiment.getId() == null) {
                continue;
            }

            boolean isProduction = experiment.getId().equals(bestExperimentId);

            mlExperimentMetricRepository.updateProductionStateByExperimentId(
                    experiment.getId(),
                    isProduction,
                    isProduction ? bestVersion : null
            );
        }

        mlExperimentRepository.markExperimentAsProduction(
                bestExperimentId,
                bestVersion
        );

        MlExperiment finalProduction = mlExperimentRepository
                .findById(bestExperimentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Production experiment disappeared after promotion"
                ));

        /*
         * Important :
         * aucun appel direct au RetrainingConfigRepository.
         * Le service de persistance ouvre une transaction indépendante,
         * courte et dotée d'un mécanisme de retry.
         */
        if (project.getId() != null
                && finalProduction.getId() != null) {

            boolean sourceSynchronized =
                    retrainingSourcePersistenceService
                            .synchronizeSourceExperiment(
                                    project.getId(),
                                    finalProduction.getId()
                            );

            if (sourceSynchronized) {
                System.out.println(
                        "[RETRAINING_SOURCE] Updated after Production "
                                + "promotion. projectId="
                                + project.getId()
                                + ", sourceExperimentId="
                                + finalProduction.getId()
                                + ", modelVersion="
                                + finalProduction.getModelVersion()
                );
            } else {
                System.err.println(
                        "[RETRAINING_SOURCE] Synchronization postponed "
                                + "after Production promotion. projectId="
                                + project.getId()
                                + ", sourceExperimentId="
                                + finalProduction.getId()
                );
            }
        }

        return finalProduction;
    }

    private boolean isEligibleExperiment(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (experiment.getModelVersion() == null
                || experiment.getModelVersion().isBlank()
                || experiment.getMlflowRunId() == null
                || experiment.getMlflowRunId().isBlank()
                || experiment.getPrimaryMetricValue() == null
                || experiment.getGlobalScore() == null) {
            return false;
        }

        MlflowTrackingClientService.MlflowRunDetails runDetails =
                mlflowTrackingClientService.getRunDetailsFromRunId(
                        experiment.getMlflowRunId(),
                        experiment.getRegisteredModelName()
                );

        /*
         * Même si une version technique existe dans MLflow, elle ne peut pas
         * être comparée ni promue si le run n'est pas réellement réussi.
         */
        return mlflowTrackingClientService
                .isSuccessfulMlflowRun(runDetails);
    }

    private Map<String, Double> loadMetricValues(
            MlExperiment experiment
    ) {
        List<MlExperimentMetric> metrics =
                mlExperimentMetricRepository
                        .findByExperiment(experiment);

        Map<String, Double> values = new HashMap<>();

        if (metrics == null || metrics.isEmpty()) {
            return values;
        }

        for (MlExperimentMetric metric : metrics) {
            if (metric == null
                    || metric.getMetricName() == null
                    || metric.getMetricValue() == null) {
                continue;
            }

            String metricName =
                    cleanMetric(metric.getMetricName());

            if (metricName != null) {
                values.put(
                        metricName,
                        metric.getMetricValue()
                );
            }
        }

        return values;
    }

    private List<String> getSelectedProjectMetrics(
            MlProject project
    ) {
        List<String> metrics = new ArrayList<>();

        if (project == null) {
            return metrics;
        }

        addMetricIfPresent(
                metrics,
                project.getPrimaryMetric1()
        );
        addMetricIfPresent(
                metrics,
                project.getPrimaryMetric2()
        );
        addMetricIfPresent(
                metrics,
                project.getPrimaryMetric3()
        );

        return metrics;
    }

    private void addMetricIfPresent(
            List<String> metrics,
            String metricName
    ) {
        String cleaned = cleanMetric(metricName);

        if (cleaned != null && !metrics.contains(cleaned)) {
            metrics.add(cleaned);
        }
    }

    private void updateExperimentMainMetricAndScore(
            MlProject project,
            MlExperiment experiment,
            List<String> selectedMetrics,
            Map<String, Double> metricValues
    ) {
        if (experiment == null
                || selectedMetrics == null
                || selectedMetrics.isEmpty()) {
            return;
        }

        String primaryMetric = null;
        Double primaryMetricValue = null;
        double[] configuredWeights = {0.50d, 0.30d, 0.20d};
        double weightedSum = 0.0d;
        double appliedWeight = 0.0d;

        for (int index = 0;
             index < selectedMetrics.size() && index < configuredWeights.length;
             index++) {

            String metricName = selectedMetrics.get(index);
            if (metricName == null || metricName.isBlank()) {
                continue;
            }

            Double value = getMetricValueCompatible(metricValues, metricName);
            if (value == null || !Double.isFinite(value)) {
                continue;
            }

            Double normalizedScore = normalizeMetricScore(metricName, value);
            if (normalizedScore == null || !Double.isFinite(normalizedScore)) {
                continue;
            }

            if (primaryMetric == null) {
                primaryMetric = metricName;
                primaryMetricValue = value;
            }

            double weight = configuredWeights[index];
            weightedSum += normalizedScore * weight;
            appliedWeight += weight;
        }

        if (primaryMetric == null || appliedWeight <= 0.0d) {
            String fallbackMetric = project != null
                    ? cleanMetric(project.getPrimaryMetric1())
                    : null;
            experiment.setPrimaryMetric(fallbackMetric);
            experiment.setPrimaryMetricValue(null);
            experiment.setDirection(fallbackMetric != null
                    ? inferDirection(fallbackMetric)
                    : "max");
            experiment.setGlobalScore(null);
            return;
        }

        experiment.setPrimaryMetric(primaryMetric);
        experiment.setPrimaryMetricValue(primaryMetricValue);
        experiment.setDirection(inferDirection(primaryMetric));
        experiment.setGlobalScore(roundDouble(weightedSum / appliedWeight, 6));
    }

    private int compareExperimentsByPriority(
            MlProject project,
            MlExperiment candidate,
            MlExperiment currentBest,
            List<String> selectedMetrics
    ) {
        if (candidate == null) {
            return -1;
        }

        if (currentBest == null) {
            return 1;
        }

        Map<String, Double> candidateMetrics =
                loadMetricValues(candidate);
        Map<String, Double> bestMetrics =
                loadMetricValues(currentBest);

        List<String> priorities =
                selectedMetrics != null
                        && !selectedMetrics.isEmpty()
                        ? selectedMetrics
                        : getSelectedProjectMetrics(project);

        int scoreComparison = compareNullableScores(
                candidate.getGlobalScore(),
                currentBest.getGlobalScore()
        );
        if (scoreComparison != 0) {
            return scoreComparison;
        }

        /* Départage déterministe par ordre de priorité uniquement si égalité. */
        for (String metricName : priorities) {
            if (metricName == null || metricName.isBlank()) {
                continue;
            }

            Double candidateValue = getMetricValueCompatible(candidateMetrics, metricName);
            Double bestValue = getMetricValueCompatible(bestMetrics, metricName);

            if (candidateValue == null && bestValue == null) {
                continue;
            }
            if (candidateValue != null && bestValue == null) {
                return 1;
            }
            if (candidateValue == null) {
                return -1;
            }

            int comparison = "min".equalsIgnoreCase(inferDirection(metricName))
                    ? Double.compare(bestValue, candidateValue)
                    : Double.compare(candidateValue, bestValue);
            if (comparison != 0) {
                return comparison;
            }
        }

        int versionComparison =
                compareModelVersions(
                        candidate.getModelVersion(),
                        currentBest.getModelVersion()
                );

        if (versionComparison != 0) {
            return versionComparison;
        }

        return compareByCreatedAt(
                candidate,
                currentBest
        );
    }

    private int compareNullableScores(
            Double candidateScore,
            Double bestScore
    ) {
        if (candidateScore == null
                && bestScore == null) {
            return 0;
        }

        if (candidateScore != null
                && bestScore == null) {
            return 1;
        }

        if (candidateScore == null) {
            return -1;
        }

        return Double.compare(
                candidateScore,
                bestScore
        );
    }

    private int compareModelVersions(
            String candidateVersion,
            String bestVersion
    ) {
        Integer candidate =
                parseVersion(candidateVersion);
        Integer best =
                parseVersion(bestVersion);

        if (candidate == null && best == null) {
            return 0;
        }

        if (candidate != null && best == null) {
            return 1;
        }

        if (candidate == null) {
            return -1;
        }

        return Integer.compare(candidate, best);
    }

    private Integer parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(version.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int compareByCreatedAt(
            MlExperiment candidate,
            MlExperiment currentBest
    ) {
        LocalDateTime candidateCreatedAt =
                candidate.getCreatedAt();
        LocalDateTime bestCreatedAt =
                currentBest.getCreatedAt();

        if (candidateCreatedAt == null
                && bestCreatedAt == null) {
            return 0;
        }

        if (candidateCreatedAt != null
                && bestCreatedAt == null) {
            return 1;
        }

        if (candidateCreatedAt == null) {
            return -1;
        }

        return candidateCreatedAt
                .compareTo(bestCreatedAt);
    }

    private Double getMetricValueCompatible(
            Map<String, Double> metrics,
            String metricName
    ) {
        if (metrics == null
                || metrics.isEmpty()
                || metricName == null
                || metricName.isBlank()) {
            return null;
        }

        String normalized = cleanMetric(metricName);

        if (normalized == null) {
            return null;
        }

        Double direct = metrics.get(normalized);

        if (direct != null) {
            return direct;
        }

        return switch (normalized) {
            case "f1_score" ->
                    firstNonNull(
                            metrics.get("f1"),
                            metrics.get("f1_weighted")
                    );
            case "precision" ->
                    metrics.get("precision_weighted");
            case "recall" ->
                    metrics.get("recall_weighted");
            case "accuracy" ->
                    metrics.get("val_accuracy");
            case "r2" ->
                    metrics.get("r2_score");
            case "loss" ->
                    metrics.get("val_loss");
            default -> null;
        };
    }

    private Double firstNonNull(
            Double first,
            Double second
    ) {
        return first != null ? first : second;
    }

    private Double normalizeMetricScore(
            String metricName,
            Double value
    ) {
        if (metricName == null || value == null) {
            return null;
        }

        if ("min".equalsIgnoreCase(
                inferDirection(metricName))) {
            double safeValue = Math.max(value, 0.0);
            return roundDouble(
                    1.0 / (1.0 + safeValue),
                    6
            );
        }

        if (value >= 0.0 && value <= 1.0) {
            return roundDouble(value, 6);
        }

        if (value > 1.0 && value <= 100.0) {
            return roundDouble(value / 100.0, 6);
        }

        if (value < 0.0) {
            return 0.0;
        }

        return 1.0;
    }

    private void updateMetricsGlobalScore(
            MlExperiment experiment,
            Double globalScore
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        mlExperimentMetricRepository.updateGlobalScoreByExperimentId(
                experiment.getId(),
                globalScore
        );
    }

    private void updateMetricsProductionState(
            MlExperiment experiment,
            boolean isProduction,
            String productionVersion
    ) {
        if (experiment == null || experiment.getId() == null) {
            return;
        }

        mlExperimentMetricRepository.updateProductionStateByExperimentId(
                experiment.getId(),
                isProduction,
                productionVersion
        );
    }

    private String inferDirection(String metricName) {
        if (metricName == null
                || metricName.isBlank()) {
            return "max";
        }

        String normalized =
                metricName.trim().toLowerCase();

        if (normalized.contains("rmse")
                || normalized.contains("mse")
                || normalized.contains("mae")
                || normalized.contains("mape")
                || normalized.contains("loss")
                || normalized.contains("error")
                || normalized.contains("cost")) {
            return "min";
        }

        return "max";
    }

    private String cleanMetric(String metricName) {
        if (metricName == null
                || metricName.isBlank()) {
            return null;
        }

        String cleaned =
                metricName.trim().toLowerCase();

        return switch (cleaned) {
            case "precision_weighted" -> "precision";
            case "recall_weighted" -> "recall";
            case "f1_weighted", "f1" -> "f1_score";
            case "r2_score", "r²" -> "r2";
            default -> cleaned;
        };
    }

    private Double roundDouble(
            Double value,
            int decimals
    ) {
        if (value == null) {
            return null;
        }

        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}