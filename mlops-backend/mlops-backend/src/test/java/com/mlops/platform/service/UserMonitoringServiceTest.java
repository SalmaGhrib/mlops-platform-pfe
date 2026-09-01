package com.mlops.platform.service;

import com.mlops.platform.entity.*;
import com.mlops.platform.repository.*;
import com.mlops.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserMonitoringServiceTest {

    @Mock private MlProjectRepository mlProjectRepository;
    @Mock private MlExperimentRepository mlExperimentRepository;
    @Mock private ModelDeploymentRepository modelDeploymentRepository;
    @Mock private RetrainingConfigRepository retrainingConfigRepository;
    @Mock private PredictionLogRepository predictionLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;
    @Mock private RetrainingDecisionService retrainingDecisionService;
    @Mock private ModelQualityService modelQualityService;
    @Mock private MonitoringAlertService monitoringAlertService;

    private UserMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new UserMonitoringService(
                mlProjectRepository, mlExperimentRepository,
                modelDeploymentRepository, retrainingConfigRepository,
                predictionLogRepository, userRepository,
                jwtService, retrainingDecisionService,
                modelQualityService, monitoringAlertService
        );
    }

    @Test
    void getDashboard_invalidToken_throwsException() {
        when(jwtService.isTokenValid("bad")).thenReturn(false);

        assertThrows(RuntimeException.class, () ->
                service.getDashboard("Bearer bad", null));
    }

    @Test
    void getDashboard_noProjects_returnsEmptyDashboard() {
        User user = new User();
        user.setId(1L);
        when(jwtService.isTokenValid("valid")).thenReturn(true);
        when(jwtService.extractEmail("valid")).thenReturn("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(mlProjectRepository.findByOwnerOrderByCreatedAtDesc(user)).thenReturn(List.of());

        var response = service.getDashboard("Bearer valid", null);

        assertNotNull(response);
    }

    @Test
    void getDashboard_withProjects_returnsDashboard() {
        User user = new User();
        user.setId(1L);
        MlProject project = new MlProject();
        project.setId(100L);
        project.setOwner(user);

        when(jwtService.isTokenValid("valid")).thenReturn(true);
        when(jwtService.extractEmail("valid")).thenReturn("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(mlProjectRepository.findByOwnerOrderByCreatedAtDesc(user)).thenReturn(List.of(project));
        when(mlProjectRepository.findByIdAndOwner(100L, user)).thenReturn(Optional.of(project));
        when(mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project)).thenReturn(List.of());
        when(modelDeploymentRepository.findByProjectIdOrderByCreatedAtDesc(100L)).thenReturn(List.of());
        when(retrainingConfigRepository.findByProjectId(100L)).thenReturn(Optional.empty());
        when(monitoringAlertService.getRecentAlertsByProject(eq(100L))).thenReturn(List.of());

        var response = service.getDashboard("Bearer valid", 100L);

        assertNotNull(response);
    }

    @Test
    void getDashboard_nullAuth_throwsException() {
        assertThrows(RuntimeException.class, () ->
                service.getDashboard(null, null));
    }
}
