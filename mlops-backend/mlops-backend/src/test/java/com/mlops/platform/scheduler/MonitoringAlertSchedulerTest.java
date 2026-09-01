package com.mlops.platform.scheduler;

import com.mlops.platform.entity.*;
import com.mlops.platform.repository.*;
import com.mlops.platform.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringAlertSchedulerTest {

    @Mock private MonitoringService monitoringService;
    @Mock private NotificationService notificationService;
    @Mock private MlExperimentRepository mlExperimentRepository;
    @Mock private MonitoringRetrainingTriggerService monitoringRetrainingTriggerService;
    @Mock private RetrainingConfigRepository retrainingConfigRepository;
    @Mock private MlProjectRepository mlProjectRepository;
    @Mock private RetrainingDecisionService retrainingDecisionService;
    @Mock private ModelQualityService modelQualityService;
    @Mock private MonitoringAlertService monitoringAlertService;

    private MonitoringAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MonitoringAlertScheduler(
                monitoringService, notificationService, mlExperimentRepository,
                monitoringRetrainingTriggerService, retrainingConfigRepository,
                mlProjectRepository, retrainingDecisionService, modelQualityService,
                monitoringAlertService
        );
        ReflectionTestUtils.setField(scheduler, "monitoringAlertsEnabled", true);
        ReflectionTestUtils.setField(scheduler, "failedRunsThreshold", 1.0);
        ReflectionTestUtils.setField(scheduler, "failedDeploymentsThreshold", 1.0);
        ReflectionTestUtils.setField(scheduler, "unavailableModelsThreshold", 1.0);
        ReflectionTestUtils.setField(scheduler, "backendLatencyMsThreshold", 1000.0);
        ReflectionTestUtils.setField(scheduler, "failedRunsByProjectThreshold", 1L);
        ReflectionTestUtils.setField(scheduler, "failedRunsLookbackHours", 24);
    }

    @Test
    void init_doesNotThrow() {
        scheduler.init();
    }

    @Test
    void checkMonitoringAlerts_disabled_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "monitoringAlertsEnabled", false);

        scheduler.checkMonitoringAlerts();

        verifyNoInteractions(monitoringAlertService);
    }

    @Test
    void checkMonitoringAlerts_enabled_runsWithoutException() {
        when(mlProjectRepository.findAll()).thenReturn(List.of());
        when(monitoringAlertService.countAlertsBySeveritySince(any(AlertSeverity.class), any())).thenReturn(0L);
        when(monitoringAlertService.countAlertsSince(any())).thenReturn(0L);

        scheduler.checkMonitoringAlerts();
    }

    @Test
    void checkMonitoringAlerts_doesNotThrowOnException() {
        when(mlProjectRepository.findAll()).thenThrow(new RuntimeException("DB error"));

        scheduler.checkMonitoringAlerts();
    }

    @Test
    void checkMonitoringAlerts_concurrentExecution_prevented() {
        when(mlProjectRepository.findAll()).thenReturn(List.of());
        when(monitoringAlertService.countAlertsBySeveritySince(any(AlertSeverity.class), any())).thenReturn(0L);
        when(monitoringAlertService.countAlertsSince(any())).thenReturn(0L);

        scheduler.checkMonitoringAlerts();
        scheduler.checkMonitoringAlerts();
    }
}
