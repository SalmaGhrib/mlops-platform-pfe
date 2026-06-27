package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "retraining_config")
public class RetrainingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long sourceExperimentId;

    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    private RetrainingFrequencyType frequencyType = RetrainingFrequencyType.WEEK;

    private Integer frequencyValue = 1;

    private LocalDateTime lastRetrainingAt;

    private LocalDateTime nextRetrainingAt;

    private boolean running = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.nextRetrainingAt == null) {
            this.nextRetrainingAt = calculateNextRetrainingDate(now);
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime calculateNextRetrainingDate(LocalDateTime fromDate) {
        if (frequencyType == null || frequencyValue == null || frequencyValue <= 0) {
            return fromDate.plusWeeks(1);
        }

        return switch (frequencyType) {
            case DAY -> fromDate.plusDays(frequencyValue);
            case WEEK -> fromDate.plusWeeks(frequencyValue);
            case MONTH -> fromDate.plusMonths(frequencyValue);
        };
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
