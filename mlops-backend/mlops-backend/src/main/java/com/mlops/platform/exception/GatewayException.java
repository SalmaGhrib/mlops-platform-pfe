package com.mlops.platform.exception;

import org.springframework.http.HttpStatus;

/*
 * Exception typée du SMART MODEL GATEWAY.
 *
 * Elle porte un statut HTTP et un code d'erreur stable.
 *
 * Codes d'erreur :
 * - 401 INVALID_API_KEY / MISSING_API_KEY
 * - 403 KEY_NOT_FOR_MODEL / EXTERNAL_ACCESS_DISABLED / KEY_DISABLED
 * - 404 MODEL_NOT_FOUND
 * - 410 KEY_REVOKED / KEY_EXPIRED
 * - 429 QUOTA_EXCEEDED
 * - 503 MODEL_NOT_DEPLOYED
 */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GatewayException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
