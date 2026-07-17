package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "retraining_config")
public class RetrainingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", unique = true)
    private Long projectId;

    private Long sourceExperimentId;

    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    private RetrainingFrequencyType frequencyType = RetrainingFrequencyType.WEEK;

    private Integer frequencyValue = 1;

    /**
     * Nouvelle stratégie de réentraînement.
     * Par défaut, on garde une logique sûre : planification + monitoring.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy")
    private RetrainingStrategy strategy = RetrainingStrategy.SCHEDULED_ONLY;

    @Column(name = "failure_monitoring_enabled", nullable = false)
    private boolean failureMonitoringEnabled = true;

    @Column(name = "performance_monitoring_enabled", nullable = false)
    private boolean performanceMonitoringEnabled = true;

    @Column(name = "data_drift_monitoring_enabled", nullable = false)
    private boolean dataDriftMonitoringEnabled = false;

    @Column(name = "monitoring_lookback_hours")
    private Integer monitoringLookbackHours = 24;

    @Column(name = "min_predictions_for_monitoring")
    private Integer minPredictionsForMonitoring = 30;

    @Column(name = "max_prediction_error_rate_percent")
    private Double maxPredictionErrorRatePercent = 20.0;

    @Column(name = "performance_drop_threshold_percent")
    private Double performanceDropThresholdPercent = 5.0;

    @Column(name = "data_drift_threshold_percent")
    private Double dataDriftThresholdPercent = 25.0;

    @Column(name = "manual_trigger_requested", nullable = false)
    private boolean manualTriggerRequested = false;

    @Column(name = "last_trigger_reason", columnDefinition = "TEXT")
    private String lastTriggerReason;

    @Column(name = "last_decision_reason", columnDefinition = "TEXT")
    private String lastDecisionReason;

    @Column(name = "last_monitoring_check_at")
    private LocalDateTime lastMonitoringCheckAt;

    private LocalDateTime lastRetrainingAt;

    private LocalDateTime nextRetrainingAt;

    private boolean running = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        this.createdAt = now;
        this.updatedAt = now;

        normalizeFrequency();
        normalizeMonitoringSettings();

        if (this.enabled && this.nextRetrainingAt == null) {
            this.nextRetrainingAt = calculateNextRetrainingDate(now);
        }

        if (!this.enabled) {
            this.nextRetrainingAt = null;
            this.running = false;
            this.manualTriggerRequested = false;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(Clock.systemUTC());
        normalizeFrequency();
        normalizeMonitoringSettings();

        if (!this.enabled) {
            this.nextRetrainingAt = null;
            this.running = false;
            this.manualTriggerRequested = false;
        }
    }

    public void normalizeFrequency() {
        if (this.frequencyType == null) {
            this.frequencyType = RetrainingFrequencyType.WEEK;
        }

        if (this.frequencyValue == null || this.frequencyValue <= 0) {
            this.frequencyValue = 1;
        }
    }

    public void normalizeMonitoringSettings() {
        if (this.strategy == null) {
            this.strategy = RetrainingStrategy.SCHEDULED_ONLY;
        }

        if (this.monitoringLookbackHours == null || this.monitoringLookbackHours <= 0) {
            this.monitoringLookbackHours = 24;
        }

        if (this.monitoringLookbackHours > 24 * 30) {
            this.monitoringLookbackHours = 24 * 30;
        }

        if (this.minPredictionsForMonitoring == null || this.minPredictionsForMonitoring <= 0) {
            this.minPredictionsForMonitoring = 30;
        }

        if (this.maxPredictionErrorRatePercent == null || this.maxPredictionErrorRatePercent <= 0) {
            this.maxPredictionErrorRatePercent = 20.0;
        }

        if (this.performanceDropThresholdPercent == null || this.performanceDropThresholdPercent <= 0) {
            this.performanceDropThresholdPercent = 5.0;
        }

        if (this.dataDriftThresholdPercent == null || this.dataDriftThresholdPercent <= 0) {
            this.dataDriftThresholdPercent = 25.0;
        }
    }

    public LocalDateTime calculateNextRetrainingDate(LocalDateTime fromDate) {
        LocalDateTime base = fromDate != null ? fromDate : LocalDateTime.now(Clock.systemUTC());
        normalizeFrequency();

        return switch (frequencyType) {
            case DAY -> base.plusDays(frequencyValue);
            case WEEK -> base.plusWeeks(frequencyValue);
            case MONTH -> base.plusMonths(frequencyValue);
        };
    }

    public void scheduleNextFrom(LocalDateTime fromDate) {
        if (!enabled) {
            this.nextRetrainingAt = null;
            this.running = false;
            this.manualTriggerRequested = false;
            return;
        }
        this.nextRetrainingAt = calculateNextRetrainingDate(fromDate);
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getSourceExperimentId() {
        return sourceExperimentId;
    }

    public void setSourceExperimentId(Long sourceExperimentId) {
        this.sourceExperimentId = sourceExperimentId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.running = false;
            this.nextRetrainingAt = null;
            this.manualTriggerRequested = false;
        }
    }

    public RetrainingFrequencyType getFrequencyType() {
        return frequencyType;
    }

    public void setFrequencyType(RetrainingFrequencyType frequencyType) {
        this.frequencyType = frequencyType != null ? frequencyType : RetrainingFrequencyType.WEEK;
    }

    public Integer getFrequencyValue() {
        return frequencyValue;
    }

    public void setFrequencyValue(Integer frequencyValue) {
        this.frequencyValue = frequencyValue != null && frequencyValue > 0 ? frequencyValue : 1;
    }

    public RetrainingStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RetrainingStrategy strategy) {
        this.strategy = strategy != null ? strategy : RetrainingStrategy.SCHEDULED_ONLY;
    }

    public boolean isFailureMonitoringEnabled() {
        return failureMonitoringEnabled;
    }

    public void setFailureMonitoringEnabled(boolean failureMonitoringEnabled) {
        this.failureMonitoringEnabled = failureMonitoringEnabled;
    }

    public boolean isPerformanceMonitoringEnabled() {
        return performanceMonitoringEnabled;
    }

    public void setPerformanceMonitoringEnabled(boolean performanceMonitoringEnabled) {
        this.performanceMonitoringEnabled = performanceMonitoringEnabled;
    }

    public boolean isDataDriftMonitoringEnabled() {
        return dataDriftMonitoringEnabled;
    }

    public void setDataDriftMonitoringEnabled(boolean dataDriftMonitoringEnabled) {
        this.dataDriftMonitoringEnabled = dataDriftMonitoringEnabled;
    }

    public Integer getMonitoringLookbackHours() {
        return monitoringLookbackHours;
    }

    public void setMonitoringLookbackHours(Integer monitoringLookbackHours) {
        this.monitoringLookbackHours = monitoringLookbackHours;
    }

    public Integer getMinPredictionsForMonitoring() {
        return minPredictionsForMonitoring;
    }

    public void setMinPredictionsForMonitoring(Integer minPredictionsForMonitoring) {
        this.minPredictionsForMonitoring = minPredictionsForMonitoring;
    }

    public Double getMaxPredictionErrorRatePercent() {
        return maxPredictionErrorRatePercent;
    }

    public void setMaxPredictionErrorRatePercent(Double maxPredictionErrorRatePercent) {
        this.maxPredictionErrorRatePercent = maxPredictionErrorRatePercent;
    }

    public Double getPerformanceDropThresholdPercent() {
        return performanceDropThresholdPercent;
    }

    public void setPerformanceDropThresholdPercent(Double performanceDropThresholdPercent) {
        this.performanceDropThresholdPercent = performanceDropThresholdPercent;
    }

    public Double getDataDriftThresholdPercent() {
        return dataDriftThresholdPercent;
    }

    public void setDataDriftThresholdPercent(Double dataDriftThresholdPercent) {
        this.dataDriftThresholdPercent = dataDriftThresholdPercent;
    }

    public boolean isManualTriggerRequested() {
        return manualTriggerRequested;
    }

    public void setManualTriggerRequested(boolean manualTriggerRequested) {
        this.manualTriggerRequested = manualTriggerRequested;
    }

    public String getLastTriggerReason() {
        return lastTriggerReason;
    }

    public void setLastTriggerReason(String lastTriggerReason) {
        this.lastTriggerReason = lastTriggerReason;
    }

    public String getLastDecisionReason() {
        return lastDecisionReason;
    }

    public void setLastDecisionReason(String lastDecisionReason) {
        this.lastDecisionReason = lastDecisionReason;
    }

    public LocalDateTime getLastMonitoringCheckAt() {
        return lastMonitoringCheckAt;
    }

    public void setLastMonitoringCheckAt(LocalDateTime lastMonitoringCheckAt) {
        this.lastMonitoringCheckAt = lastMonitoringCheckAt;
    }

    public LocalDateTime getLastRetrainingAt() {
        return lastRetrainingAt;
    }

    public void setLastRetrainingAt(LocalDateTime lastRetrainingAt) {
        this.lastRetrainingAt = lastRetrainingAt;
    }

    public LocalDateTime getNextRetrainingAt() {
        return nextRetrainingAt;
    }

    public void setNextRetrainingAt(LocalDateTime nextRetrainingAt) {
        this.nextRetrainingAt = nextRetrainingAt;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
