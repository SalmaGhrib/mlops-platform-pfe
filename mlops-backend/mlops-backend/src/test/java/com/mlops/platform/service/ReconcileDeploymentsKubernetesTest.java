package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires de la réconciliation des déploiements avec Kubernetes
 * (Mockito, sans contexte Spring, sans base de données).
 *
 * Vérifie l'architecture corrigée Phase B / Phase C :
 *  1. un déploiement DEPLOYED+actif mais non prêt dans Kubernetes est marqué
 *     OUT_OF_SYNC dans une transaction courte REQUIRES_NEW, et les autres
 *     déploiements ne sont pas bloqués ;
 *  2. un déploiement DEPLOYED+actif réellement prêt n'est pas réécrit ;
 *  3. la vérification Kubernetes se fait HORS transaction.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileDeploymentsKubernetesTest {

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
                transactionManager
        );
    }

    @Test
    void deployedButNotReady_isMarkedOutOfSyncInOwnTransaction() throws Exception {
        ModelDeployment deployment = deployedDeployment(1L);
        deployment.setActive(true);
        deployment.setProjectId(10L);

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(deployment));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1"))
                .thenReturn(false);
        when(modelDeploymentRepository.findById(1L)).thenReturn(Optional.of(deployment));
        when(modelDeploymentRepository.saveAndFlush(deployment)).thenReturn(deployment);
        when(mlProjectRepository.findById(10L)).thenReturn(Optional.empty());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        assertSame(DeploymentStatus.OUT_OF_SYNC, deployment.getDeploymentStatus());
        assertFalse(Boolean.TRUE.equals(deployment.getActive()));
        assertNotNull(deployment.getErrorMessage());

        // Écriture dans une transaction REQUIRES_NEW courte : ouverture + commit.
        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(any());
        verify(modelDeploymentRepository).saveAndFlush(deployment);

        // Notification OUT_OF_SYNC déclenchée une seule fois.
        verify(notificationService).notifyDeploymentOutOfSync(eq(1L), any(), any());
    }

    @Test
    void deployedAndReady_isNotRewritten() throws Exception {
        ModelDeployment deployment = deployedDeployment(1L);
        deployment.setActive(true);

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(deployment));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1"))
                .thenReturn(true);

        service.reconcileDeploymentsWithKubernetes();

        assertSame(DeploymentStatus.DEPLOYED, deployment.getDeploymentStatus());
        assertTrue(Boolean.TRUE.equals(deployment.getActive()));
        assertNull(deployment.getErrorMessage());

        // Aucune écriture inutile : ni transaction, ni saveAndFlush, ni notification.
        verify(transactionManager, never()).getTransaction(any());
        verify(modelDeploymentRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).notifyDeploymentOutOfSync(any(), any(), any());
    }

    @Test
    void healthyDeploymentDoesNotBlockBrokenOne() throws Exception {
        ModelDeployment broken = deployedDeployment(1L);
        broken.setActive(true);
        broken.setProjectId(10L);

        ModelDeployment healthy = deployedDeployment(2L);
        healthy.setDeploymentName("dep-2");
        healthy.setServiceName("svc-2");
        healthy.setActive(true);
        healthy.setProjectId(20L);

        when(modelDeploymentRepository.findAll()).thenReturn(List.of(broken, healthy));
        when(kubernetesModelServingService.isModelServerReady("dep-1", "svc-1"))
                .thenReturn(false);
        when(kubernetesModelServingService.isModelServerReady("dep-2", "svc-2"))
                .thenReturn(true);
        when(modelDeploymentRepository.findById(1L)).thenReturn(Optional.of(broken));
        when(modelDeploymentRepository.saveAndFlush(broken)).thenReturn(broken);
        when(mlProjectRepository.findById(10L)).thenReturn(Optional.empty());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileDeploymentsWithKubernetes();

        assertSame(DeploymentStatus.OUT_OF_SYNC, broken.getDeploymentStatus());
        assertSame(DeploymentStatus.DEPLOYED, healthy.getDeploymentStatus());
        assertTrue(Boolean.TRUE.equals(healthy.getActive()));

        // Seul le déploiement cassé a été écrit, le sain n'a jamais été touché.
        verify(modelDeploymentRepository).saveAndFlush(broken);
        verify(modelDeploymentRepository, never()).saveAndFlush(healthy);
    }

    private ModelDeployment deployedDeployment(Long id) throws Exception {
        ModelDeployment deployment = new ModelDeployment();
        Field idField = ModelDeployment.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(deployment, id);
        deployment.setDeploymentName("dep-" + id);
        deployment.setServiceName("svc-" + id);
        deployment.setRegisteredModelName("model-x");
        deployment.setModelVersion("1");
        deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        return deployment;
    }
}
