package com.mlops.platform.entity;

/**
 * Niveau de sévérité d'une décision de monitoring.
 *
 * NONE     : aucun signal.
 * LOW      : écart mineur, aucun déclenchement.
 * MEDIUM   : signal notable, peut justifier un réentraînement.
 * HIGH     : signal fort, réentraînement recommandé.
 * CRITICAL : signal critique, réentraînement requis ou action sécurité.
 */
public enum RetrainingSeverity {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
