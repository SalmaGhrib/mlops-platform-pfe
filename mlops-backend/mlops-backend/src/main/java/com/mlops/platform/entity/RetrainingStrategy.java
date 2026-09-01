package com.mlops.platform.entity;

/**
 * Stratégie de déclenchement du réentraînement automatique.
 *
 * Seuls MONITORING_SMART et GITHUB_ACTIONS sont les deux mécanismes valides.
 * Toute autre valeur rencontrée en base est automatiquement migrée vers MONITORING_SMART.
 */
public enum RetrainingStrategy {

    /**
     * Surveillance intelligente : détection de baisse de performance et data drift.
     * Le retraining est déclenché uniquement si un signal pertinent est détecté.
     */
    MONITORING_SMART,

    /**
     * Déclenchement via GitHub Actions.
     * Le workflow CI/CD appelle un endpoint sécurisé pour lancer le retraining.
     */
    GITHUB_ACTIONS;

    /**
     * Mappe toute valeur invalide/null vers MONITORING_SMART.
     */
    public static RetrainingStrategy normalize(RetrainingStrategy strategy) {
        if (strategy == null) {
            return MONITORING_SMART;
        }
        return switch (strategy) {
            case MONITORING_SMART, GITHUB_ACTIONS -> strategy;
            default -> MONITORING_SMART;
        };
    }

    public boolean isActive() {
        return this == MONITORING_SMART || this == GITHUB_ACTIONS;
    }
}
