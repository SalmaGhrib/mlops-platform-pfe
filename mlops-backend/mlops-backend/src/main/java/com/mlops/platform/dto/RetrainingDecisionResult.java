package com.mlops.platform.dto;

import com.mlops.platform.entity.RetrainingTriggerType;

public class RetrainingDecisionResult {

    private boolean shouldRetrain;
    private RetrainingTriggerType triggerType;
    private String reason;
    private double predictionErrorRatePercent;
    private double dataDriftScorePercent;
    private long recentPredictionCount;
    private long recentFailedRuns;

    public static RetrainingDecisionResult run(RetrainingTriggerType triggerType, String reason) {
        RetrainingDecisionResult result = new RetrainingDecisionResult();
        result.setShouldRetrain(true);
        result.setTriggerType(triggerType);
        result.setReason(reason);
        return result;
    }

    public static RetrainingDecisionResult skip(String reason) {
        RetrainingDecisionResult result = new RetrainingDecisionResult();
        result.setShouldRetrain(false);
        result.setTriggerType(RetrainingTriggerType.SKIPPED_NO_SIGNAL);
        result.setReason(reason);
        return result;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getPredictionErrorRatePercent() {
        return predictionErrorRatePercent;
    }

    public void setPredictionErrorRatePercent(double predictionErrorRatePercent) {
        this.predictionErrorRatePercent = predictionErrorRatePercent;
    }

    public double getDataDriftScorePercent() {
        return dataDriftScorePercent;
    }

    public void setDataDriftScorePercent(double dataDriftScorePercent) {
        this.dataDriftScorePercent = dataDriftScorePercent;
    }

    public long getRecentPredictionCount() {
        return recentPredictionCount;
    }

    public void setRecentPredictionCount(long recentPredictionCount) {
        this.recentPredictionCount = recentPredictionCount;
    }

    public long getRecentFailedRuns() {
        return recentFailedRuns;
    }

    public void setRecentFailedRuns(long recentFailedRuns) {
        this.recentFailedRuns = recentFailedRuns;
    }
}
