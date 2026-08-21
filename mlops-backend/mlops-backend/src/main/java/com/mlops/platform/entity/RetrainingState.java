package com.mlops.platform.entity;

/**
 * Cycle de vie d'une configuration de réentraînement.
 *
 * IDLE        : en attente de la prochaine échéance.
 * EVALUATING  : l'échéance est réclamée et la décision est en cours.
 * TRIGGERED   : la décision impose un réentraînement, lancement en cours.
 * RUNNING     : une expérience de réentraînement est active.
 * COMPLETED   : la dernière expérience s'est terminée avec succès.
 * FAILED      : la dernière tentative a échoué.
 * COOLDOWN    : période d'attente après trop d'échecs consécutifs.
 */
public enum RetrainingState {
    IDLE,
    EVALUATING,
    TRIGGERED,
    RUNNING,
    COMPLETED,
    FAILED,
    COOLDOWN
}
