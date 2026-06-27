package com.mlops.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class MlProjectRequest {

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must not exceed 255 characters")
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    /**
     * Type de données :
     * - TABULAR
     * - IMAGE
     * - TEXT
     */
    private String dataType;

    /**
     * Type de tâche :
     * - classification
     * - regression
     * - sentiment_analysis
     * - translation
     * - qa
     * - llm_finetuning
     * - custom
     */
   

    private String runtimeMode;

    /**
     * Ancien champ gardé pour compatibilité :
     * - regression
     * - classification
     */
    private String projectType;

    @Size(max = 255, message = "Target column must not exceed 255 characters")
    private String targetColumn;

    /**
     * Ordre de priorité des métriques.
     * primaryMetric1 = priorité 1
     * primaryMetric2 = priorité 2
     * primaryMetric3 = priorité 3
     */
    @Size(max = 100, message = "Primary metric 1 must not exceed 100 characters")
    private String primaryMetric1;

    @Size(max = 100, message = "Primary metric 2 must not exceed 100 characters")
    private String primaryMetric2;

    @Size(max = 100, message = "Primary metric 3 must not exceed 100 characters")
    private String primaryMetric3;

    private Boolean autoPromoteBestModel;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = cleanText(name);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = cleanNullableText(description);
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = cleanUpper(dataType);
    }

    

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public void setRuntimeMode(String runtimeMode) {
        this.runtimeMode = cleanUpper(runtimeMode);
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = cleanLower(projectType);
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = cleanNullableText(targetColumn);
    }

    public String getPrimaryMetric1() {
        return primaryMetric1;
    }

    public void setPrimaryMetric1(String primaryMetric1) {
        this.primaryMetric1 = cleanLower(primaryMetric1);
    }

    public String getPrimaryMetric2() {
        return primaryMetric2;
    }

    public void setPrimaryMetric2(String primaryMetric2) {
        this.primaryMetric2 = cleanLower(primaryMetric2);
    }

    public String getPrimaryMetric3() {
        return primaryMetric3;
    }

    public void setPrimaryMetric3(String primaryMetric3) {
        this.primaryMetric3 = cleanLower(primaryMetric3);
    }

    public Boolean getAutoPromoteBestModel() {
        return autoPromoteBestModel;
    }

    public void setAutoPromoteBestModel(Boolean autoPromoteBestModel) {
        this.autoPromoteBestModel = autoPromoteBestModel;
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }

        return value.trim();
    }

    private String cleanNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String cleanLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toLowerCase();
    }

    private String cleanUpper(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toUpperCase();
    }

    
}