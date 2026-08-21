package com.mlops.platform.dto;

import com.mlops.platform.entity.RetrainingSecurityLevel;
import com.mlops.platform.entity.RetrainingSeverity;
import com.mlops.platform.entity.RetrainingTriggerType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Décision centralisée de réentraînement.
 *
 * Produite par RetrainingDecisionService pour chaque configuration,
 * indépendamment de la stratégie. Elle porte :
 * - la décision (shouldRetrain) et son déclencheur ;
 * - la sévérité et la classification sécurité ;
 * - des codes de raison stables (reasonCodes) ;
 * - les métriques observées et les seuils appliqués ;
 * - l'horodatage d'évaluation.
 */
public class RetrainingDecision {

    private boolean shouldRetrain;
    private RetrainingTriggerType triggerType;
    private RetrainingSeverity severity = RetrainingSeverity.NONE;
    private RetrainingSecurityLevel securityLevel = RetrainingSecurityLevel.NONE;
    private String reason;
    private List<String> reasonCodes = new ArrayList<>();
    private Map<String, Double> observedMetrics = new LinkedHashMap<>();
    private Map<String, Double> thresholds = new LinkedHashMap<>();
    private LocalDateTime evaluatedAt = LocalDateTime.now(java.time.Clock.systemUTC());

    public static RetrainingDecision run(
            RetrainingTriggerType triggerType,
            RetrainingSeverity severity,
            String reason
    ) {
        RetrainingDecision decision = new RetrainingDecision();
        decision.setShouldRetrain(true);
        decision.setTriggerType(triggerType);
        decision.setSeverity(severity);
        decision.setReason(reason);
        return decision;
    }

    public static RetrainingDecision skip(
            RetrainingSeverity severity,
            String reason
    ) {
        RetrainingDecision decision = new RetrainingDecision();
        decision.setShouldRetrain(false);
        decision.setTriggerType(RetrainingTriggerType.SKIPPED_NO_SIGNAL);
        decision.setSeverity(severity);
        decision.setReason(reason);
        return decision;
    }

    public boolean isShouldRetrain() {
        return shouldRetrain;
    }

    public void setShouldRetrain(boolean shouldRetrain) {
        this.shouldRetrain = shouldRetrain;
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
        this.severity = severity != null ? severity : RetrainingSeverity.NONE;
    }

    public RetrainingSecurityLevel getSecurityLevel() {
        return securityLevel;
    }

    public void setSecurityLevel(RetrainingSecurityLevel securityLevel) {
        this.securityLevel = securityLevel != null ? securityLevel : RetrainingSecurityLevel.NONE;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(List<String> reasonCodes) {
        this.reasonCodes = reasonCodes != null ? reasonCodes : new ArrayList<>();
    }

    public void addReasonCode(String reasonCode) {
        if (reasonCode != null && !reasonCode.isBlank()) {
            this.reasonCodes.add(reasonCode);
        }
    }

    public Map<String, Double> getObservedMetrics() {
        return observedMetrics;
    }

    public void setObservedMetrics(Map<String, Double> observedMetrics) {
        this.observedMetrics = observedMetrics != null ? observedMetrics : new LinkedHashMap<>();
    }

    public void addObservedMetric(String name, Double value) {
        if (name != null) {
            this.observedMetrics.put(name, value);
        }
    }

    public Map<String, Double> getThresholds() {
        return thresholds;
    }

    public void setThresholds(Map<String, Double> thresholds) {
        this.thresholds = thresholds != null ? thresholds : new LinkedHashMap<>();
    }

    public void addThreshold(String name, Double value) {
        if (name != null) {
            this.thresholds.put(name, value);
        }
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt != null ? evaluatedAt : LocalDateTime.now(java.time.Clock.systemUTC());
    }
}
