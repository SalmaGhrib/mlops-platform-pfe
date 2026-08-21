package com.mlops.platform.entity;

/**
 * Types d'alertes de monitoring applicatif (modèle métier).
 * Chaque alerte est dédupliquée via une clé d'idempotence et peut générer
 * une notification utilisateur et/ou administrateur.
 */
public enum AlertType {
    DATA_DRIFT,
    PREDICTION_ERROR_RATE,
    PERFORMANCE_DROP,
    GLOBAL_SCORE_TOO_LOW,
    MODEL_QUALITY_FAILED,
    DEPLOYMENT_UNHEALTHY,
    DEPLOYMENT_FAILED,
    RETRAINING_TRIGGERED,
    RETRAINING_FAILED,
    RETRAINING_COMPLETED,
    NEW_PRODUCTION_VERSION
}