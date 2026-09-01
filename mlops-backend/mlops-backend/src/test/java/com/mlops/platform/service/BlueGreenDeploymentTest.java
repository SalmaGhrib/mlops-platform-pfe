package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires du flux blue/green (Mockito, sans Spring, sans base de données).
 *
 * Vérifie :
 *  1. rollback automatique : la Production active qui perd sa readiness est
 *     remplacée par la dernière bonne version (switch du Service stable inclus) ;
 *  2. nettoyage des versions obsolètes (STALE_DEPLOYMENT -> OLD_DEPLOYMENT_CLEANED)
 *     après la fenêtre de rollback ;
 *  3. une seule Production active par modèle : la plus récente gagne.
 */
@ExtendWith(MockitoExtension.class)
class BlueGreenDeploymentTest {

    @Mock
    private ModelDeploymentRepository modelDeploymentRepository;

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    @Mock
    private KubernetesModelServingService kubernetesModelServingService;

    @Mock
    private ModelServingImageBuildService modelServingImageBuildService;

    @Mock
    private ModelDeploymentShareRepository modelDeploymentShareRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MlflowTrackingClientService mlflowTrackingClientService;

    @Mock
    private MlProjectRepository mlProjectRepository;

    @Mock
    private ModelArtifactStandardizerService modelArtifactStandardizerService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ModelApiKeyRepository modelApiKeyRepository;

    private ModelDeploymentService service;

    @BeforeEach
    void setUp() {
        service = new ModelDeploymentService(
                modelDeploymentRepository,
                mlExperimentRepository,
                kubernetesModelServingService,
                modelServingImageBuildService,
                modelDeploymentShareRepository,
                userRepository,
                mlflowTrackingClientService,
                mlProjectRepository,
                modelArtifactStandardizerService,
                notificationService,
                transactionManager,
                modelApiKeyRepository
        );
        ReflectionTestUtils.setField(service, "rollbackWindowMs", 600000L);
        ReflectionTestUtils.setField(service, "predictionSmokeTestEnabled", false);
    }

    @Test
    void activeDeploymentLosingReadiness_rollsBackToPreviousVersion() {
        ModelDeployment previous = deployment(1L, "model-x", "1", "blue", "model-x-service");
        previous.setActive(false);
        previous.setDeploymentStatus(DeploymentStatus.DEPLOYED);

        ModelDeployment current = deployment(2L, "model-x", "2", "green", "model-x-service");
        current.setActive(true);
        current.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        current.setDeploymentName("dep-2");
        current.setServiceName("svc-2");

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(previous, current));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1")).thenReturn(true);
        when(kubernetesModelServingService.isModelServerReady("dep-2", "svc-2")).thenReturn(false);
        when(modelDeploymentRepository.findById(2L)).thenReturn(Optional.of(current));
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-x"))
                .thenReturn(List.of(current, previous));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        assertTrue(Boolean.TRUE.equals(previous.getActive()));
        assertSame(DeploymentStatus.DEPLOYED, previous.getDeploymentStatus());
        assertFalse(Boolean.TRUE.equals(current.getActive()));
        assertSame(DeploymentStatus.OUT_OF_SYNC, current.getDeploymentStatus());

        verify(kubernetesModelServingService).switchStableServiceTrack(
                eq("model-x-service"), eq("model-x"), eq("blue")
        );
    }

    @Test
    void activeDeploymentLosingReadiness_withoutPreviousVersion_isMarkedOutOfSync() {
        ModelDeployment current = deployment(2L, "model-x", "2", "green", "model-x-service");
        current.setActive(true);
        current.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        current.setDeploymentName("dep-2");
        current.setServiceName("svc-2");

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(current));
        when(kubernetesModelServingService.isModelServerReady("dep-2", "svc-2")).thenReturn(false);
        when(modelDeploymentRepository.findById(2L)).thenReturn(Optional.of(current));
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-x"))
                .thenReturn(List.of(current));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        assertSame(DeploymentStatus.OUT_OF_SYNC, current.getDeploymentStatus());
        assertFalse(Boolean.TRUE.equals(current.getActive()));
        verify(kubernetesModelServingService, never()).switchStableServiceTrack(any(), any(), any());
    }

    @Test
    void staleSupersededDeployment_isCleanedUpAfterRollbackWindow() {
        ModelDeployment stale = deployment(1L, "model-x", "1", "blue", "model-x-service");
        stale.setActive(false);
        stale.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        stale.setDeployedAt(LocalDateTime.now().minusMinutes(30));
        ReflectionTestUtils.setField(stale, "updatedAt", LocalDateTime.now().minusMinutes(30));

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(stale));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1")).thenReturn(true);
        when(modelDeploymentRepository.findById(1L)).thenReturn(Optional.of(stale));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        verify(kubernetesModelServingService).deleteModelServer("dep-1", "svc-1");
        assertSame(DeploymentStatus.OUT_OF_SYNC, stale.getDeploymentStatus());
        assertNotNull(stale.getErrorMessage());
    }

    @Test
    void staleButWithinRollbackWindow_isKeptForRollback() {
        ModelDeployment recent = deployment(1L, "model-x", "1", "blue", "model-x-service");
        recent.setActive(false);
        recent.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        ReflectionTestUtils.setField(recent, "updatedAt", LocalDateTime.now().minusSeconds(30));

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(recent));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1")).thenReturn(true);

        service.reconcileDeploymentsWithKubernetes();

        verify(kubernetesModelServingService, never()).deleteModelServer(any(), any());
        assertSame(DeploymentStatus.DEPLOYED, recent.getDeploymentStatus());
    }

    @Test
    void onlyNewestDeploymentRemainsActivePerModel() {
        ModelDeployment older = deployment(1L, "model-x", "1", "blue", "model-x-service");
        older.setActive(true);
        older.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        older.setDeployedAt(LocalDateTime.now().minusDays(1));

        ModelDeployment newer = deployment(2L, "model-x", "2", "green", "model-x-service");
        newer.setActive(true);
        newer.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        newer.setDeployedAt(LocalDateTime.now());

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(newer, older));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1")).thenReturn(true);
        when(kubernetesModelServingService.isModelServerReady("dep-2", "svc-2")).thenReturn(true);
        when(modelDeploymentRepository.findById(1L)).thenReturn(Optional.of(older));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        assertTrue(Boolean.TRUE.equals(newer.getActive()));
        assertFalse(Boolean.TRUE.equals(older.getActive()));
    }

    private ModelDeployment deployment(Long id, String model, String version, String track, String stableService) {
        ModelDeployment deployment = new ModelDeployment();
        ReflectionTestUtils.setField(deployment, "id", id);
        deployment.setRegisteredModelName(model);
        deployment.setModelVersion(version);
        deployment.setServingTrack(track);
        deployment.setStableServiceName(stableService);
        deployment.setDeploymentName("dep-" + id);
        deployment.setServiceName("svc-" + id);
        deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        deployment.setActive(false);
        return deployment;
    }
}