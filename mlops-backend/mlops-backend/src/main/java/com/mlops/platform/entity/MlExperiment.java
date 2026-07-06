package com.mlops.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ml_experiments")
public class MlExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_name", nullable = false)
    private String experimentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExperimentStatus status;

    /*
     * L'expérience appartient à un projet.
     * Pas de cascade ici : ce n'est pas l'expérience qui supprime le projet.
     * Le projet est le parent, donc c'est lui qui contrôle la suppression.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MlProject project;

    /*
     * Fichier dataset utilisé par l'expérience.
     * Important : pas de cascade REMOVE ici.
     * Le fichier appartient au projet, pas à l'expérience.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_file_id")
    private UploadedProjectFile datasetFile;

    /*
     * Fichier modèle utilisé par l'expérience.
     * Important : pas de cascade REMOVE ici.
     * Le fichier appartient au projet, pas à l'expérience.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_file_id")
    private UploadedProjectFile modelFile;

    /*
     * Les métriques appartiennent à l'expérience.
     * Donc quand on supprime une expérience, ses métriques doivent être supprimées aussi.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "experiment",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<MlExperimentMetric> metrics = new ArrayList<>();

    @Column(name = "data_type")
    private String dataType = "TABULAR";

    @Column(name = "model_type")
    private String modelType;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "primary_metric")
    private String primaryMetric;

    @Column(name = "target_column")
    private String targetColumn;

    @Column(name = "direction")
    private String direction;

    @Column(name = "kubeflow_run_id")
    private String kubeflowRunId;

    @Column(name = "kubeflow_run_name")
    private String kubeflowRunName;

    @Column(name = "kubeflow_status")
    private String kubeflowStatus;

    @Column(name = "mlflow_experiment_name")
    private String mlflowExperimentName;

    @Column(name = "mlflow_experiment_id")
    private String mlflowExperimentId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "mlflow_run_id")
    private String mlflowRunId;

    @Column(name = "registered_model_name")
    private String registeredModelName;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "latest_version")
    private String latestVersion;

    @Column(name = "primary_metric_value")
    private Double primaryMetricValue;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "production_version")
    private String productionVersion;

    @Column(name = "is_production", nullable = false)
    private Boolean production = false;

    @Column(name = "global_score")
    private Double globalScore;

    @Column(name = "detected_algorithms", columnDefinition = "TEXT")
    private String detectedAlgorithms;

    @Column(name = "used_algorithms", columnDefinition = "TEXT")
    private String usedAlgorithms;

    @Column(name = "runtime_mode")
    private String runtimeMode = "STANDARD_RUNTIME";

    @Column(name = "custom_image_uri", length = 1000)
    private String customImageUri;

    @Column(name = "dockerfile_path", length = 1000)
    private String dockerfilePath;

    @Column(name = "requirements_path", length = 1000)
    private String requirementsPath;

    @Column(name = "build_status")
    private String buildStatus;

    @Column(name = "build_logs", columnDefinition = "LONGTEXT")
    private String buildLogs;

    @Column(name = "build_job_name")
    private String buildJobName;

    @Column(name = "training_job_name")
    private String trainingJobName;

    @Column(name = "training_logs", columnDefinition = "LONGTEXT")
    private String trainingLogs;

    @Column(name = "failure_phase")
    private String failurePhase;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "failure_details", columnDefinition = "TEXT")
    private String failureDetails;

    @Column(name = "failure_suggestion", columnDefinition = "TEXT")
    private String failureSuggestion;

    @Column(name = "failure_log_excerpt", columnDefinition = "TEXT")
    private String failureLogExcerpt;

    @Column(name = "failure_source_file")
    private String failureSourceFile;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());

        if (this.createdAt == null) {
            this.createdAt = now;
        }

        if (this.startedAt == null) {
            this.startedAt = now;
        }

        this.updatedAt = now;

        if (this.status == null) {
            this.status = ExperimentStatus.CREATED;
        }

        if (this.production == null) {
            this.production = false;
        }

        if (this.runtimeMode == null || this.runtimeMode.isBlank()) {
            this.runtimeMode = "STANDARD_RUNTIME";
        }

        if (this.dataType == null || this.dataType.isBlank()) {
            this.dataType = "TABULAR";
        }

        normalizeFields();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(Clock.systemUTC());

        if (this.production == null) {
            this.production = false;
        }

        if (this.runtimeMode == null || this.runtimeMode.isBlank()) {
            this.runtimeMode = "STANDARD_RUNTIME";
        }

        if (this.dataType == null || this.dataType.isBlank()) {
            this.dataType = "TABULAR";
        }

        normalizeFields();
    }

    private void normalizeFields() {
        if (this.dataType != null) {
            this.dataType = this.dataType.trim().toUpperCase();

            if (!this.dataType.equals("TABULAR")
                    && !this.dataType.equals("IMAGE")
                    && !this.dataType.equals("TEXT")) {
                this.dataType = "TABULAR";
            }
        }

        if (this.primaryMetric != null) {
            this.primaryMetric = this.primaryMetric.trim().toLowerCase();

            if (this.primaryMetric.isBlank()) {
                this.primaryMetric = null;
            }
        }

        if (this.targetColumn != null) {
            this.targetColumn = this.targetColumn.trim();

            if (this.targetColumn.isBlank()) {
                this.targetColumn = null;
            }
        }

        if (this.direction != null) {
            this.direction = this.direction.trim().toLowerCase();

            if (this.direction.isBlank()) {
                this.direction = null;
            }
        }

        if (this.registeredModelName != null) {
            this.registeredModelName = this.registeredModelName.trim();

            if (this.registeredModelName.isBlank()) {
                this.registeredModelName = null;
            }
        }

        if (this.modelVersion != null) {
            this.modelVersion = this.modelVersion.trim();

            if (this.modelVersion.isBlank()) {
                this.modelVersion = null;
            }
        }

        if (this.latestVersion != null) {
            this.latestVersion = this.latestVersion.trim();

            if (this.latestVersion.isBlank()) {
                this.latestVersion = null;
            }
        }

        if (this.productionVersion != null) {
            this.productionVersion = this.productionVersion.trim();

            if (this.productionVersion.isBlank()) {
                this.productionVersion = null;
            }
        }

        if (this.runtimeMode != null) {
            this.runtimeMode = this.runtimeMode.trim().toUpperCase();

            if (this.runtimeMode.isBlank()) {
                this.runtimeMode = "STANDARD_RUNTIME";
            }
        }

        if (this.customImageUri != null) {
            this.customImageUri = this.customImageUri.trim();

            if (this.customImageUri.isBlank()) {
                this.customImageUri = null;
            }
        }

        if (this.dockerfilePath != null) {
            this.dockerfilePath = this.dockerfilePath.trim();

            if (this.dockerfilePath.isBlank()) {
                this.dockerfilePath = null;
            }
        }

        if (this.requirementsPath != null) {
            this.requirementsPath = this.requirementsPath.trim();

            if (this.requirementsPath.isBlank()) {
                this.requirementsPath = null;
            }
        }

        if (this.buildStatus != null) {
            this.buildStatus = this.buildStatus.trim().toUpperCase();

            if (this.buildStatus.isBlank()) {
                this.buildStatus = null;
            }
        }

        if (this.buildJobName != null) {
            this.buildJobName = this.buildJobName.trim();

            if (this.buildJobName.isBlank()) {
                this.buildJobName = null;
            }
        }

        if (this.trainingJobName != null) {
            this.trainingJobName = this.trainingJobName.trim();

            if (this.trainingJobName.isBlank()) {
                this.trainingJobName = null;
            }
        }

        if (this.failurePhase != null) {
            this.failurePhase = this.failurePhase.trim();

            if (this.failurePhase.isBlank()) {
                this.failurePhase = null;
            }
        }

        if (this.failureSourceFile != null) {
            this.failureSourceFile = this.failureSourceFile.trim();

            if (this.failureSourceFile.isBlank()) {
                this.failureSourceFile = null;
            }
        }
    }

    public void addMetric(MlExperimentMetric metric) {
        if (metric == null) {
            return;
        }

        if (!this.metrics.contains(metric)) {
            this.metrics.add(metric);
        }

        metric.setExperiment(this);
    }

    public void removeMetric(MlExperimentMetric metric) {
        if (metric == null) {
            return;
        }

        this.metrics.remove(metric);
        metric.setExperiment(null);
    }

    public Long getId() {
        return id;
    }

    public String getExperimentName() {
        return experimentName;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public MlProject getProject() {
        return project;
    }

    public UploadedProjectFile getDatasetFile() {
        return datasetFile;
    }

    public UploadedProjectFile getModelFile() {
        return modelFile;
    }

    public List<MlExperimentMetric> getMetrics() {
        return metrics;
    }

    public String getDataType() {
        return dataType;
    }

    public String getModelType() {
        return modelType;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPrimaryMetric() {
        return primaryMetric;
    }

    public String getPrimaryMetricName() {
        return primaryMetric;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public String getDirection() {
        return direction;
    }

    public String getMetricDirection() {
        return direction;
    }

    public String getKubeflowRunId() {
        return kubeflowRunId;
    }

    public String getKubeflowRunName() {
        return kubeflowRunName;
    }

    public String getKubeflowStatus() {
        return kubeflowStatus;
    }

    public String getMlflowExperimentName() {
        return mlflowExperimentName;
    }

    public String getMlflowExperimentId() {
        return mlflowExperimentId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getMlflowRunId() {
        return mlflowRunId;
    }

    public String getRegisteredModelName() {
        return registeredModelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public Double getPrimaryMetricValue() {
        return primaryMetricValue;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getProductionVersion() {
        return productionVersion;
    }

    public Boolean getProduction() {
        return production;
    }

    public Boolean isProduction() {
        return production;
    }

    public Double getGlobalScore() {
        return globalScore;
    }

    public String getDetectedAlgorithms() {
        return detectedAlgorithms;
    }

    public String getUsedAlgorithms() {
        return usedAlgorithms;
    }

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public String getCustomImageUri() {
        return customImageUri;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public String getRequirementsPath() {
        return requirementsPath;
    }

    public String getBuildStatus() {
        return buildStatus;
    }

    public String getBuildLogs() {
        return buildLogs;
    }

    public String getBuildJobName() {
        return buildJobName;
    }

    public String getTrainingJobName() {
        return trainingJobName;
    }

    public String getTrainingLogs() {
        return trainingLogs;
    }

    public String getFailurePhase() {
        return failurePhase;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getFailureDetails() {
        return failureDetails;
    }

    public String getFailureSuggestion() {
        return failureSuggestion;
    }

    public String getFailureLogExcerpt() {
        return failureLogExcerpt;
    }

    public String getFailureSourceFile() {
        return failureSourceFile;
    }

    public LocalDateTime getLastErrorAt() {
        return lastErrorAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setExperimentName(String experimentName) {
        this.experimentName = cleanText(experimentName);
    }

    public void setStatus(ExperimentStatus status) {
        this.status = status;
    }

    public void setProject(MlProject project) {
        this.project = project;
    }

    public void setDatasetFile(UploadedProjectFile datasetFile) {
        this.datasetFile = datasetFile;
    }

    public void setModelFile(UploadedProjectFile modelFile) {
        this.modelFile = modelFile;
    }

    public void setMetrics(List<MlExperimentMetric> metrics) {
        this.metrics.clear();

        if (metrics != null) {
            for (MlExperimentMetric metric : metrics) {
                addMetric(metric);
            }
        }
    }

    public void setDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            this.dataType = "TABULAR";
        } else {
            this.dataType = dataType.trim().toUpperCase();
        }
    }

    public void setModelType(String modelType) {
        this.modelType = cleanText(modelType);
    }

    public void setModelName(String modelName) {
        this.modelName = cleanText(modelName);
    }

    public void setPrimaryMetric(String primaryMetric) {
        this.primaryMetric = cleanLower(primaryMetric);
    }

    public void setPrimaryMetricName(String primaryMetricName) {
        this.primaryMetric = cleanLower(primaryMetricName);
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = cleanText(targetColumn);
    }

    public void setDirection(String direction) {
        this.direction = cleanLower(direction);
    }

    public void setMetricDirection(String metricDirection) {
        this.direction = cleanLower(metricDirection);
    }

    public void setKubeflowRunId(String kubeflowRunId) {
        this.kubeflowRunId = cleanText(kubeflowRunId);
    }

    public void setKubeflowRunName(String kubeflowRunName) {
        this.kubeflowRunName = cleanText(kubeflowRunName);
    }

    public void setKubeflowStatus(String kubeflowStatus) {
        this.kubeflowStatus = cleanText(kubeflowStatus);
    }

    public void setMlflowExperimentName(String mlflowExperimentName) {
        this.mlflowExperimentName = cleanText(mlflowExperimentName);
    }

    public void setMlflowExperimentId(String mlflowExperimentId) {
        this.mlflowExperimentId = cleanText(mlflowExperimentId);
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public void setMlflowRunId(String mlflowRunId) {
        this.mlflowRunId = cleanText(mlflowRunId);
    }

    public void setRegisteredModelName(String registeredModelName) {
        this.registeredModelName = cleanText(registeredModelName);
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = cleanText(modelVersion);
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = cleanText(latestVersion);
    }

    public void setPrimaryMetricValue(Double primaryMetricValue) {
        this.primaryMetricValue = primaryMetricValue;
    }

    public void setMessage(String message) {
        this.message = cleanText(message);
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = cleanText(errorMessage);
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setProductionVersion(String productionVersion) {
        this.productionVersion = cleanText(productionVersion);
    }

    public void setProduction(Boolean production) {
        this.production = production;
    }

    public void setGlobalScore(Double globalScore) {
        this.globalScore = globalScore;
    }

    public void setDetectedAlgorithms(String detectedAlgorithms) {
        this.detectedAlgorithms = cleanText(detectedAlgorithms);
    }

    public void setUsedAlgorithms(String usedAlgorithms) {
        this.usedAlgorithms = cleanText(usedAlgorithms);
    }

    public void setRuntimeMode(String runtimeMode) {
        if (runtimeMode == null || runtimeMode.isBlank()) {
            this.runtimeMode = "STANDARD_RUNTIME";
        } else {
            this.runtimeMode = runtimeMode.trim().toUpperCase();
        }
    }

    public void setCustomImageUri(String customImageUri) {
        this.customImageUri = cleanText(customImageUri);
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = cleanText(dockerfilePath);
    }

    public void setRequirementsPath(String requirementsPath) {
        this.requirementsPath = cleanText(requirementsPath);
    }

    public void setBuildStatus(String buildStatus) {
        this.buildStatus = cleanText(buildStatus);
    }

    public void setBuildLogs(String buildLogs) {
        this.buildLogs = buildLogs;
    }

    public void setBuildJobName(String buildJobName) {
        this.buildJobName = cleanText(buildJobName);
    }

    public void setTrainingJobName(String trainingJobName) {
        this.trainingJobName = cleanText(trainingJobName);
    }

    public void setTrainingLogs(String trainingLogs) {
        this.trainingLogs = trainingLogs;
    }

    public void setFailurePhase(String failurePhase) {
        this.failurePhase = cleanText(failurePhase);
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = cleanText(failureReason);
    }

    public void setFailureDetails(String failureDetails) {
        this.failureDetails = cleanText(failureDetails);
    }

    public void setFailureSuggestion(String failureSuggestion) {
        this.failureSuggestion = cleanText(failureSuggestion);
    }

    public void setFailureLogExcerpt(String failureLogExcerpt) {
        this.failureLogExcerpt = cleanText(failureLogExcerpt);
    }

    public void setFailureSourceFile(String failureSourceFile) {
        this.failureSourceFile = cleanText(failureSourceFile);
    }

    public void setLastErrorAt(LocalDateTime lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    private String cleanLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toLowerCase();
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}