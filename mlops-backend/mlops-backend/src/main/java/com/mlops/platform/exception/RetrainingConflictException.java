package com.mlops.platform.exception;

/**
 * Levée lorsqu'un réentraînement est déjà actif pour le projet.
 *
 * Mappée par le contrôleur vers HTTP 409 Conflict avec un corps
 * ManualRetrainingResponse contenant le code RETRAINING_ALREADY_RUNNING.
 */
public class RetrainingConflictException extends RuntimeException {

    private final String code;

    public RetrainingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RetrainingConflictException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
