package com.mlops.platform.entity;

/**
 * @deprecated La sélection manuelle de la source de données est supprimée.
 * Le retraining utilise automatiquement le dernier dataset valide du projet.
 * Conservé pour la compatibilité BDD uniquement.
 */
@Deprecated
public enum RetrainingDataPolicy {

    ORIGINAL_DATASET,

    LATEST_DATASET,

    MERGED_DATASET
}