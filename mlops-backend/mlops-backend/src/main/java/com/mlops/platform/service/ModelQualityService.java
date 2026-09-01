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

import java.util.Locale;

/**
 * Gate qualité des modèles, sensible au type de projet.
 *
 * Le quality gate compare UNIQUEMENT le score global normalisé au seuil
 * adapté au type de projet :
 *  - CLASSIFICATION → classificationMinimumGlobalScore
 *  - REGRESSION     → regressionMinimumGlobalScore
 *
 * La règle est toujours : globalScore >= threshold.
 *
 * Les métriques brutes (accuracy, RMSE, MAE, etc.) ne sont PAS comparées
 * au seuil global. Elles servent uniquement au calcul du score global.
 *
 * Si le seuil minimal de qualité n'est pas configuré (retraining_config
 * inexistant ou seuil null), aucun contrôle n'est appliqué et
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

        String projectType = resolveProjectType(project);
        Double threshold = resolveThreshold(project.getId(), projectType);

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

        boolean conforming = globalScore != null && globalScore >= threshold;
        String reason = conforming ? "OK" : "GLOBAL_SCORE_BELOW_THRESHOLD";

        System.out.println(
                "[PRODUCTION_GATE] projectId=" + project.getId()
                        + ", experimentId=" + experiment.getId()
                        + ", projectType=" + projectType
                        + ", globalScore=" + globalScore
                        + ", threshold=" + threshold
                        + ", eligible=" + conforming
        );

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
     * Seuil minimum de qualité du modèle, adapté au type de projet.
     * Null si aucun contrôle n'est configuré.
     */
    public Double resolveThreshold(Long projectId, String projectType) {
        if (projectId == null) {
            return null;
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);

        if (config == null) {
            return null;
        }

        if ("regression".equalsIgnoreCase(projectType)) {
            return config.getRegressionMinimumGlobalScore();
        }

        if ("classification".equalsIgnoreCase(projectType)) {
            return config.getClassificationMinimumGlobalScore();
        }

        return config.getGlobalScoreThreshold();
    }

    /**
     * Backward-compatible : résout le seuil sans connaître le type projet.
     */
    public Double resolveThreshold(Long projectId) {
        return resolveThreshold(projectId, null);
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

        String projectName = safeValue(project.getName(), "projet");
        String modelName = safeValue(
                evaluation.registeredModelName(), "modele"
        );
        String version = safeValue(evaluation.modelVersion(), "inconnu");

        String title = "Modele non promu en Production — " + projectName;

        String message = "Le modele \""
                + modelName + "\" (version " + version + ") a termine son "
                + "entrainement, mais son score global de "
                + formatPercent(evaluation.globalScore())
                + " est inferieur au seuil minimum requis de "
                + formatPercent(threshold)
                + ". Le modele a ete enregistre dans MLflow, mais il n'a "
                + "pas ete promu en Production ni deploye. "
                + "Vous pouvez ameliorer les donnees ou relancer un "
                + "entrainement.";

        String idempotencyKey = MonitoringAlertService.buildExperimentIdKey(
                AlertType.MODEL_QUALITY_FAILED,
                project.getId(),
                evaluation.experimentId()
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
                "Reevaluer le modele ou abaisser le seuil de qualite.",
                message,
                title,
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

    private String resolveProjectType(MlProject project) {
        if (project == null || project.getProjectType() == null) {
            return null;
        }
        return project.getProjectType().trim().toLowerCase(Locale.ROOT);
    }

    private String formatScore(Double score) {
        if (score == null) {
            return "n/d";
        }
        return String.format(Locale.ROOT, "%.2f", score);
    }

    private String formatPercent(Double score) {
        if (score == null) {
            return "n/d";
        }
        return String.format(Locale.ROOT, "%.2f %%", score * 100);
    }

    private String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}