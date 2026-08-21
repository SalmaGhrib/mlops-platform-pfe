package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.repository.MlExperimentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires de l'idempotence du standardizer d'artefacts MLflow.
 *
 * Règle métier : 1 run réussi = exactement 1 version MLflow.
 * Le standardizer doit vérifier dans MLflow l'existence d'une version pour le
 * run AVANT de relancer le script : s'il y en a une, il la réutilise et ne crée
 * JAMAIS de nouvelle version (idempotent_reuse), même si le script était relancé
 * par un scheduler (watcher Docker / reconciler de production).
 */
@ExtendWith(MockitoExtension.class)
class ModelArtifactStandardizerServiceTest {

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    @Mock
    private MlflowTrackingClientService mlflowTrackingClientService;

    private ModelArtifactStandardizerService service;

    @BeforeEach
    void setUp() {
        service = new ModelArtifactStandardizerService(
                mlExperimentRepository,
                mlflowTrackingClientService
        );
        ReflectionTestUtils.setField(service, "standardizerEnabled", true);
    }

    @Test
    void standardizeIfNeeded_reusesExistingMlflowVersion_withoutRunningScript() {
        MlExperiment experiment = experiment(1L, "run-abc", "my_model");

        when(mlflowTrackingClientService.findModelVersionByRunId("my_model", "run-abc"))
                .thenReturn("5");

        ModelArtifactStandardizerService.StandardizationResult result =
                service.standardizeIfNeeded(experiment);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertFalse(result.isSkipped());
        assertTrue(result.isAlreadyMlflowModel());
        assertEquals("idempotent_reuse", result.getStandardizationType());
        assertEquals("5", result.getNewVersion());

        verify(mlflowTrackingClientService)
                .findModelVersionByRunId("my_model", "run-abc");
        verify(mlExperimentRepository).saveAndFlush(experiment);
    }

    @Test
    void standardizeIfNeeded_idempotentReuse_setsMissingModelVersion() {
        MlExperiment experiment = experiment(1L, "run-abc", "my_model");
        experiment.setModelVersion(null);

        when(mlflowTrackingClientService.findModelVersionByRunId("my_model", "run-abc"))
                .thenReturn("7");

        service.standardizeIfNeeded(experiment);

        assertEquals("7", experiment.getModelVersion());
        assertNotNull(experiment.getMessage());
        assertTrue(experiment.getMessage().contains("Aucune nouvelle version créée"));
        assertNull(experiment.getErrorMessage());
    }

    @Test
    void standardizeIfNeeded_doesNotReuse_whenVersionLookupFails_andScriptMissing() {
        MlExperiment experiment = experiment(1L, "run-abc", "my_model");
        ReflectionTestUtils.setField(service, "standardizerScriptPath", "does-not-exist.py");

        when(mlflowTrackingClientService.findModelVersionByRunId("my_model", "run-abc"))
                .thenThrow(new RuntimeException("MLflow unreachable"));

        ModelArtifactStandardizerService.StandardizationResult result =
                service.standardizeIfNeeded(experiment);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Standardizer script not found"));

        verify(mlExperimentRepository, never()).saveAndFlush(any());
    }

    @Test
    void standardizeIfNeeded_skips_whenNoMlflowRunId() {
        MlExperiment experiment = experiment(1L, null, "my_model");

        ModelArtifactStandardizerService.StandardizationResult result =
                service.standardizeIfNeeded(experiment);

        assertTrue(result.isSkipped());
        verify(mlflowTrackingClientService, never()).findModelVersionByRunId(any(), any());
        verify(mlExperimentRepository, never()).saveAndFlush(any());
    }

    @Test
    void standardizeIfNeeded_skips_whenNoRegisteredModelName() {
        MlExperiment experiment = experiment(1L, "run-abc", null);

        ModelArtifactStandardizerService.StandardizationResult result =
                service.standardizeIfNeeded(experiment);

        assertTrue(result.isSkipped());
        verify(mlflowTrackingClientService, never()).findModelVersionByRunId(any(), any());
    }

    @Test
    void standardizeIfNeeded_reusesVersion_onConsecutiveSchedulerCalls() {
        /*
         * Simule deux cycles de scheduler (watcher + reconciler) pour le même run :
         * la version existe déjà à chaque cycle -> idempotent_reuse à chaque fois,
         * jamais de nouvelle version créée (findModelVersionByRunId n'est jamais
         * contourné par un appel au script).
         */
        MlExperiment experiment = experiment(1L, "run-abc", "my_model");

        when(mlflowTrackingClientService.findModelVersionByRunId(eq("my_model"), eq("run-abc")))
                .thenReturn("9");

        ModelArtifactStandardizerService.StandardizationResult first =
                service.standardizeIfNeeded(experiment);
        ModelArtifactStandardizerService.StandardizationResult second =
                service.standardizeIfNeeded(experiment);

        assertEquals("idempotent_reuse", first.getStandardizationType());
        assertEquals("idempotent_reuse", second.getStandardizationType());
        assertEquals("9", first.getNewVersion());
        assertEquals("9", second.getNewVersion());

        verify(mlflowTrackingClientService, times(2))
                .findModelVersionByRunId("my_model", "run-abc");
        verify(mlExperimentRepository, times(2)).saveAndFlush(experiment);
    }

    private MlExperiment experiment(Long id, String mlflowRunId, String registeredModelName) {
        MlExperiment experiment = new MlExperiment();
        experiment.setId(id);
        experiment.setMlflowRunId(mlflowRunId);
        experiment.setRegisteredModelName(registeredModelName);
        experiment.setStatus(ExperimentStatus.COMPLETED);
        return experiment;
    }
}
