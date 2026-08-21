package com.mlops.platform.entity;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Étape réelle de progression d'un run ML (Standard/Kubeflow ou Custom Docker/Kubernetes).
 *
 * Chaque run possède ses propres lignes, identifiées par (experimentId, stepCode).
 * Les transitions autorisées sont monotones : PENDING -> RUNNING -> SUCCEEDED/FAILED.
 * Une étape ne peut jamais revenir en arrière (idempotence des événements).
 */
@Entity
@Table(
        name = "run_pipeline_steps",
        indexes = {
                @Index(name = "idx_rps_experiment", columnList = "experiment_id"),
                @Index(name = "idx_rps_order", columnList = "order_index")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rps_experiment_step",
                        columnNames = {"experiment_id", "step_code"}
                )
        }
)
public class RunPipelineStep {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false)
    private Long experimentId;

    @Column(name = "run_id", length = 255)
    private String runId;

    @Column(name = "step_code", nullable = false, length = 80)
    private String stepCode;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "execution_mode", length = 40)
    private String executionMode;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "progress", nullable = false)
    private Integer progress = 0;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Column(name = "job_name", length = 255)
    private String jobName;

    @Column(name = "pod_name", length = 255)
    private String podName;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "logs", columnDefinition = "LONGTEXT")
    private String logs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null || this.status.isBlank()) {
            this.status = STATUS_PENDING;
        }
        if (this.progress == null) {
            this.progress = 0;
        }
        if (this.orderIndex == null) {
            this.orderIndex = 0;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(Clock.systemUTC());
    }

    public Long getId() { return id; }
    public Long getExperimentId() { return experimentId; }
    public String getRunId() { return runId; }
    public String getStepCode() { return stepCode; }
    public String getLabel() { return label; }
    public String getExecutionMode() { return executionMode; }
    public String getStatus() { return status; }
    public Integer getProgress() { return progress; }
    public Integer getOrderIndex() { return orderIndex; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getMessage() { return message; }
    public String getErrorCode() { return errorCode; }
    public String getErrorDetails() { return errorDetails; }
    public String getJobName() { return jobName; }
    public String getPodName() { return podName; }
    public String getExternalId() { return externalId; }
    public String getLogs() { return logs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public void setRunId(String runId) { this.runId = runId; }
    public void setStepCode(String stepCode) { this.stepCode = stepCode; }
    public void setLabel(String label) { this.label = label; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public void setStatus(String status) { this.status = status; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public void setMessage(String message) { this.message = message; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public void setErrorDetails(String errorDetails) { this.errorDetails = errorDetails; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public void setPodName(String podName) { this.podName = podName; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public void setLogs(String logs) { this.logs = logs; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
