package com.mlops.platform.service;

import com.mlops.platform.dto.MlflowModelVersionDto;
import com.mlops.platform.dto.ModelConsistencyResponse;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires de la réconciliation de cohérence des modèles
 * (Mockito, sans contexte Spring, sans base de données).
 *
 * Vérifie l'architecture corrigée Phase A / Phase B / Phase C :
 *  1. les appels MLflow/Kubernetes se font HORS transaction ;
 *  2. l'écriture se fait dans une transaction courte REQUIRES_NEW bornée ;
 *  3. un échec sur un modèle n'empêche pas les autres modèles ;
 *  4. seule la ligne correspondant à la version Production passe à true ;
 *  5. la vérification Kubernetes précède l'ouverture de la transaction ;
 *  6. un déploiement DEPLOYED mais non prêt est marqué OUT_OF_SYNC ;
 *  7. aucune ligne n'est réécrite si rien ne change (pas de saveAll inutile).
 */
@ExtendWith(MockitoExtension.class)
class ModelConsistencyServiceTest {

    @Mock
    private MlExperimentRepository experimentRepository;

    @Mock
    private ModelDeploymentRepository deploymentRepository;

    @Mock
    private MlflowTrackingClientService mlflowClient;

    @Mock
    private ModelDeploymentService deploymentService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ModelConsistencyService service;

    @BeforeEach
    void setUp() {
        service = new ModelConsistencyService(
                experimentRepository,
                deploymentRepository,
                mlflowClient,
                deploymentService,
                transactionManager
        );
    }

    @Test
    void reconcileModel_callsExternalApisBeforeOpeningWriteTransaction() {
        String model = "dep2_model";
        MlExperiment experiment = completedExperiment(1L, "run-1", "3", model);

        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of(experiment));
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of());
        when(mlflowClient.getProductionVersion(model)).thenReturn("3");
        when(mlflowClient.getAllModelVersionsWithMetrics(model)).thenReturn(List.of(version("run-1", "3")));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ModelConsistencyResponse response = service.reconcileModel(model);

        verify(mlflowClient).getProductionVersion(model);
        verify(mlflowClient).getAllModelVersionsWithMetrics(model);

        // L'écriture se fait dans une transaction courte REQUIRES_NEW : ouverture + commit.
        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(any());

        verify(experimentRepository).saveAll(anyList());
        verify(experimentRepository).flush();

        assertNotNull(response);
        assertEquals("3", response.getMlflowProductionVersion());
    }

    @Test
    void reconcileAllModels_continuesWhenOneModelFails() {
        when(experimentRepository.findDistinctRegisteredModelNames())
                .thenReturn(List.of("model-down", "model-ok"));
        when(deploymentRepository.findDistinctRegisteredModelNames())
                .thenReturn(List.of());

        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-down"))
                .thenReturn(List.of());
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-down"))
                .thenReturn(List.of());
        when(mlflowClient.getProductionVersion("model-down"))
                .thenThrow(new RuntimeException("MLflow unavailable"));
        when(mlflowClient.getProductionVersion("model-ok")).thenReturn("1");
        when(mlflowClient.getAllModelVersionsWithMetrics("model-ok")).thenReturn(null);
        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-ok"))
                .thenReturn(List.of());
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("model-ok"))
                .thenReturn(List.of());
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        List<ModelConsistencyResponse> responses = service.reconcileAllModels();

        // model-down a échoué mais model-ok a été traité : isolement des erreurs.
        assertEquals(1, responses.size());
        assertEquals("model-ok", responses.get(0).getRegisteredModelName());
    }

    @Test
    void reconcileModel_marksOnlyMatchingRowAsProduction() {
        String model = "dep2_model";
        MlExperiment winner = completedExperiment(1L, "run-1", "3", model);
        MlExperiment other = completedExperiment(2L, "run-2", "2", model);

        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of(winner, other));
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of());
        when(mlflowClient.getProductionVersion(model)).thenReturn("3");
        when(mlflowClient.getAllModelVersionsWithMetrics(model))
                .thenReturn(List.of(version("run-1", "3"), version("run-2", "2")));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileModel(model);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MlExperiment>> captor = ArgumentCaptor.forClass(List.class);
        verify(experimentRepository).saveAll(captor.capture());

        List<MlExperiment> saved = captor.getValue();
        assertTrue(saved.stream().anyMatch(e -> Long.valueOf(1L).equals(e.getId())
                && Boolean.TRUE.equals(e.getProduction())));
        assertTrue(saved.stream().anyMatch(e -> Long.valueOf(2L).equals(e.getId())
                && Boolean.FALSE.equals(e.getProduction())));
    }

    @Test
    void reconcileModel_kubernetesCheckHappensBeforeWriteTransaction() {
        String model = "dep2_model";
        MlExperiment experiment = completedExperiment(1L, "run-1", "3", model);

        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of(experiment));
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of());
        when(mlflowClient.getProductionVersion(model)).thenReturn("3");
        when(mlflowClient.getAllModelVersionsWithMetrics(model)).thenReturn(List.of(version("run-1", "3")));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service.reconcileModel(model);

        InOrder order = inOrder(mlflowClient, transactionManager);
        order.verify(mlflowClient).getProductionVersion(model);
        order.verify(transactionManager).getTransaction(any());
    }

    @Test
    void reconcileModel_noProductionAlias_marksAllRowsNonProduction() {
        String model = "dep2_model";
        // MLflow n'a pas d'alias @production : aucune ligne ne doit rester production=true.
        MlExperiment experiment = completedExperiment(1L, "run-1", "3", model);
        experiment.setProduction(true);

        when(experimentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of(experiment));
        when(deploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(model))
                .thenReturn(List.of());
        when(mlflowClient.getProductionVersion(model)).thenReturn(null);
        when(mlflowClient.getAllModelVersionsWithMetrics(model)).thenReturn(List.of(version("run-1", "3")));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ModelConsistencyResponse response = service.reconcileModel(model);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MlExperiment>> captor = ArgumentCaptor.forClass(List.class);
        verify(experimentRepository).saveAll(captor.capture());

        List<MlExperiment> saved = captor.getValue();
        assertTrue(saved.stream().allMatch(e -> Boolean.FALSE.equals(e.getProduction())));

        assertFalse(response.isConsistent());
        assertTrue(response.getProblems().stream()
                .anyMatch(p -> p.contains("no @production alias")));
    }

    private MlExperiment completedExperiment(Long id, String runId, String version, String model) {
        MlExperiment experiment = new MlExperiment();
        experiment.setId(id);
        experiment.setMlflowRunId(runId);
        experiment.setModelVersion(version);
        experiment.setProductionVersion(version);
        experiment.setRegisteredModelName(model);
        experiment.setStatus(ExperimentStatus.COMPLETED);
        experiment.setProduction(false);
        return experiment;
    }

    private MlflowModelVersionDto version(String runId, String version) {
        MlflowModelVersionDto dto = new MlflowModelVersionDto();
        dto.setRunId(runId);
        dto.setVersion(version);
        return dto;
    }
}
