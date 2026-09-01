package com.mlops.platform.service;

import com.mlops.platform.entity.*;
import com.mlops.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminMonitoringOverviewServiceTest {

    @Mock private MlProjectRepository mlProjectRepository;
    @Mock private ModelDeploymentRepository modelDeploymentRepository;
    @Mock private RetrainingConfigRepository retrainingConfigRepository;
    @Mock private MlExperimentRepository mlExperimentRepository;
    @Mock private MonitoringAlertService monitoringAlertService;
    @Mock private RetrainingDecisionService retrainingDecisionService;
    @Mock private ModelQualityService modelQualityService;

    private AdminMonitoringOverviewService service;

    @BeforeEach
    void setUp() {
        service = new AdminMonitoringOverviewService(
                mlProjectRepository, modelDeploymentRepository,
                retrainingConfigRepository, mlExperimentRepository,
                monitoringAlertService, retrainingDecisionService,
                modelQualityService
        );
    }

    @Test
    void getOverview_emptyData_returnsDefaults() {
        when(mlProjectRepository.findAll()).thenReturn(List.of());
        when(modelDeploymentRepository.findAll()).thenReturn(List.of());
        when(retrainingConfigRepository.findEnabledConfigs()).thenReturn(List.of());
        when(monitoringAlertService.getRecentAlerts(50)).thenReturn(List.of());

        var response = service.getOverview();

        assertNotNull(response);
        assertEquals(0, response.totalProjects);
        assertEquals(0, response.totalDeployments);
    }

    @Test
    void getOverview_withDeployments_returnsCounts() {
        ModelDeployment dep = new ModelDeployment();
        dep.setActive(true);
        dep.setDeploymentStatus(DeploymentStatus.DEPLOYED);

        when(mlProjectRepository.findAll()).thenReturn(List.of());
        when(modelDeploymentRepository.findAll()).thenReturn(List.of(dep));
        when(retrainingConfigRepository.findEnabledConfigs()).thenReturn(List.of());
        when(monitoringAlertService.getRecentAlerts(50)).thenReturn(List.of());

        var response = service.getOverview();

        assertNotNull(response);
        assertEquals(1, response.totalDeployments);
        assertEquals(1, response.activeProductionModels);
    }

    @Test
    void getOverview_handlesException() {
        when(mlProjectRepository.findAll()).thenThrow(new RuntimeException("DB error"));

        try {
            service.getOverview();
        } catch (RuntimeException e) {
            assertEquals("DB error", e.getMessage());
        }
    }
}
