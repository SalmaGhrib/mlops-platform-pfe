package com.mlops.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
        name = "ml_experiment_metrics",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_metric_experiment_name",
                        columnNames = {"experiment_id", "metric_name"}
                )
        }
)
public class MlExperimentMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_name", nullable = false, length = 120)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private Double metricValue;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "production_version", length = 100)
    private String productionVersion;

    @Column(name = "is_production", nullable = false)
    private Boolean production = Boolean.FALSE;

    @Column(name = "global_score")
    private Double globalScore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MlExperiment experiment;

    public MlExperimentMetric() {
        // Constructeur requis par JPA.
    }

    public MlExperimentMetric(
            MlExperiment experiment,
            String metricName,
            Double metricValue,
            String direction
    ) {
        this.experiment = experiment;
        this.metricName = metricName;
        this.metricValue = metricValue;
        this.direction = direction;
        normalize();
    }

    @PrePersist
    @PreUpdate
    public void normalize() {
        metricName = cleanLower(metricName);

        if (metricName == null) {
            throw new IllegalStateException("metricName ne peut pas être null ou vide.");
        }

        if (metricValue == null) {
            throw new IllegalStateException("metricValue ne peut pas être null.");
        }

        direction = cleanLower(direction);

        if (!"min".equals(direction) && !"max".equals(direction)) {
            direction = inferDirection(metricName);
        }

        if (production == null) {
            production = Boolean.FALSE;
        }

        if (productionVersion != null && productionVersion.isBlank()) {
            productionVersion = null;
        }
    }

    private String inferDirection(String name) {
        if (name == null) {
            return "max";
        }

        String normalized = name.trim().toLowerCase();

        if (normalized.contains("rmse")
                || normalized.contains("mse")
                || normalized.contains("mae")
                || normalized.contains("loss")
                || normalized.contains("error")) {
            return "min";
        }

        return "max";
    }

    private String cleanLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toLowerCase();
    }

    public Long getId() {
        return id;
    }

    public String getMetricName() {
        return metricName;
    }

    public Double getMetricValue() {
        return metricValue;
    }

    public String getDirection() {
        return direction;
    }

    public String getProductionVersion() {
        return productionVersion;
    }

    public Boolean getProduction() {
        return production;
    }

    public boolean isProduction() {
        return Boolean.TRUE.equals(production);
    }

    public Double getGlobalScore() {
        return globalScore;
    }

    public MlExperiment getExperiment() {
        return experiment;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setMetricName(String metricName) {
        this.metricName = cleanLower(metricName);
    }

    public void setMetricValue(Double metricValue) {
        this.metricValue = metricValue;
    }

    public void setDirection(String direction) {
        this.direction = cleanLower(direction);
    }

    public void setProductionVersion(String productionVersion) {
        this.productionVersion =
                productionVersion == null || productionVersion.isBlank()
                        ? null
                        : productionVersion.trim();
    }

    public void setProduction(Boolean production) {
        this.production = production;
    }

    public void setGlobalScore(Double globalScore) {
        this.globalScore = globalScore;
    }

    public void setExperiment(MlExperiment experiment) {
        this.experiment = experiment;
    }
}