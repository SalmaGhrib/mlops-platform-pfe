package com.mlops.platform.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO utilisé pour modifier l'ordre des métriques prioritaires d'un projet.
 *
 * Nouvelle logique :
 * - primaryMetric1 = priorité 1
 * - primaryMetric2 = priorité 2
 * - primaryMetric3 = priorité 3
 *
 * Il n'y a plus de poids.
 * La meilleure version est choisie en comparant les métriques dans cet ordre.
 */
public class MlProjectMetricsRequest {

    @Size(max = 100, message = "La métrique 1 ne doit pas dépasser 100 caractères")
    private String primaryMetric1;

    @Size(max = 100, message = "La métrique 2 ne doit pas dépasser 100 caractères")
    private String primaryMetric2;

    @Size(max = 100, message = "La métrique 3 ne doit pas dépasser 100 caractères")
    private String primaryMetric3;

    /**
     * Si true :
     * après modification de l'ordre des métriques,
     * le backend recalcule les scores et remet la meilleure version en production.
     */
    private Boolean autoPromoteBestModel;

    public String getPrimaryMetric1() {
        return primaryMetric1;
    }

    public void setPrimaryMetric1(String primaryMetric1) {
        this.primaryMetric1 = clean(primaryMetric1);
    }

    public String getPrimaryMetric2() {
        return primaryMetric2;
    }

    public void setPrimaryMetric2(String primaryMetric2) {
        this.primaryMetric2 = clean(primaryMetric2);
    }

    public String getPrimaryMetric3() {
        return primaryMetric3;
    }

    public void setPrimaryMetric3(String primaryMetric3) {
        this.primaryMetric3 = clean(primaryMetric3);
    }

    public Boolean getAutoPromoteBestModel() {
        return autoPromoteBestModel;
    }

    public void setAutoPromoteBestModel(Boolean autoPromoteBestModel) {
        this.autoPromoteBestModel = autoPromoteBestModel;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toLowerCase();
    }
}