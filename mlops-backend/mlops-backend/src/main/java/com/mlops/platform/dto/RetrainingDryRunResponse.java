package com.mlops.platform.dto;

import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingState;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.entity.RetrainingTriggerType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Résultat du diagnostic DRY-RUN global du réentraînement.
 *
 * Aucune écriture n'est effectuée : chaque configuration est évaluée en
 * lecture seule et classée selon l'action qui serait prise au prochain
 * cycle du scheduler.
 */
public class RetrainingDryRunResponse {

    public enum RecommendedAction {
        WOULD_RUN,
        WOULD_SKIP,
        NO_VALID_SOURCE,
        BLOCKED_BY_SECURITY,
        INVALID_CONFIG,
        NOT_DUE
    }

    private LocalDateTime evaluatedAt;
    private List<ConfigDryRun> configs = new ArrayList<>();
    private int wouldRun;
    private int wouldSkip;
    private int noSource;
    private int blockedBySecurity;
    private int invalidConfigs;
    private int notDue;

    public static class ConfigDryRun {
        private Long configId;
        private Long projectId;
        private boolean enabled;
        private RetrainingState state;
        private RetrainingStrategy strategy;
        private String frequency;
        private LocalDateTime nextRetrainingAt;
        private LocalDateTime lastRetrainingAt;
        private boolean due;
        private boolean manualRequest;
        private Long cachedSourceExperimentId;
        private boolean sourceReady;
        private RecommendedAction action;
        private boolean wouldRetrain;
        private RetrainingTriggerType triggerType;
        private RetrainingSeverity severity;
        private RetrainingSecurityLevel securityLevel;
        private List<String> reasonCodes = new ArrayList<>();
        private String reason;

        public Long getConfigId() {
            return configId;
        }

        public void setConfigId(Long configId) {
            this.configId = configId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RetrainingState getState() {
            return state;
        }

        public void setState(RetrainingState state) {
            this.state = state;
        }

        public RetrainingStrategy getStrategy() {
            return strategy;
        }

        public void setStrategy(RetrainingStrategy strategy) {
            this.strategy = strategy;
        }

        public String getFrequency() {
            return frequency;
        }

        public void setFrequency(String frequency) {
            this.frequency = frequency;
        }

        public LocalDateTime getNextRetrainingAt() {
            return nextRetrainingAt;
        }

        public void setNextRetrainingAt(LocalDateTime nextRetrainingAt) {
            this.nextRetrainingAt = nextRetrainingAt;
        }

        public LocalDateTime getLastRetrainingAt() {
            return lastRetrainingAt;
        }

        public void setLastRetrainingAt(LocalDateTime lastRetrainingAt) {
            this.lastRetrainingAt = lastRetrainingAt;
        }

        public boolean isDue() {
            return due;
        }

        public void setDue(boolean due) {
            this.due = due;
        }

        public boolean isManualRequest() {
            return manualRequest;
        }

        public void setManualRequest(boolean manualRequest) {
            this.manualRequest = manualRequest;
        }

        public Long getCachedSourceExperimentId() {
            return cachedSourceExperimentId;
        }

        public void setCachedSourceExperimentId(Long cachedSourceExperimentId) {
            this.cachedSourceExperimentId = cachedSourceExperimentId;
        }

        public boolean isSourceReady() {
            return sourceReady;
        }

        public void setSourceReady(boolean sourceReady) {
            this.sourceReady = sourceReady;
        }

        public RecommendedAction getAction() {
            return action;
        }

        public void setAction(RecommendedAction action) {
            this.action = action;
        }

        public boolean isWouldRetrain() {
            return wouldRetrain;
        }

        public void setWouldRetrain(boolean wouldRetrain) {
            this.wouldRetrain = wouldRetrain;
        }

        public RetrainingTriggerType getTriggerType() {
            return triggerType;
        }

        public void setTriggerType(RetrainingTriggerType triggerType) {
            this.triggerType = triggerType;
        }

        public RetrainingSeverity getSeverity() {
            return severity;
        }

        public void setSeverity(RetrainingSeverity severity) {
            this.severity = severity;
        }

        public RetrainingSecurityLevel getSecurityLevel() {
            return securityLevel;
        }

        public void setSecurityLevel(RetrainingSecurityLevel securityLevel) {
            this.securityLevel = securityLevel;
        }

        public List<String> getReasonCodes() {
            return reasonCodes;
        }

        public void setReasonCodes(List<String> reasonCodes) {
            this.reasonCodes = reasonCodes != null ? reasonCodes : new ArrayList<>();
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public List<ConfigDryRun> getConfigs() {
        return configs;
    }

    public void setConfigs(List<ConfigDryRun> configs) {
        this.configs = configs != null ? configs : new ArrayList<>();
    }

    public int getWouldRun() {
        return wouldRun;
    }

    public void setWouldRun(int wouldRun) {
        this.wouldRun = wouldRun;
    }

    public int getWouldSkip() {
        return wouldSkip;
    }

    public void setWouldSkip(int wouldSkip) {
        this.wouldSkip = wouldSkip;
    }

    public int getNoSource() {
        return noSource;
    }

    public void setNoSource(int noSource) {
        this.noSource = noSource;
    }

    public int getBlockedBySecurity() {
        return blockedBySecurity;
    }

    public void setBlockedBySecurity(int blockedBySecurity) {
        this.blockedBySecurity = blockedBySecurity;
    }

    public int getInvalidConfigs() {
        return invalidConfigs;
    }

    public void setInvalidConfigs(int invalidConfigs) {
        this.invalidConfigs = invalidConfigs;
    }

    public int getNotDue() {
        return notDue;
    }

    public void setNotDue(int notDue) {
        this.notDue = notDue;
    }
}
