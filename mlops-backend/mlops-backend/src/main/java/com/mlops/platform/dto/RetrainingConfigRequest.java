package com.mlops.platform.dto;

import com.mlops.platform.entity.RetrainingFrequencyType;

public class RetrainingConfigRequest {

    private boolean enabled;
    private RetrainingFrequencyType frequencyType;
    private Integer frequencyValue;

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
}