package com.mlops.platform.dto;

import com.mlops.platform.entity.RetrainingFrequencyType;
import com.mlops.platform.entity.RetrainingStrategy;

public class RetrainingConfigRequest {

    private boolean enabled;
    private RetrainingFrequencyType frequencyType;
    private Integer frequencyValue;

    /*
     * Nouvelles options avancées.
     * Si le frontend ne les envoie pas, le backend applique des valeurs par défaut.
     */
    private RetrainingStrategy strategy;
    private Boolean failureMonitoringEnabled;
    private Boolean performanceMonitoringEnabled;
    private Boolean dataDriftMonitoringEnabled;
    private Integer monitoringLookbackHours;
    private Integer minPredictionsForMonitoring;
    private Double maxPredictionErrorRatePercent;
    private Double performanceDropThresholdPercent;
    private Double dataDriftThresholdPercent;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RetrainingFrequencyType getFrequencyType() {
        return frequencyType;
    }

    public void setFrequencyType(RetrainingFrequencyType frequencyType) {
        this.frequencyType = frequencyType;
    }

    public Integer getFrequencyValue() {
        return frequencyValue;
    }

    public void setFrequencyValue(Integer frequencyValue) {
        this.frequencyValue = frequencyValue;
    }

    public RetrainingStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RetrainingStrategy strategy) {
        this.strategy = strategy;
    }

    public Boolean getFailureMonitoringEnabled() {
        return failureMonitoringEnabled;
    }

    public void setFailureMonitoringEnabled(Boolean failureMonitoringEnabled) {
        this.failureMonitoringEnabled = failureMonitoringEnabled;
    }

    public Boolean getPerformanceMonitoringEnabled() {
        return performanceMonitoringEnabled;
    }

    public void setPerformanceMonitoringEnabled(Boolean performanceMonitoringEnabled) {
        this.performanceMonitoringEnabled = performanceMonitoringEnabled;
    }

    public Boolean getDataDriftMonitoringEnabled() {
        return dataDriftMonitoringEnabled;
    }

    public void setDataDriftMonitoringEnabled(Boolean dataDriftMonitoringEnabled) {
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
}
