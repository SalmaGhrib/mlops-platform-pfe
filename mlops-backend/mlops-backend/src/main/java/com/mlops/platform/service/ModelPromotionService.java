package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public ModelPromotionService(
            MlExperimentRepository mlExperimentRepository,
            MlExperimentMetricRepository mlExperimentMetricRepository,
            MlflowTrackingClientService mlflowTrackingClientService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlExperimentMetricRepository = mlExperimentMetricRepository;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
    }

    /**
     * Recalcule la meilleure expérience d'un projet selon l'ordre de priorité des métriques,
     * puis met une seule expérience en production.
     *
     * Nouvelle règle :
     * - primaryMetric1 = priorité 1
     * - primaryMetric2 = priorité 2
     * - primaryMetric3 = priorité 3
     *
     * Important :
     * - modelVersion = version réelle MLflow du run. On ne l'écrase jamais ici.
     * - productionVersion = version actuellement promue en production.
     * - production = true uniquement pour le meilleur experiment ID.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MlExperiment recalculateProductionForProject(
            MlProject project,
            String registeredModelName
    ) {
        if (project == null) {
            throw new RuntimeException("Project is required");
        }

        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new RuntimeException("registeredModelName is required");
        }

        String cleanRegisteredModelName = registeredModelName.trim();

        List<String> selectedMetrics = getSelectedProjectMetrics(project);

        if (selectedMetrics.isEmpty()) {
            throw new RuntimeException("Project must have at least one priority metric");
        }

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
                        project,
                        cleanRegisteredModelName
                );

        if (experiments == null || experiments.isEmpty()) {
            throw new RuntimeException(
                    "No experiments found for registeredModelName=" + cleanRegisteredModelName
            );
        }

        MlExperiment bestExperiment = null;

        /*
         * Étape 1 :
         * On recalcule primaryMetric, primaryMetricValue et globalScore
         * pour toutes les expériences COMPLETED qui ont une vraie version MLflow.
         */
        for (MlExperiment experiment : experiments) {
            if (!isEligibleExperiment(experiment)) {
                continue;
            }

            Map<String, Double> metricValues = loadMetricValues(experiment);

            if (metricValues.isEmpty()) {
                continue;
            }

            updateExperimentMainMetricAndScore(
                    project,
                    experiment,
                    selectedMetrics,
                    metricValues
            );

            MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

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
            throw new RuntimeException(
                    "No eligible completed experiment found for production promotion"
            );
        }

        String bestVersion = bestExperiment.getModelVersion();

        if (bestVersion == null || bestVersion.isBlank()) {
            throw new RuntimeException("Best experiment has no model version");
        }

        /*
         * Étape 2 :
         * MLflow reçoit l'alias production sur la version gagnante.
         */
        mlflowTrackingClientService.setProductionAlias(
                cleanRegisteredModelName,
                bestVersion
        );

        /*
         * Étape 3 :
         * Côté MySQL, UNE SEULE ligne doit être production.
         *
         * Très important :
         * On ne teste pas seulement modelVersion == bestVersion,
         * parce que si deux runs ont accidentellement modelVersion = 1,
         * les deux deviendraient production.
         *
         * On teste l'ID exact du meilleur run.
         */
        Long bestExperimentId = bestExperiment.getId();

        for (MlExperiment experiment : experiments) {
            boolean isProduction =
                    experiment.getId() != null
                            && bestExperimentId != null
                            && experiment.getId().equals(bestExperimentId);

            experiment.setProduction(isProduction);

            if (isProduction) {
                experiment.setProductionVersion(bestVersion);
            } else {
                experiment.setProductionVersion(null);
            }

            MlExperiment savedExperiment = mlExperimentRepository.saveAndFlush(experiment);

            updateMetricsProductionState(
                    savedExperiment,
                    isProduction,
                    isProduction ? bestVersion : null
            );
        }

        bestExperiment.setProduction(true);
        bestExperiment.setProductionVersion(bestVersion);

        return mlExperimentRepository.saveAndFlush(bestExperiment);
    }

    private boolean isEligibleExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        return experiment.getModelVersion() != null
                && !experiment.getModelVersion().isBlank();
    }

    private Map<String, Double> loadMetricValues(MlExperiment experiment) {
        List<MlExperimentMetric> metrics =
                mlExperimentMetricRepository.findByExperiment(experiment);

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

            String metricName = cleanMetric(metric.getMetricName());

            if (metricName == null) {
                continue;
            }

            values.put(metricName, metric.getMetricValue());
        }

        return values;
    }

    private List<String> getSelectedProjectMetrics(MlProject project) {
        List<String> metrics = new ArrayList<>();

        if (project == null) {
            return metrics;
        }

        addMetricIfPresent(metrics, project.getPrimaryMetric1());
        addMetricIfPresent(metrics, project.getPrimaryMetric2());
        addMetricIfPresent(metrics, project.getPrimaryMetric3());

        return metrics;
    }

    private void addMetricIfPresent(List<String> metrics, String metricName) {
        String cleaned = cleanMetric(metricName);

        if (cleaned == null) {
            return;
        }

        if (!metrics.contains(cleaned)) {
            metrics.add(cleaned);
        }
    }

    private void updateExperimentMainMetricAndScore(
            MlProject project,
            MlExperiment experiment,
            List<String> selectedMetrics,
            Map<String, Double> metricValues
    ) {
        if (experiment == null || selectedMetrics == null || selectedMetrics.isEmpty()) {
            return;
        }

        for (String metricName : selectedMetrics) {
            if (metricName == null || metricName.isBlank()) {
                continue;
            }

            Double value = getMetricValueCompatible(metricValues, metricName);

            if (value == null) {
                continue;
            }

            experiment.setPrimaryMetric(metricName);
            experiment.setPrimaryMetricValue(value);
            experiment.setDirection(inferDirection(metricName));
            experiment.setGlobalScore(normalizeMetricScore(metricName, value));
            return;
        }

        String fallbackMetric = project != null ? cleanMetric(project.getPrimaryMetric1()) : null;

        experiment.setPrimaryMetric(fallbackMetric);
        experiment.setPrimaryMetricValue(null);
        experiment.setDirection(fallbackMetric != null ? inferDirection(fallbackMetric) : "max");
        experiment.setGlobalScore(null);
    }

    /**
     * Retour :
     *  1  => candidate est meilleur que currentBest
     * -1  => currentBest est meilleur
     *  0  => égalité
     */
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

        Map<String, Double> candidateMetrics = loadMetricValues(candidate);
        Map<String, Double> bestMetrics = loadMetricValues(currentBest);

        List<String> priorities =
                selectedMetrics != null && !selectedMetrics.isEmpty()
                        ? selectedMetrics
                        : getSelectedProjectMetrics(project);

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

            String direction = inferDirection(metricName);

            int comparison;

            if ("min".equalsIgnoreCase(direction)) {
                /*
                 * Pour rmse, mse, mae, loss :
                 * plus petit = meilleur.
                 */
                comparison = Double.compare(bestValue, candidateValue);
            } else {
                /*
                 * Pour accuracy, precision, recall, f1_score, r2 :
                 * plus grand = meilleur.
                 */
                comparison = Double.compare(candidateValue, bestValue);
            }

            if (comparison != 0) {
                return comparison;
            }
        }

        /*
         * Fallback 1 : globalScore.
         */
        int scoreComparison = compareNullableScores(
                candidate.getGlobalScore(),
                currentBest.getGlobalScore()
        );

        if (scoreComparison != 0) {
            return scoreComparison;
        }

        /*
         * Fallback 2 : version MLflow la plus élevée.
         * Ça évite de garder arbitrairement une ancienne version en cas d'égalité parfaite.
         */
        int versionComparison = compareModelVersions(
                candidate.getModelVersion(),
                currentBest.getModelVersion()
        );

        if (versionComparison != 0) {
            return versionComparison;
        }

        /*
         * Fallback 3 : run le plus récent.
         */
        return compareByCreatedAt(candidate, currentBest);
    }

    private int compareNullableScores(Double candidateScore, Double bestScore) {
        if (candidateScore == null && bestScore == null) {
            return 0;
        }

        if (candidateScore != null && bestScore == null) {
            return 1;
        }

        if (candidateScore == null) {
            return -1;
        }

        return Double.compare(candidateScore, bestScore);
    }

    private int compareModelVersions(String candidateVersion, String bestVersion) {
        Integer candidate = parseVersion(candidateVersion);
        Integer best = parseVersion(bestVersion);

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
        } catch (Exception e) {
            return null;
        }
    }

    private int compareByCreatedAt(MlExperiment candidate, MlExperiment currentBest) {
        LocalDateTime candidateCreatedAt = candidate.getCreatedAt();
        LocalDateTime bestCreatedAt = currentBest.getCreatedAt();

        if (candidateCreatedAt == null && bestCreatedAt == null) {
            return 0;
        }

        if (candidateCreatedAt != null && bestCreatedAt == null) {
            return 1;
        }

        if (candidateCreatedAt == null) {
            return -1;
        }

        return candidateCreatedAt.compareTo(bestCreatedAt);
    }

    private Double getMetricValueCompatible(Map<String, Double> metrics, String metricName) {
        if (metrics == null || metrics.isEmpty() || metricName == null || metricName.isBlank()) {
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

        if (normalized.equals("f1_score")) {
            Double value = metrics.get("f1_score");
            if (value != null) return value;

            value = metrics.get("f1");
            if (value != null) return value;

            return metrics.get("f1_weighted");
        }

        if (normalized.equals("precision")) {
            Double value = metrics.get("precision");
            if (value != null) return value;

            return metrics.get("precision_weighted");
        }

        if (normalized.equals("recall")) {
            Double value = metrics.get("recall");
            if (value != null) return value;

            return metrics.get("recall_weighted");
        }

        if (normalized.equals("accuracy")) {
            Double value = metrics.get("accuracy");
            if (value != null) return value;

            return metrics.get("val_accuracy");
        }

        if (normalized.equals("r2")) {
            Double value = metrics.get("r2");
            if (value != null) return value;

            return metrics.get("r2_score");
        }

        if (normalized.equals("loss")) {
            Double value = metrics.get("loss");
            if (value != null) return value;

            return metrics.get("val_loss");
        }

        return null;
    }

    /**
     * globalScore est seulement un score d'affichage.
     * La vraie décision de production se fait par comparaison hiérarchique des métriques.
     */
    private Double normalizeMetricScore(String metricName, Double value) {
        if (metricName == null || value == null) {
            return null;
        }

        String direction = inferDirection(metricName);

        if ("min".equalsIgnoreCase(direction)) {
            double safeValue = Math.max(value, 0.0);
            return roundDouble(1.0 / (1.0 + safeValue), 6);
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
        List<MlExperimentMetric> metrics =
                mlExperimentMetricRepository.findByExperiment(experiment);

        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        for (MlExperimentMetric metric : metrics) {
            metric.setGlobalScore(globalScore);
            mlExperimentMetricRepository.save(metric);
        }
    }

    private void updateMetricsProductionState(
            MlExperiment experiment,
            boolean isProduction,
            String productionVersion
    ) {
        List<MlExperimentMetric> metrics =
                mlExperimentMetricRepository.findByExperiment(experiment);

        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        for (MlExperimentMetric metric : metrics) {
            metric.setProduction(isProduction);
            metric.setProductionVersion(productionVersion);
            mlExperimentMetricRepository.save(metric);
        }
    }

    private String inferDirection(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return "max";
        }

        String normalized = metricName.trim().toLowerCase();

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
        if (metricName == null || metricName.isBlank()) {
            return null;
        }

        String cleaned = metricName.trim().toLowerCase();

        if ("precision_weighted".equals(cleaned)) {
            return "precision";
        }

        if ("recall_weighted".equals(cleaned)) {
            return "recall";
        }

        if ("f1_weighted".equals(cleaned) || "f1".equals(cleaned)) {
            return "f1_score";
        }

        if ("r2_score".equals(cleaned) || "r²".equals(cleaned)) {
            return "r2";
        }

        return cleaned;
    }

    private Double roundDouble(Double value, int decimals) {
        if (value == null) {
            return null;
        }

        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}