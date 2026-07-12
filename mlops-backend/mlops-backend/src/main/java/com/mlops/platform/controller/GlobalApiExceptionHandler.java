package com.mlops.platform.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        return response(exception.getStatusCode().value(), exception.getReason(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        System.err.println("[API] Data integrity error on " + request.getMethod() + " "
                + request.getRequestURI() + ": " + exception.getMostSpecificCause().getMessage());
        return response(HttpStatus.CONFLICT.value(),
                "The operation is blocked by linked data. Please retry after dependent resources are cleaned.",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        System.err.println("[API] Unexpected error on " + request.getMethod() + " "
                + request.getRequestURI() + ": " + exception.getMessage());
        exception.printStackTrace();
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                exception.getMessage() == null || exception.getMessage().isBlank()
                        ? "Internal server error"
                        : exception.getMessage(),
                request);
    }

    private ResponseEntity<Map<String, Object>> response(
            int status,
            String message,
            HttpServletRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        body.put("status", status);
        body.put("error", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("message", message == null ? "" : message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
