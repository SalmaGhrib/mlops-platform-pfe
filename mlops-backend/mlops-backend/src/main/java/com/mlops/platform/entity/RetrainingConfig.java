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

    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    private RetrainingFrequencyType frequencyType = RetrainingFrequencyType.WEEK;

    private Integer frequencyValue = 1;

    /**
     * Nouvelle stratégie de réentraînement.
     * Par défaut, on garde une logique sûre : planification + monitoring.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy")
    private RetrainingStrategy strategy = RetrainingStrategy.MONITORING_SMART;

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

    /**
     * Seuil minimum de qualité du modèle (score global normalisé dans [0,1]).
     * Le modèle ne peut pas être promu / maintenu en production si son score
     * est strictement inférieur à ce seuil (déclenche MODEL_QUALITY_FAILED).
     * Nullable : si non renseigné, aucun contrôle de qualité minimal n'est appliqué.
     */
    @Column(name = "global_score_threshold")
    private Double globalScoreThreshold = 0.75;

    /**
     * Seuil minimum de qualité pour les projets de CLASSIFICATION.
     * Score global normalisé dans [0,1].
     */
    @Column(name = "classification_min_global_score")
    private Double classificationMinimumGlobalScore = 0.75;

    /**
     * Seuil minimum de qualité pour les projets de REGRESSION.
     * Score global normalisé dans [0,1].
     */
    @Column(name = "regression_min_global_score")
    private Double regressionMinimumGlobalScore = 0.75;

    @Column(name = "manual_trigger_requested", nullable = false)
    private boolean manualTriggerRequested = false;

    @Column(name = "last_trigger_reason", columnDefinition = "TEXT")
    private String lastTriggerReason;

    @Column(name = "last_decision_reason", columnDefinition = "TEXT")
    private String lastDecisionReason;

    @Column(name = "last_monitoring_check_at")
    private LocalDateTime lastMonitoringCheckAt;

    /**
     * Politique de source de données pour un retraining.
     * Valeurs : ORIGINAL_DATASET, LATEST_DATASET, MERGED_DATASET.
     * Conservée en base pour la reproductibilité (le retraining ne dépend pas de
     * fichiers temporaires déjà supprimés).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_policy", length = 40)
    private RetrainingDataPolicy dataPolicy = RetrainingDataPolicy.ORIGINAL_DATASET;

    /**
     * Prochaine échéance du contrôle périodique (stratégies avec monitoring).
     * Notion DISTINCTE de nextRetrainingAt : le contrôle monitoring peut avoir
     * lieu avant l'échéance planifiée de retraining.
     */
    @Column(name = "next_monitoring_check_at")
    private LocalDateTime nextMonitoringCheckAt;

    private LocalDateTime lastRetrainingAt;

    private LocalDateTime nextRetrainingAt;

    private boolean running = false;

    /**
     * Cycle de vie de la configuration.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private RetrainingState state = RetrainingState.IDLE;

    /**
     * Fenêtre de déclenchement de la tentative en cours (clé d'idempotence).
     */
    @Column(name = "last_trigger_window")
    private LocalDateTime lastTriggerWindow;

    /**
     * Version du modèle source lors de la dernière tentative
     * (clé d'idempotence : configId + triggerWindow + sourceModelVersion).
     */
    @Column(name = "last_source_model_version", length = 128)
    private String lastSourceModelVersion;

    /**
     * Expérience de réentraînement créée lors de la dernière tentative.
     */
    @Column(name = "last_experiment_id")
    private Long lastExperimentId;

    /**
     * Fin de la période de cooldown, si l'état est COOLDOWN.
     */
    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    /**
     * Nombre d'échecs consécutifs de lancement.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        this.createdAt = now;
        this.updatedAt = now;

        this.enabled = true;
        if (this.strategy == null) {
            this.strategy = RetrainingStrategy.MONITORING_SMART;
        }
        this.strategy = RetrainingStrategy.normalize(this.strategy);

        if (this.state == null) {
            this.state = RetrainingState.IDLE;
        }

        normalizeMonitoringSettings();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(Clock.systemUTC());
        this.enabled = true;
        if (this.strategy == null) {
            this.strategy = RetrainingStrategy.MONITORING_SMART;
        }
        this.strategy = RetrainingStrategy.normalize(this.strategy);

        if (this.state == null) {
            this.state = RetrainingState.IDLE;
        }

        normalizeMonitoringSettings();
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
            this.strategy = RetrainingStrategy.MONITORING_SMART;
        }

        if (this.monitoringLookbackHours == null || this.monitoringLookbackHours <= 0) {
            this.monitoringLookbackHours = 24;
        }

        if (this.monitoringLookbackHours > 24 * 30) {
            this.monitoringLookbackHours = 24 * 30;
        }

        if (this.minPredictionsForMonitoring == null || this.minPredictionsForMonitoring <= 0) {
            this.minPredictionsForMonitoring = 20;
        }

        if (this.performanceDropThresholdPercent == null || this.performanceDropThresholdPercent <= 0) {
            this.performanceDropThresholdPercent = 10.0;
        }

        if (this.dataDriftThresholdPercent == null || this.dataDriftThresholdPercent <= 0) {
            this.dataDriftThresholdPercent = 5.0;
        }
    }

    private Double normalizeGlobalScoreThreshold(Double value) {
        if (value == null) {
            return 0.75;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
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

    /**
     * Échéance du prochain contrôle périodique de monitoring.
     * Par défaut aligné sur la période de retraining (même fréquence) mais stocké
     * dans un champ dédié (nextMonitoringCheckAt) distinct de nextRetrainingAt.
     */
    public LocalDateTime calculateNextMonitoringCheckDate(LocalDateTime fromDate) {
        LocalDateTime base = fromDate != null ? fromDate : LocalDateTime.now(Clock.systemUTC());
        return switch (frequencyType) {
            case DAY -> base.plusDays(frequencyValue);
            case WEEK -> base.plusWeeks(frequencyValue);
            case MONTH -> base.plusMonths(frequencyValue);
        };
    }

    public void scheduleNextMonitoringCheckFrom(LocalDateTime fromDate) {
        this.nextMonitoringCheckAt = fromDate.plusHours(1);
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
            this.state = RetrainingState.IDLE;
            this.cooldownUntil = null;
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
        this.strategy = strategy != null ? strategy : RetrainingStrategy.MONITORING_SMART;
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

    public Double getGlobalScoreThreshold() {
        return globalScoreThreshold;
    }

    public void setGlobalScoreThreshold(Double globalScoreThreshold) {
        this.globalScoreThreshold = globalScoreThreshold;
    }

    public Double getClassificationMinimumGlobalScore() {
        return classificationMinimumGlobalScore;
    }

    public void setClassificationMinimumGlobalScore(Double classificationMinimumGlobalScore) {
        this.classificationMinimumGlobalScore = classificationMinimumGlobalScore;
    }

    public Double getRegressionMinimumGlobalScore() {
        return regressionMinimumGlobalScore;
    }

    public void setRegressionMinimumGlobalScore(Double regressionMinimumGlobalScore) {
        this.regressionMinimumGlobalScore = regressionMinimumGlobalScore;
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

    public LocalDateTime getNextMonitoringCheckAt() {
        return nextMonitoringCheckAt;
    }

    public void setNextMonitoringCheckAt(LocalDateTime nextMonitoringCheckAt) {
        this.nextMonitoringCheckAt = nextMonitoringCheckAt;
    }

    public RetrainingDataPolicy getDataPolicy() {
        return dataPolicy != null ? dataPolicy : RetrainingDataPolicy.ORIGINAL_DATASET;
    }

    public void setDataPolicy(RetrainingDataPolicy dataPolicy) {
        this.dataPolicy = dataPolicy != null ? dataPolicy : RetrainingDataPolicy.ORIGINAL_DATASET;
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

    public RetrainingState getState() {
        return state;
    }

    public void setState(RetrainingState state) {
        this.state = state != null ? state : RetrainingState.IDLE;
    }

    public LocalDateTime getLastTriggerWindow() {
        return lastTriggerWindow;
    }

    public void setLastTriggerWindow(LocalDateTime lastTriggerWindow) {
        this.lastTriggerWindow = lastTriggerWindow;
    }

    public String getLastSourceModelVersion() {
        return lastSourceModelVersion;
    }

    public void setLastSourceModelVersion(String lastSourceModelVersion) {
        this.lastSourceModelVersion = lastSourceModelVersion;
    }

    public Long getLastExperimentId() {
        return lastExperimentId;
    }

    public void setLastExperimentId(Long lastExperimentId) {
        this.lastExperimentId = lastExperimentId;
    }

    public LocalDateTime getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(LocalDateTime cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
    }

    public void incrementConsecutiveFailures() {
        this.consecutiveFailures++;
    }

    public void resetConsecutiveFailures() {
        this.consecutiveFailures = 0;
    }

    /**
     * Indique si la configuration peut être réclamée par le scheduler.
     *
     * Une configuration en COOLDOWN doit avoir dépassé sa période d'attente.
     * Les états EVALUATING / TRIGGERED / RUNNING sont exclus : une autre
     * tentative est déjà en cours.
     */
    public boolean isClaimable(LocalDateTime now) {
        if (running) {
            return false;
        }

        if (state == RetrainingState.EVALUATING
                || state == RetrainingState.TRIGGERED
                || state == RetrainingState.RUNNING) {
            return false;
        }

        if (state == RetrainingState.COOLDOWN) {
            return cooldownUntil == null
                    || !cooldownUntil.isAfter(now);
        }

        return true;
    }

    /**
     * Vrai si l'échéance planifiée est atteinte à la date fournie.
     */
    public boolean isScheduleDue(LocalDateTime now) {
        return nextRetrainingAt != null
                && !nextRetrainingAt.isAfter(now);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
