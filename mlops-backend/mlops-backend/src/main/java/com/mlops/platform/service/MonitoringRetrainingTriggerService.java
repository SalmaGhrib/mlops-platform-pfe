package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MonitoringRetrainingTriggerService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final RetrainingDecisionService retrainingDecisionService;
    private final RetrainingSourceResolverService sourceResolver;
    private final PlatformTransactionManager transactionManager;

    @Value("${monitoring.retraining.cooldown-minutes:120}")
    private long cooldownMinutes;

    public MonitoringRetrainingTriggerService(
            RetrainingConfigRepository retrainingConfigRepository,
            MlExperimentRepository mlExperimentRepository,
            RetrainingDecisionService retrainingDecisionService,
            RetrainingSourceResolverService sourceResolver,
            PlatformTransactionManager transactionManager
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.retrainingDecisionService = retrainingDecisionService;
        this.sourceResolver = sourceResolver;
        this.transactionManager = transactionManager;
    }

    public boolean triggerRetrainingFromMonitoringAlert(
            Long projectId,
            String reason
    ) {
        if (projectId == null) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: projectId is null"
            );
            return false;
        }

        RetrainingConfig config =
                retrainingConfigRepository
                        .findByProjectId(projectId)
                        .orElse(null);

        if (config == null) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: no retraining config "
                            + "for projectId="
                            + projectId
            );
            return false;
        }

        if (!canRequestMonitoringRetraining(config, projectId)) {
            return false;
        }

        boolean requested = requestNow(
                config.getId(),
                "MONITORING_ALERT",
                reason
        );

        System.out.println(
                "[MONITORING_RETRAINING] Retraining requested from "
                        + "monitoring alert. projectId="
                        + projectId
                        + ", requested="
                        + requested
                        + ", reason="
                        + safeMessage(reason)
        );

        return requested;
    }

    /**
     * Analyse toutes les configurations intelligentes actives.
     *
     * Une configuration détectée en drift est marquée par une mise à jour
     * atomique :
     * - manualTriggerRequested=true ;
     * - nextRetrainingAt dans le passé ;
     * - raison et décision conservées.
     */
    public int evaluateSmartTriggersForAllConfigs() {
        List<RetrainingConfig> configs =
                retrainingConfigRepository
                        .findEnabledNotRunningConfigs();

        if (configs == null || configs.isEmpty()) {
            System.out.println(
                    "[MONITORING_RETRAINING] No enabled smart config found."
            );
            return 0;
        }

        int requestedCount = 0;

        for (RetrainingConfig config : configs) {
            if (config == null
                    || config.getId() == null
                    || config.getProjectId() == null) {
                continue;
            }

            RetrainingStrategy strategy = config.getStrategy();

            if (strategy != RetrainingStrategy.SMART_MONITORING_ONLY
                    && strategy != RetrainingStrategy.FULL_SMART) {
                continue;
            }

            Long projectId = config.getProjectId();

            try {
                if (!canRequestMonitoringRetraining(config, projectId)) {
                    continue;
                }

                boolean sourceReady =
                        sourceResolver.resolveSourceExperimentIdOrNull(
                                projectId,
                                config.getSourceExperimentId()
                        ) != null;

                RetrainingDecision decision =
                        retrainingDecisionService.buildDecision(
                                config,
                                utcNow(),
                                sourceReady
                        );

                if (decision == null) {
                    System.out.println(
                            "[MONITORING_RETRAINING] No decision returned. "
                                    + "projectId="
                                    + projectId
                    );
                    continue;
                }

                System.out.println(
                        "[MONITORING_RETRAINING] Decision evaluated. "
                                + "projectId="
                                + projectId
                                + ", shouldRetrain="
                                + decision.isShouldRetrain()
                                + ", trigger="
                                + decision.getTriggerType()
                                + ", severity="
                                + decision.getSeverity()
                                + ", security="
                                + decision.getSecurityLevel()
                                + ", reasonCodes="
                                + decision.getReasonCodes()
                                + ", reason="
                                + safeMessage(decision.getReason())
                );

                if (!decision.isShouldRetrain()) {
                    continue;
                }

                String triggerReason =
                        decision.getTriggerType() != null
                                ? decision.getTriggerType().name()
                                : "SMART_MONITORING";

                boolean requested = requestNow(
                        config.getId(),
                        triggerReason,
                        decision.getReason()
                );

                if (requested) {
                    requestedCount++;
                }

            } catch (Exception exception) {
                System.err.println(
                        "[MONITORING_RETRAINING] Evaluation failed. "
                                + "projectId="
                                + projectId
                                + ", reason="
                                + safeMessage(exception.getMessage())
                );
                exception.printStackTrace();
            }
        }

        System.out.println(
                "[MONITORING_RETRAINING] Smart monitoring evaluation "
                        + "finished. requested="
                        + requestedCount
        );

        return requestedCount;
    }

    private boolean canRequestMonitoringRetraining(
            RetrainingConfig config,
            Long projectId
    ) {
        if (config == null || config.getId() == null) {
            return false;
        }

        if (!config.isEnabled()) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: retraining disabled "
                            + "for projectId="
                            + projectId
            );
            return false;
        }

        if (config.isRunning()) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: config already running "
                            + "for projectId="
                            + projectId
            );
            return false;
        }

        /*
         * Une demande déjà persistée ne doit pas être recréée.
         */
        if (config.isManualTriggerRequested()) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: smart/manual request "
                            + "already pending for projectId="
                            + projectId
            );
            return false;
        }

        Long activeCount =
                mlExperimentRepository
                        .countActiveScheduledRetrainingForProject(projectId);

        if (activeCount != null && activeCount > 0) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: active scheduled "
                            + "retraining already exists for projectId="
                            + projectId
            );
            return false;
        }

        LocalDateTime now = utcNow();

        /*
         * Si la configuration est déjà échue, AutoRetrainingScheduler doit la
         * traiter directement. Le monitoring n'a pas besoin de la redemander.
         */
        if (config.getNextRetrainingAt() != null
                && !config.getNextRetrainingAt().isAfter(now)) {
            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: config already due "
                            + "for scheduler. projectId="
                            + projectId
                            + ", nextRetrainingAt="
                            + config.getNextRetrainingAt()
            );
            return false;
        }

        if (config.getLastRetrainingAt() != null
                && cooldownMinutes > 0
                && config.getLastRetrainingAt()
                .isAfter(now.minusMinutes(cooldownMinutes))) {

            System.out.println(
                    "[MONITORING_RETRAINING] Ignored: cooldown active "
                            + "for projectId="
                            + projectId
                            + ", lastRetrainingAt="
                            + config.getLastRetrainingAt()
                            + ", cooldownMinutes="
                            + cooldownMinutes
            );
            return false;
        }

        return true;
    }

    private boolean requestNow(
            Long configId,
            String triggerReason,
            String decisionReason
    ) {
        if (configId == null) {
            return false;
        }

        LocalDateTime now = utcNow();
        LocalDateTime dueAt = now.minusSeconds(5);

        int updatedRows = executeInNewTransaction(() ->
                retrainingConfigRepository.requestSmartRetraining(
                        configId,
                        dueAt,
                        safeMessage(triggerReason),
                        safeMessage(decisionReason),
                        now
                )
        );

        System.out.println(
                "[MONITORING_RETRAINING] Atomic request. configId="
                        + configId
                        + ", updatedRows="
                        + updatedRows
                        + ", dueAt="
                        + dueAt
                        + ", trigger="
                        + safeMessage(triggerReason)
        );

        return updatedRows == 1;
    }

    private int executeInNewTransaction(
            IntTransactionCallback callback
    ) {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(10);

        Integer result =
                template.execute(status -> callback.execute());

        return result == null ? 0 : result;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(Clock.systemUTC());
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Aucune raison fournie";
        }

        String clean = message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();

        return clean.length() > 500
                ? clean.substring(0, 497) + "..."
                : clean;
    }

    @FunctionalInterface
    private interface IntTransactionCallback {
        int execute();
    }
}
