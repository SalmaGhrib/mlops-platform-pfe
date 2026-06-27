package com.mlops.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ml_projects")
public class MlProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nom technique du projet.
     * Important : ce nom ne doit pas être modifié après création,
     * car il est utilisé pour construire les noms MLflow/Kubeflow/Registry.
     */
    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    /**
     * Type de données :
     * - TABULAR
     * - IMAGE
     * - TEXT
     */
    @Column(name = "data_type", nullable = false)
    private String dataType = "TABULAR";

    /**
     * Type ML historique.
     * On le garde pour compatibilité avec l’ancien code.
     * Valeurs :
     * - classification
     * - regression
     */
    @Column(name = "project_type", nullable = false)
    private String projectType = "classification";

    /**
     * Colonne cible du dataset.
     */
    @Column(name = "target_column")
    private String targetColumn;

    /**
     * Ordre de priorité des métriques.
     *
     * primaryMetric1 = priorité 1
     * primaryMetric2 = priorité 2
     * primaryMetric3 = priorité 3
     *
     * Il n’y a plus de poids.
     */
    @Column(name = "primary_metric_1", nullable = false)
    private String primaryMetric1 = "f1_score";

    @Column(name = "primary_metric_2")
    private String primaryMetric2;

    @Column(name = "primary_metric_3")
    private String primaryMetric3;

    /**
     * Mode d’exécution :
     * - STANDARD_RUNTIME
     * - CUSTOM_DOCKERFILE
     */
    @Column(name = "runtime_mode", nullable = false)
    private String runtimeMode = "STANDARD_RUNTIME";

    @Column(name = "custom_image_uri", length = 1000)
    private String customImageUri;

    @Column(name = "dockerfile_path", length = 1000)
    private String dockerfilePath;

    @Column(name = "requirements_path", length = 1000)
    private String requirementsPath;

    @Column(name = "auto_promote_best_model", nullable = false)
    private Boolean autoPromoteBestModel = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User owner;

    /**
     * Quand on supprime un projet :
     * - Hibernate supprime automatiquement les expériences liées.
     * - orphanRemoval supprime aussi les expériences retirées de la liste.
     * - CascadeType.ALL évite les erreurs avec des entités enfants non persistées.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "project",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<MlExperiment> experiments = new ArrayList<>();

    /**
     * Quand on supprime un projet :
     * - Hibernate supprime automatiquement les fichiers uploadés liés.
     * - CascadeType.ALL corrige l’erreur TransientObjectException.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "project",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<UploadedProjectFile> uploadedFiles = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        normalizeFields();
        ensureDefaults();
    }

    @PreUpdate
    public void preUpdate() {
        normalizeFields();
        ensureDefaults();
    }

    private void ensureDefaults() {
        if (this.dataType == null || this.dataType.isBlank()) {
            this.dataType = "TABULAR";
        }

        this.dataType = normalizeDataType(this.dataType);
        this.projectType = normalizeProjectType(this.projectType);

        if (this.runtimeMode == null || this.runtimeMode.isBlank()) {
            this.runtimeMode = "STANDARD_RUNTIME";
        }

        this.runtimeMode = this.runtimeMode.trim().toUpperCase();

        this.primaryMetric1 = normalizeMetricName(this.primaryMetric1);
        this.primaryMetric2 = normalizeMetricName(this.primaryMetric2);
        this.primaryMetric3 = normalizeMetricName(this.primaryMetric3);

        if (this.primaryMetric1 == null || this.primaryMetric1.isBlank()) {
            this.primaryMetric1 = "f1_score";
        }

        if (this.autoPromoteBestModel == null) {
            this.autoPromoteBestModel = true;
        }
    }

    private void normalizeFields() {
        if (this.name != null) {
            this.name = this.name.trim();
        }

        if (this.description != null) {
            this.description = this.description.trim();

            if (this.description.isBlank()) {
                this.description = null;
            }
        }

        if (this.dataType != null) {
            this.dataType = normalizeDataType(this.dataType);
        }

        if (this.projectType != null) {
            this.projectType = normalizeProjectType(this.projectType);
        }

        if (this.targetColumn != null) {
            this.targetColumn = this.targetColumn.trim();

            if (this.targetColumn.isBlank()) {
                this.targetColumn = null;
            }
        }

        if (this.primaryMetric1 != null) {
            this.primaryMetric1 = normalizeMetricName(this.primaryMetric1);
        }

        if (this.primaryMetric2 != null) {
            this.primaryMetric2 = normalizeMetricName(this.primaryMetric2);
        }

        if (this.primaryMetric3 != null) {
            this.primaryMetric3 = normalizeMetricName(this.primaryMetric3);
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
    }

    private String normalizeDataType(String value) {
        if (value == null || value.isBlank()) {
            return "TABULAR";
        }

        String normalized = value.trim().toUpperCase();

        return switch (normalized) {
            case "TABULAR", "TABLE", "CSV", "TSV" -> "TABULAR";
            case "IMAGE", "IMAGES", "VISION" -> "IMAGE";
            case "TEXT", "NLP", "LLM" -> "TEXT";
            default -> "TABULAR";
        };
    }

    private String normalizeProjectType(String value) {
        if (value == null || value.isBlank()) {
            return "classification";
        }

        String normalized = value.trim().toLowerCase();

        if ("regression".equals(normalized)) {
            return "regression";
        }

        return "classification";
    }

    private String normalizeMetricName(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return null;
        }

        String value = metricName.trim().toLowerCase();

        if ("f1".equals(value) || "f1_weighted".equals(value)) {
            return "f1_score";
        }

        if ("precision_weighted".equals(value)) {
            return "precision";
        }

        if ("recall_weighted".equals(value)) {
            return "recall";
        }

        if ("r2_score".equals(value) || "r²".equals(value)) {
            return "r2";
        }

        return value;
    }

    public String getMainMetricDirection() {
        return inferDirection(primaryMetric1);
    }

    public String inferDirection(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return "max";
        }

        String normalized = metricName.trim().toLowerCase();

        if (normalized.contains("loss")
                || normalized.contains("val_loss")
                || normalized.contains("log_loss")
                || normalized.contains("error")
                || normalized.contains("cost")
                || normalized.contains("rmse")
                || normalized.contains("mae")
                || normalized.contains("mse")
                || normalized.contains("mape")) {
            return "min";
        }

        return "max";
    }

    public List<String> getMetricPriorities() {
        List<String> priorities = new ArrayList<>();

        if (primaryMetric1 != null && !primaryMetric1.isBlank()) {
            priorities.add(primaryMetric1);
        }

        if (primaryMetric2 != null && !primaryMetric2.isBlank()) {
            priorities.add(primaryMetric2);
        }

        if (primaryMetric3 != null && !primaryMetric3.isBlank()) {
            priorities.add(primaryMetric3);
        }

        return priorities;
    }

    /**
     * Méthode propre pour ajouter une expérience au projet.
     * Elle synchronise les deux côtés de la relation JPA.
     */
    public void addExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        if (!this.experiments.contains(experiment)) {
            this.experiments.add(experiment);
        }

        experiment.setProject(this);
    }

    /**
     * Méthode propre pour retirer une expérience du projet.
     * Avec orphanRemoval = true, elle sera supprimée de la base.
     */
    public void removeExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return;
        }

        this.experiments.remove(experiment);
        experiment.setProject(null);
    }

    /**
     * Méthode propre pour ajouter un fichier uploadé au projet.
     * Elle évite les erreurs Hibernate liées aux entités transient.
     */
    public void addUploadedFile(UploadedProjectFile uploadedFile) {
        if (uploadedFile == null) {
            return;
        }

        if (!this.uploadedFiles.contains(uploadedFile)) {
            this.uploadedFiles.add(uploadedFile);
        }

        uploadedFile.setProject(this);
    }

    /**
     * Méthode propre pour retirer un fichier uploadé du projet.
     * Avec orphanRemoval = true, il sera supprimé de la base.
     */
    public void removeUploadedFile(UploadedProjectFile uploadedFile) {
        if (uploadedFile == null) {
            return;
        }

        this.uploadedFiles.remove(uploadedFile);
        uploadedFile.setProject(null);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDataType() {
        return dataType;
    }

    public String getProjectType() {
        return projectType;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public String getPrimaryMetric1() {
        return primaryMetric1;
    }

    public String getPrimaryMetric2() {
        return primaryMetric2;
    }

    public String getPrimaryMetric3() {
        return primaryMetric3;
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

    public Boolean getAutoPromoteBestModel() {
        return autoPromoteBestModel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public User getOwner() {
        return owner;
    }

    public List<MlExperiment> getExperiments() {
        return experiments;
    }

    public List<UploadedProjectFile> getUploadedFiles() {
        return uploadedFiles;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = cleanText(name);
    }

    public void setDescription(String description) {
        this.description = cleanText(description);
    }

    public void setDataType(String dataType) {
        this.dataType = normalizeDataType(dataType);
    }

    public void setProjectType(String projectType) {
        this.projectType = normalizeProjectType(projectType);
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = cleanText(targetColumn);
    }

    public void setPrimaryMetric1(String primaryMetric1) {
        this.primaryMetric1 = normalizeMetricName(primaryMetric1);

        if (this.primaryMetric1 == null || this.primaryMetric1.isBlank()) {
            this.primaryMetric1 = "f1_score";
        }
    }

    public void setPrimaryMetric2(String primaryMetric2) {
        this.primaryMetric2 = normalizeMetricName(primaryMetric2);
    }

    public void setPrimaryMetric3(String primaryMetric3) {
        this.primaryMetric3 = normalizeMetricName(primaryMetric3);
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

    public void setAutoPromoteBestModel(Boolean autoPromoteBestModel) {
        this.autoPromoteBestModel = autoPromoteBestModel;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    /**
     * Setter sécurisé.
     * Ne pas faire seulement this.experiments = experiments,
     * car il faut synchroniser experiment.setProject(this).
     */
    public void setExperiments(List<MlExperiment> experiments) {
        this.experiments.clear();

        if (experiments != null) {
            for (MlExperiment experiment : experiments) {
                addExperiment(experiment);
            }
        }
    }

    /**
     * Setter sécurisé.
     * Ne pas faire seulement this.uploadedFiles = uploadedFiles,
     * car il faut synchroniser uploadedFile.setProject(this).
     */
    public void setUploadedFiles(List<UploadedProjectFile> uploadedFiles) {
        this.uploadedFiles.clear();

        if (uploadedFiles != null) {
            for (UploadedProjectFile uploadedFile : uploadedFiles) {
                addUploadedFile(uploadedFile);
            }
        }
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}