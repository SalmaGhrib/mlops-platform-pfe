package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.dto.RetrainingDryRunResponse;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingState;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du diagnostic DRY-RUN global.
 *
 * Vérifie la classification lecture seule de chaque configuration
 * (WOULD_RUN / WOULD_SKIP / NO_VALID_SOURCE / BLOCKED_BY_SECURITY /
 * INVALID_CONFIG / NOT_DUE) et qu'aucune écriture n'est déclenchée.
 */
@ExtendWith(MockitoExtension.class)
class RetrainingDryRunServiceTest {

    @Mock
    private RetrainingConfigRepository retrainingConfigRepository;

    @Mock
    private RetrainingDecisionService retrainingDecisionService;

    @Mock
    private RetrainingSourceResolverService sourceResolver;

    private RetrainingDryRunService service;

    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new RetrainingDryRunService(
                retrainingConfigRepository,
                retrainingDecisionService,
                sourceResolver
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
        config.setState(RetrainingState.IDLE);
        return config;
    }

    private RetrainingDecision skipDecision(String... reasonCodes) {
        RetrainingDecision decision = RetrainingDecision.skip(
                RetrainingSeverity.NONE,
                "Raison de test."
        );
        for (String code : reasonCodes) {
            decision.addReasonCode(code);
        }
        return decision;
    }

    @Test
    void diagnose_classifiesWouldRunConfig() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(anyLong(), any()))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(RetrainingDecision.run(
                        RetrainingTriggerType.SCHEDULED,
                        RetrainingSeverity.MEDIUM,
                        "Échéance atteinte."
                ));

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getConfigs().size());
        assertEquals(1, response.getWouldRun());
        RetrainingDryRunResponse.ConfigDryRun entry = response.getConfigs().get(0);
        assertEquals(RetrainingDryRunResponse.RecommendedAction.WOULD_RUN, entry.getAction());
        assertTrue(entry.isWouldRetrain());
        assertTrue(entry.isSourceReady());
    }

    @Test
    void diagnose_classifiesNoValidSource() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(anyLong(), any()))
                .thenReturn(null);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(skipDecision("NO_VALID_SOURCE", "DEFERRED"));

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getNoSource());
        RetrainingDryRunResponse.ConfigDryRun entry = response.getConfigs().get(0);
        assertEquals(
                RetrainingDryRunResponse.RecommendedAction.NO_VALID_SOURCE,
                entry.getAction()
        );
        assertFalse(entry.isSourceReady());
    }

    @Test
    void diagnose_classifiesWouldSkip() {
        RetrainingConfig config = config(RetrainingStrategy.SMART_MONITORING_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(anyLong(), any()))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(skipDecision("MONITORING_OK"));

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getWouldSkip());
        assertEquals(
                RetrainingDryRunResponse.RecommendedAction.WOULD_SKIP,
                response.getConfigs().get(0).getAction()
        );
    }

    @Test
    void diagnose_classifiesBlockedBySecurity() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(anyLong(), any()))
                .thenReturn(42L);

        RetrainingDecision blocked = RetrainingDecision.run(
                RetrainingTriggerType.SCHEDULED,
                RetrainingSeverity.CRITICAL,
                "Données suspectes."
        );
        blocked.setSecurityLevel(RetrainingSecurityLevel.BLOCK_MODEL);
        blocked.addReasonCode("SUSPECT_DATA");
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(blocked);

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getBlockedBySecurity());
        assertEquals(
                RetrainingDryRunResponse.RecommendedAction.BLOCKED_BY_SECURITY,
                response.getConfigs().get(0).getAction()
        );
    }

    @Test
    void diagnose_classifiesNotDue() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().plusDays(7));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getNotDue());
        assertEquals(
                RetrainingDryRunResponse.RecommendedAction.NOT_DUE,
                response.getConfigs().get(0).getAction()
        );
    }

    @Test
    void diagnose_classifiesRunningStateAsNotDue() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));
        config.setState(RetrainingState.RUNNING);

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));

        RetrainingDryRunResponse response = service.diagnose();

        assertEquals(1, response.getNotDue());
        assertEquals(
                RetrainingDryRunResponse.RecommendedAction.NOT_DUE,
                response.getConfigs().get(0).getAction()
        );
    }

    @Test
    void diagnose_performsNoWrites() {
        RetrainingConfig config = config(RetrainingStrategy.SCHEDULED_ONLY);
        config.setNextRetrainingAt(now().minusMinutes(5));

        when(retrainingConfigRepository.findEnabledConfigs())
                .thenReturn(List.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(anyLong(), any()))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(RetrainingDecision.skip(
                        RetrainingSeverity.NONE,
                        "Aucun signal."
                ));

        service.diagnose();

        verify(retrainingConfigRepository, never()).save(any());
        verify(retrainingConfigRepository, never()).saveAndFlush(any());
        verify(retrainingConfigRepository, never()).delete(any());
    }
}
