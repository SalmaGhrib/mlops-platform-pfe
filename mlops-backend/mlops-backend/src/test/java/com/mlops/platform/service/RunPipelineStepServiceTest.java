package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.RunPipelineStep;
import com.mlops.platform.repository.RunPipelineStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunPipelineStepServiceTest {

    @Mock
    private RunPipelineStepRepository stepRepository;

    private RunPipelineStepService service;

    @BeforeEach
    void setUp() {
        service = new RunPipelineStepService(stepRepository);
    }

    private MlExperiment experiment(String runtimeMode) {
        MlExperiment e = new MlExperiment();
        ReflectionTestUtils.setField(e, "id", 42L);
        e.setRuntimeMode(runtimeMode);
        e.setProject(new MlProject());
        return e;
    }

    private RunPipelineStep step(String code, String status, int order) {
        RunPipelineStep s = new RunPipelineStep();
        ReflectionTestUtils.setField(s, "id", 1L);
        s.setStepCode(code);
        s.setStatus(status);
        s.setOrderIndex(order);
        s.setExperimentId(42L);
        return s;
    }

    @Test
    void standardStepDefinitions_returnsExpectedSteps() {
        var defs = RunPipelineStepService.standardStepDefinitions();
        assertNotNull(defs);
        assertTrue(defs.containsKey("SUBMITTED"));
        assertTrue(defs.containsKey("KFP_TRAINING_RUNNING"));
        assertTrue(defs.containsKey("METRICS_EXTRACTED"));
        assertTrue(defs.containsKey("MLFLOW_REGISTERED"));
    }

    @Test
    void customStepDefinitions_returnsExpectedSteps() {
        var defs = RunPipelineStepService.customStepDefinitions();
        assertNotNull(defs);
        assertTrue(defs.containsKey("FILES_VALIDATED"));
        assertTrue(defs.containsKey("IMAGE_BUILD_STARTED"));
        assertTrue(defs.containsKey("TRAINING_JOB_CREATED"));
        assertTrue(defs.containsKey("USER_SCRIPT_COMPLETED"));
    }

    @Test
    void stepCodesForMode_standard_returnsStandardCodes() {
        var codes = RunPipelineStepService.stepCodesForMode("STANDARD_RUNTIME");
        assertNotNull(codes);
        assertFalse(codes.isEmpty());
        assertEquals(RunPipelineStepService.standardStepCodes(), codes);
    }

    @Test
    void stepCodesForMode_custom_returnsCustomCodes() {
        var codes = RunPipelineStepService.stepCodesForMode("CUSTOM_DOCKERFILE");
        assertNotNull(codes);
        assertFalse(codes.isEmpty());
        assertEquals(RunPipelineStepService.customStepCodes(), codes);
    }

    @Test
    void isCustomMode_custom_returnsTrue() {
        assertTrue(RunPipelineStepService.isCustomMode("CUSTOM_DOCKERFILE"));
    }

    @Test
    void isCustomMode_standard_returnsFalse() {
        assertFalse(RunPipelineStepService.isCustomMode("STANDARD_RUNTIME"));
    }

    @Test
    void initializeForExperiment_createsAllSteps() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        when(stepRepository.findByExperimentIdAndStepCode(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.initializeForExperiment(e);

        verify(stepRepository, atLeast(10)).saveAndFlush(any(RunPipelineStep.class));
    }

    @Test
    void markRunning_setsStatusToRunning() {
        MlExperiment e = experiment("CUSTOM_DOCKERFILE");
        RunPipelineStep s = step("FILES_VALIDATED", "PENDING", 1);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "FILES_VALIDATED"))
                .thenReturn(Optional.of(s));
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.markRunning(e, "FILES_VALIDATED", "Building...");

        assertEquals("RUNNING", s.getStatus());
        assertNotNull(s.getStartedAt());
        verify(stepRepository).saveAndFlush(s);
    }

    @Test
    void markRunning_terminalStep_doesNotOverride() {
        MlExperiment e = experiment("CUSTOM_DOCKERFILE");
        RunPipelineStep s = step("FILES_VALIDATED", "SUCCEEDED", 1);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "FILES_VALIDATED"))
                .thenReturn(Optional.of(s));
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.markRunning(e, "FILES_VALIDATED", "Building...");

        assertEquals("SUCCEEDED", s.getStatus());
    }

    @Test
    void markSucceeded_setsStatusToSucceeded() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        RunPipelineStep s = step("KFP_TRAINING_RUNNING", "RUNNING", 3);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "KFP_TRAINING_RUNNING"))
                .thenReturn(Optional.of(s));
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.markSucceeded(e, "KFP_TRAINING_RUNNING", "Training completed.");

        assertEquals("SUCCEEDED", s.getStatus());
        assertNotNull(s.getCompletedAt());
        verify(stepRepository).saveAndFlush(s);
    }

    @Test
    void markSucceeded_failedStep_cannotOverride() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        RunPipelineStep s = step("KFP_TRAINING_RUNNING", "FAILED", 3);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "KFP_TRAINING_RUNNING"))
                .thenReturn(Optional.of(s));

        service.markSucceeded(e, "KFP_TRAINING_RUNNING", "Done.");

        assertEquals("FAILED", s.getStatus());
        verify(stepRepository, never()).saveAndFlush(any());
    }

    @Test
    void markFailed_setsStatusAndSkipsDownstream() {
        MlExperiment e = experiment("CUSTOM_DOCKERFILE");
        RunPipelineStep failedStep = step("IMAGE_BUILD_STARTED", "RUNNING", 2);
        RunPipelineStep downstream = step("TRAINING_JOB_CREATED", "PENDING", 3);

        when(stepRepository.findByExperimentIdAndStepCode(42L, "IMAGE_BUILD_STARTED"))
                .thenReturn(Optional.of(failedStep));
        when(stepRepository.findByExperimentIdOrderByOrderIndexAsc(42L))
                .thenReturn(List.of(failedStep, downstream));
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.markFailed(e, "IMAGE_BUILD_STARTED", "BUILD_FAILED", "Kaniko error",
                "Image build failed", "kaniko logs...");

        assertEquals("FAILED", failedStep.getStatus());
        assertEquals("SKIPPED", downstream.getStatus());
        verify(stepRepository, atLeastOnce()).saveAndFlush(any());
    }

    @Test
    void markSkipped_setsStatusToSkipped() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        RunPipelineStep s = step("METRICS_EXTRACTED", "PENDING", 2);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "METRICS_EXTRACTED"))
                .thenReturn(Optional.of(s));
        when(stepRepository.findByExperimentIdOrderByOrderIndexAsc(42L))
                .thenReturn(List.of(s));
        when(stepRepository.saveAndFlush(any(RunPipelineStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.markSkipped(e, "METRICS_EXTRACTED", "Skipped due to failure.");

        assertEquals("SKIPPED", s.getStatus());
        verify(stepRepository).saveAndFlush(s);
    }

    @Test
    void markSkipped_terminalStep_doesNotOverride() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        RunPipelineStep s = step("METRICS_EXTRACTED", "SUCCEEDED", 2);
        when(stepRepository.findByExperimentIdAndStepCode(42L, "METRICS_EXTRACTED"))
                .thenReturn(Optional.of(s));

        service.markSkipped(e, "METRICS_EXTRACTED", "Skipped.");

        assertEquals("SUCCEEDED", s.getStatus());
    }

    @Test
    void markRunning_nullExperiment_doesNothing() {
        assertDoesNotThrow(() -> service.markRunning(null, "CODE", "msg"));
    }

    @Test
    void markFailed_nullExperiment_doesNothing() {
        assertDoesNotThrow(() -> service.markFailed(null, "CODE", "ERR", "detail", "msg", "logs"));
    }

    @Test
    void markSucceeded_nullExperiment_doesNothing() {
        assertDoesNotThrow(() -> service.markSucceeded(null, "CODE", "msg"));
    }

    @Test
    void getPipelineStatus_emptySteps_returnsDefault() {
        MlExperiment e = experiment("STANDARD_RUNTIME");
        when(stepRepository.findByExperimentIdOrderByOrderIndexAsc(42L)).thenReturn(List.of());

        var response = service.getPipelineStatus(e);

        assertNotNull(response);
    }
}
