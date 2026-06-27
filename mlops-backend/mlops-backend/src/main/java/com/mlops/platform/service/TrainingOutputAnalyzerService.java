package com.mlops.platform.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TrainingOutputAnalyzerService {

    public DetectedTrainingOutput analyze(String logs) {
        DetectedTrainingOutput output = new DetectedTrainingOutput();

        if (!hasText(logs)) {
            return output;
        }

        String normalizedLogs = normalizeLogs(logs);

        output.getMetrics().putAll(extractMetrics(normalizedLogs));
        output.getParameters().putAll(extractParameters(normalizedLogs));
        output.getModelFiles().addAll(extractModelFiles(logs));
        output.getAlgorithms().addAll(extractAlgorithms(logs));

        return output;
    }

    private Map<String, Double> extractMetrics(String logs) {
        Map<String, Double> metrics = new LinkedHashMap<>();

        extractGenericMetricKeyValues(logs, metrics);
        extractMetricsFromAliases(logs, metrics);
        extractMetricsInsideParentheses(logs, metrics);

        return metrics;
    }

    private void extractGenericMetricKeyValues(String logs, Map<String, Double> metrics) {
        Pattern pattern = Pattern.compile(
                "(?i)\\b([a-zA-ZÀ-ÿ][a-zA-ZÀ-ÿ0-9_\\-\\. ]{1,70})\\b\\s*(?:=|:|->|=>)\\s*(-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)(\\s*%)?"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String rawName = matcher.group(1);
            String rawValue = matcher.group(2);
            boolean percent = matcher.group(3) != null;

            addMetricIfRecognized(metrics, rawName, rawValue, percent);
        }
    }

    private void extractMetricsFromAliases(String logs, Map<String, Double> metrics) {
        for (Map.Entry<String, List<String>> entry : metricAliases().entrySet()) {
            String canonicalMetric = entry.getKey();

            for (String alias : entry.getValue()) {
                String normalizedAlias = normalizeText(alias);

                Pattern pattern = Pattern.compile(
                        "(?i)(" + Pattern.quote(normalizedAlias) + ")[^\\n\\r:=]{0,120}(?:=|:)\\s*(-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)(\\s*%)?"
                );

                Matcher matcher = pattern.matcher(logs);

                if (matcher.find()) {
                    String rawValue = matcher.group(2);
                    boolean percent = matcher.group(3) != null;
                    addMetricValue(metrics, canonicalMetric, rawValue, percent);
                }
            }
        }
    }

    private void extractMetricsInsideParentheses(String logs, Map<String, Double> metrics) {
        Pattern pattern = Pattern.compile(
                "(?i)[a-zA-ZÀ-ÿ0-9_\\-\\. ]*\\(([^)]+)\\)[^\\n\\r:=]{0,100}(?:=|:)\\s*(-?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)(\\s*%)?"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String rawName = matcher.group(1);
            String rawValue = matcher.group(2);
            boolean percent = matcher.group(3) != null;

            addMetricIfRecognized(metrics, rawName, rawValue, percent);
        }
    }

    private Map<String, String> extractParameters(String logs) {
        Map<String, String> parameters = new LinkedHashMap<>();

        Pattern pattern = Pattern.compile(
                "(?i)\\b(n_estimators|max_depth|learning_rate|test_size|random_state|batch_size|epochs|epoch|num_epochs|optimizer|criterion|kernel|gamma|c|alpha|lambda|subsample|colsample_bytree|min_samples_split|min_samples_leaf)\\b\\s*(?:=|:|->|=>)\\s*([^\\n\\r,;]+)"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String name = normalizeParameterName(matcher.group(1));
            String value = matcher.group(2).trim();

            if (value.length() > 120) {
                value = value.substring(0, 120);
            }

            parameters.putIfAbsent(name, value);
        }

        return parameters;
    }

    private List<String> extractModelFiles(String logs) {
        List<String> files = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "(?i)([a-zA-Z0-9_\\-./\\\\]+\\.(?:pkl|joblib|pickle|sav|h5|keras|pt|pth|onnx))"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String file = matcher.group(1).trim();

            if (!files.contains(file)) {
                files.add(file);
            }
        }

        return files;
    }

    private List<String> extractAlgorithms(String logs) {
        List<String> algorithms = new ArrayList<>();

        List<String> knownAlgorithms = List.of(
                "LinearRegression",
                "LogisticRegression",
                "RandomForestClassifier",
                "RandomForestRegressor",
                "DecisionTreeClassifier",
                "DecisionTreeRegressor",
                "XGBClassifier",
                "XGBRegressor",
                "LGBMClassifier",
                "LGBMRegressor",
                "CatBoostClassifier",
                "CatBoostRegressor",
                "SVC",
                "SVR",
                "KNeighborsClassifier",
                "KNeighborsRegressor",
                "GradientBoostingClassifier",
                "GradientBoostingRegressor",
                "AdaBoostClassifier",
                "AdaBoostRegressor",
                "KMeans",
                "DBSCAN"
        );

        for (String algorithm : knownAlgorithms) {
            Pattern pattern = Pattern.compile("(?i)\\b" + Pattern.quote(algorithm) + "\\b");
            Matcher matcher = pattern.matcher(logs);

            if (matcher.find() && !algorithms.contains(algorithm)) {
                algorithms.add(algorithm);
            }
        }

        return algorithms;
    }

    private void addMetricIfRecognized(
            Map<String, Double> metrics,
            String rawMetricName,
            String rawValue,
            boolean percent
    ) {
        String normalizedName = normalizeMetricName(rawMetricName);

        if (!isRecognizedMetric(normalizedName)) {
            return;
        }

        addMetricValue(metrics, normalizedName, rawValue, percent);
    }

    private void addMetricValue(
            Map<String, Double> metrics,
            String metricName,
            String rawValue,
            boolean percent
    ) {
        if (!hasText(metricName) || !hasText(rawValue)) {
            return;
        }

        try {
            String normalizedName = normalizeMetricName(metricName);
            double value = Double.parseDouble(rawValue.trim());

            if (percent && value > 1.0) {
                value = value / 100.0;
            }

            metrics.putIfAbsent(normalizedName, value);
        } catch (Exception ignored) {
        }
    }

    public String normalizeMetricName(String metricName) {
        if (!hasText(metricName)) {
            return "";
        }

        String value = normalizeText(metricName);

        value = value
                .replace("-", "_")
                .replace(".", "_")
                .replace("/", "_")
                .replace(" ", "_");

        value = value.replaceAll("_+", "_");
        value = value.replaceAll("^_|_$", "");

        Map<String, String> aliases = new LinkedHashMap<>();

        aliases.put("acc", "accuracy");
        aliases.put("exactitude", "accuracy");
        aliases.put("precision_globale", "accuracy");
        aliases.put("score_accuracy", "accuracy");

        aliases.put("f1", "f1_score");
        aliases.put("f1score", "f1_score");
        aliases.put("f1_score", "f1_score");
        aliases.put("score_f1", "f1_score");
        aliases.put("f_mesure", "f1_score");

        aliases.put("r_2", "r2");
        aliases.put("r2_score", "r2");
        aliases.put("score_r2", "r2");
        aliases.put("coefficient_de_determination", "r2");
        aliases.put("qualite_d_ajustement", "r2");

        aliases.put("mean_squared_error", "mse");
        aliases.put("fonction_objectif", "mse");
        aliases.put("erreur_quadratique_moyenne", "mse");

        aliases.put("root_mean_squared_error", "rmse");
        aliases.put("erreur_moyenne", "rmse");
        aliases.put("racine_de_l_erreur_quadratique_moyenne", "rmse");

        aliases.put("mean_absolute_error", "mae");
        aliases.put("erreur_absolue_moyenne", "mae");

        aliases.put("mean_absolute_percentage_error", "mape");
        aliases.put("erreur_absolue_pourcentage_moyenne", "mape");

        aliases.put("roc_auc_score", "roc_auc");
        aliases.put("auc", "roc_auc");
        aliases.put("auc_roc", "roc_auc");

        aliases.put("logloss", "log_loss");
        aliases.put("binary_logloss", "log_loss");
        aliases.put("cross_entropy", "log_loss");

        aliases.put("balanced_acc", "balanced_accuracy");
        aliases.put("balanced_accuracy_score", "balanced_accuracy");

        aliases.put("silhouette_score", "silhouette");

        return aliases.getOrDefault(value, value);
    }

    public boolean isRecognizedMetric(String metricName) {
        if (!hasText(metricName)) {
            return false;
        }

        String metric = normalizeMetricName(metricName);

        return metric.equals("accuracy")
                || metric.equals("balanced_accuracy")
                || metric.equals("precision")
                || metric.equals("precision_weighted")
                || metric.equals("precision_macro")
                || metric.equals("precision_micro")
                || metric.equals("recall")
                || metric.equals("recall_weighted")
                || metric.equals("recall_macro")
                || metric.equals("recall_micro")
                || metric.equals("f1_score")
                || metric.equals("f1_weighted")
                || metric.equals("f1_macro")
                || metric.equals("f1_micro")
                || metric.equals("roc_auc")
                || metric.equals("pr_auc")
                || metric.equals("average_precision")
                || metric.equals("mcc")
                || metric.equals("kappa")
                || metric.equals("cohen_kappa")
                || metric.equals("log_loss")
                || metric.equals("loss")
                || metric.equals("val_loss")
                || metric.equals("error")
                || metric.equals("mae")
                || metric.equals("mse")
                || metric.equals("rmse")
                || metric.equals("r2")
                || metric.equals("mape")
                || metric.equals("msle")
                || metric.equals("rmsle")
                || metric.equals("median_absolute_error")
                || metric.equals("explained_variance")
                || metric.equals("silhouette")
                || metric.equals("calinski_harabasz")
                || metric.equals("davies_bouldin")
                || metric.equals("inertia");
    }

    private Map<String, List<String>> metricAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();

        aliases.put("accuracy", List.of(
                "accuracy",
                "acc",
                "exactitude",
                "precision globale",
                "score accuracy"
        ));

        aliases.put("balanced_accuracy", List.of(
                "balanced accuracy",
                "balanced_accuracy",
                "accuracy equilibree",
                "exactitude equilibree"
        ));

        aliases.put("precision", List.of(
                "precision",
                "précision"
        ));

        aliases.put("recall", List.of(
                "recall",
                "rappel",
                "sensibilite",
                "sensibilité"
        ));

        aliases.put("f1_score", List.of(
                "f1",
                "f1 score",
                "f1_score",
                "score f1",
                "f-mesure"
        ));

        aliases.put("roc_auc", List.of(
                "roc auc",
                "roc_auc",
                "auc",
                "auc roc"
        ));

        aliases.put("log_loss", List.of(
                "log loss",
                "log_loss",
                "logloss",
                "cross entropy",
                "entropie croisee"
        ));

        aliases.put("mse", List.of(
                "mse",
                "mean squared error",
                "fonction objectif",
                "erreur quadratique moyenne"
        ));

        aliases.put("rmse", List.of(
                "rmse",
                "root mean squared error",
                "erreur moyenne",
                "racine de l erreur quadratique moyenne"
        ));

        aliases.put("mae", List.of(
                "mae",
                "mean absolute error",
                "erreur absolue moyenne"
        ));

        aliases.put("mape", List.of(
                "mape",
                "mean absolute percentage error",
                "erreur absolue pourcentage moyenne"
        ));

        aliases.put("r2", List.of(
                "r2",
                "r 2",
                "score r2",
                "coefficient de determination",
                "qualite d ajustement",
                "qualité d ajustement"
        ));

        aliases.put("loss", List.of(
                "loss",
                "perte",
                "cout",
                "coût"
        ));

        aliases.put("val_loss", List.of(
                "val loss",
                "val_loss",
                "validation loss",
                "perte validation"
        ));

        aliases.put("silhouette", List.of(
                "silhouette",
                "silhouette score",
                "score silhouette"
        ));

        aliases.put("inertia", List.of(
                "inertia",
                "inertie"
        ));

        return aliases;
    }

    private String normalizeLogs(String logs) {
        return normalizeText(logs)
                .replace("€", "")
                .replace(",", ".")
                .replace("’", "'")
                .replace("“", "\"")
                .replace("”", "\"");
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }

        String value = text
                .replace("²", "2")
                .replace("R²", "R2")
                .replace("r²", "r2");

        value = Normalizer.normalize(value, Normalizer.Form.NFD);
        value = value.replaceAll("\\p{M}", "");

        return value.toLowerCase().trim();
    }

    private String normalizeParameterName(String parameterName) {
        if (!hasText(parameterName)) {
            return "";
        }

        return normalizeText(parameterName)
                .replace("-", "_")
                .replace(".", "_")
                .replace(" ", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class DetectedTrainingOutput {

        private final Map<String, Double> metrics = new LinkedHashMap<>();
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private final List<String> modelFiles = new ArrayList<>();
        private final List<String> algorithms = new ArrayList<>();

        public Map<String, Double> getMetrics() {
            return metrics;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public List<String> getModelFiles() {
            return modelFiles;
        }

        public List<String> getAlgorithms() {
            return algorithms;
        }
    }
}