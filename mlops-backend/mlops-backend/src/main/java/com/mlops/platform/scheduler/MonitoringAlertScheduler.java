package com.mlops.platform.scheduler;

import com.mlops.platform.dto.MonitoringSummaryResponse;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlExperimentRepository.FailedRunsByProjectProjection;
import com.mlops.platform.service.MonitoringRetrainingTriggerService;
import com.mlops.platform.service.MonitoringService;
import com.mlops.platform.service.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MonitoringAlertScheduler {

    private final MonitoringService monitoringService;
    private final NotificationService notificationService;
    private final MlExperimentRepository mlExperimentRepository;
    private final MonitoringRetrainingTriggerService monitoringRetrainingTriggerService;

    @Value("${app.monitoring.alerts.enabled:true}")
    private boolean monitoringAlertsEnabled;

    @Value("${monitoring.alert.failed-runs-threshold:1}")
    private double failedRunsThreshold;

    @Value("${monitoring.alert.failed-deployments-threshold:1}")
    private double failedDeploymentsThreshold;

    @Value("${monitoring.alert.unavailable-models-threshold:1}")
    private double unavailableModelsThreshold;

    @Value("${monitoring.alert.backend-latency-ms-threshold:1000}")
    private double backendLatencyMsThreshold;

    @Value("${monitoring.alert.failed-runs-by-project-threshold:1}")
    private long failedRunsByProjectThreshold;

    @Value("${monitoring.alert.failed-runs-lookback-hours:24}")
    private int failedRunsLookbackHours;

    public MonitoringAlertScheduler(
            MonitoringService monitoringService,
            NotificationService notificationService,
            MlExperimentRepository mlExperimentRepository,
            MonitoringRetrainingTriggerService monitoringRetrainingTriggerService
    ) {
        this.monitoringService = monitoringService;
        this.notificationService = notificationService;
        this.mlExperimentRepository = mlExperimentRepository;
        this.monitoringRetrainingTriggerService = monitoringRetrainingTriggerService;
    }

    @Scheduled(fixedDelayString = "${app.monitoring.alerts.delay-ms:60000}")
    public void checkMonitoringAlerts() {
        if (!monitoringAlertsEnabled) {
            return;
        }

        try {
            System.out.println("[MONITORING_ALERT] Checking monitoring thresholds...");

            MonitoringSummaryResponse summary = monitoringService.getSummary();

            if (summary == null) {
                System.out.println("[MONITORING_ALERT] Monitoring summary is null. Skipped.");
                return;
            }

            checkGlobalFailedRuns(summary);
            checkFailedDeployments(summary);
            checkUnavailableModels(summary);
            checkBackendLatency(summary);

            checkFailedRunsByProjectAndTriggerRetraining();
            checkSmartMonitoringRetrainingTriggers();

        } catch (Exception e) {
            System.err.println("[MONITORING_ALERT] Failed to check monitoring alerts: "
                    + e.getMessage());
        }
    }

    private void checkGlobalFailedRuns(MonitoringSummaryResponse summary) {
        double failedRuns = summary.getFailedRuns();

        if (failedRuns >= failedRunsThreshold) {
            Long alertTargetId = dailyAlertTargetId(101);

            notificationService.notifyFailedRunsAlert(
                    alertTargetId,
                    failedRuns
            );

            System.out.println("[MONITORING_ALERT] Global failed runs alert detected. failedRuns="
                    + failedRuns);
        }
    }

    private void checkFailedRunsByProjectAndTriggerRetraining() {
        List<FailedRunsByProjectProjection> projects =
                mlExperimentRepository.findProjectsWithRecentFailedRuns(
                        failedRunsLookbackHours,
                        failedRunsByProjectThreshold
                );

        if (projects == null || projects.isEmpty()) {
            System.out.println("[MONITORING_ALERT] No project-specific failed run alert found.");
            return;
        }

        for (FailedRunsByProjectProjection item : projects) {
            if (item == null || item.getProjectId() == null) {
                continue;
            }

            Long projectId = item.getProjectId();
            long failedRuns = item.getFailedRuns() != null ? item.getFailedRuns() : 0L;

            Long alertTargetId = dailyProjectAlertTargetId(201, projectId);

            String title = "Alerte monitoring : projet avec runs échoués";
            String message = "Le projet ID "
                    + projectId
                    + " a "
                    + failedRuns
                    + " run(s) échoué(s) sur les "
                    + failedRunsLookbackHours
                    + " dernières heures.";

            notificationService.notifyAdmins(
                    title,
                    message,
                    NotificationType.MONITORING_ALERT,
                    NotificationTargetType.MONITORING,
                    alertTargetId
            );

            boolean retrainingRequested =
                    monitoringRetrainingTriggerService.triggerRetrainingFromMonitoringAlert(
                            projectId,
                            "Failed runs detected by monitoring. failedRuns="
                                    + failedRuns
                                    + ", lookbackHours="
                                    + failedRunsLookbackHours
                    );

            System.out.println("[MONITORING_ALERT] Project failed runs alert. projectId="
                    + projectId
                    + ", failedRuns="
                    + failedRuns
                    + ", retrainingRequested="
                    + retrainingRequested);
        }
    }


    private void checkSmartMonitoringRetrainingTriggers() {
        try {
            int requested = monitoringRetrainingTriggerService.evaluateSmartTriggersForAllConfigs();
            System.out.println("[MONITORING_ALERT] Smart retraining trigger evaluation finished. requested="
                    + requested);
        } catch (Exception e) {
            System.err.println("[MONITORING_ALERT] Smart retraining trigger evaluation failed: "
                    + e.getMessage());
        }
    }

    private void checkFailedDeployments(MonitoringSummaryResponse summary) {
        double failedDeployments = summary.getFailedDeployments();

        if (failedDeployments >= failedDeploymentsThreshold) {
            Long alertTargetId = dailyAlertTargetId(102);

            notificationService.notifyFailedDeploymentsAlert(
                    alertTargetId,
                    failedDeployments
            );

            System.out.println("[MONITORING_ALERT] Failed deployments alert detected. failedDeployments="
                    + failedDeployments);
        }
    }

    private void checkUnavailableModels(MonitoringSummaryResponse summary) {
        double unavailableModels = summary.getUnavailableModelDeployments();

        if (unavailableModels >= unavailableModelsThreshold) {
            Long alertTargetId = dailyAlertTargetId(103);

            notificationService.notifyUnavailableModelsAlert(
                    alertTargetId,
                    unavailableModels
            );

            System.out.println("[MONITORING_ALERT] Unavailable models alert detected. unavailableModels="
                    + unavailableModels);
        }
    }

    private void checkBackendLatency(MonitoringSummaryResponse summary) {
        double latencyMs = summary.getAverageBackendLatencyMs();

        if (latencyMs >= backendLatencyMsThreshold) {
            Long alertTargetId = dailyAlertTargetId(104);

            notificationService.notifyBackendLatencyAlert(
                    alertTargetId,
                    latencyMs
            );

            System.out.println("[MONITORING_ALERT] Backend latency alert detected. latencyMs="
                    + latencyMs);
        }
    }

    private Long dailyAlertTargetId(int alertCode) {
        String datePrefix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return Long.parseLong(datePrefix + alertCode);
    }

    private Long dailyProjectAlertTargetId(int alertCode, Long projectId) {
        String datePrefix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long safeProjectId = projectId != null ? Math.abs(projectId % 100000) : 0L;

        return Long.parseLong(
                datePrefix
                        + alertCode
                        + String.format("%05d", safeProjectId)
        );
    }
}