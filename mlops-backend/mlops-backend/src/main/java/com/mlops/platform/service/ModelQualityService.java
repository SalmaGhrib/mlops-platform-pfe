package com.mlops.platform.service;

import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.MonitoringAlert;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Gate qualité des modèles, sensible à la direction des métriques.
 *
 * Un modèle est conforme si :
 *  - sa métrique primaire respecte la direction du projet :
 *      * direction "max" (accuracy, f1, r2...) : valeur >= seuil ;
 *      * direction "min" (mae, rmse, mse, loss...) : valeur <= seuil ;
 *  - ET son score global normalisé est >= seuil.
 *
 * Si le seuil minimal de qualité n'est pas configuré (retraining_config
 * inexistant ou globalScoreThreshold null), aucun contrôle n'est appliqué et
 * le modèle est considéré conforme.
 *
 * Un modèle non conforme ne peut pas être promu (MODEL_QUALITY_FAILED).
 */
@Component
public class ModelQualityService {

    public static final double DEFAULT_GLOBAL_SCORE_THRESHOLD = 0.75;

    private final MlExperimentRepository mlExperimentRepository;
    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MetricNormalizationHelper metricNormalizationHelper;
    private final MonitoringAlertService monitoringAlertService;

    public ModelQualityService(
            MlExperimentRepository mlExperimentRepository,
            RetrainingConfigRepository retrainingConfigRepository,
            MetricNormalizationHelper metricNormalizationHelper,
            MonitoringAlertService monitoringAlertService
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.metricNormalizationHelper = metricNormalizationHelper;
        this.monitoringAlertService = monitoringAlertService;
    }

    public record QualityEvaluation(
            Long projectId,
            Long experimentId,
            String registeredModelName,
            String modelVersion,
            String primaryMetric,
            Double primaryMetricValue,
            String direction,
            Double globalScore,
            Double threshold,
            boolean conforming,
            String reason
    ) {
    }

    /**
     * Évalue la qualité du meilleur candidat (celui qui serait promu).
     */
    public QualityEvaluation evaluateCandidate(MlProject project) {
        if (project == null || project.getId() == null) {
            return null;
        }

        MlExperiment candidate = mlExperimentRepository
                .findCurrentProductionExperimentForProject(project.getId())
                .orElse(null);

        if (candidate == null) {
            return null;
        }

        return evaluate(project, candidate);
    }

    public QualityEvaluation evaluate(
            MlProject project,
            MlExperiment experiment
    ) {
        if (project == null || project.getId() == null || experiment == null) {
            return null;
        }

        Double threshold = resolveThreshold(project.getId());

        String primaryMetric = experiment.getPrimaryMetric();
        Double primaryMetricValue = experiment.getPrimaryMetricValue();
        String direction = resolveDirection(project, experiment);
        Double globalScore = experiment.getGlobalScore();

        if (threshold == null) {
            return new QualityEvaluation(
                    project.getId(),
                    experiment.getId(),
                    experiment.getRegisteredModelName(),
                    experiment.getModelVersion(),
                    primaryMetric,
                    primaryMetricValue,
                    direction,
                    globalScore,
                    null,
                    true,
                    "NO_THRESHOLD"
            );
        }

        boolean conforming = true;
        String reason = "OK";

        if (primaryMetricValue == null) {
            conforming = false;
            reason = "NO_PRIMARY_METRIC_VALUE";
        } else if ("min".equalsIgnoreCase(direction)) {
            conforming = primaryMetricValue <= threshold;
            reason = conforming
                    ? "OK"
                    : "METRIC_ABOVE_THRESHOLD";
        } else {
            conforming = primaryMetricValue >= threshold;
            reason = conforming
                    ? "OK"
                    : "METRIC_BELOW_THRESHOLD";
        }

        if (conforming && globalScore != null && globalScore < threshold) {
            conforming = false;
            reason = "GLOBAL_SCORE_BELOW_THRESHOLD";
        }

        return new QualityEvaluation(
                project.getId(),
                experiment.getId(),
                experiment.getRegisteredModelName(),
                experiment.getModelVersion(),
                primaryMetric,
                primaryMetricValue,
                direction,
                globalScore,
                threshold,
                conforming,
                reason
        );
    }

    /**
     * Seuil minimum de qualité du modèle.
     * Null si aucun contrôle n'est configuré.
     */
    public Double resolveThreshold(Long projectId) {
        if (projectId == null) {
            return null;
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);

        if (config == null || config.getGlobalScoreThreshold() == null) {
            return null;
        }

        return config.getGlobalScoreThreshold();
    }

    /**
     * Crée une alerte MODEL_QUALITY_FAILED si la qualité est insuffisante.
     * Idempotente par jour + modèle : on ne spamme pas l'utilisateur.
     *
     * @return l'alerte créée, ou null si conforme / déjà signalée.
     */
    public MonitoringAlert raiseQualityAlertIfNeeded(MlProject project) {
        if (project == null || project.getId() == null) {
            return null;
        }

        QualityEvaluation evaluation = evaluateCandidate(project);
        if (evaluation == null || evaluation.conforming()) {
            return null;
        }

        Double threshold = evaluation.threshold() != null
                ? evaluation.threshold()
                : DEFAULT_GLOBAL_SCORE_THRESHOLD;

        String message = "Le score global du modèle "
                + safeValue(evaluation.modelVersion(), "inconnu")
                + " (" + formatScore(evaluation.globalScore()) + ") est inférieur "
                + "au seuil minimum de qualité ("
                + formatScore(threshold) + ").";

        if ("GLOBAL_SCORE_BELOW_THRESHOLD".equals(evaluation.reason())) {
            message += " Le modèle ne peut pas être promu en production.";
        }

        String idempotencyKey = MonitoringAlertService.buildIdempotencyKey(
                AlertType.MODEL_QUALITY_FAILED,
                project.getId(),
                evaluation.registeredModelName(),
                LocalDateTime.now()
        );

        return monitoringAlertService.raiseAlert(
                AlertType.MODEL_QUALITY_FAILED,
                AlertSeverity.WARNING,
                project.getId(),
                evaluation.registeredModelName(),
                evaluation.modelVersion(),
                evaluation.globalScore(),
                threshold,
                evaluation.reason(),
                "Réévaluer le modèle ou abaisser le seuil de qualité.",
                message,
                idempotencyKey,
                project.getOwner()
        );
    }

    private String resolveDirection(MlProject project, MlExperiment experiment) {
        if (experiment != null && experiment.getDirection() != null) {
            return experiment.getDirection().trim().toLowerCase(Locale.ROOT);
        }

        String projectDirection = project.getMainMetricDirection();
        if (projectDirection != null && !projectDirection.isBlank()) {
            return projectDirection.trim().toLowerCase(Locale.ROOT);
        }

        return metricNormalizationHelper.getDirectionFromMetric(
                experiment != null ? experiment.getPrimaryMetric() : null
        );
    }

    private String formatScore(Double score) {
        if (score == null) {
            return "n/d";
        }
        return String.format(Locale.ROOT, "%.2f", score);
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}