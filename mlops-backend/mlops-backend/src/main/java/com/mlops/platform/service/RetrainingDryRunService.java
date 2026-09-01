package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingDecision;
import com.mlops.platform.dto.RetrainingDryRunResponse;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingState;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Diagnostic DRY-RUN global du réentraînement intelligent.
 *
 * Évalue chaque configuration en lecture seule (source + décision) et
 * simule l'action que prendrait le scheduler, SANS AUCUNE écriture.
 * Il est exécuté avant toute réactivation réelle des schedulers afin de
 * valider qu'aucun lancement en masse des retrainings historiques échus
 * ne se produira.
 */
@Service
public class RetrainingDryRunService {

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final RetrainingDecisionService retrainingDecisionService;
    private final RetrainingSourceResolverService sourceResolver;

    public RetrainingDryRunService(
            RetrainingConfigRepository retrainingConfigRepository,
            RetrainingDecisionService retrainingDecisionService,
            RetrainingSourceResolverService sourceResolver
    ) {
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.retrainingDecisionService = retrainingDecisionService;
        this.sourceResolver = sourceResolver;
    }

    public RetrainingDryRunResponse diagnose() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        RetrainingDryRunResponse response = new RetrainingDryRunResponse();
        response.setEvaluatedAt(now);

        List<RetrainingConfig> configs = retrainingConfigRepository.findEnabledConfigs();

        if (configs == null || configs.isEmpty()) {
            return response;
        }

        for (RetrainingConfig config : configs) {
            try {
                RetrainingDryRunResponse.ConfigDryRun entry =
                        evaluate(config, now);

                response.getConfigs().add(entry);

                switch (entry.getAction()) {
                    case WOULD_RUN -> response.setWouldRun(
                            response.getWouldRun() + 1);
                    case WOULD_SKIP -> response.setWouldSkip(
                            response.getWouldSkip() + 1);
                    case NO_VALID_SOURCE -> response.setNoSource(
                            response.getNoSource() + 1);
                    case BLOCKED_BY_SECURITY -> response.setBlockedBySecurity(
                            response.getBlockedBySecurity() + 1);
                    case INVALID_CONFIG -> response.setInvalidConfigs(
                            response.getInvalidConfigs() + 1);
                    case NOT_DUE -> response.setNotDue(
                            response.getNotDue() + 1);
                }

            } catch (Exception evaluationError) {
                System.err.println(
                        "[RETRAINING_DRY_RUN] Evaluation failed. "
                                + "configId="
                                + config.getId()
                                + ", projectId="
                                + config.getProjectId()
                                + ", reason="
                                + safeMessage(evaluationError.getMessage())
                );
            }
        }

        System.out.println(
                "[RETRAINING_DRY_RUN] Global diagnosis finished. "
                        + "configs="
                        + response.getConfigs().size()
                        + ", wouldRun="
                        + response.getWouldRun()
                        + ", wouldSkip="
                        + response.getWouldSkip()
                        + ", noSource="
                        + response.getNoSource()
                        + ", blockedBySecurity="
                        + response.getBlockedBySecurity()
                        + ", invalid="
                        + response.getInvalidConfigs()
                        + ", notDue="
                        + response.getNotDue()
        );

        return response;
    }

    private RetrainingDryRunResponse.ConfigDryRun evaluate(
            RetrainingConfig config,
            LocalDateTime now
    ) {
        RetrainingDryRunResponse.ConfigDryRun entry =
                new RetrainingDryRunResponse.ConfigDryRun();

        entry.setConfigId(config.getId());
        entry.setProjectId(config.getProjectId());
        entry.setEnabled(config.isEnabled());
        entry.setState(config.getState());
        entry.setStrategy(config.getStrategy());
        entry.setLastRetrainingAt(config.getLastRetrainingAt());
        entry.setManualRequest(config.isManualTriggerRequested());
        entry.setCachedSourceExperimentId(config.getSourceExperimentId());

        if (config.getProjectId() == null) {
            entry.setAction(
                    RetrainingDryRunResponse.RecommendedAction.INVALID_CONFIG
            );
            entry.setWouldRetrain(false);
            entry.setReason("Configuration invalide : projectId absent.");
            return entry;
        }

        if (!config.isEnabled()) {
            entry.setAction(
                    RetrainingDryRunResponse.RecommendedAction.INVALID_CONFIG
            );
            entry.setWouldRetrain(false);
            entry.setReason("Configuration désactivée.");
            return entry;
        }

        if (config.getState() == RetrainingState.EVALUATING
                || config.getState() == RetrainingState.TRIGGERED
                || config.getState() == RetrainingState.RUNNING) {
            entry.setAction(
                    RetrainingDryRunResponse.RecommendedAction.NOT_DUE
            );
            entry.setWouldRetrain(false);
            entry.setReason(
                    "Tentative en cours : état " + config.getState() + "."
            );
            return entry;
        }

        boolean sourceReady =
                sourceResolver.resolveSourceExperimentIdOrNull(
                        config.getProjectId(),
                        config.getSourceExperimentId()
                ) != null;

        entry.setSourceReady(sourceReady);

        RetrainingDecision decision =
                retrainingDecisionService.buildDecision(
                        config,
                        now,
                        sourceReady
                );

        entry.setWouldRetrain(decision.isShouldRetrain());
        entry.setTriggerType(decision.getTriggerType());
        entry.setSeverity(decision.getSeverity());
        entry.setSecurityLevel(decision.getSecurityLevel());
        entry.setReasonCodes(decision.getReasonCodes());
        entry.setReason(decision.getReason());

        if (decision.getSecurityLevel()
                == RetrainingSecurityLevel.BLOCK_MODEL) {
            entry.setAction(
                    RetrainingDryRunResponse.RecommendedAction
                            .BLOCKED_BY_SECURITY
            );
            return entry;
        }

        if (!decision.isShouldRetrain()) {
            entry.setAction(
                    decision.getReasonCodes().contains("NO_VALID_SOURCE")
                            ? RetrainingDryRunResponse.RecommendedAction
                            .NO_VALID_SOURCE
                            : RetrainingDryRunResponse.RecommendedAction
                            .WOULD_SKIP
            );
            return entry;
        }

        entry.setAction(
                RetrainingDryRunResponse.RecommendedAction.WOULD_RUN
        );
        return entry;
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Erreur inconnue";
        }
        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }
}
