package com.mlops.platform.service;

import com.mlops.platform.dto.ModelApiKeyCreateRequest;
import com.mlops.platform.dto.ModelApiKeyResponse;
import com.mlops.platform.dto.ModelApiKeyStatsResponse;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelApiUsage;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelApiUsageRepository;
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
import java.util.List;

/*
 * SMART MODEL GATEWAY — service central.
 *
 * Responsabilités :
 * 1. Gestion des clés API (création / liste / activer / désactiver / révoquer)
 *    — réservée au propriétaire du modèle.
 * 2. Validation d'une clé reçue sur /api/external (préfixe + hash, jamais en clair).
 * 3. Quota journalier (compteur atomique REQUIRES_NEW).
 * 4. Enregistrement d'usage (REQUIRES_NEW, ne bloque jamais la prédiction).
 *
 * Règles de sécurité :
 * - la clé complète n'est retournée qu'à la création (affichée une seule fois)
 * - en base : keyPrefix (recherche) + keyHash (vérification) uniquement
 * - jamais de log de la clé complète
 * - aucune livraison par email : la clé est simplement affichée au propriétaire
 */
@Service
public class ModelGatewayService {

    private static final String KEY_PREFIX_LITERAL = "mlops_live_";
    private static final int HEX_PREFIX_BYTES = 4;

    private final ModelApiKeyRepository modelApiKeyRepository;
    private final ModelApiUsageRepository modelApiUsageRepository;
    private final ModelDeploymentService modelDeploymentService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelGatewayService(
            ModelApiKeyRepository modelApiKeyRepository,
            ModelApiUsageRepository modelApiUsageRepository,
            ModelDeploymentService modelDeploymentService
    ) {
        this.modelApiKeyRepository = modelApiKeyRepository;
        this.modelApiUsageRepository = modelApiUsageRepository;
        this.modelDeploymentService = modelDeploymentService;
    }

    /*
     * =========================================================
     * GESTION DES CLÉS (owner only)
     * =========================================================
     */

    @Transactional
    public ModelApiKeyResponse createApiKey(
            Long deploymentId,
            User owner,
            ModelApiKeyCreateRequest request
    ) {
        requireOwner(deploymentId, owner);

        ModelDeployment deployment = modelDeploymentService.findDeploymentOrThrowPublic(deploymentId);

        DeploymentVisibility visibility = deployment.getVisibility();

        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        if (DeploymentVisibility.PRIVATE.equals(visibility)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "EXTERNAL_ACCESS_DISABLED",
                    "Ce modèle est privé : la création de clé API est désactivée. " +
                            "Passez le modèle en PUBLIC_API ou SHARED pour exposer l'API."
            );
        }

        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(deploymentId);
        key.setOwnerId(owner.getId());

        String name = request == null ? null : request.getName();
        key.setName(name == null || name.isBlank() ? "clé " + deployment.getRegisteredModelName() : name);

        Long quota = request == null ? null : request.getDailyQuota();
        key.setDailyQuota(quota != null && quota > 0 ? quota : null);

        key.setExpiresAt(request == null ? null : request.getExpiresAt());

        GeneratedKeyParts parts = generateKeyParts();

        key.setKeyPrefix(parts.prefix);
        key.setKeyHash(parts.hash);
        key.setEnabled(true);
        key.setCreatedAt(LocalDateTime.now());

        ModelApiKey saved = modelApiKeyRepository.save(key);

        ModelApiKeyResponse response = ModelApiKeyResponse.fromEntity(saved);

        /*
         * La clé complète n'est retournée qu'ici, une seule fois.
         * En base : uniquement keyPrefix + keyHash.
         */
        response.setApiKey(parts.fullKey);

        System.out.println("[SMART_GATEWAY] API key created id=" + saved.getId()
                + " deploymentId=" + deploymentId
                + " ownerId=" + owner.getId());

        return response;
    }

    @Transactional(readOnly = true)
    public List<ModelApiKeyResponse> listApiKeys(Long deploymentId, User owner) {
        requireOwner(deploymentId, owner);

        List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);

        List<ModelApiKeyResponse> responses = new ArrayList<>();

        if (keys != null) {
            for (ModelApiKey key : keys) {
                if (key == null) {
                    continue;
                }
                responses.add(ModelApiKeyResponse.fromEntity(key));
            }
        }

        return responses;
    }

    @Transactional
    public ModelApiKeyResponse setKeyEnabled(Long keyId, User owner, boolean enabled) {
        ModelApiKey key = findKeyOrThrow(keyId);
        requireOwner(key.getDeploymentId(), owner);

        if (Boolean.TRUE.equals(key.getEnabled()) == enabled) {
            return ModelApiKeyResponse.fromEntity(key);
        }

        key.setEnabled(enabled);

        ModelApiKey saved = modelApiKeyRepository.save(key);

        System.out.println("[SMART_GATEWAY] API key " + (enabled ? "enabled" : "disabled")
                + " id=" + saved.getId()
                + " deploymentId=" + saved.getDeploymentId());

        return ModelApiKeyResponse.fromEntity(saved);
    }

    @Transactional
    public ModelApiKeyResponse revokeApiKey(Long keyId, User owner) {
        ModelApiKey key = findKeyOrThrow(keyId);
        requireOwner(key.getDeploymentId(), owner);

        key.setEnabled(false);
        key.setRevokedAt(LocalDateTime.now());

        ModelApiKey saved = modelApiKeyRepository.save(key);

        System.out.println("[SMART_GATEWAY] API key revoked id=" + saved.getId()
                + " deploymentId=" + saved.getDeploymentId());

        return ModelApiKeyResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public ModelApiKeyStatsResponse getStats(Long deploymentId, User owner) {
        requireOwner(deploymentId, owner);

        LocalDateTime since = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        long totalToday = modelApiUsageRepository.countByDeploymentIdAndRequestedAtAfter(deploymentId, since);
        long successToday = modelApiUsageRepository.countByDeploymentIdAndRequestedAtAfterAndSuccessTrue(deploymentId, since);
        long failedToday = totalToday - successToday;

        double successRate = totalToday == 0 ? 0.0 : (successToday * 100.0) / totalToday;

        double avgLatencyMs = 0.0;
        List<ModelApiUsage> usages =
                modelApiUsageRepository.findByDeploymentIdAndRequestedAtAfterOrderByRequestedAtAsc(deploymentId, since);

        if (usages != null && !usages.isEmpty()) {
            long totalLatency = 0;
            int count = 0;
            for (ModelApiUsage usage : usages) {
                if (usage.getLatencyMs() == null) {
                    continue;
                }
                totalLatency += usage.getLatencyMs();
                count++;
            }
            if (count > 0) {
                avgLatencyMs = (double) totalLatency / count;
            }
        }

        Long dailyQuota = null;
        Long quotaRemaining = null;

        List<ModelApiKey> keys = modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(deploymentId);

        if (keys != null) {
            for (ModelApiKey key : keys) {
                if (key == null || !Boolean.TRUE.equals(key.getEnabled())) {
                    continue;
                }
                Long quota = key.getDailyQuota();
                if (quota != null && quota > 0) {
                    LocalDate today = now.toLocalDate();
                    if (!today.equals(key.getUsageDate())) {
                        dailyQuota = quota;
                        quotaRemaining = quota;
                    } else {
                        dailyQuota = quota;
                        quotaRemaining = Math.max(0L, quota - (key.getRequestsToday() == null ? 0L : key.getRequestsToday()));
                    }
                    break;
                }
            }
        }

        ModelApiKeyStatsResponse stats = new ModelApiKeyStatsResponse();
        stats.setTotalToday(totalToday);
        stats.setSuccessToday(successToday);
        stats.setFailedToday(failedToday);
        stats.setSuccessRate(Math.round(successRate * 100.0) / 100.0);
        stats.setAvgLatencyMs(Math.round(avgLatencyMs * 100.0) / 100.0);
        stats.setDailyQuota(dailyQuota);
        stats.setQuotaRemaining(quotaRemaining);
        stats.setKeys(listApiKeys(deploymentId, owner));

        return stats;
    }

    /*
     * =========================================================
     * VALIDATION EXTERNE
     * =========================================================
     */

    /*
     * Valide une clé API brute reçue depuis /api/external.
     *
     * Étapes :
     * 1. préfixe extrait du token (mlops_live_<hexPrefix>_<secret>)
     * 2. recherche de la clé par préfixe en base
     * 3. vérification du hash du secret
     * 4. vérification enabled / revoked / expires
     * 5. correspondance avec le registeredModelName demandé
     *
     * Retourne la ModelApiKey valide. Lève GatewayException sinon.
     */
    @Transactional(readOnly = true)
    public ModelApiKey validateApiKey(String rawKey, String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "MODEL_NAME_REQUIRED",
                    "registeredModelName est obligatoire."
            );
        }

        if (rawKey == null || rawKey.isBlank()) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "MISSING_API_KEY",
                    "Header X-API-KEY manquant."
            );
        }

        String cleanKey = rawKey.trim();
        String prefix = extractKeyPrefix(cleanKey);

        if (prefix == null) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Clé API invalide."
            );
        }

        ModelApiKey key = modelApiKeyRepository.findByKeyPrefix(prefix)
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_API_KEY",
                        "Clé API invalide pour ce modèle."
                ));

        String secret = extractKeySecret(cleanKey);

        if (secret == null || !MessageDigest.isEqual(
                hashSecret(secret).getBytes(StandardCharsets.UTF_8),
                key.getKeyHash().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Clé API invalide pour ce modèle."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        if (key.getRevokedAt() != null) {
            throw new GatewayException(
                    HttpStatus.GONE,
                    "KEY_REVOKED",
                    "Cette clé API a été révoquée."
            );
        }

        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now)) {
            throw new GatewayException(
                    HttpStatus.GONE,
                    "KEY_EXPIRED",
                    "Cette clé API a expiré."
            );
        }

        if (Boolean.FALSE.equals(key.getEnabled())) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "KEY_DISABLED",
                    "Cette clé API est désactivée."
            );
        }

        ModelDeployment deployment;
        try {
            deployment = modelDeploymentService.findDeploymentOrThrowPublic(key.getDeploymentId());
        } catch (RuntimeException e) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modèle introuvable pour cette clé API."
            );
        }

        String modelName = registeredModelName.trim();

        if (deployment.getRegisteredModelName() == null
                || !deployment.getRegisteredModelName().trim().equalsIgnoreCase(modelName)) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "KEY_NOT_FOR_MODEL",
                    "Cette clé API n'est pas autorisée pour ce modèle."
            );
        }

        return key;
    }

    /*
     * Résout le déploiement Kubernetes actif et prêt pour la clé donnée.
     * Réutilise la logique existante (aucune duplication).
     */
    @Transactional(readOnly = true)
    public ModelDeployment resolveGatewayDeployment(ModelApiKey key) {
        return modelDeploymentService.findGatewayDeploymentForExternalApiOrThrow(
                key.getDeploymentId()
        );
    }

    /*
     * =========================================================
     * QUOTA (compteur atomique REQUIRES_NEW)
     * =========================================================
     */

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuotaResult reserveQuota(Long keyId) {
        ModelApiKey key = modelApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_API_KEY",
                        "Clé API introuvable."
                ));

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        if (key.getUsageDate() == null || !today.equals(key.getUsageDate())) {
            key.setRequestsToday(0L);
            key.setUsageDate(today);
        }

        Long quota = key.getDailyQuota();

        if (quota != null && quota > 0 && (key.getRequestsToday() == null || key.getRequestsToday() >= quota)) {
            System.out.println("[SMART_GATEWAY_QUOTA] QUOTA_EXCEEDED keyId=" + keyId
                    + " requestsToday=" + key.getRequestsToday()
                    + " dailyQuota=" + quota);
            return new QuotaResult(false, quota, 0L);
        }

        key.setRequestsToday((key.getRequestsToday() == null ? 0L : key.getRequestsToday()) + 1L);
        key.setTotalRequests((key.getTotalRequests() == null ? 0L : key.getTotalRequests()) + 1L);
        key.setLastUsedAt(now);

        modelApiKeyRepository.save(key);

        long remaining = (quota == null || quota <= 0)
                ? -1L
                : Math.max(0L, quota - key.getRequestsToday());

        return new QuotaResult(true, quota, remaining);
    }

    /*
     * =========================================================
     * USAGE (REQUIRES_NEW, ne doit jamais bloquer la prédiction)
     * =========================================================
     */

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
        try {
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
        } catch (Exception e) {
            System.out.println("[SMART_GATEWAY_USAGE] Could not persist usage row: "
                    + e.getMessage());
        }
    }

    /*
     * =========================================================
     * HELPERS
     * =========================================================
     */

    private void requireOwner(Long deploymentId, User owner) {
        if (owner == null || owner.getId() == null) {
            throw new GatewayException(
                    HttpStatus.UNAUTHORIZED,
                    "NOT_AUTHENTICATED",
                    "Authentification requise."
            );
        }

        boolean owned;

        try {
            owned = modelDeploymentService.isDeploymentOwnedByUser(deploymentId, owner);
        } catch (RuntimeException e) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "MODEL_NOT_FOUND",
                    "Modèle introuvable."
            );
        }

        if (!owned) {
            throw new GatewayException(
                    HttpStatus.FORBIDDEN,
                    "NOT_OWNER",
                    "Vous n'êtes pas le propriétaire de ce modèle."
            );
        }
    }

    private ModelApiKey findKeyOrThrow(Long keyId) {
        if (keyId == null) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "KEY_ID_REQUIRED",
                    "L'identifiant de la clé est obligatoire."
            );
        }

        return modelApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.NOT_FOUND,
                        "API_KEY_NOT_FOUND",
                        "Clé API introuvable."
                ));
    }

    private GeneratedKeyParts generateKeyParts() {
        byte[] prefixBytes = new byte[HEX_PREFIX_BYTES];
        secureRandom.nextBytes(prefixBytes);

        StringBuilder prefixBuilder = new StringBuilder();
        for (byte b : prefixBytes) {
            prefixBuilder.append(String.format("%02x", b));
        }

        String prefix = KEY_PREFIX_LITERAL + prefixBuilder;

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
            throw new IllegalStateException("Impossible de hasher la clé API.", e);
        }
    }

    /*
     * La clé brute a le format : mlops_live_<8 hex>_<secret>
     * Le préfixe de recherche est : mlops_live_<8 hex>
     */
    private String extractKeyPrefix(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX_LITERAL)) {
            return null;
        }

        if (rawKey.length() < KEY_PREFIX_LITERAL.length() + 9) {
            return null;
        }

        int separatorIndex = KEY_PREFIX_LITERAL.length() + 8;

        if (rawKey.charAt(separatorIndex) != '_') {
            return null;
        }

        return rawKey.substring(0, separatorIndex);
    }

    private String extractKeySecret(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX_LITERAL)) {
            return null;
        }

        int separatorIndex = KEY_PREFIX_LITERAL.length() + 8;

        if (rawKey.length() <= separatorIndex + 1 || rawKey.charAt(separatorIndex) != '_') {
            return null;
        }

        return rawKey.substring(separatorIndex + 1);
    }

    public static class QuotaResult {
        private final boolean allowed;
        private final Long limit;
        private final Long remaining;

        public QuotaResult(boolean allowed, Long limit, Long remaining) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public Long getLimit() {
            return limit;
        }

        public Long getRemaining() {
            return remaining;
        }
    }

    private static class GeneratedKeyParts {
        private String prefix;
        private String secret;
        private String hash;
        private String fullKey;
    }
}
