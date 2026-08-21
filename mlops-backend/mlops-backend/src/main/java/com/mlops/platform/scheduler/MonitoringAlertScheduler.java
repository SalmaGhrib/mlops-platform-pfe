package com.mlops.platform.scheduler;

import com.mlops.platform.dto.MonitoringSummaryResponse;
import com.mlops.platform.dto.RetrainingDecisionResult;
import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingTriggerType;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlExperimentRepository.FailedRunsByProjectProjection;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import com.mlops.platform.service.ModelQualityService;
import com.mlops.platform.service.MonitoringAlertService;
import com.mlops.platform.service.MonitoringRetrainingTriggerService;
import com.mlops.platform.service.MonitoringService;
import com.mlops.platform.service.NotificationService;
import com.mlops.platform.service.RetrainingDecisionService;

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
    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlProjectRepository mlProjectRepository;
    private final RetrainingDecisionService retrainingDecisionService;
    private final ModelQualityService modelQualityService;
    private final MonitoringAlertService monitoringAlertService;

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
            MonitoringRetrainingTriggerService monitoringRetrainingTriggerService,
            RetrainingConfigRepository retrainingConfigRepository,
            MlProjectRepository mlProjectRepository,
            RetrainingDecisionService retrainingDecisionService,
            ModelQualityService modelQualityService,
            MonitoringAlertService monitoringAlertService
    ) {
        this.monitoringService = monitoringService;
        this.notificationService = notificationService;
        this.mlExperimentRepository = mlExperimentRepository;
        this.monitoringRetrainingTriggerService =
                monitoringRetrainingTriggerService;
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.retrainingDecisionService = retrainingDecisionService;
        this.modelQualityService = modelQualityService;
        this.monitoringAlertService = monitoringAlertService;
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
            checkProjectQualityAndSignals();

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

                /*
                 * Les runs échoués sont un signal TECHNIQUE (Job Docker,
                 * MLflow, déploiement) : on alerte mais on ne déclenche
                 * JAMAIS de réentraînement automatique. Un modèle qui tourne
                 * mal ne se corrige pas en ré-entraînant sur des données
                 * potentiellement corrompues.
                 */
                System.out.println(
                        "[MONITORING_ALERT] FAILED_RUNS signal detected. "
                                + "projectId="
                                + projectId
                                + ", failedRuns="
                                + failedRuns
                                + ", threshold="
                                + failedRunsByProjectThreshold
                                + ", retraining=NOT_TRIGGERED"
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

    /**
     * Alertes applicatives par projet :
     * 1. Gate qualité : modèle sous le seuil minimal -> MODEL_QUALITY_FAILED.
     * 2. Signaux monitoring -> PREDICTION_ERROR_RATE / DATA_DRIFT /
     *    PERFORMANCE_DROP.
     *
     * Contrairement aux runs échoués, ces signaux sont des indicateurs de
     * SANTÉ DU MODÈLE : ils peuvent déclencher un réentraînement via le
     * moteur de décision (stratégie, seuils, cooldown, running state).
     */
    private void checkProjectQualityAndSignals() {
        try {
            List<RetrainingConfig> configs =
                    retrainingConfigRepository.findEnabledConfigs();

            if (configs == null || configs.isEmpty()) {
                System.out.println(
                        "[MONITORING_ALERT] No enabled retraining config. "
                                + "Project-level alerts skipped."
                );
                return;
            }

            for (RetrainingConfig config : configs) {
                if (config == null || config.getProjectId() == null) {
                    continue;
                }

                MlProject project = mlProjectRepository
                        .findById(config.getProjectId())
                        .orElse(null);

                if (project == null) {
                    continue;
                }

                modelQualityService.raiseQualityAlertIfNeeded(project);
                raiseSignalAlerts(project, config);
            }

        } catch (Exception projectAlertError) {
            System.err.println(
                    "[MONITORING_ALERT] Project-level alert evaluation failed. reason="
                            + safeMessage(projectAlertError.getMessage())
            );
            projectAlertError.printStackTrace();
        }
    }

    private void raiseSignalAlerts(MlProject project, RetrainingConfig config) {
        if (project == null || config == null) {
            return;
        }

        RetrainingDecisionResult decision =
                retrainingDecisionService.evaluateMonitoringSignals(config);

        if (decision == null || !decision.isShouldRetrain()) {
            return;
        }

        RetrainingTriggerType triggerType = decision.getTriggerType();
        AlertType alertType = mapToAlertType(triggerType);
        if (alertType == null) {
            return;
        }

        AlertSeverity severity = config.getDataDriftThresholdPercent() != null
                && AlertType.DATA_DRIFT == alertType
                && decision.getDataDriftScorePercent() >= config.getDataDriftThresholdPercent() * 2
                ? AlertSeverity.CRITICAL
                : AlertSeverity.WARNING;

        double observedValue = switch (triggerType) {
            case PREDICTION_ERRORS -> decision.getPredictionErrorRatePercent();
            case DATA_DRIFT -> decision.getDataDriftScorePercent();
            case PERFORMANCE_DROP -> decision.getPerformanceDropPercent();
            default -> 0.0;
        };

        double thresholdValue = switch (triggerType) {
            case PREDICTION_ERRORS -> safeDouble(config.getMaxPredictionErrorRatePercent(), 20.0);
            case DATA_DRIFT -> safeDouble(config.getDataDriftThresholdPercent(), 25.0);
            case PERFORMANCE_DROP -> safeDouble(config.getPerformanceDropThresholdPercent(), 5.0);
            default -> 0.0;
        };

        String idempotencyKey = MonitoringAlertService.buildHourlyIdempotencyKey(
                alertType,
                project.getId(),
                null,
                java.time.LocalDateTime.now()
        );

        monitoringAlertService.raiseAlert(
                alertType,
                severity,
                project.getId(),
                null,
                null,
                observedValue,
                thresholdValue,
                triggerType.name(),
                "Vérifier les prédictions récentes et envisager un réentraînement.",
                "Alerte détectée pour le projet \""
                        + safeName(project.getName(), project.getId())
                        + "\" : "
                        + safeMessage(decision.getReason()),
                idempotencyKey,
                project.getOwner()
        );

        System.out.println(
                "[MONITORING_ALERT] Signal alert raised. projectId="
                        + project.getId()
                        + ", alertType="
                        + alertType
                        + ", observed="
                        + observedValue
                        + ", threshold="
                        + thresholdValue
        );
    }

    private AlertType mapToAlertType(RetrainingTriggerType triggerType) {
        if (triggerType == null) {
            return null;
        }

        return switch (triggerType) {
            case PREDICTION_ERRORS -> AlertType.PREDICTION_ERROR_RATE;
            case DATA_DRIFT -> AlertType.DATA_DRIFT;
            case PERFORMANCE_DROP -> AlertType.PERFORMANCE_DROP;
            default -> null;
        };
    }

    private String safeName(String value, Long fallbackId) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return "project " + (fallbackId != null ? fallbackId : "?");
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

    private double safeDouble(Double value, double fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}