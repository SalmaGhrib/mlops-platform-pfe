package com.mlops.platform.entity;

/**
 * Classification d'un signal de sécurité lié au monitoring.
 *
 * NONE                 : aucune suspicion.
 * SECURITY_ALERT_ONLY  : activité suspecte détectée, alerte envoyée,
 *                        aucun réentraînement déclenché.
 * BLOCK_MODEL          : la confiance du modèle est remise en cause,
 *                        le réentraînement est bloqué par précaution.
 * RETRAIN_RECOMMENDED  : les données restent fiables, un réentraînement
 *                        est recommandé.
 * RETRAIN_REQUIRED     : les données restent fiables, un réentraînement
 *                        est requis immédiatement.
 */
public enum RetrainingSecurityLevel {
    NONE,
    SECURITY_ALERT_ONLY,
    BLOCK_MODEL,
    RETRAIN_RECOMMENDED,
    RETRAIN_REQUIRED
}
