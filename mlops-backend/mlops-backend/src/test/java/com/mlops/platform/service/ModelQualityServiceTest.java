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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du gate qualité (globalScore >= seuil par type projet).
 *
 * Le quality gate compare UNIQUEMENT le score global normalisé au seuil :
 *  - CLASSIFICATION → classificationMinimumGlobalScore
 *  - REGRESSION     → regressionMinimumGlobalScore
 *
 * Les métriques brutes (accuracy, RMSE, MAE…) ne sont PAS comparées au seuil.
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

    private MlExperiment experiment(Double globalScore, Double primaryValue, String direction) {
        MlExperiment experiment = new MlExperiment();
        experiment.setId(1L);
        experiment.setGlobalScore(globalScore);
        experiment.setPrimaryMetricValue(primaryValue);
        experiment.setDirection(direction);
        return experiment;
    }

    private RetrainingConfig configWithThresholds(Double classification, Double regression) {
        RetrainingConfig config = new RetrainingConfig();
        config.setProjectId(1L);
        config.setClassificationMinimumGlobalScore(classification);
        config.setRegressionMinimumGlobalScore(regression);
        return config;
    }

    // ─── CLASSIFICATION ──────────────────────────────────────────────

    @Test
    void classification_globalScoreAboveThreshold_eligible() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.90, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
        assertEquals("OK", evaluation.reason());
        assertEquals(0.75, evaluation.threshold());
    }

    @Test
    void classification_globalScoreExactlyThreshold_eligible() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.75, 0.80, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
    }

    @Test
    void classification_globalScoreBelowThreshold_rejected() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.74, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
        assertEquals("GLOBAL_SCORE_BELOW_THRESHOLD", evaluation.reason());
    }

    @Test
    void classification_globalScoreNull_rejected() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(null, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
    }

    // ─── REGRESSION ──────────────────────────────────────────────────

    @Test
    void regression_globalScoreAboveThreshold_eligible() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.82, 0.70, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
        assertEquals("OK", evaluation.reason());
    }

    @Test
    void regression_globalScoreExactlyThreshold_eligible() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.75, 0.80, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
    }

    @Test
    void regression_globalScoreBelowThreshold_rejected() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.70, 0.60, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
        assertEquals("GLOBAL_SCORE_BELOW_THRESHOLD", evaluation.reason());
    }

    @Test
    void regression_globalScoreNull_rejected() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(null, 0.60, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming());
    }

    // ─── ANTI-REGRESSION: raw metric must NOT be compared to threshold ─

    @Test
    void regression_rawRmseNotComparedToThreshold_eligible() {
        /*
         * Test anti-régression obligatoire :
         * projectType = REGRESSION
         * primaryMetric = RMSE
         * raw RMSE = 2.5
         * globalScore = 0.82
         * regressionMinimumGlobalScore = 0.75
         *
         * Résultat attendu : ELIGIBLE
         * car on ne fait PAS : 2.5 <= 0.75
         */
        MlProject project = project(1L, "regression", "rmse");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.82, 2.5, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming(),
                "ELIGIBLE: globalScore (0.82) >= threshold (0.75). "
                        + "Raw RMSE (2.5) must NOT be compared to threshold.");
        assertEquals("OK", evaluation.reason());
    }

    @Test
    void classification_rawAccuracyNotComparedToThreshold_eligible() {
        /*
         * Même logique pour classification :
         * raw accuracy = 0.55, mais globalScore = 0.90.
         * Le modèle est ELIGIBLE car le seuil concerne le globalScore.
         */
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.90, 0.55, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming());
    }

    // ─── DIFFERENTIATED THRESHOLDS ───────────────────────────────────

    @Test
    void classification_usesClassificationThreshold() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.80, 0.60);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.75, 0.90, "max");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertFalse(evaluation.conforming(),
                "globalScore (0.75) < classificationMinimumGlobalScore (0.80)");
        assertEquals(0.80, evaluation.threshold());
    }

    @Test
    void regression_usesRegressionThreshold() {
        MlProject project = project(1L, "regression", "mae");
        RetrainingConfig config = configWithThresholds(0.80, 0.60);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        MlExperiment experiment = experiment(0.70, 0.50, "min");

        ModelQualityService.QualityEvaluation evaluation = service.evaluate(project, experiment);

        assertNotNull(evaluation);
        assertTrue(evaluation.conforming(),
                "globalScore (0.70) >= regressionMinimumGlobalScore (0.60)");
        assertEquals(0.60, evaluation.threshold());
    }

    // ─── NO THRESHOLD CONFIGURED ─────────────────────────────────────

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
    void resolveThreshold_returnsClassificationThreshold() {
        RetrainingConfig config = configWithThresholds(0.80, 0.60);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        assertEquals(0.80, service.resolveThreshold(1L, "classification"));
    }

    @Test
    void resolveThreshold_returnsRegressionThreshold() {
        RetrainingConfig config = configWithThresholds(0.80, 0.60);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        assertEquals(0.60, service.resolveThreshold(1L, "regression"));
    }

    @Test
    void resolveThreshold_backwardCompatible() {
        RetrainingConfig config = configWithThresholds(0.80, 0.60);
        config.setGlobalScoreThreshold(0.85);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));

        assertEquals(0.85, service.resolveThreshold(1L));
    }

    // ─── ALERT TESTS ─────────────────────────────────────────────────

    @Test
    void raiseQualityAlertIfNeeded_conforming_noAlert() {
        MlProject project = project(1L, "classification", "accuracy");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
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
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(experiment(0.63, 0.63, "max")));

        com.mlops.platform.entity.MonitoringAlert raised = new com.mlops.platform.entity.MonitoringAlert();
        raised.setId(42L);
        when(monitoringAlertService.raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(raised);

        com.mlops.platform.entity.MonitoringAlert alert =
                service.raiseQualityAlertIfNeeded(project);

        assertNotNull(alert);
        assertEquals(42L, alert.getId());
    }

    @Test
    void raiseQualityAlertIfNeeded_usesStableIdempotencyKey() {
        MlProject project = project(1L, "classification", "accuracy");
        MlExperiment exp = experiment(0.63, 0.63, "max");
        exp.setId(99L);
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(exp));

        com.mlops.platform.entity.MonitoringAlert raised = new com.mlops.platform.entity.MonitoringAlert();
        raised.setId(1L);
        when(monitoringAlertService.raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(raised);

        service.raiseQualityAlertIfNeeded(project);

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(monitoringAlertService).raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), keyCaptor.capture(), any());

        String capturedKey = keyCaptor.getValue();
        assertEquals("MODEL_QUALITY_FAILED:1:exp:99", capturedKey);
    }

    @Test
    void raiseQualityAlertIfNeeded_messageContainsProjectNameAndPercent() {
        MlProject project = project(1L, "classification", "accuracy");
        project.setName("wind_speed_model");
        MlExperiment exp = experiment(0.7117, 0.80, "max");
        exp.setId(55L);
        exp.setRegisteredModelName("wind_speed_model");
        exp.setModelVersion("1");
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(exp));

        when(monitoringAlertService.raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        service.raiseQualityAlertIfNeeded(project);

        org.mockito.ArgumentCaptor<String> messageCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(monitoringAlertService).raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), messageCaptor.capture(), any(), any(), any());

        String message = messageCaptor.getValue();
        assertTrue(message.contains("wind_speed_model"),
                "Message should contain project name");
        assertTrue(message.contains("71.17"),
                "Message should contain score as percentage");
        assertTrue(message.contains("75.00"),
                "Message should contain threshold as percentage");
    }

    @Test
    void raiseQualityAlertIfNeeded_titleContainsProjectName() {
        MlProject project = project(1L, "classification", "accuracy");
        project.setName("weather_predictor");
        MlExperiment exp = experiment(0.50, 0.60, "max");
        exp.setId(77L);
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(exp));

        when(monitoringAlertService.raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        service.raiseQualityAlertIfNeeded(project);

        org.mockito.ArgumentCaptor<String> titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(monitoringAlertService).raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), titleCaptor.capture(), any(), any());

        String title = titleCaptor.getValue();
        assertTrue(title.contains("weather_predictor"),
                "Title should contain project name");
    }

    @Test
    void raiseQualityAlertIfNeeded_sameExperiment_calledTwice_onlyOneAlert() {
        MlProject project = project(1L, "classification", "accuracy");
        MlExperiment exp = experiment(0.60, 0.60, "max");
        exp.setId(88L);
        RetrainingConfig config = configWithThresholds(0.75, 0.75);
        when(retrainingConfigRepository.findByProjectId(1L)).thenReturn(Optional.of(config));
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(1L))
                .thenReturn(Optional.of(exp));

        when(monitoringAlertService.raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        service.raiseQualityAlertIfNeeded(project);
        service.raiseQualityAlertIfNeeded(project);

        verify(monitoringAlertService, times(2)).raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(monitoringAlertService, times(2)).raiseAlert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), keyCaptor.capture(), any());

        java.util.List<String> keys = keyCaptor.getAllValues();
        assertEquals(keys.get(0), keys.get(1),
                "Same experiment should produce the same idempotency key");
    }
}
