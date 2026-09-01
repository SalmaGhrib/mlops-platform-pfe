package com.mlops.platform.entity;

/**
 * Stratégie de déclenchement du réentraînement automatique.
 *
 * Seuls MONITORING_SMART et GITHUB_ACTIONS sont des stratégies actives.
 * Les anciennes valeurs sont conservées pour la compatibilité BDD
 * et automatiquement migrées vers MONITORING_SMART.
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
    GITHUB_ACTIONS,

    // --- Valeurs historiques conservées pour compatibilité BDD (deprecated) ---

    @Deprecated
    SCHEDULED_ONLY,

    @Deprecated
    SCHEDULED_WITH_MONITORING,

    @Deprecated
    SMART_MONITORING_ONLY,

    @Deprecated
    FULL_SMART;

    /**
     * Mappe les anciennes stratégies vers la stratégie active correspondante.
     * Toutes les anciennes stratégies sont mappées vers MONITORING_SMART
     * car c'est le mode de retraining automatique unique.
     */
    public static RetrainingStrategy normalize(RetrainingStrategy strategy) {
        if (strategy == null) {
            return MONITORING_SMART;
        }
        switch (strategy) {
            case MONITORING_SMART:
            case GITHUB_ACTIONS:
                return strategy;
            default:
                return MONITORING_SMART;
        }
    }

    /**
     * Vérifie si la stratégie est active (non dépréciée).
     */
    public boolean isActive() {
        return this == MONITORING_SMART || this == GITHUB_ACTIONS;
    }
}
