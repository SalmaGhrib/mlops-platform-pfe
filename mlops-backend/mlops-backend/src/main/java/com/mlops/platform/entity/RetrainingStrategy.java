package com.mlops.platform.entity;

/**
 * Stratégie de déclenchement du réentraînement automatique.
 */
public enum RetrainingStrategy {
    /**
     * Comportement classique : lancer le réentraînement à chaque échéance.
     */
    SCHEDULED_ONLY,

    /**
     * L'échéance planifiée arrive, puis la plateforme analyse le monitoring.
     * Si aucune donnée de monitoring n'est disponible, elle garde le réentraînement planifié.
     */
    SCHEDULED_WITH_MONITORING,

    /**
     * Le réentraînement est lancé uniquement si le monitoring détecte un problème.
     */
    SMART_MONITORING_ONLY,

    /**
     * Mode avancé : combine planification, erreurs de runs, erreurs de prédiction et data drift.
     */
    FULL_SMART
}
