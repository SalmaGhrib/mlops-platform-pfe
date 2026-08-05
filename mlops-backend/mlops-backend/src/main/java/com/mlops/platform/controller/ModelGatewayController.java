package com.mlops.platform.controller;

import com.mlops.platform.dto.ModelApiKeyCreateRequest;
import com.mlops.platform.dto.ModelApiKeyResponse;
import com.mlops.platform.dto.ModelApiKeyStatsResponse;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import com.mlops.platform.service.ModelGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * SMART MODEL GATEWAY — gestion des clés API.
 *
 * Toutes ces opérations sont réservées au propriétaire du modèle
 * (vérification via le header Authorization JWT).
 *
 * Endpoints :
 * - GET    /api/models/{modelId}/api-keys
 * - POST   /api/models/{modelId}/api-keys
 * - GET    /api/models/{modelId}/api-keys/stats
 * - PATCH  /api/model-api-keys/{keyId}/enable
 * - PATCH  /api/model-api-keys/{keyId}/disable
 * - DELETE /api/model-api-keys/{keyId}
 */
@RestController
@CrossOrigin(origins = "*")
public class ModelGatewayController {

    private final ModelGatewayService modelGatewayService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public ModelGatewayController(
            ModelGatewayService modelGatewayService,
            JwtService jwtService,
            UserRepository userRepository
    ) {
        this.modelGatewayService = modelGatewayService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @GetMapping("/api/models/{modelId}/api-keys")
    public ResponseEntity<List<ModelApiKeyResponse>> listApiKeys(
            @PathVariable Long modelId,
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        return ResponseEntity.ok(modelGatewayService.listApiKeys(modelId, currentUser));
    }

    @PostMapping("/api/models/{modelId}/api-keys")
    public ResponseEntity<ModelApiKeyResponse> createApiKey(
            @PathVariable Long modelId,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) ModelApiKeyCreateRequest request
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        return ResponseEntity.ok(modelGatewayService.createApiKey(modelId, currentUser, request));
    }

    @GetMapping("/api/models/{modelId}/api-keys/stats")
    public ResponseEntity<ModelApiKeyStatsResponse> getStats(
            @PathVariable Long modelId,
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        return ResponseEntity.ok(modelGatewayService.getStats(modelId, currentUser));
    }

    @PatchMapping("/api/model-api-keys/{keyId}/enable")
    public ResponseEntity<ModelApiKeyResponse> enableApiKey(
            @PathVariable Long keyId,
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        return ResponseEntity.ok(modelGatewayService.setKeyEnabled(keyId, currentUser, true));
    }

    @PatchMapping("/api/model-api-keys/{keyId}/disable")
    public ResponseEntity<ModelApiKeyResponse> disableApiKey(
            @PathVariable Long keyId,
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        return ResponseEntity.ok(modelGatewayService.setKeyEnabled(keyId, currentUser, false));
    }

    @DeleteMapping("/api/model-api-keys/{keyId}")
    public ResponseEntity<Map<String, Object>> revokeApiKey(
            @PathVariable Long keyId,
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);
        modelGatewayService.revokeApiKey(keyId, currentUser);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("message", "Clé API révoquée.");
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGatewayException(GatewayException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    private User getUserFromAuthorizationHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new GatewayException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "NOT_AUTHENTICATED",
                    "Missing or invalid Authorization header"
            );
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            throw new GatewayException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "NOT_AUTHENTICATED",
                    "Invalid or expired token"
            );
        }

        String email = jwtService.extractEmail(token);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new GatewayException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "NOT_AUTHENTICATED",
                        "User not found"
                ));
    }
}
