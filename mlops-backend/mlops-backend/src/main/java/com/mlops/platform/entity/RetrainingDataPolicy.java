package com.mlops.platform.entity;

/**
 * Politique de source de données pour un retraining (voir exigence "dataset évolutif").
 * Le retraining ne réutilise PAS automatiquement le dataset d'origine :
 * l'utilisateur choisit explicitement la stratégie de source.
 */
public enum RetrainingDataPolicy {

    /**
     * Réutilise exactement le dataset du run source (comportement historique).
     */
    ORIGINAL_DATASET,

    /**
     * Utilise le dernier dataset uploadé/associé au projet au moment du déclenchement.
     */
    LATEST_DATASET,

    /**
     * Fusionne le dataset source et le dataset le plus récent.
     * (Abstraction : la fusion est implémentée selon le type de données au runtime.)
     */
    MERGED_DATASET
}