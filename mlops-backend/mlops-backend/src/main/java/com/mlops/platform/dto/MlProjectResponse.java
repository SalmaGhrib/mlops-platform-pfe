package com.mlops.platform.dto;

import java.util.ArrayList;
import java.util.List;

public class MlProjectResponse {

    private Long id;
    private String name;
    private String description;

    /**
     * TABULAR / IMAGE / TEXT
     */
    private String dataType;

    /**
     * Ancien champ historique : classification / regression
     */
    private String projectType;

    private String targetColumn;

    /**
     * Ordre de priorité des métriques :
     * primaryMetric1 = priorité 1
     * primaryMetric2 = priorité 2
     * primaryMetric3 = priorité 3
     */
    private String primaryMetric1;
    private String primaryMetric2;
    private String primaryMetric3;

    private Boolean autoPromoteBestModel;
    private String createdAt;

    private String runtimeMode;

    private String customImageUri;
    private String dockerfilePath;
    private String requirementsPath;

    public MlProjectResponse() {
    }

    /**
     * Constructeur principal.
     */
    public MlProjectResponse(
            Long id,
            String name,
            String description,
            String dataType,
            String projectType,
            String targetColumn,
            String primaryMetric1,
            String primaryMetric2,
            String primaryMetric3,
            Boolean autoPromoteBestModel,
            String createdAt,
            String runtimeMode,
            String customImageUri,
            String dockerfilePath,
            String requirementsPath
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.dataType = normalizeDataType(dataType);
        this.projectType = normalizeProjectType(projectType);
        this.targetColumn = targetColumn;
        this.primaryMetric1 = primaryMetric1;
        this.primaryMetric2 = primaryMetric2;
        this.primaryMetric3 = primaryMetric3;
        this.autoPromoteBestModel = autoPromoteBestModel;
        this.createdAt = createdAt;
        this.runtimeMode = runtimeMode;
        this.customImageUri = customImageUri;
        this.dockerfilePath = dockerfilePath;
        this.requirementsPath = requirementsPath;
    }

    /**
     * Ancien constructeur historique sans dataType.
     * On garde ce constructeur pour ne pas casser les anciens appels.
     */
    public MlProjectResponse(
            Long id,
            String name,
            String description,
            String projectType,
            String targetColumn,
            String primaryMetric1,
            String primaryMetric2,
            String primaryMetric3,
            Boolean autoPromoteBestModel,
            String createdAt,
            String runtimeMode,
            String customImageUri,
            String dockerfilePath,
            String requirementsPath
    ) {
        this(
                id,
                name,
                description,
                "TABULAR",
                projectType,
                targetColumn,
                primaryMetric1,
                primaryMetric2,
                primaryMetric3,
                autoPromoteBestModel,
                createdAt,
                runtimeMode,
                customImageUri,
                dockerfilePath,
                requirementsPath
        );
    }

    public List<String> getMetricsPriority() {
        List<String> metrics = new ArrayList<>();

        if (primaryMetric1 != null && !primaryMetric1.isBlank()) {
            metrics.add(primaryMetric1);
        }

        if (primaryMetric2 != null && !primaryMetric2.isBlank()) {
            metrics.add(primaryMetric2);
        }

        if (primaryMetric3 != null && !primaryMetric3.isBlank()) {
            metrics.add(primaryMetric3);
        }

        return metrics;
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

    public Boolean getAutoPromoteBestModel() {
        return autoPromoteBestModel;
    }

    public String getCreatedAt() {
        return createdAt;
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

    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setDataType(String dataType) {
        this.dataType = normalizeDataType(dataType);
    }

    public void setProjectType(String projectType) {
        this.projectType = normalizeProjectType(projectType);
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public void setPrimaryMetric1(String primaryMetric1) {
        this.primaryMetric1 = primaryMetric1;
    }

    public void setPrimaryMetric2(String primaryMetric2) {
        this.primaryMetric2 = primaryMetric2;
    }

    public void setPrimaryMetric3(String primaryMetric3) {
        this.primaryMetric3 = primaryMetric3;
    }

    public void setAutoPromoteBestModel(Boolean autoPromoteBestModel) {
        this.autoPromoteBestModel = autoPromoteBestModel;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public void setRuntimeMode(String runtimeMode) {
        this.runtimeMode = runtimeMode;
    }

    public void setCustomImageUri(String customImageUri) {
        this.customImageUri = customImageUri;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public void setRequirementsPath(String requirementsPath) {
        this.requirementsPath = requirementsPath;
    }

    private String normalizeDataType(String value) {
        if (value == null || value.isBlank()) {
            return "TABULAR";
        }

        String normalized = value.trim().toUpperCase();

        if (!normalized.equals("TABULAR")
                && !normalized.equals("IMAGE")
                && !normalized.equals("TEXT")) {
            return "TABULAR";
        }

        return normalized;
    }

    private String normalizeProjectType(String value) {
        if (value == null || value.isBlank()) {
            return "classification";
        }

        String normalized = value.trim().toLowerCase();

        if (normalized.equals("regression")) {
            return "regression";
        }

        return "classification";
    }
}