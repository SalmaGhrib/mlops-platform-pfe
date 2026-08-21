package com.mlops.platform.service;

import com.mlops.platform.entity.MlProject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Source unique de vérité pour la normalisation des noms de métriques,
 * la compatibilité avec le type de projet et le calcul du score global
 * pondéré (P1=50%, P2=30%, P3=20%).
 *
 * Utilisé à la fois par MlExperimentService (affichage/recalcul) et
 * ModelPromotionService (sélection en Production) afin de garantir que la
 * version promue correspond toujours au score réellement affiché.
 */
@Component
public class MetricNormalizationHelper {

    private static final double[] CONFIGURED_WEIGHTS = {0.50d, 0.30d, 0.20d};

    public String normalizeMetricKey(String metricName) {
        if (metricName == null) {
            return "";
        }

        String normalized = metricName.trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "_")
                .replace(" ", "_")
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (normalized.equals("f1score") || normalized.equals("f1_score_score")) {
            return "f1_score";
        }

        if (normalized.equals("f1_score") || normalized.equals("f1")) {
            return normalized;
        }

        if (normalized.equals("acc")
                || normalized.equals("accuracy_score")
                || normalized.equals("test_accuracy")
                || normalized.equals("val_accuracy")
                || normalized.equals("validation_accuracy")
                || normalized.equals("model_accuracy")) {
            return "accuracy";
        }

        if (normalized.equals("precision_score")
                || normalized.equals("precision_macro")
                || normalized.equals("macro_precision")) {
            return "precision";
        }

        if (normalized.equals("recall_score")
                || normalized.equals("recall_macro")
                || normalized.equals("macro_recall")) {
            return "recall";
        }

        if (normalized.equals("f1_macro")
                || normalized.equals("macro_f1")
                || normalized.equals("f1_score_macro")) {
            return "f1_score";
        }

        if (normalized.equals("r2score") || normalized.equals("r2_score")) {
            return "r2";
        }

        if (normalized.equals("root_mean_squared_error")) {
            return "rmse";
        }

        if (normalized.equals("mean_absolute_error")) {
            return "mae";
        }

        if (normalized.equals("mean_squared_error")) {
            return "mse";
        }

        return normalized;
    }

    public boolean isDisplayablePerformanceMetric(String metricName) {
        String normalized = normalizeMetricKey(metricName);

        if (normalized.isBlank()) {
            return false;
        }

        if (normalized.equals("prediction")
                || normalized.equals("prediction_example")
                || normalized.equals("sample_prediction")
                || normalized.equals("return_code")
                || normalized.equals("status")
                || normalized.equals("epoch")
                || normalized.equals("step")
                || normalized.contains("example")) {
            return false;
        }

        return normalized.equals("accuracy")
                || normalized.equals("acc")
                || normalized.equals("precision")
                || normalized.equals("precision_weighted")
                || normalized.equals("recall")
                || normalized.equals("recall_weighted")
                || normalized.equals("f1")
                || normalized.equals("f1_score")
                || normalized.equals("f1_weighted")
                || normalized.equals("r2")
                || normalized.equals("r2_score")
                || normalized.equals("rmse")
                || normalized.equals("mae")
                || normalized.equals("mse")
                || normalized.equals("log_loss")
                || normalized.equals("loss")
                || normalized.contains("loss")
                || normalized.contains("error");
    }

    public boolean isRegressionProject(MlProject project) {
        if (project == null || project.getProjectType() == null) {
            return false;
        }

        return project.getProjectType().trim().equalsIgnoreCase("regression");
    }

    public boolean isClassificationProject(MlProject project) {
        if (project == null || project.getProjectType() == null) {
            return true;
        }

        return project.getProjectType().trim().equalsIgnoreCase("classification");
    }

    public boolean isMetricCompatibleWithProjectType(MlProject project, String metricName) {
        String normalized = normalizeMetricKey(metricName);

        if (normalized.isBlank()) {
            return false;
        }

        if (isRegressionProject(project)) {
            return normalized.equals("rmse")
                    || normalized.equals("mae")
                    || normalized.equals("mse")
                    || normalized.equals("r2")
                    || normalized.equals("r2_score")
                    || normalized.equals("loss")
                    || normalized.contains("error");
        }

        return normalized.equals("accuracy")
                || normalized.equals("acc")
                || normalized.equals("precision")
                || normalized.equals("precision_weighted")
                || normalized.equals("recall")
                || normalized.equals("recall_weighted")
                || normalized.equals("f1")
                || normalized.equals("f1_score")
                || normalized.equals("f1_weighted")
                || normalized.equals("log_loss")
                || normalized.equals("loss");
    }

    public String defaultPrimaryMetricForProject(MlProject project) {
        if (isRegressionProject(project)) {
            return "rmse";
        }

        return "accuracy";
    }

    public String getDirectionFromMetric(String metric) {
        String normalizedMetric = normalizeMetricKey(metric);

        if (normalizedMetric.isBlank()) {
            return "max";
        }

        if (normalizedMetric.equals("loss")
                || normalizedMetric.equals("log_loss")
                || normalizedMetric.equals("rmse")
                || normalizedMetric.equals("mse")
                || normalizedMetric.equals("mae")
                || normalizedMetric.equals("error")
                || normalizedMetric.endsWith("_loss")
                || normalizedMetric.endsWith("_error")
                || normalizedMetric.contains("loss")
                || normalizedMetric.contains("error")) {
            return "min";
        }

        return "max";
    }

    public Map<String, Double> normalizeMetricsMap(Map<String, Double> metrics) {
        Map<String, Double> normalizedMetrics = new LinkedHashMap<>();

        if (metrics == null || metrics.isEmpty()) {
            return normalizedMetrics;
        }

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String normalizedKey = normalizeMetricKey(entry.getKey());

            if (normalizedKey.isBlank() || !isDisplayablePerformanceMetric(normalizedKey)) {
                continue;
            }

            normalizedMetrics.put(normalizedKey, entry.getValue());
        }

        return normalizedMetrics;
    }

    public Double getMetricValueCompatible(Map<String, Double> metrics, String metricName) {
        if (metrics == null || metrics.isEmpty() || metricName == null || metricName.isBlank()) {
            return null;
        }

        Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);
        String normalized = normalizeMetricKey(metricName);

        if (normalizedMetrics.containsKey(normalized)) {
            return normalizedMetrics.get(normalized);
        }

        if (normalized.equals("f1") || normalized.equals("f1_score")) {
            return firstMetricValue(normalizedMetrics, "f1_score", "f1", "f1_weighted");
        }

        if (normalized.equals("precision")) {
            return firstMetricValue(normalizedMetrics, "precision", "precision_weighted");
        }

        if (normalized.equals("recall")) {
            return firstMetricValue(normalizedMetrics, "recall", "recall_weighted");
        }

        if (normalized.equals("accuracy") || normalized.equals("acc")) {
            return firstMetricValue(normalizedMetrics, "accuracy", "acc");
        }

        if (normalized.equals("r2") || normalized.equals("r2_score")) {
            return firstMetricValue(normalizedMetrics, "r2", "r2_score");
        }

        if (normalized.equals("rmse")) {
            return firstMetricValue(normalizedMetrics, "rmse", "root_mean_squared_error");
        }

        if (normalized.equals("mse")) {
            return firstMetricValue(normalizedMetrics, "mse", "mean_squared_error");
        }

        if (normalized.equals("mae")) {
            return firstMetricValue(normalizedMetrics, "mae", "mean_absolute_error");
        }

        return null;
    }

    private Double firstMetricValue(Map<String, Double> metrics, String... names) {
        if (metrics == null || metrics.isEmpty() || names == null) {
            return null;
        }

        for (String name : names) {
            String normalizedName = normalizeMetricKey(name);

            if (metrics.containsKey(normalizedName)) {
                return metrics.get(normalizedName);
            }
        }

        return null;
    }

    public Double normalizeMetricScore(String metricName, Double value) {
        if (metricName == null || value == null) {
            return null;
        }

        String normalizedMetric = normalizeMetricKey(metricName);
        String direction = getDirectionFromMetric(normalizedMetric);

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

    public Double roundDouble(Double value, int decimals) {
        if (value == null) {
            return null;
        }

        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /**
     * Liste des priorités configurées sur le projet, dans l'ordre exact
     * primaryMetric1 -> primaryMetric2 -> primaryMetric3, filtrées sur la
     * compatibilité avec le type de projet.
     */
    public List<String> getProjectMetricPriorities(MlProject project) {
        List<String> priorities = new ArrayList<>();

        if (project == null) {
            priorities.add("accuracy");
            return priorities;
        }

        List<String> configured = new ArrayList<>();
        configured.add(project.getPrimaryMetric1());
        configured.add(project.getPrimaryMetric2());
        configured.add(project.getPrimaryMetric3());

        for (String metric : configured) {
            String normalized = normalizeMetricKey(metric);

            if (normalized.isBlank()) {
                continue;
            }

            if (!isMetricCompatibleWithProjectType(project, normalized)) {
                System.out.println("[METRICS] Ignored incompatible project metric. project="
                        + project.getName()
                        + ", projectType="
                        + project.getProjectType()
                        + ", metric="
                        + metric);
                continue;
            }

            if (!priorities.contains(normalized)) {
                priorities.add(normalized);
            }
        }

        if (!priorities.isEmpty()) {
            return priorities;
        }

        if (isRegressionProject(project)) {
            priorities.add("rmse");
            priorities.add("mae");
            priorities.add("r2");
            priorities.add("mse");
            return priorities;
        }

        priorities.add("f1_score");
        priorities.add("accuracy");
        priorities.add("precision");
        priorities.add("recall");
        return priorities;
    }

    /**
     * Score global pondéré selon l'ordre de priorité des métriques du projet.
     * Si une métrique manque dans un run, les poids des métriques disponibles
     * sont renormalisés : le run reste comparable sans inventer de valeur.
     */
    public Double calculatePriorityGlobalScore(MlProject project, Map<String, Double> metrics) {
        if (project == null || metrics == null || metrics.isEmpty()) {
            return null;
        }

        Map<String, Double> normalizedMetrics = normalizeMetricsMap(metrics);
        List<String> priorities = getProjectMetricPriorities(project);

        if (priorities == null || priorities.isEmpty()) {
            return null;
        }

        double weightedSum = 0.0d;
        double appliedWeight = 0.0d;

        for (int index = 0; index < priorities.size() && index < CONFIGURED_WEIGHTS.length; index++) {
            String metricName = priorities.get(index);

            if (metricName == null
                    || metricName.isBlank()
                    || !isMetricCompatibleWithProjectType(project, metricName)) {
                continue;
            }

            Double metricValue = getMetricValueCompatible(normalizedMetrics, metricName);
            if (metricValue == null || !Double.isFinite(metricValue)) {
                continue;
            }

            Double normalizedScore = normalizeMetricScore(metricName, metricValue);
            if (normalizedScore == null || !Double.isFinite(normalizedScore)) {
                continue;
            }

            double weight = CONFIGURED_WEIGHTS[index];
            weightedSum += normalizedScore * weight;
            appliedWeight += weight;

            System.out.println("[METRICS] projectType="
                    + project.getProjectType()
                    + ", metric="
                    + normalizeMetricKey(metricName)
                    + ", rawValue="
                    + metricValue
                    + ", normalized="
                    + normalizedScore
                    + ", weight="
                    + weight);
        }

        if (appliedWeight <= 0.0d) {
            return null;
        }

        Double globalScore = roundDouble(weightedSum / appliedWeight, 6);
        System.out.println("[METRICS] weightedGlobalScore=" + globalScore
                + ", appliedWeight=" + appliedWeight
                + ", priorities=" + priorities);
        return globalScore;
    }
}
