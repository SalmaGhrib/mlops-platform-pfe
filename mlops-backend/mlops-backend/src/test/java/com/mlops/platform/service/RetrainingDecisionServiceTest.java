package com.mlops.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.PredictionLog;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
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
import static org.mockito.Mockito.when;

/*
 * Tests unitaires de la décision centralisée de réentraînement
 * (Mockito pur, sans Spring, sans base de données).
 *
 * Vérifie :
 *  1. les 4 stratégies (SCHEDULED_ONLY, SCHEDULED_WITH_MONITORING,
 *     SMART_MONITORING_ONLY, FULL_SMART) ;
 *  2. la sécurité : jamais de réentraînement sur des données suspectes
 *     (SECURITY_ALERT_ONLY / BLOCK_MODEL) ;
 *  3. la demande manuelle et l'absence de source Production.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrainingDecisionServiceTest {

    @Mock
    private PredictionLogRepository predictionLogRepository;

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    private RetrainingDecisionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long PROJECT_ID = 1L;

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

    private RetrainingConfig config(RetrainingStrategy strategy) {
        RetrainingConfig config = new RetrainingConfig();
        config.setProjectId(PROJECT_ID);
        config.setEnabled(true);
        config.setStrategy(strategy);
        return config;
    }

    private RetrainingConfig dueConfig(RetrainingStrategy strategy) {
        RetrainingConfig config = config(strategy);
        config.setNextRetrainingAt(now().minusMinutes(5));
        return config;
    }

    private void noMonitoringSignals() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(100L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);
    }

    // ------------------------------------------------------------------
    // SCHEDULED_ONLY
    // ------------------------------------------------------------------

    @Test
    void scheduledOnly_due_returnsRunWithScheduledTrigger() {
        RetrainingConfig config = dueConfig(RetrainingStrategy.SCHEDULED_ONLY);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SCHEDULED, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("SCHEDULE_DUE"));
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
    }

    @Test
    void scheduledOnly_notDue_returnsSkip() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().plusDays(7));

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
    }

    // ------------------------------------------------------------------
    // SCHEDULED_WITH_MONITORING
    // ------------------------------------------------------------------

    @Test
    void scheduledWithMonitoring_due_noSignal_enoughPredictions_skips() {
        noMonitoringSignals();
        RetrainingConfig config = dueConfig(RetrainingStrategy.SCHEDULED_WITH_MONITORING);
        config.setMinPredictionsForMonitoring(30);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void scheduledWithMonitoring_due_insufficientPredictions_runsScheduled() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(5L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);

        RetrainingConfig config = dueConfig(RetrainingStrategy.SCHEDULED_WITH_MONITORING);
        config.setMinPredictionsForMonitoring(30);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SCHEDULED, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("INSUFFICIENT_DATA"));
    }

    @Test
    void scheduledWithMonitoring_due_highErrorRate_runsPredictionErrors() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(10L);

        RetrainingConfig config = dueConfig(RetrainingStrategy.SCHEDULED_WITH_MONITORING);
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.PREDICTION_ERRORS, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("PREDICTION_ERRORS"));
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
    }

    // ------------------------------------------------------------------
    // SMART_MONITORING_ONLY
    // ------------------------------------------------------------------

    @Test
    void smartMonitoringOnly_dueButNoSignal_skips() {
        noMonitoringSignals();
        RetrainingConfig config = dueConfig(RetrainingStrategy.SMART_MONITORING_ONLY);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    @Test
    void smartMonitoringOnly_failedRuns_alertOnly_noRetrain() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(2L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);

        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setNextRetrainingAt(now().plusDays(7));

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        /*
         * Les runs échoués sont un signal technique : alerte uniquement.
         * Aucun réentraînement ne doit être déclenché.
         */
        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("FAILED_RUNS_TECHNICAL"));
        assertEquals(2.0, decision.getObservedMetrics().get("recentFailedRuns"), 0.001);
    }

    // ------------------------------------------------------------------
    // FULL_SMART
    // ------------------------------------------------------------------

    @Test
    void fullSmart_noSignal_due_runsScheduled() {
        noMonitoringSignals();
        RetrainingConfig config = dueConfig(RetrainingStrategy.FULL_SMART);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SCHEDULED, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("SCHEDULE_DUE"));
    }

    @Test
    void fullSmart_noSignal_notDue_skips() {
        noMonitoringSignals();
        RetrainingConfig config = config(RetrainingStrategy.FULL_SMART);
        config.setNextRetrainingAt(now().plusDays(7));

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("MONITORING_OK"));
    }

    // ------------------------------------------------------------------
    // Data drift
    // ------------------------------------------------------------------

    @Test
    void dataDrift_detected_returnsRun() {
        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setDataDriftMonitoringEnabled(true);
        config.setPerformanceMonitoringEnabled(false);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(3);
        config.setDataDriftThresholdPercent(25.0);

        List<PredictionLog> baseline = predictions("1.0", "1.0", "1.0", "1.0", "1.0");
        List<PredictionLog> recent = predictions("1.3", "1.3", "1.3", "1.3", "1.3");

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(5L);
        when(predictionLogRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                eq(PROJECT_ID), any(), any())).thenReturn(baseline, recent);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.DATA_DRIFT, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("DATA_DRIFT"));
        assertEquals(RetrainingSecurityLevel.RETRAIN_RECOMMENDED, decision.getSecurityLevel());
    }

    @Test
    void dataDrift_extreme_blocksRetraining() {
        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setDataDriftMonitoringEnabled(true);
        config.setPerformanceMonitoringEnabled(false);
        config.setFailureMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(3);
        config.setDataDriftThresholdPercent(25.0);

        List<PredictionLog> baseline = predictions("1.0", "1.0", "1.0", "1.0", "1.0");
        List<PredictionLog> recent = predictions("100.0", "100.0", "100.0", "100.0", "100.0");

        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(5L);
        when(predictionLogRepository.findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                eq(PROJECT_ID), any(), any())).thenReturn(baseline, recent);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.BLOCKED_BY_SECURITY, decision.getTriggerType());
        assertEquals(RetrainingSecurityLevel.BLOCK_MODEL, decision.getSecurityLevel());
        assertTrue(decision.getReasonCodes().contains("SUSPECT_DATA"));
        assertTrue(decision.getReasonCodes().contains("BLOCKED_FOR_SECURITY"));
    }

    // ------------------------------------------------------------------
    // Sécurité : taux d'erreur extrême
    // ------------------------------------------------------------------

    @Test
    void errorRate_extreme_alertsAndSkips() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(20L);

        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_WITH_MONITORING);
        config.setNextRetrainingAt(now().minusMinutes(5));
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_SUSPECT_DATA, decision.getTriggerType());
        assertEquals(RetrainingSecurityLevel.SECURITY_ALERT_ONLY, decision.getSecurityLevel());
        assertTrue(decision.getReasonCodes().contains("SUSPECT_DATA"));
    }

    @Test
    void errorRate_blocked_marksBlockModel() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(30L);

        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setMinPredictionsForMonitoring(30);
        config.setMaxPredictionErrorRatePercent(20.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingSecurityLevel.BLOCK_MODEL, decision.getSecurityLevel());
        assertEquals(RetrainingTriggerType.BLOCKED_BY_SECURITY, decision.getTriggerType());
    }

    // ------------------------------------------------------------------
    // Baisse de performance (PERFORMANCE_DROP)
    // ------------------------------------------------------------------

    @Test
    void performanceDrop_detected_returnsRun() {
        MlExperiment production = new MlExperiment();
        production.setRegisteredModelName("model-a");
        production.setGlobalScore(0.60);

        MlExperiment bestCandidate = new MlExperiment();
        bestCandidate.setGlobalScore(0.80);

        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(eq(PROJECT_ID)))
                .thenReturn(Optional.of(production));
        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(bestCandidate));

        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setFailureMonitoringEnabled(false);
        config.setPerformanceMonitoringEnabled(true);
        config.setDataDriftMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(5.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.PERFORMANCE_DROP, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("PERFORMANCE_DROP"));
    }

    @Test
    void performanceDrop_belowThreshold_skips() {
        MlExperiment production = new MlExperiment();
        production.setRegisteredModelName("model-a");
        production.setGlobalScore(0.78);

        MlExperiment bestCandidate = new MlExperiment();
        bestCandidate.setGlobalScore(0.80);

        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(eq(PROJECT_ID)))
                .thenReturn(Optional.of(production));
        when(mlExperimentRepository.findEligibleProductionCandidates("model-a"))
                .thenReturn(List.of(bestCandidate));

        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setFailureMonitoringEnabled(false);
        config.setPerformanceMonitoringEnabled(true);
        config.setDataDriftMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(10.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
    }

    @Test
    void performanceDrop_noProductionModel_noRetrain() {
        when(mlExperimentRepository.countFailedRunsForProjectSince(eq(PROJECT_ID), any(Integer.class)))
                .thenReturn(0L);
        when(predictionLogRepository.countByProjectIdAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(40L);
        when(predictionLogRepository.countByProjectIdAndSuccessFalseAndCreatedAtAfter(eq(PROJECT_ID), any()))
                .thenReturn(0L);
        when(mlExperimentRepository.findCurrentProductionExperimentForProject(eq(PROJECT_ID)))
                .thenReturn(Optional.empty());

        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setFailureMonitoringEnabled(false);
        config.setPerformanceMonitoringEnabled(true);
        config.setDataDriftMonitoringEnabled(false);
        config.setMinPredictionsForMonitoring(30);
        config.setPerformanceDropThresholdPercent(5.0);

        RetrainingDecision decision = service.buildDecision(config, now(), true);

        assertFalse(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.SKIPPED_NO_SIGNAL, decision.getTriggerType());
    }

    // ------------------------------------------------------------------
    // Demande manuelle / source absente
    // ------------------------------------------------------------------

    @Test
    void manualRequest_alwaysRunsEvenWithoutSource() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setManualTriggerRequested(true);

        RetrainingDecision decision = service.buildDecision(config, now(), false);

        assertTrue(decision.isShouldRetrain());
        assertEquals(RetrainingTriggerType.MANUAL, decision.getTriggerType());
        assertTrue(decision.getReasonCodes().contains("MANUAL_REQUEST"));
    }

    @Test
    void noValidSource_defersEvenWhenDue() {
        RetrainingConfig config = dueConfig(RetrainingStrategy.SCHEDULED_ONLY);

        RetrainingDecision decision = service.buildDecision(config, now(), false);

        assertFalse(decision.isShouldRetrain());
        assertTrue(decision.getReasonCodes().contains("NO_VALID_SOURCE"));
        assertTrue(decision.getReasonCodes().contains("DEFERRED"));
        assertEquals(RetrainingSecurityLevel.SECURITY_ALERT_ONLY, decision.getSecurityLevel());
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
}
