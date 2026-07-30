package com.mlops.platform.scheduler;

import com.mlops.platform.dto.MonitoringSummaryResponse;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlExperimentRepository.FailedRunsByProjectProjection;
import com.mlops.platform.service.MonitoringRetrainingTriggerService;
import com.mlops.platform.service.MonitoringService;
import com.mlops.platform.service.NotificationService;

import jakarta.annotation.PostConstruct;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "app.monitoring.alerts.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MonitoringAlertScheduler {

    private final MonitoringService monitoringService;
    private final NotificationService notificationService;
    private final MlExperimentRepository mlExperimentRepository;
    private final MonitoringRetrainingTriggerService monitoringRetrainingTriggerService;

    private final AtomicBoolean localCycleRunning = new AtomicBoolean(false);

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
        this.monitoringRetrainingTriggerService =
                monitoringRetrainingTriggerService;
    }

    @PostConstruct
    public void init() {
        System.out.println(
                "[MONITORING_ALERT] Scheduler loaded. enabled="
                        + monitoringAlertsEnabled
        );
    }

    @Scheduled(
            initialDelayString =
                    "${app.monitoring.alerts.initial-delay-ms:30000}",
            fixedDelayString =
                    "${app.monitoring.alerts.delay-ms:60000}"
    )
    @SchedulerLock(
            name = "monitoringAlertScheduler",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT5S"
    )
    public void checkMonitoringAlerts() {
        if (!monitoringAlertsEnabled) {
            System.out.println(
                    "[MONITORING_ALERT] Scheduler disabled by configuration."
            );
            return;
        }

        if (!localCycleRunning.compareAndSet(false, true)) {
            System.out.println(
                    "[MONITORING_ALERT] Previous local cycle still running. Skipped."
            );
            return;
        }

        try {
            System.out.println(
                    "[MONITORING_ALERT] Starting monitoring cycle."
            );

            /*
             * Les déclencheurs de réentraînement intelligent sont évalués
             * indépendamment du résumé Prometheus.
             *
             * Ainsi, une panne temporaire de Prometheus ne bloque pas
             * SMART_MONITORING_ONLY.
             */
            checkFailedRunsByProjectAndTriggerRetraining();
            checkSmartMonitoringRetrainingTriggers();

            /*
             * Le résumé global sert principalement aux alertes administrateur.
             */
            checkGlobalMonitoringAlerts();

            System.out.println(
                    "[MONITORING_ALERT] Monitoring cycle completed."
            );

        } catch (Exception cycleError) {
            System.err.println(
                    "[MONITORING_ALERT] Monitoring cycle failed. type="
                            + cycleError.getClass().getSimpleName()
                            + ", reason="
                            + safeMessage(cycleError.getMessage())
            );
            cycleError.printStackTrace();

        } finally {
            localCycleRunning.set(false);
        }
    }

    private void checkGlobalMonitoringAlerts() {
        try {
            MonitoringSummaryResponse summary = monitoringService.getSummary();

            if (summary == null) {
                System.out.println(
                        "[MONITORING_ALERT] Monitoring summary is null. "
                                + "Global alerts skipped."
                );
                return;
            }

            checkGlobalFailedRuns(summary);
            checkFailedDeployments(summary);
            checkUnavailableModels(summary);
            checkBackendLatency(summary);

        } catch (Exception summaryError) {
            /*
             * Une erreur Prometheus ou une erreur de résumé ne doit pas
             * empêcher la détection intelligente basée sur MySQL.
             */
            System.err.println(
                    "[MONITORING_ALERT] Global summary unavailable. "
                            + "Smart retraining evaluation was still executed. "
                            + "reason="
                            + safeMessage(summaryError.getMessage())
            );
        }
    }

    private void checkGlobalFailedRuns(
            MonitoringSummaryResponse summary
    ) {
        double failedRuns = summary.getFailedRuns();

        if (failedRuns < failedRunsThreshold) {
            return;
        }

        Long alertTargetId = dailyAlertTargetId(101);

        notificationService.notifyFailedRunsAlert(
                alertTargetId,
                failedRuns
        );

        System.out.println(
                "[MONITORING_ALERT] Global failed-runs alert detected. "
                        + "failedRuns="
                        + failedRuns
                        + ", threshold="
                        + failedRunsThreshold
        );
    }

    private void checkFailedRunsByProjectAndTriggerRetraining() {
        try {
            List<FailedRunsByProjectProjection> projects =
                    mlExperimentRepository
                            .findProjectsWithRecentFailedRuns(
                                    failedRunsLookbackHours,
                                    failedRunsByProjectThreshold
                            );

            if (projects == null || projects.isEmpty()) {
                System.out.println(
                        "[MONITORING_ALERT] No project with recent failed runs. "
                                + "lookbackHours="
                                + failedRunsLookbackHours
                                + ", threshold="
                                + failedRunsByProjectThreshold
                );
                return;
            }

            for (FailedRunsByProjectProjection item : projects) {
                if (item == null || item.getProjectId() == null) {
                    continue;
                }

                Long projectId = item.getProjectId();
                long failedRuns = item.getFailedRuns() == null
                        ? 0L
                        : item.getFailedRuns();

                if (failedRuns < failedRunsByProjectThreshold) {
                    continue;
                }

                Long alertTargetId =
                        dailyProjectAlertTargetId(201, projectId);

                String title =
                        "Alerte monitoring : runs échoués détectés";

                String message =
                        "Le projet ID "
                                + projectId
                                + " possède "
                                + failedRuns
                                + " run(s) échoué(s) pendant les "
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
                        monitoringRetrainingTriggerService
                                .triggerRetrainingFromMonitoringAlert(
                                        projectId,
                                        "FAILED_RUNS detected. failedRuns="
                                                + failedRuns
                                                + ", lookbackHours="
                                                + failedRunsLookbackHours
                                );

                System.out.println(
                        "[MONITORING_ALERT] FAILED_RUNS signal evaluated. "
                                + "projectId="
                                + projectId
                                + ", failedRuns="
                                + failedRuns
                                + ", threshold="
                                + failedRunsByProjectThreshold
                                + ", retrainingRequested="
                                + retrainingRequested
                );
            }

        } catch (Exception failedRunError) {
            System.err.println(
                    "[MONITORING_ALERT] Failed-runs evaluation failed. reason="
                            + safeMessage(failedRunError.getMessage())
            );
            failedRunError.printStackTrace();
        }
    }

    private void checkSmartMonitoringRetrainingTriggers() {
        try {
            int requested =
                    monitoringRetrainingTriggerService
                            .evaluateSmartTriggersForAllConfigs();

            System.out.println(
                    "[MONITORING_ALERT] Smart trigger evaluation completed. "
                            + "requested="
                            + requested
            );

        } catch (Exception smartTriggerError) {
            System.err.println(
                    "[MONITORING_ALERT] Smart trigger evaluation failed. "
                            + "reason="
                            + safeMessage(smartTriggerError.getMessage())
            );
            smartTriggerError.printStackTrace();
        }
    }

    private void checkFailedDeployments(
            MonitoringSummaryResponse summary
    ) {
        double failedDeployments = summary.getFailedDeployments();

        if (failedDeployments < failedDeploymentsThreshold) {
            return;
        }

        Long alertTargetId = dailyAlertTargetId(102);

        notificationService.notifyFailedDeploymentsAlert(
                alertTargetId,
                failedDeployments
        );

        System.out.println(
                "[MONITORING_ALERT] Failed-deployments alert detected. "
                        + "failedDeployments="
                        + failedDeployments
                        + ", threshold="
                        + failedDeploymentsThreshold
        );
    }

    private void checkUnavailableModels(
            MonitoringSummaryResponse summary
    ) {
        double unavailableModels =
                summary.getUnavailableModelDeployments();

        if (unavailableModels < unavailableModelsThreshold) {
            return;
        }

        Long alertTargetId = dailyAlertTargetId(103);

        notificationService.notifyUnavailableModelsAlert(
                alertTargetId,
                unavailableModels
        );

        System.out.println(
                "[MONITORING_ALERT] Unavailable-models alert detected. "
                        + "unavailableModels="
                        + unavailableModels
                        + ", threshold="
                        + unavailableModelsThreshold
        );
    }

    private void checkBackendLatency(
            MonitoringSummaryResponse summary
    ) {
        double latencyMs = summary.getAverageBackendLatencyMs();

        if (latencyMs < backendLatencyMsThreshold) {
            return;
        }

        Long alertTargetId = dailyAlertTargetId(104);

        notificationService.notifyBackendLatencyAlert(
                alertTargetId,
                latencyMs
        );

        System.out.println(
                "[MONITORING_ALERT] Backend-latency alert detected. "
                        + "latencyMs="
                        + latencyMs
                        + ", threshold="
                        + backendLatencyMsThreshold
        );
    }

    private Long dailyAlertTargetId(int alertCode) {
        String datePrefix =
                LocalDate.now()
                        .format(DateTimeFormatter.BASIC_ISO_DATE);

        return Long.parseLong(datePrefix + alertCode);
    }

    private Long dailyProjectAlertTargetId(
            int alertCode,
            Long projectId
    ) {
        String datePrefix =
                LocalDate.now()
                        .format(DateTimeFormatter.BASIC_ISO_DATE);

        long safeProjectId = projectId == null
                ? 0L
                : Math.abs(projectId % 100000);

        return Long.parseLong(
                datePrefix
                        + alertCode
                        + String.format("%05d", safeProjectId)
        );
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }
}