package com.mlops.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.PredictionLog;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.PredictionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de la décision centralisée de réentraînement.
 *
 * Architecture actuelle :
 * - stratégie automatique : MONITORING_SMART ;
 * - déclencheurs métier : PERFORMANCE_DROP et DATA_DRIFT ;
 * - les runs/prédictions en échec ne déclenchent pas de retraining ;
 * - le data drift utilise uniquement les prédictions réussies de
 *   l'expérience Production actuelle ;
 * - un drift extrême reste un DATA_DRIFT valide et déclenche le retraining ;
 * - la demande manuelle et l'absence de source Production restent gérées.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrainingDecisionServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long PRODUCTION_EXPERIMENT_ID = 100L;

    @Mock
    private PredictionLogRepository predictionLogRepository;

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    private RetrainingDecisionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RetrainingDecisionService(
                predictionLogRepository,
                mlExperimentRepository,
                objectMapper
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC());
    }

    private RetrainingConfig config() {
        RetrainingConfig config = new RetrainingConfig();
        config.setProjectId(PROJECT_ID);
        config.setEnabled(true);
        config.setStrategy(RetrainingStrategy.MONITORING_SMART);
        return config;
    }

    /**
     * Configure une Production stable :
     * - aucune baisse de performance ;
     * - premières N et dernières N prédictions identiques ;
     * - donc aucun data drift.
     */
    private void mockStableMonitoring(long recentPredictionCount, int minPredictions) {
        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.80
        );

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(recentPredictionCount);

        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(0L);

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(production));

        int required = Math.max(2, minPredictions * 2);

        when(predictionLogRepository.findSuccessfulPredictionsForProduction(
                PROJECT_ID,
                PRODUCTION_EXPERIMENT_ID
        )).thenReturn(repeatedPredictions(required, "1.0"));
    }

    private MlExperiment productionExperiment(
            Long id,
            String registeredModelName,
            double globalScore
    ) {
        MlExperiment experiment = mock(MlExperiment.class);

        when(experiment.getId()).thenReturn(id);
        when(experiment.getRegisteredModelName()).thenReturn(registeredModelName);
        when(experiment.getGlobalScore()).thenReturn(globalScore);

        return experiment;
    }

    // ------------------------------------------------------------------
    // MONITORING_SMART - aucun signal
    // ------------------------------------------------------------------

    @Test
    void monitoringSmart_noSignal_returnsSkip() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);
        config.setPerformanceDropThresholdPercent(5.0);
        config.setDataDriftThresholdPercent(10.0);

        mockStableMonitoring(10L, 5);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
        assertEquals(RetrainingSecurityLevel.NONE, decision.getSecurityLevel());
    }

    @Test
    void monitoringSmart_withoutPredictions_returnsSkip() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(0L);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("INSUFFICIENT_DATA"));
    }

    @Test
    void monitoringSmart_enoughStablePredictions_returnsMonitoringOk() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);
        config.setDataDriftThresholdPercent(10.0);

        mockStableMonitoring(100L, 30);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void monitoringSmart_insufficientPredictions_returnsInsufficientData() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(5L);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("INSUFFICIENT_DATA"));
    }

    // ------------------------------------------------------------------
    // Les erreurs de prédiction ne déclenchent plus le retraining
    // ------------------------------------------------------------------

    @Test
    void failedPredictions_doNotTriggerRetraining() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        mockStableMonitoring(40L, 30);

        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(10L);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
        assertEquals(RetrainingSecurityLevel.NONE, decision.getSecurityLevel());
    }

    @Test
    void highPredictionFailureRate_doesNotTriggerRetraining() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        mockStableMonitoring(40L, 30);

        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(20L);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertEquals(RetrainingSecurityLevel.NONE, decision.getSecurityLevel());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void veryHighPredictionFailureRate_doesNotBlockModel() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        mockStableMonitoring(40L, 30);

        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(30L);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertEquals(RetrainingSecurityLevel.NONE, decision.getSecurityLevel());
    }

    // ------------------------------------------------------------------
    // Baseline Production du data drift
    // ------------------------------------------------------------------

    @Test
    void monitoringSmart_usesProductionPredictionWindow() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);
        config.setDataDriftThresholdPercent(10.0);

        mockStableMonitoring(17L, 5);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
        assertEquals(0.0,
                decision.getObservedMetrics().get("dataDriftScorePercent"),
                0.001);
    }

    @Test
    void productionWindow_notEnoughSuccessfulPredictions_skips() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);
        config.setDataDriftThresholdPercent(10.0);

        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.80
        );

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(17L);

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(production));

        // Il faut 10 prédictions réussies pour baseline=5 + recent=5.
        when(predictionLogRepository.findSuccessfulPredictionsForProduction(
                PROJECT_ID,
                PRODUCTION_EXPERIMENT_ID
        )).thenReturn(repeatedPredictions(9, "1.0"));

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("INSUFFICIENT_DRIFT_DATA"));
    }

    // ------------------------------------------------------------------
    // DATA_DRIFT
    // ------------------------------------------------------------------

    @Test
    void dataDrift_detected_returnsRun() {
        RetrainingConfig config = config();
        config.setDataDriftMonitoringEnabled(true);
        config.setPerformanceMonitoringEnabled(false);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(3);
        config.setDataDriftThresholdPercent(25.0);

        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.80
        );

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(production));

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(6L);

        List<PredictionLog> productionPredictions = new ArrayList<>();
        productionPredictions.addAll(predictions("1.0", "1.0", "1.0"));
        productionPredictions.addAll(predictions("1.3", "1.3", "1.3"));

        when(predictionLogRepository.findSuccessfulPredictionsForProduction(
                PROJECT_ID,
                PRODUCTION_EXPERIMENT_ID
        )).thenReturn(productionPredictions);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.DATA_DRIFT, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("DATA_DRIFT"));
        assertFalse(decision.getReasonCodes().contains("DRIFT_EXTREME"));
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
        assertEquals(30.0,
                decision.getObservedMetrics().get("dataDriftScorePercent"),
                0.001);
    }

    @Test
    void dataDrift_extreme_stillTriggersRetraining() {
        RetrainingConfig config = config();
        config.setDataDriftMonitoringEnabled(true);
        config.setPerformanceMonitoringEnabled(false);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(3);
        config.setDataDriftThresholdPercent(25.0);

        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.80
        );

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(production));

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(6L);

        List<PredictionLog> productionPredictions = new ArrayList<>();
        productionPredictions.addAll(predictions("1.0", "1.0", "1.0"));
        productionPredictions.addAll(predictions("100.0", "100.0", "100.0"));

        when(predictionLogRepository.findSuccessfulPredictionsForProduction(
                PROJECT_ID,
                PRODUCTION_EXPERIMENT_ID
        )).thenReturn(productionPredictions);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.DATA_DRIFT, decision.getTriggerType());
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
        assertEquals(RetrainingSeverity.CRITICAL, decision.getSeverity());
        assertTrue(decision.getReasonCodes().contains("DATA_DRIFT"));
        assertTrue(decision.getReasonCodes().contains("DRIFT_EXTREME"));
        assertFalse(decision.getReasonCodes().contains("BLOCKED_FOR_SECURITY"));
    }

    // ------------------------------------------------------------------
    // PERFORMANCE_DROP
    // ------------------------------------------------------------------

    @Test
    void performanceDrop_detected_returnsRun() {
        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.60
        );

        MlExperiment bestCandidate = productionExperiment(
                101L,
                "model-a",
                0.80
        );

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(40L);

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(bestCandidate));

        RetrainingConfig config = config();
        config.setPerformanceMonitoringEnabled(true);
        config.setDataDriftMonitoringEnabled(false);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(5.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.PERFORMANCE_DROP, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("PERFORMANCE_DROP"));
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
    }

    @Test
    void performanceDrop_belowThreshold_skips() {
        MlExperiment production = productionExperiment(
                PRODUCTION_EXPERIMENT_ID,
                "model-a",
                0.78
        );

        MlExperiment bestCandidate = productionExperiment(
                101L,
                "model-a",
                0.80
        );

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(40L);

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.of(production));

        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(bestCandidate));

        when(predictionLogRepository.findSuccessfulPredictionsForProduction(
                PROJECT_ID,
                PRODUCTION_EXPERIMENT_ID
        )).thenReturn(repeatedPredictions(60, "1.0"));

        RetrainingConfig config = config();
        config.setPerformanceMonitoringEnabled(true);
        config.setDataDriftMonitoringEnabled(true);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(10.0);
        config.setDataDriftThresholdPercent(10.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void performanceDrop_noProductionModel_noRetrain() {
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(
                eq(PROJECT_ID),
                any()
        )).thenReturn(40L);

        when(mlExperimentRepository.findCurrentProductionExperimentForProject(PROJECT_ID))
                .thenReturn(Optional.empty());

        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(5.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("INSUFFICIENT_DRIFT_DATA"));
    }

    // ------------------------------------------------------------------
    // Autres cas MONITORING_SMART
    // ------------------------------------------------------------------

    @Test
    void monitoringSmart_stableWindow_doesNotRetrain() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);
        config.setDataDriftThresholdPercent(10.0);

        mockStableMonitoring(20L, 5);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void monitoringSmart_doesNotUseLegacyScheduleAsTrigger() {
        RetrainingConfig config = config();
        config.setMinPredictionsForMonitoring(5);

        mockStableMonitoring(10L, 5);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertNotEquals(RetrainingTriggerType.MANUAL, decision.getTriggerType());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
    }

    // ------------------------------------------------------------------
    // Demande manuelle / source absente
    // ------------------------------------------------------------------

    @Test
    void manualRequest_alwaysRunsEvenWithoutSource() {
        RetrainingConfig config = config();
        config.setManualTriggerRequested(true);

        RetrainingDecision decision =
                service.buildDecision(config, now(), false);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.MANUAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MANUAL_REQUEST"));
    }

    @Test
    void noValidSource_defersRetraining() {
        RetrainingConfig config = config();

        RetrainingDecision decision =
                service.buildDecision(config, now(), false);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("NO_VALID_SOURCE"));
        assertTrue(decision.getReasonCodes().contains("DEFERRED"));
        assertEquals(
                RetrainingSecurityLevel.SECURITY_ALERT_ONLY,
                decision.getSecurityLevel()
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<PredictionLog> predictions(String... featureValues) {
        List<PredictionLog> logs = new ArrayList<>();

        for (String value : featureValues) {
            PredictionLog log = new PredictionLog();
            log.setProjectId(PROJECT_ID);
            log.setInputJson("{\"feature1\": " + value + "}");
            log.setSuccess(true);
            logs.add(log);
        }

        return logs;
    }

    private List<PredictionLog> repeatedPredictions(
            int count,
            String featureValue
    ) {
        List<PredictionLog> logs = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            logs.addAll(predictions(featureValue));
        }

        return logs;
    }
}
