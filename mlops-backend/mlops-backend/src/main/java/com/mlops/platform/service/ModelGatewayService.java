package com.mlops.platform.service;

import com.mlops.platform.dto.ModelApiKeyCreateRequest;
import com.mlops.platform.dto.ModelApiKeyResponse;
import com.mlops.platform.dto.ModelApiKeyStatsResponse;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelApiUsage;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.ModelDeploymentShare;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelApiUsageRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
 * SMART MODEL GATEWAY — service central.
 *
 * Responsabilites :
 * 1. Gestion des cles API (creation / liste / activer / desactiver / revoquer)
 *    - proprietaire : gere toutes les cles du modele
 *    - utilisateur autorise (SHARED) : gere uniquement sa propre cle
 * 2. Validation d'une cle recue sur /api/external (prefixe + hash, jamais en clair).
 * 3. Enregistrement d'usage (REQUIRES_NEW, ne bloque jamais la prediction).
 *
 * Regles de securite :
 * - la cle complete n'est retournee qu'a la creation (affichee une seule fois)
 * - en base : keyPrefix (recherche) + keyHash (verification) uniquement
 * - jamais de log de la cle complete
 * - format cle : mlp_<8hex>_<secret> (nouveau) | mlops_live_<8hex>_<secret> (legacy)
 */
@Service
public class ModelGatewayService {

    private static final String KEY_PREFIX_LEGACY = "mlops_live_";
    private static final String KEY_PREFIX_NEW = "mlp_";
    private static final int HEX_PREFIX_BYTES = 4;

    private final ModelApiKeyRepository modelApiKeyRepository;
    private final ModelApiUsageRepository modelApiUsageRepository;
    private final ModelDeploymentService modelDeploymentService;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final ModelAccessControlService modelAccessControlService;
    private final ModelDeploymentShareRepository modelDeploymentShareRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelGatewayService(
            ModelApiKeyRepository modelApiKeyRepository,
            ModelApiUsageRepository modelApiUsageRepository,
            ModelDeploymentService modelDeploymentService,
            ModelDeploymentRepository modelDeploymentRepository,
            ModelAccessControlService modelAccessControlService,
            ModelDeploymentShareRepository modelDeploymentShareRepository,
            UserRepository userRepository
    ) {
        this.modelApiKeyRepository = modelApiKeyRepository;
        this.modelApiUsageRepository = modelApiUsageRepository;
        this.modelDeploymentService = modelDeploymentService;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.modelAccessControlService = modelAccessControlService;
        this.modelDeploymentShareRepository = modelDeploymentShareRepository;
        this.userRepository = userRepository;
    }

    /*
     * =========================================================
     * GESTION DES CLES
     * =========================================================
     */

    @Transactional
    public ModelApiKeyResponse createApiKey(
            Long deploymentId,
            User user,
            ModelApiKeyCreateRequest request
    ) {
        ModelDeployment deployment = loadAndValidateDeployment(deploymentId);

        DeploymentVisibility visibility = deployment.getVisibility();
        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "EXTERNAL_ACCESS_DISABLED",
                    "Ce modele est prive : la creation de cle API est desactivee. " +
                            "Passez le modele en PUBLIC_API ou SHARED pour exposer l'API."
            );
        }

        boolean isOwner = modelAccessControlService.isOwner(user, deployment);
        boolean isAuthorizedShared = DeploymentVisibility.SHARED.equals(visibility)
                && modelAccessControlService.canAccessDeployment(user, deployment);

        if (!isOwner && !isAuthorizedShared) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "NOT_AUTHORIZED",
                    "Vous n'etes pas autorise a creer une cle API pour ce modele."
            );
        }

        if (isOwner) {
            requireOwner(deploymentId, user);
        } else {
            requireAuthorizedShared(user, deployment);
        }

        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(deploymentId);
        key.setOwnerId(user.getId());

        String name = request == null ? null : request.getName();
        key.setName(name == null || name.isBlank()
                ? "cle " + deployment.getRegisteredModelName()
                : name);

        key.setExpiresAt(request == null ? null : request.getExpiresAt());

        GeneratedKeyParts parts = generateKeyParts();

        key.setKeyPrefix(parts.prefix);
        key.setKeyHash(parts.hash);
        key.setEnabled(true);
        key.setCreatedAt(LocalDateTime.now());
        key.setTotalRequests(0L);

        ModelApiKey saved = modelApiKeyRepository.save(key);

        ModelApiKeyResponse response = ModelApiKeyResponse.fromEntity(saved);
        response.setApiKey(parts.fullKey);

        System.out.println("[SMART_GATEWAY] API key created id=" + saved.getId()
                + " deploymentId=" + deploymentId
                + " ownerId=" + user.getId());

        return response;
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> listApiKeys(Long deploymentId, User user) {
        ModelDeployment deployment = loadAndValidateDeployment(deploymentId);
        boolean isOwner = modelAccessControlService.isOwner(user, deployment);

        if (isOwner) {
            List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);
            return toResponseList(keys);
        }

        requireAuthorizedShared(user, deployment);

        return modelApiKeyRepository.findByDeploymentIdAndOwnerId(deploymentId, user.getId())
                .map(k -> List.of(ModelApiKeyResponse.fromEntity(k)))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> listAllApiKeysForDeployment(Long deploymentId, User user) {
        requireOwner(deploymentId, user);

        List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);
        return toResponseList(keys);
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> getMyApiKeys(Long deploymentId, User user) {
        ModelDeployment deployment = loadAndValidateDeployment(deploymentId);

        boolean isOwner = modelAccessControlService.isOwner(user, deployment);
        if (!isOwner) {
            requireAuthorizedShared(user, deployment);
        }

        List<ModelApiKey> keys = modelApiKeyRepository
                .findByDeploymentIdAndOwnerIdOrderByCreatedAtDesc(deploymentId, user.getId());
        return toResponseList(keys);
    }

    @Transactional
    public void deleteApiKey(Long keyId, User user) {
        ModelApiKey key = findKeyOrThrow(keyId);

        boolean isKeyOwner = key.getOwnerId() != null && key.getOwnerId().equals(user.getId());
        boolean isDeploymentOwner;

        try {
            isDeploymentOwner = modelDeploymentService.isDeploymentOwnedByUser(key.getDeploymentId(), user);
        } catch (RuntimeException e) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modele introuvable."
            );
        }

        if (!isKeyOwner && !isDeploymentOwner) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "NOT_OWNER",
                    "Vous n'etes pas autorise a supprimer cette cle API."
            );
        }

        modelApiKeyRepository.deleteById(keyId);

        System.out.println("[SMART_GATEWAY] API key permanently deleted id=" + keyId
                + " deploymentId=" + key.getDeploymentId()
                + " ownerId=" + key.getOwnerId());
    }

    @Transactional(readOnly = true)
    public ModelApiKeyStatsResponse getStats(Long deploymentId, User user) {
        requireOwner(deploymentId, user);

        LocalDateTime since = LocalDate.now().atStartOfDay();

        Map<String, Object> agg = modelApiUsageRepository.getAggregatedStats(deploymentId, since);

        long totalToday = agg.get("total") != null ? ((Number) agg.get("total")).longValue() : 0L;
        long successToday = agg.get("successCount") != null ? ((Number) agg.get("successCount")).longValue() : 0L;
        long failedToday = totalToday - successToday;

        double successRate = totalToday == 0 ? 0.0 : (successToday * 100.0) / totalToday;
        double avgLatencyMs = agg.get("avgLatency") != null ? ((Number) agg.get("avgLatency")).doubleValue() : 0.0;

        Object lastUsedObj = agg.get("lastUsedAt");
        LocalDateTime lastUsedAt = lastUsedObj instanceof LocalDateTime ? (LocalDateTime) lastUsedObj : null;

        List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);

        ModelApiKeyStatsResponse stats = new ModelApiKeyStatsResponse();
        stats.setTotalToday(totalToday);
        stats.setSuccessToday(successToday);
        stats.setFailedToday(failedToday);
        stats.setSuccessRate(Math.round(successRate * 100.0) / 100.0);
        stats.setAvgLatencyMs(Math.round(avgLatencyMs * 100.0) / 100.0);
        stats.setLastUsedAt(lastUsedAt);
        stats.setKeys(toResponseList(keys));

        return stats;
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> listAllApiKeysForModel(String registeredModelName, User user) {
        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(registeredModelName);

        if (deployments == null || deployments.isEmpty()) {
            return List.of();
        }

        List<ModelApiKeyResponse> allKeys = new ArrayList<>();

        for (ModelDeployment d : deployments) {
            if (d == null || d.getId() == null) {
                continue;
            }

            List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(d.getId());
            if (keys != null) {
                for (ModelApiKey k : keys) {
                    if (k != null) {
                        allKeys.add(ModelApiKeyResponse.fromEntity(k));
                    }
                }
            }
        }

        return allKeys;
    }

    /*
     * =========================================================
     * ENDPOINTS PAR registeredModelName
     * =========================================================
     */

    private ModelDeployment resolveDeploymentByName(String registeredModelName) {
        List<ModelDeployment> deployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(registeredModelName);
        if (deployments == null || deployments.isEmpty()) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Aucun deploiement trouve pour le modele : " + registeredModelName
            );
        }
        return deployments.get(0);
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> getMyApiKeysByName(String registeredModelName, User user) {
        ModelDeployment deployment = resolveDeploymentByName(registeredModelName);
        return getMyApiKeys(deployment.getId(), user);
    }

    @Transactional
    public ModelApiKeyResponse createApiKeyByName(
            String registeredModelName,
            User user,
            ModelApiKeyCreateRequest request
    ) {
        ModelDeployment deployment = resolveDeploymentByName(registeredModelName);
        return createApiKey(deployment.getId(), user, request);
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> listAllApiKeysForModelOwner(String registeredModelName, User user) {
        ModelDeployment deployment = resolveDeploymentByName(registeredModelName);
        requireOwner(deployment.getId(), user);
        List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
        return toResponseList(keys);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSharedUsersKeyStatus(Long deploymentId, User owner) {
        requireOwner(deploymentId, owner);

        List<ModelDeploymentShare> shares =
                modelDeploymentShareRepository.findByDeploymentId(deploymentId);

        List<Map<String, Object>> result = new ArrayList<>();

        for (ModelDeploymentShare share : shares) {
            Long userId = share.getSharedWithUserId();
            User sharedUser = userRepository.findById(userId).orElse(null);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", userId);
            entry.put("username", sharedUser != null ? sharedUser.getEmail() : null);
            entry.put("email", sharedUser != null ? sharedUser.getEmail() : null);

            Optional<ModelApiKey> userKey =
                    modelApiKeyRepository.findByDeploymentIdAndOwnerId(deploymentId, userId);

            if (userKey.isPresent()) {
                ModelApiKey key = userKey.get();
                entry.put("hasKey", true);
                entry.put("keyId", key.getId());
                entry.put("keyPrefix", key.getKeyPrefix());
                entry.put("totalRequests", key.getTotalRequests());
                entry.put("lastUsedAt", key.getLastUsedAt());
            } else {
                entry.put("hasKey", false);
            }

            result.add(entry);
        }

        return result;
    }

    /*
     * =========================================================
     * VALIDATION EXTERNE
     * =========================================================
     */

    @Transactional(readOnly = true)
    public ModelApiKey validateApiKey(String rawKey, String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=MODEL_NAME_REQUIRED model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "MODEL_NAME_REQUIRED",
                    "registeredModelName est obligatoire."
            );
        }

        if (rawKey == null || rawKey.isBlank()) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=MISSING_API_KEY model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "MISSING_API_KEY",
                    "Header X-API-KEY manquant."
            );
        }

        String cleanKey = rawKey.trim();
        String prefix = extractKeyPrefix(cleanKey);

        if (prefix == null) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=INVALID_API_KEY reason=bad_prefix model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Cle API invalide."
            );
        }

        ModelApiKey key = modelApiKeyRepository.findByKeyPrefix(prefix)
                .orElseThrow(() -> {
                    System.out.println("[SMART_GATEWAY_AUTH] REJECT code=INVALID_API_KEY reason=prefix_not_found prefix=" + prefix + " model=" + registeredModelName);
                    return new GatewayException(
                            HttpStatus.UNAUTHORIZED,
                            "INVALID_API_KEY",
                            "Cle API invalide pour ce modele."
                    );
                });

        String secret = extractKeySecret(cleanKey);

        if (secret == null || !MessageDigest.isEqual(
                hashSecret(secret).getBytes(StandardCharsets.UTF_8),
                key.getKeyHash().getBytes(StandardCharsets.UTF_8)
        )) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=INVALID_API_KEY reason=hash_mismatch keyId=" + key.getId() + " model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Cle API invalide pour ce modele."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(now)) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=KEY_EXPIRED keyId=" + key.getId() + " model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.GONE,
                    "KEY_EXPIRED",
                    "Cette cle API a expire."
            );
        }

        ModelDeployment deployment;
        try {
            deployment = modelDeploymentService.findDeploymentOrThrowPublic(key.getDeploymentId());
        } catch (RuntimeException e) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=MODEL_NOT_FOUND keyId=" + key.getId() + " deploymentId=" + key.getDeploymentId() + " model=" + registeredModelName);
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modele introuvable pour cette cle API."
            );
        }

        String modelName = registeredModelName.trim();

        if (deployment.getRegisteredModelName() == null
                || !deployment.getRegisteredModelName().trim().equalsIgnoreCase(modelName)) {
            System.out.println("[SMART_GATEWAY_AUTH] REJECT code=KEY_NOT_FOR_MODEL keyId=" + key.getId() + " keyModel=" + deployment.getRegisteredModelName() + " requestedModel=" + modelName);
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "KEY_NOT_FOR_MODEL",
                    "Cette cle API n'est pas autorisee pour ce modele."
            );
        }

        DeploymentVisibility visibility = deployment.getVisibility();
        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.SHARED.equals(visibility)) {
            User keyOwner = userRepository.findById(key.getOwnerId()).orElse(null);

            if (keyOwner == null) {
                System.out.println("[SMART_GATEWAY_AUTH] REJECT code=KEY_OWNER_NOT_FOUND keyId=" + key.getId() + " ownerId=" + key.getOwnerId());
                throw new GatewayException(
                        HttpStatus.FORBIDDEN,
                        "KEY_OWNER_NOT_FOUND",
                        "Le proprietaire de cette cle API est introuvable."
                );
            }

            if (!modelAccessControlService.canUseApiKey(keyOwner, deployment)) {
                System.out.println("[SMART_GATEWAY_AUTH] REJECT code=SHARED_ACCESS_REVOKED keyId=" + key.getId() + " ownerId=" + key.getOwnerId() + " model=" + modelName);
                throw new GatewayException(
                        HttpStatus.FORBIDDEN,
                        "SHARED_ACCESS_REVOKED",
                        "Vous n'etes plus autorise a utiliser ce modele partage. " +
                                "Le proprietaire a revoque votre acces."
                );
            }
        }

        System.out.println("[SMART_GATEWAY_AUTH] OK keyId=" + key.getId() + " model=" + modelName + " visibility=" + visibility);
        return key;
    }

    @Transactional(readOnly = true)
    public ModelDeployment resolveGatewayDeployment(ModelApiKey key) {
        ModelDeployment deployment = modelDeploymentService.findGatewayDeploymentForExternalApiOrThrow(
                key.getDeploymentId()
        );

        DeploymentVisibility visibility = deployment.getVisibility();
        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "EXTERNAL_ACCESS_DISABLED",
                    "Ce modele est prive."
            );
        }

        return deployment;
    }

    /*
     * =========================================================
     * USAGE (REQUIRES_NEW, ne doit jamais bloquer la prediction)
     * =========================================================
     */

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementUsage(Long keyId) {
        modelApiKeyRepository.incrementUsageAtomic(
                keyId,
                LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementUsageSuccess(Long keyId) {
        modelApiKeyRepository.incrementSuccessAtomic(
                keyId,
                LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementUsageFailure(Long keyId) {
        modelApiKeyRepository.incrementFailureAtomic(
                keyId,
                LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(
            Long keyId,
            Long modelId,
            String registeredModelName,
            String modelVersion,
            int statusCode,
            long latencyMs,
            boolean success,
            String endpointType,
            String errorCode
    ) {
        ModelApiUsage usage = new ModelApiUsage();
        usage.setApiKeyId(keyId);
        usage.setModelId(modelId);
        usage.setRegisteredModelName(registeredModelName);
        usage.setModelVersion(modelVersion);
        usage.setRequestedAt(LocalDateTime.now());
        usage.setStatusCode(statusCode);
        usage.setLatencyMs(latencyMs);
        usage.setSuccess(success);
        usage.setEndpointType(endpointType);
        usage.setErrorCode(errorCode);

        modelApiUsageRepository.save(usage);
    }

    /*
     * =========================================================
     * HELPERS
     * =========================================================
     */

    private ModelDeployment loadAndValidateDeployment(Long deploymentId) {
        try {
            return modelDeploymentService.findDeploymentOrThrowPublic(deploymentId);
        } catch (RuntimeException e) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modele introuvable."
            );
        }
    }

    private void requireOwner(Long deploymentId, User user) {
        if (user == null || user.getId() == null) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "NOT_AUTHENTICATED",
                    "Authentification requise."
            );
        }

        boolean owned;

        try {
            owned = modelDeploymentService.isDeploymentOwnedByUser(deploymentId, user);
        } catch (RuntimeException e) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modele introuvable."
            );
        }

        if (!owned) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "NOT_OWNER",
                    "Vous n'etes pas le proprietaire de ce modele."
            );
        }
    }

    private void requireAuthorizedShared(User user, ModelDeployment deployment) {
        if (user == null || user.getId() == null) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "NOT_AUTHENTICATED",
                    "Authentification requise."
            );
        }

        if (!modelAccessControlService.canAccessDeployment(user, deployment)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "NOT_AUTHORIZED",
                    "Vous n'etes pas autorise a acceder a ce modele."
            );
        }
    }

    private ModelApiKey findKeyOrThrow(Long keyId) {
        if (keyId == null) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "KEY_ID_REQUIRED",
                    "L'identifiant de la cle est obligatoire."
            );
        }

        return modelApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.NOT_FOUND,
                        "API_KEY_NOT_FOUND",
                        "Cle API introuvable."
                ));
    }

    private GeneratedKeyParts generateKeyParts() {
        byte[] hexBytes = new byte[HEX_PREFIX_BYTES];
        secureRandom.nextBytes(hexBytes);

        StringBuilder hexBuilder = new StringBuilder();
        for (byte b : hexBytes) {
            hexBuilder.append(String.format("%02x", b));
        }

        String prefix = KEY_PREFIX_NEW + hexBuilder;

        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(secretBytes);

        String fullKey = prefix + "_" + secret;

        GeneratedKeyParts parts = new GeneratedKeyParts();
        parts.prefix = prefix;
        parts.secret = secret;
        parts.hash = hashSecret(secret);
        parts.fullKey = fullKey;
        return parts;
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(secret.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de hasher la cle API.", e);
        }
    }

    private String extractKeyPrefix(String rawKey) {
        if (rawKey == null) {
            return null;
        }

        if (rawKey.startsWith(KEY_PREFIX_NEW)) {
            return extractPrefixForKey(rawKey, KEY_PREFIX_NEW);
        }

        if (rawKey.startsWith(KEY_PREFIX_LEGACY)) {
            return extractPrefixForKey(rawKey, KEY_PREFIX_LEGACY);
        }

        return null;
    }

    private String extractPrefixForKey(String rawKey, String prefixLiteral) {
        if (rawKey.length() < prefixLiteral.length() + 9) {
            return null;
        }

        int separatorIndex = prefixLiteral.length() + 8;

        if (rawKey.charAt(separatorIndex) != '_') {
            return null;
        }

        return rawKey.substring(0, separatorIndex);
    }

    private String extractKeySecret(String rawKey) {
        if (rawKey == null) {
            return null;
        }

        String prefixLiteral;

        if (rawKey.startsWith(KEY_PREFIX_NEW)) {
            prefixLiteral = KEY_PREFIX_NEW;
        } else if (rawKey.startsWith(KEY_PREFIX_LEGACY)) {
            prefixLiteral = KEY_PREFIX_LEGACY;
        } else {
            return null;
        }

        int separatorIndex = prefixLiteral.length() + 8;

        if (rawKey.length() <= separatorIndex + 1 || rawKey.charAt(separatorIndex) != '_') {
            return null;
        }

        return rawKey.substring(separatorIndex + 1);
    }

    private List<ModelApiKeyResponse> toResponseList(List<ModelApiKey> keys) {
        List<ModelApiKeyResponse> responses = new ArrayList<>();

        if (keys != null) {
            for (ModelApiKey key : keys) {
                if (key != null) {
                    responses.add(ModelApiKeyResponse.fromEntity(key));
                }
            }
        }

        return responses;
    }

    private static class GeneratedKeyParts {
        private String prefix;
        private String secret;
        private String hash;
        private String fullKey;
    }
}
