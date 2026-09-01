package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingState;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du service de réentraînement automatique
 * (Mockito pur, sans Spring, sans base de données).
 *
 * Vérifie :
 *  1. le claim atomique avec source valide ;
 *  2. le report d'échéance quand la source est invalide (bug latent corrigé :
 *     une configuration échue ne doit plus rester bloquée pour toujours) ;
 *  3. l'absence de claim quand la configuration n'est pas échue ;
 *  4. la transition COOLDOWN après dépassement du seuil d'échecs consécutifs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoRetrainingServiceTest {

    @Mock
    private RetrainingConfigRepository retrainingConfigRepository;

    @Mock
    private MlExperimentService mlExperimentService;

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    @Mock
    private MlProjectRepository mlProjectRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RetrainingDecisionService retrainingDecisionService;

    @Mock
    private RetrainingSourceResolverService sourceResolver;

    @Mock
    private KubernetesJobService kubernetesJobService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private GitHubFileFetcherService gitHubFileFetcherService;

    private AutoRetrainingService service;

    private static final Long CONFIG_ID = 1L;
    private static final Long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new AutoRetrainingService(
                retrainingConfigRepository,
                mlExperimentService,
                mlExperimentRepository,
                mlProjectRepository,
                notificationService,
                retrainingDecisionService,
                sourceResolver,
                kubernetesJobService,
                transactionManager,
                gitHubFileFetcherService
        );

        ReflectionTestUtils.setField(service, "maxConsecutiveFailures", 3);
        ReflectionTestUtils.setField(service, "cooldownHours", 24L);

        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC());
    }

    private RetrainingConfig dueConfig() {
        RetrainingConfig config = new RetrainingConfig();
        config.setProjectId(PROJECT_ID);
        config.setEnabled(true);
        config.setStrategy(RetrainingStrategy.MONITORING_SMART);
        config.setNextRetrainingAt(now().minusMinutes(5));
        return config;
    }

    // ------------------------------------------------------------------
    // tryValidateAndClaimInNewTransaction
    // ------------------------------------------------------------------

    @Test
    void validateAndClaim_withValidSource_claims() {
        RetrainingConfig config = dueConfig();
        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(42L);
        when(retrainingConfigRepository.claimDueConfig(eq(CONFIG_ID), any()))
                .thenReturn(1);

        config.setManualTriggerRequested(true);
        int claimed = service.tryValidateAndClaimInNewTransaction(CONFIG_ID);

        assertEquals(1, claimed);
        verify(retrainingConfigRepository).claimDueConfig(eq(CONFIG_ID), any());
        verify(retrainingConfigRepository, never())
                .updateStateForUnclaimedConfig(any(), any(), any(), any(), any());
    }

    @Test
    void validateAndClaim_withInvalidSource_advancesScheduleAndSkipsClaim() {
        RetrainingConfig config = dueConfig();
        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(null);

        int claimed = service.tryValidateAndClaimInNewTransaction(CONFIG_ID);

        assertEquals(0, claimed);
        verify(retrainingConfigRepository).findById(CONFIG_ID);
        verify(retrainingConfigRepository, never()).claimDueConfig(any(), any());
    }

    @Test
    void validateAndClaim_whenNotDue_claimsBecauseScheduleRemoved() {
        RetrainingConfig config = dueConfig();
        config.setNextRetrainingAt(now().plusDays(7));

        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(42L);
        when(retrainingConfigRepository.claimDueConfig(eq(CONFIG_ID), any()))
                .thenReturn(1);

        int claimed = service.tryValidateAndClaimInNewTransaction(CONFIG_ID);

        assertEquals(1, claimed);
        verify(retrainingConfigRepository).claimDueConfig(eq(CONFIG_ID), any());
    }

    @Test
    void validateAndClaim_whenConfigMissing_returnsZero() {
        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.empty());

        int claimed = service.tryValidateAndClaimInNewTransaction(CONFIG_ID);

        assertEquals(0, claimed);
        verify(retrainingConfigRepository, never()).claimDueConfig(any(), any());
    }

    // ------------------------------------------------------------------
    // processClaimedConfig -> cooldown
    // ------------------------------------------------------------------

    @Test
    void processClaimedConfig_afterConsecutiveFailures_entersCooldown() {
        RetrainingConfig config = dueConfig();
        config.setRunning(true);
        config.setConsecutiveFailures(3);
        config.setState(RetrainingState.EVALUATING);

        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(RetrainingDecision.run(
                        RetrainingTriggerType.SCHEDULED,
                        RetrainingSeverity.MEDIUM,
                        "Échéance atteinte."
                ));
        when(sourceResolver.resolveAndSynchronize(PROJECT_ID, null))
                .thenReturn(null);
        when(retrainingConfigRepository.releaseAfterCooldown(
                eq(CONFIG_ID), any(), any(), any()))
                .thenReturn(1);

        service.processClaimedConfig(CONFIG_ID);

        verify(retrainingConfigRepository).releaseAfterCooldown(
                eq(CONFIG_ID), any(), any(), any()
        );
        verify(retrainingConfigRepository, never())
                .releaseAfterFailure(any(), any(), any());
    }

    @Test
    void processClaimedConfig_whenNotBlockedAndSourceOk_launches() {
        RetrainingConfig config = dueConfig();
        config.setRunning(true);
        config.setState(RetrainingState.EVALUATING);

        MlExperiment source = new MlExperiment();
        source.setId(42L);
        source.setModelVersion("7");

        MlProject project = new MlProject();
        project.setId(PROJECT_ID);

        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(RetrainingDecision.run(
                        RetrainingTriggerType.SCHEDULED,
                        RetrainingSeverity.MEDIUM,
                        "Échéance atteinte."
                ));
        when(sourceResolver.resolveAndSynchronize(PROJECT_ID, null))
                .thenReturn(source);

        com.mlops.platform.dto.MlExperimentResponse response =
                new com.mlops.platform.dto.MlExperimentResponse();
        response.setId(99L);
        response.setStatus("CREATED");

        when(mlExperimentService.retrainFromCurrentProductionExperiment(
                PROJECT_ID, 42L)).thenReturn(response);
        when(mlProjectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(retrainingConfigRepository.releaseAfterSuccess(
                eq(CONFIG_ID), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.processClaimedConfig(CONFIG_ID);

        verify(mlExperimentService).retrainFromCurrentProductionExperiment(
                PROJECT_ID, 42L
        );
        verify(retrainingConfigRepository).releaseAfterSuccess(
                eq(CONFIG_ID), any(), any(), eq("7"), eq(99L), any()
        );
    }

    @Test
    void processClaimedConfig_blockedBySecurity_doesNotLaunch() {
        RetrainingConfig config = dueConfig();
        config.setRunning(true);
        config.setState(RetrainingState.EVALUATING);

        RetrainingDecision blocked = RetrainingDecision.run(
                RetrainingTriggerType.SCHEDULED,
                RetrainingSeverity.CRITICAL,
                "Données suspectes."
        );
        blocked.setSecurityLevel(RetrainingSecurityLevel.BLOCK_MODEL);

        when(retrainingConfigRepository.findById(CONFIG_ID))
                .thenReturn(Optional.of(config));
        when(sourceResolver.resolveSourceExperimentIdOrNull(PROJECT_ID, null))
                .thenReturn(42L);
        when(retrainingDecisionService.buildDecision(any(), any(), anyBoolean()))
                .thenReturn(blocked);
        when(retrainingConfigRepository.releaseAfterSkipped(
                eq(CONFIG_ID), any(), any(), any()))
                .thenReturn(1);

        service.processClaimedConfig(CONFIG_ID);

        verify(mlExperimentService, never())
                .retrainFromCurrentProductionExperiment(anyLong(), anyLong());
        verify(retrainingConfigRepository).releaseAfterSkipped(
                eq(CONFIG_ID), any(), any(), any()
        );
    }

    @Test
    void reconcileFinishedRunningConfigs_resetsWhenNoActiveExperiment() {
        RetrainingConfig finished = new RetrainingConfig();
        ReflectionTestUtils.setField(finished, "id", CONFIG_ID);
        finished.setProjectId(PROJECT_ID);
        finished.setEnabled(true);
        finished.setRunning(false);
        finished.setState(RetrainingState.RUNNING);

        when(retrainingConfigRepository.findFinishedRunningConfigs())
                .thenReturn(List.of(finished));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(0L);
        when(retrainingConfigRepository.resetFinishedConfigToIdle(eq(CONFIG_ID), any(), any()))
                .thenReturn(1);

        int reset = service.reconcileFinishedRunningConfigs();

        assertEquals(1, reset);
        verify(retrainingConfigRepository).resetFinishedConfigToIdle(eq(CONFIG_ID), any(), any());
    }

    @Test
    void reconcileFinishedRunningConfigs_skipsWhenExperimentStillActive() {
        RetrainingConfig finished = new RetrainingConfig();
        ReflectionTestUtils.setField(finished, "id", CONFIG_ID);
        finished.setProjectId(PROJECT_ID);
        finished.setEnabled(true);
        finished.setRunning(false);
        finished.setState(RetrainingState.RUNNING);

        when(retrainingConfigRepository.findFinishedRunningConfigs())
                .thenReturn(List.of(finished));
        when(mlExperimentRepository.countActiveScheduledRetrainingForProject(PROJECT_ID))
                .thenReturn(1L);

        int reset = service.reconcileFinishedRunningConfigs();

        assertEquals(0, reset);
        verify(retrainingConfigRepository, never())
                .resetFinishedConfigToIdle(anyLong(), any(), any());
    }
}
