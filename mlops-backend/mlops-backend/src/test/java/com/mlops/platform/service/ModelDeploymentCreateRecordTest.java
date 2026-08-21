package com.mlops.platform.service;

import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.DeploymentVisibility;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires de la création du record de déploiement.
 *
 * Règle métier : un (registeredModelName, modelVersion) ne peut exister qu'une
 * seule fois (contrainte UNIQUE uk_deployment_model_version en MySQL).
 *  1. si le record existe déjà pour la version -> on le réutilise, aucun doublon ;
 *  2. si deux schedulers créent la même ligne en même temps, la violation de
 *     contrainte est rattrapée et on retourne la ligne existante.
 */
@ExtendWith(MockitoExtension.class)
class ModelDeploymentCreateRecordTest {

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
    void createDeploymentRecord_reusesExistingRow_whenSameVersionAlreadyExists() {
        ModelDeployment existing = readyDeployment(100L, "m", "5");

        when(modelDeploymentRepository
                .findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc("m", "5"))
                .thenReturn(List.of(existing));
        when(modelDeploymentRepository.saveAndFlush(existing)).thenReturn(existing);
        when(mlflowTrackingClientService.getProductionVersion("m")).thenReturn("5");
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("m"))
                .thenReturn(List.of());

        ModelDeploymentResponse response =
                service.createDeploymentRecord(1L, 2L, "m", "5", "run-1", "kube-1");

        assertNotNull(response);
        assertEquals(100L, response.getId());

        // Aucune nouvelle ligne : seul le record existant a été réutilisé.
        verify(modelDeploymentRepository)
                .findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc("m", "5");
    }

    @Test
    void createDeploymentRecord_recoversFromUniqueConstraintRace() {
        AtomicInteger versionLookupCalls = new AtomicInteger(0);
        ModelDeployment raced = readyDeployment(200L, "m", "5");

        when(modelDeploymentRepository
                .findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc("m", "5"))
                .thenAnswer(invocation ->
                        versionLookupCalls.getAndIncrement() == 0
                                ? List.of()
                                : List.of(raced));
        when(modelDeploymentRepository
                .findTopByRegisteredModelNameAndApiEnabledTrueAndApiKeyIsNotNullOrderByUpdatedAtDesc("m"))
                .thenReturn(Optional.empty());
        when(modelDeploymentRepository.existsByApiKey(anyString())).thenReturn(false);

        // Premier insert -> violation UNIQUE (un autre scheduler a gagné la course),
        // deuxième saveAndFlush (celui de la ligne existante) -> succès.
        when(modelDeploymentRepository.saveAndFlush(any(ModelDeployment.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(mlflowTrackingClientService.getProductionVersion("m")).thenReturn("5");
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("m"))
                .thenReturn(List.of());

        ModelDeploymentResponse response =
                service.createDeploymentRecord(1L, 2L, "m", "5", "run-1", "kube-1");

        assertNotNull(response);
        assertEquals(200L, response.getId());
        assertEquals("5", response.getModelVersion());

        verify(modelDeploymentRepository, times(2))
                .findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc("m", "5");
        verify(modelDeploymentRepository, times(2)).saveAndFlush(any(ModelDeployment.class));
    }

    @Test
    void createDeploymentRecord_rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createDeploymentRecord(null, 2L, "m", "5", "run", "kube"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createDeploymentRecord(1L, null, "m", "5", "run", "kube"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createDeploymentRecord(1L, 2L, null, "5", "run", "kube"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createDeploymentRecord(1L, 2L, "m", null, "run", "kube"));
    }

    private ModelDeployment readyDeployment(Long id, String modelName, String version) {
        ModelDeployment deployment = new ModelDeployment();
        ReflectionTestUtils.setField(deployment, "id", id);
        deployment.setRegisteredModelName(modelName);
        deployment.setModelVersion(version);
        deployment.setApiEnabled(true);
        deployment.setVisibility(DeploymentVisibility.PUBLIC_API);
        deployment.setApiKey("key-" + id);
        deployment.setDeploymentStatus(DeploymentStatus.DEPLOYED);
        return deployment;
    }
}
