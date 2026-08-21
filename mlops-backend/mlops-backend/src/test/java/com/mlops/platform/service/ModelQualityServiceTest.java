package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du gate qualité direction-aware
 * (Mockito pur, sans Spring, sans base de données).
 *
 * Vérifie :
 *  1. classification (direction max) : accuracy >= seuil -> conforme ;
 *  2. régression (direction min) : mae <= seuil -> conforme ;
 *  3. score global sous le seuil -> non conforme ;
 *  4. absence de seuil configuré -> conforme (pas de gate).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelQualityServiceTest {

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    @Mock
    private RetrainingConfigRepository retrainingConfigRepository;

    @Mock
    private MetricNormalizationHelper metricNormalizationHelper;

    @Mock
    private MonitoringAlertService monitoringAlertService;

    private ModelQualityService service;

    @BeforeEach
    void setUp() {
        service = new ModelQualityService(
                mlExperimentRepository,
                retrainingConfigRepository,
                metricNormalizationHelper,
                monitoringAlertService
        );
    }

    private MlProject project(Long id, String type, String primaryMetric1) {
        MlProject project = new MlProject();
        project.setId(id);
        project.setName("test-project");
        project.setProjectType(type);
        project.setPrimaryMetric1(primaryMetric1);
        return project;
    }

    private MlExperiment experiment(double globalScore, Double primaryValue, String direction) {
        MlExperiment experiment = new MlExperiment();
        experiment.setId(1L);
        experiment.setGlobalScore(globalScore);
        experiment.setPrimaryMetricValue(primaryValue);
        experiment.setDirection(direction);
        return experiment;
    }

    private RetrainingConfig configWithThreshold(Double threshold) {
        RetrainingConfig config = new RetrainingConfig();
        config.setProjectId(1L);
        config.setGlobalScoreThreshold(threshold);
        return config;
    }

    @Test
    void classification_aboveThreshold_conforming() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.90, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
        assertEquals("OK", evaluation.reason());
        assertEquals(0.75, evaluation.threshold());
    }

    @Test
    void classification_belowThreshold_notConforming() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.63, 0.63, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
        assertEquals("METRIC_BELOW_THRESHOLD", evaluation.reason());
    }

    @Test
    void regression_metricUnderThreshold_conforming() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        /*
         * Direction "min" : mae (0.70) <= seuil (0.75) -> conforme.
         * Le score global normalisé reste > seuil (toujours higher-is-better).
         */
        MlExperiment experiment = experiment(0.80, 0.70, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
    }

    @Test
    void regression_metricAboveThreshold_notConforming() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.80, 0.80, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
        assertEquals("METRIC_ABOVE_THRESHOLD", evaluation.reason());
    }

    @Test
    void metricOkButGlobalScoreBelowThreshold_notConforming() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        /*
         * La métrique primaire est OK mais le score global pondéré est
         * en-dessous du seuil : le modèle est insuffisant.
         */
        MlExperiment experiment = experiment(0.63, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
        assertEquals("GLOBAL_SCORE_BELOW_THRESHOLD", evaluation.reason());
    }

    @Test
    void noThresholdConfigured_conforming() {
        MlProject project = project(1L, "classification", "accuracy");
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.empty());

        MlExperiment experiment = experiment(0.20, 0.20, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
        assertEquals("NO_THRESHOLD", evaluation.reason());
        assertNull(evaluation.threshold());
    }

    @Test
    void nullProjectOrExperiment_returnsNull() {
        MlProject project = project(1L, "classification", "accuracy");
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service.evaluate(null, experiment(0.9, 0.9, "max")));
        assertNull(service.evaluate(project, null));
    }

    @Test
    void resolveThreshold_returnsConfiguredValue() {
        RetrainingConfig config = configWithThreshold(0.8);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        assertEquals(0.8, service.resolveThreshold(1L));
    }

    @Test
    void raiseQualityAlertIfNeeded_conforming_noAlert() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(experiment(0.90, 0.90, "max")));

        com.mlops.platform.entity.MonitoringAlert alert =
                service.raiseQualityAlertIfNeeded(project);

        assertNull(alert);
    }

    @Test
    void raiseQualityAlertIfNeeded_insufficient_raisesAlert() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThreshold(0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(experiment(0.63, 0.63, "max")));

        com.mlops.platform.entity.MonitoringAlert raised = new com.mlops.platform.entity.MonitoringAlert();
        raised.setId(42L);
        when(monitoringAlertService.raiseAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(raised);

        com.mlops.platform.entity.MonitoringAlert alert =
                service.raiseQualityAlertIfNeeded(project);

        assertNotNull(alert);
        assertEquals(42L, alert.getId());
    }
}