package com.mlops.platform.service;

import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MonitoringAlert;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MonitoringAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du service d'alertes monitoring
 * (Mockito pur, sans Spring, sans base de données).
 *
 * Vérifie :
 *  1. la déduplication par clé d'idempotence (aucun doublon) ;
 *  2. la génération d'une alerte nouvelle ;
 *  3. les notifications : propriétaire toujours, admins uniquement en CRITICAL ;
 *  4. les helpers de construction de clés.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringAlertServiceTest {

    @Mock
    private MonitoringAlertRepository monitoringAlertRepository;

    @Mock
    private NotificationService notificationService;

    private MonitoringAlertService service;

    private static final Long PROJECT_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new MonitoringAlertService(
                monitoringAlertRepository,
                notificationService
        );
    }

    private User owner() {
        User user = new User();
        user.setId(100L);
        user.setEmail("owner@test.com");
        return user;
    }

    private String key() {
        return MonitoringAlertService.buildIdempotencyKey(
                AlertType.DATA_DRIFT,
                PROJECT_ID,
                "model-a",
                LocalDateTime.now(Clock.systemUTC())
        );
    }

    @Test
    void raiseAlert_newKey_persistsAndNotifiesOwner() {
        when(monitoringAlertRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        MonitoringAlert saved = new MonitoringAlert();
        saved.setId(1L);
        saved.setAlertType(AlertType.DATA_DRIFT);
        when(monitoringAlertRepository.saveAndFlush(any(MonitoringAlert.class)))
                .thenAnswer(invocation -> {
                    MonitoringAlert alert = invocation.getArgument(0);
                    alert.setId(1L);
                    return alert;
                });

        MonitoringAlert result = service.raiseAlert(
                AlertType.DATA_DRIFT,
                AlertSeverity.WARNING,
                PROJECT_ID,
                "model-a",
                "3",
                16.4,
                10.0,
                "DATA_DRIFT",
                "Réentraîner le modèle.",
                "Drift 16.4% seuil 10%.",
                key(),
                owner()
        );

        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(notificationService, times(1)).notifyUser(
                any(User.class),
                anyString(),
                anyString(),
                any(),
                any(),
                anyLong()
        );
        verify(notificationService, never()).notifyAdmins(
                anyString(), anyString(), any(), any(), anyLong()
        );
    }

    @Test
    void raiseAlert_duplicateKey_returnsNull() {
        when(monitoringAlertRepository.existsByIdempotencyKey(anyString())).thenReturn(true);

        MonitoringAlert result = service.raiseAlert(
                AlertType.DATA_DRIFT,
                AlertSeverity.WARNING,
                PROJECT_ID,
                "model-a",
                "3",
                16.4,
                10.0,
                "DATA_DRIFT",
                "Réentraîner le modèle.",
                "Drift 16.4% seuil 10%.",
                key(),
                owner()
        );

        assertNull(result);
        verify(monitoringAlertRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).notifyUser(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void raiseAlert_critical_notifiesAdmins() {
        when(monitoringAlertRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        MonitoringAlert saved = new MonitoringAlert();
        saved.setId(2L);
        when(monitoringAlertRepository.saveAndFlush(any(MonitoringAlert.class)))
                .thenAnswer(invocation -> {
                    MonitoringAlert alert = invocation.getArgument(0);
                    alert.setId(2L);
                    return alert;
                });

        MonitoringAlert result = service.raiseAlert(
                AlertType.PERFORMANCE_DROP,
                AlertSeverity.CRITICAL,
                PROJECT_ID,
                "model-a",
                "3",
                25.0,
                5.0,
                "PERFORMANCE_DROP",
                "Réentraîner le modèle.",
                "Baisse de performance 25%.",
                key(),
                owner()
        );

        assertNotNull(result);
        verify(notificationService, times(1)).notifyUser(any(), any(), any(), any(), any(), anyLong());
        verify(notificationService, times(1)).notifyAdmins(anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void buildIdempotencyKey_isStableForSameWindow() {
        LocalDateTime window = LocalDateTime.of(2026, 8, 19, 12, 0);

        String key1 = MonitoringAlertService.buildIdempotencyKey(
                AlertType.DATA_DRIFT, PROJECT_ID, "model-a", window
        );
        String key2 = MonitoringAlertService.buildIdempotencyKey(
                AlertType.DATA_DRIFT, PROJECT_ID, "model-a", window
        );

        assertEquals(key1, key2);
        assertTrue(key1.startsWith("DATA_DRIFT:7:model-a:"));
    }

    @Test
    void buildExperimentIdKey_isStable() {
        String key1 = MonitoringAlertService.buildExperimentIdKey(
                AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 42L
        );
        String key2 = MonitoringAlertService.buildExperimentIdKey(
                AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 42L
        );

        assertEquals(key1, key2);
        assertEquals("MODEL_QUALITY_FAILED:7:exp:42", key1);
    }

    @Test
    void buildExperimentIdKey_differentExperiment_differentKey() {
        String key1 = MonitoringAlertService.buildExperimentIdKey(
                AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 42L
        );
        String key2 = MonitoringAlertService.buildExperimentIdKey(
                AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 99L
        );

        assertNotEquals(key1, key2);
    }

    @Test
    void raiseAlert_withTitle_usesCustomTitle() {
        when(monitoringAlertRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(monitoringAlertRepository.existsByAlertTypeAndProjectIdAndModelVersion(
                any(), anyLong(), anyString()
        )).thenReturn(false);

        MonitoringAlert saved = new MonitoringAlert();
        saved.setId(5L);
        when(monitoringAlertRepository.saveAndFlush(any(MonitoringAlert.class)))
                .thenAnswer(invocation -> {
                    MonitoringAlert alert = invocation.getArgument(0);
                    alert.setId(5L);
                    return alert;
                });

        MonitoringAlert result = service.raiseAlert(
                AlertType.MODEL_QUALITY_FAILED,
                AlertSeverity.WARNING,
                PROJECT_ID,
                "wind_speed_model",
                "1",
                0.71,
                0.75,
                "GLOBAL_SCORE_BELOW_THRESHOLD",
                "Reevaluer le modele.",
                "Le score est inferieur au seuil.",
                "Modele non promu en Production",
                MonitoringAlertService.buildExperimentIdKey(
                        AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 42L
                ),
                owner()
        );

        assertNotNull(result);
        verify(notificationService, times(1)).notifyUser(
                any(User.class),
                eq("Modele non promu en Production"),
                anyString(),
                any(),
                any(),
                anyLong()
        );
    }

    @Test
    void raiseAlert_modelVersionDuplicate_returnsNull() {
        when(monitoringAlertRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(monitoringAlertRepository.existsByAlertTypeAndProjectIdAndModelVersion(
                any(), anyLong(), anyString()
        )).thenReturn(true);

        MonitoringAlert result = service.raiseAlert(
                AlertType.MODEL_QUALITY_FAILED,
                AlertSeverity.WARNING,
                PROJECT_ID,
                "wind_speed_model",
                "1",
                0.71,
                0.75,
                "GLOBAL_SCORE_BELOW_THRESHOLD",
                "Reevaluer.",
                "Score inferieur.",
                MonitoringAlertService.buildExperimentIdKey(
                        AlertType.MODEL_QUALITY_FAILED, PROJECT_ID, 42L
                ),
                owner()
        );

        assertNull(result);
        verify(monitoringAlertRepository, never()).saveAndFlush(any());
    }

    @Test
    void buildHourlyIdempotencyKey_ignoresMinutes() {
        LocalDateTime t1 = LocalDateTime.of(2026, 8, 19, 12, 5, 30);
        LocalDateTime t2 = LocalDateTime.of(2026, 8, 19, 12, 45, 10);

        String key1 = MonitoringAlertService.buildHourlyIdempotencyKey(
                AlertType.PREDICTION_ERROR_RATE, PROJECT_ID, "model-a", t1
        );
        String key2 = MonitoringAlertService.buildHourlyIdempotencyKey(
                AlertType.PREDICTION_ERROR_RATE, PROJECT_ID, "model-a", t2
        );

        assertEquals(key1, key2);
    }
}