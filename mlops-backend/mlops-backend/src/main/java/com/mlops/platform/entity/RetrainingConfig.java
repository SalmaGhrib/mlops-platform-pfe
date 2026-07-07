package com.mlops.platform.entity;

import jakarta.persistence.*;
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

        normalizeFrequency();

        if (this.enabled && this.nextRetrainingAt == null) {
            this.nextRetrainingAt = calculateNextRetrainingDate(now);
        }

        if (!this.enabled) {
            this.nextRetrainingAt = null;
            this.running = false;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        normalizeFrequency();

        if (!this.enabled) {
            this.nextRetrainingAt = null;
            this.running = false;
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

    public LocalDateTime calculateNextRetrainingDate(LocalDateTime fromDate) {
        LocalDateTime base = fromDate != null ? fromDate : LocalDateTime.now();
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
