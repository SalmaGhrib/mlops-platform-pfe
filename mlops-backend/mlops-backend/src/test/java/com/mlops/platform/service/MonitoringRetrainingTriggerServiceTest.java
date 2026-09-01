package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.*;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringRetrainingTriggerServiceTest {

    @Mock private RetrainingConfigRepository retrainingConfigRepository;
    @Mock private MlExperimentRepository mlExperimentRepository;
    @Mock private RetrainingDecisionService retrainingDecisionService;
    @Mock private RetrainingSourceResolverService sourceResolver;
    @Mock private AutoRetrainingService autoRetrainingService;

    private MonitoringRetrainingTriggerService service;

    private static final Long PROJECT_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new MonitoringRetrainingTriggerService(
                retrainingConfigRepository,
                mlExperimentRepository,
                retrainingDecisionService,
                sourceResolver,
                autoRetrainingService,
                null
        );
        ReflectionTestUtils.setField(service, "cooldownMinutes", 120);
    }

    private RetrainingConfig config(boolean enabled, RetrainingState state) {
        RetrainingConfig c = new RetrainingConfig();
        ReflectionTestUtils.setField(c, "id", 1L);
        c.setProjectId(PROJECT_ID);
        c.setEnabled(enabled);
        c.setState(state);
        c.setStrategy(RetrainingStrategy.MONITORING_SMART);
        c.setLastRetrainingAt(LocalDateTime.now().minusHours(5));
        return c;
    }

    @Test
    void trigger_nullProjectId_returnsFalse() {
        assertFalse(service.triggerRetrainingFromMonitoringAlert(null, "test"));
    }

    @Test
    void trigger_noConfig_returnsFalse() {
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.empty());
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_disabledConfig_returnsFalse() {
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config(false, null)));
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_runningConfig_returnsFalse() {
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config(true, RetrainingState.RUNNING)));
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_manualTriggerPending_returnsFalse() {
        RetrainingConfig c = config(true, null);
        c.setManualTriggerRequested(true);
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(c));
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_activeRetrainingExists_returnsFalse() {
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config(true, null)));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(1L);
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_inCooldown_returnsFalse() {
        RetrainingConfig c = config(true, null);
        c.setLastRetrainingAt(LocalDateTime.now().minusMinutes(30));
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(c));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_claimFails_returnsFalse() {
        RetrainingConfig c = config(true, null);
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(c));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(autoRetrainingService.tryValidateAndClaimInNewTransaction(c.getId()))
                .thenReturn(0);
        assertFalse(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
    }

    @Test
    void trigger_claimSucceeds_returnsTrue() {
        RetrainingConfig c = config(true, null);
        when(retrainingConfigRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(c));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(autoRetrainingService.tryValidateAndClaimInNewTransaction(c.getId()))
                .thenReturn(1);

        assertTrue(service.triggerRetrainingFromMonitoringAlert(PROJECT_ID, "drift"));
        verify(autoRetrainingService).processClaimedConfig(c.getId());
    }

    @Test
    void evaluate_noConfigs_returnsZero() {
        when(retrainingConfigRepository.findEnabledNotRunningConfigs())
                .thenReturn(List.of());
        assertEquals(0, service.evaluateSmartTriggersForAllConfigs());
    }

    @Test
    void evaluate_configWithNullProjectId_skipped() {
        RetrainingConfig c = config(true, null);
        c.setProjectId(null);
        when(retrainingConfigRepository.findEnabledNotRunningConfigs()).thenReturn(List.of(c));
        assertEquals(0, service.evaluateSmartTriggersForAllConfigs());
    }

    @Test
    void evaluate_decisionSaysNoRetrain_skipped() {
        RetrainingConfig c = config(true, null);
        when(retrainingConfigRepository.findEnabledNotRunningConfigs()).thenReturn(List.of(c));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(1L);
        when(retrainingDecisionService.buildDecision(any(), any(), eq(true)))
                .thenReturn(RetrainingDecision.skip(RetrainingSeverity.NONE, "ok"));
        assertEquals(0, service.evaluateSmartTriggersForAllConfigs());
    }

    @Test
    void evaluate_decisionSaysRetrain_claimsAndProcesses() {
        RetrainingConfig c = config(true, null);
        when(retrainingConfigRepository.findEnabledNotRunningConfigs()).thenReturn(List.of(c));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(1L);
        when(retrainingDecisionService.buildDecision(any(), any(), eq(true)))
                .thenReturn(RetrainingDecision.run(
                        RetrainingTriggerType.DATA_DRIFT,
                        RetrainingSeverity.HIGH, "drift"));
        when(autoRetrainingService.tryValidateAndClaimInNewTransaction(c.getId()))
                .thenReturn(1);

        assertEquals(1, service.evaluateSmartTriggersForAllConfigs());
        verify(autoRetrainingService).processClaimedConfig(c.getId());
    }

    @Test
    void evaluate_multipleConfigs_onlyRetrainOnesProcessed() {
        RetrainingConfig c1 = config(true, null);
        ReflectionTestUtils.setField(c1, "id", 1L);
        RetrainingConfig c2 = config(true, null);
        ReflectionTestUtils.setField(c2, "id", 2L);

        when(retrainingConfigRepository.findEnabledNotRunningConfigs())
                .thenReturn(List.of(c1, c2));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(1L);

        when(retrainingDecisionService.buildDecision(eq(c1), any(), eq(true)))
                .thenReturn(RetrainingDecision.run(
                        RetrainingTriggerType.DATA_DRIFT,
                        RetrainingSeverity.HIGH, "drift"));
        when(retrainingDecisionService.buildDecision(eq(c2), any(), eq(true)))
                .thenReturn(RetrainingDecision.skip(RetrainingSeverity.NONE, "ok"));

        when(autoRetrainingService.tryValidateAndClaimInNewTransaction(1L))
                .thenReturn(1);

        assertEquals(1, service.evaluateSmartTriggersForAllConfigs());
    }
}
