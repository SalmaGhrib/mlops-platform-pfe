package com.mlops.platform.entity;

import jakarta.persistence.*;
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

    @Column(name = "production_version")
    private String productionVersion;

    @Column(name = "is_production", nullable = false)
    private Boolean production = false;

    @Column(name = "global_score")
    private Double globalScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MlExperiment experiment;

    @PrePersist
    @PreUpdate
    public void normalize() {
        if (metricName != null) {
            metricName = metricName.trim().toLowerCase();
        }

        if (direction == null || direction.isBlank()) {
            direction = inferDirection(metricName);
        } else {
            direction = direction.trim().toLowerCase();
        }

        if (!"min".equals(direction) && !"max".equals(direction)) {
            direction = inferDirection(metricName);
        }

        if (production == null) {
            production = false;
        }
    }

    private String inferDirection(String metricName) {
        if (metricName == null) {
            return "max";
        }

        String normalized = metricName.trim().toLowerCase();

        if (normalized.contains("rmse")
                || normalized.contains("mse")
                || normalized.contains("mae")
                || normalized.contains("loss")
                || normalized.contains("error")) {
            return "min";
        }

        return "max";
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

    public Boolean isProduction() {
        return production;
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
        this.productionVersion = productionVersion;
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

    private String cleanLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
