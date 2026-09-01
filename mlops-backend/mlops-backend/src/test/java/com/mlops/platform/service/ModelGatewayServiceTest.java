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
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du SMART MODEL GATEWAY (Mockito, sans contexte Spring).
 *
 * Couvre les scénarios de non-régulation demandés :
 *  1. création sans email → clé affichée une seule fois, hash stocké, aucun secret en clair
 *  2. modèle PRIVATE → 403 NOT_AUTHORIZED (canAccessDeployment = false)
 *  3. clé valide → prédiction (validation) réussie
 *  4. clé invalide → 401
 *  5. clé désactivée → 403 KEY_DISABLED
 *  6. clé expirée → 410 KEY_EXPIRED
 *  7. clé révoquée → acceptée (hard delete remplace révocation)
 *  8. clé du modèle A utilisée sur le modèle B → 403 KEY_NOT_FOR_MODEL
 *  9. deux clés du même modèle → fonctionnent indépendamment
 * 10. ancienne clé livrée par email → continue de fonctionner
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelGatewayServiceTest {

    @Mock
    private ModelApiKeyRepository modelApiKeyRepository;

    @Mock
    private ModelApiUsageRepository modelApiUsageRepository;

    @Mock
    private ModelDeploymentService modelDeploymentService;

    @Mock
    private ModelDeploymentRepository modelDeploymentRepository;

    @Mock
    private ModelAccessControlService modelAccessControlService;

    @Mock
    private UserRepository userRepository;

    private ModelGatewayService service;

    @BeforeEach
    void setUp() {
        service = new ModelGatewayService(
                modelApiKeyRepository,
                modelApiUsageRepository,
                modelDeploymentService,
                modelDeploymentRepository,
                modelAccessControlService,
                userRepository
        );
    }

    private User owner() {
        User user = new User();
        user.setId(7L);
        return user;
    }

    private ModelDeployment deployment(String modelName, DeploymentVisibility visibility) {
        ModelDeployment deployment = new ModelDeployment();
        deployment.setRegisteredModelName(modelName);
        deployment.setVisibility(visibility);
        return deployment;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ModelApiKey key(String prefix, String secret, ModelDeployment deployment) {
        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(deployment.getId() != null ? deployment.getId() : 1L);
        key.setKeyPrefix(prefix);
        key.setKeyHash(sha256Hex(secret));
        key.setEnabled(true);
        return key;
    }

    private void stubOwnerAndDeployment(Long deploymentId, ModelDeployment deployment) {
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.isOwner(any(User.class), any(ModelDeployment.class))).thenReturn(true);
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class))).thenReturn(true);
    }

    private void stubSaveReturnsArgument() {
        when(modelApiKeyRepository.save(any(ModelApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    /*
     * Test 1 : création d'une clé SANS email.
     * - la clé complète est retournée une seule fois (réponse de création)
     * - en base : keyPrefix + keyHash uniquement, jamais le secret complet
     */
    @Test
    void createApiKey_withoutEmail_storesHashAndReturnsFullKeyOnce() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        stubOwnerAndDeployment(1L, deployment);
        stubSaveReturnsArgument();

        ModelApiKeyCreateRequest request = new ModelApiKeyCreateRequest();
        request.setName("Application bancaire");

        ModelApiKeyResponse response = service.createApiKey(1L, owner(), request);

        assertNotNull(response.getApiKey(), "La clé complète doit être affichée une seule fois");
        assertTrue(response.getApiKey().startsWith("mlp_"), "La clé doit commencer par mlp_");
        assertNotNull(response.getKeyPrefix());

        ArgumentCaptor<ModelApiKey> captor = ArgumentCaptor.forClass(ModelApiKey.class);
        org.mockito.Mockito.verify(modelApiKeyRepository).save(captor.capture());
        ModelApiKey saved = captor.getValue();

        assertNotNull(saved.getKeyHash(), "Le hash doit être stocké");
        assertEquals(64, saved.getKeyHash().length(), "Le hash doit être un SHA-256 hex (64 caractères)");
        assertNotEquals(response.getApiKey(), saved.getKeyHash(), "Aucun secret en clair en base");
        assertEquals("mlp_", saved.getKeyPrefix().substring(0, "mlp_".length()));
        assertFalse(
                saved.getKeyHash().contains(response.getApiKey()),
                "Le hash ne doit jamais contenir la clé complète"
        );
        assertEquals("Application bancaire", saved.getName());
    }

    /*
     * Test 2 : modèle PRIVATE → création bloquée en 403 NOT_AUTHORIZED.
     */
    @Test
    void createApiKey_privateModel_forbidden() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PRIVATE);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class))).thenReturn(false);

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.createApiKey(1L, owner(), new ModelApiKeyCreateRequest())
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("NOT_AUTHORIZED", ex.getCode());
    }

    /*
     * Test 3 : clé valide → validation réussie (prédiction autorisée).
     */
    @Test
    void validateApiKey_validKey_succeeds() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live7a3f";
        ModelApiKey key = key(prefix, secret, deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        ModelApiKey validated = service.validateApiKey(prefix + "_" + secret, "fraud_model");

        assertNotNull(validated);
        assertEquals(prefix, validated.getKeyPrefix());
    }

    /*
     * Test 4 : clé invalide → 401 INVALID_API_KEY.
     */
    @Test
    void validateApiKey_invalidKey_unauthorized() {
        when(modelApiKeyRepository.findByKeyPrefix("mlp_live0000"))
                .thenReturn(Optional.empty());

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey("mlp_live0000_zzzz", "fraud_model")
        );

        assertEquals(401, ex.getStatus().value());
        assertEquals("INVALID_API_KEY", ex.getCode());
    }

    /*
     * Test 5 : Clé désactivée (enabled=false) → 403 KEY_DISABLED.
     */
    @Test
    void validateApiKey_disabledKey_rejected() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live11aa";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setEnabled(false);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey(prefix + "_" + secret, "fraud_model")
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("KEY_DISABLED", ex.getCode());
    }

    /*
     * Test 6 : clé expirée → 410 KEY_EXPIRED.
     */
    @Test
    void validateApiKey_expiredKey_gone() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live22bb";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey(prefix + "_" + secret, "fraud_model")
        );

        assertEquals(410, ex.getStatus().value());
        assertEquals("KEY_EXPIRED", ex.getCode());
    }

    /*
     * Test 7 : Ancienne clé "révoquée" — on ne vérifie plus revokedAt au validation.
     * La clé est acceptée (la suppression hard delete remplace la révocation).
     */
    @Test
    void validateApiKey_revokedKey_accepted() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live33cc";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setRevokedAt(LocalDateTime.now());
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        ModelApiKey result = service.validateApiKey(prefix + "_" + secret, "fraud_model");
        assertNotNull(result, "La clé doit être acceptée même si revokedAt est défini (hard delete remplace révocation)");
    }

    /*
     * Test 8 : clé du modèle A utilisée sur le modèle B → 403 KEY_NOT_FOR_MODEL.
     */
    @Test
    void validateApiKey_keyNotForRequestedModel_forbidden() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live44dd";
        ModelApiKey key = key(prefix, secret, deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey(prefix + "_" + secret, "another_model")
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("KEY_NOT_FOR_MODEL", ex.getCode());
    }

    /*
     * Test 9 : deux clés du même modèle fonctionnent indépendamment.
     */
    @Test
    void validateApiKey_twoKeysSameModel_separateKeys() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret1 = randomSecret();
        String prefix1 = "mlp_liveaabb";
        ModelApiKey key1 = key(prefix1, secret1, deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix1)).thenReturn(Optional.of(key1));

        ModelApiKey validated1 = service.validateApiKey(prefix1 + "_" + secret1, "fraud_model");
        assertNotNull(validated1);
        assertEquals(prefix1, validated1.getKeyPrefix());

        String secret2 = randomSecret();
        String prefix2 = "mlp_live1122";
        ModelApiKey key2 = key(prefix2, secret2, deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix2)).thenReturn(Optional.of(key2));

        ModelApiKey validated2 = service.validateApiKey(prefix2 + "_" + secret2, "fraud_model");
        assertNotNull(validated2);
        assertNotEquals(prefix1, prefix2);
    }

    /*
     * Test 10 : ancienne clé livrée par email → continue de fonctionner.
     * Les clés historiques sont validées par key_prefix + key_hash uniquement,
     * indépendamment de toute logique de livraison.
     */
    @Test
    void validateApiKey_legacyEmailDeliveredKey_stillWorks() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlp_live55ee";
        ModelApiKey legacyKey = key(prefix, secret, deployment);
        legacyKey.setEnabled(true);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(legacyKey));

        ModelApiKey validated = service.validateApiKey(prefix + "_" + secret, "fraud_model");

        assertNotNull(validated, "L'ancienne clé doit rester utilisable sans logique de livraison");
        assertEquals(prefix, validated.getKeyPrefix());
    }

    /*
     * =========================================================
     * TESTS SHARED KEY LIFECYCLE (scénarios 1-13)
     * =========================================================
     */

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private ModelDeployment sharedDeployment(String name) {
        return deployment(name, DeploymentVisibility.SHARED);
    }

    private ModelApiKey userKey(Long deploymentId, Long ownerId, boolean enabled) {
        ModelApiKey k = new ModelApiKey();
        k.setDeploymentId(deploymentId);
        k.setOwnerId(ownerId);
        k.setKeyPrefix("mlp_" + Long.toHexString(ownerId));
        k.setKeyHash(sha256Hex("secret_" + ownerId));
        k.setEnabled(enabled);
        k.setTotalRequests(0L);
        return k;
    }

    private void stubSharedDeployment(ModelDeployment deployment, User owner, User sharedUser) {
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.isOwner(any(User.class), any(ModelDeployment.class)))
                .thenReturn(owner.getId().equals(sharedUser.getId()));
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class)))
                .thenReturn(true);
        when(modelAccessControlService.canUseApiKey(any(User.class), any(ModelDeployment.class)))
                .thenReturn(true);
    }

    /*
     * Scenario 1 : Owner partage modèle avec User B.
     */
    @Test
    void shared_ownerSharesModelWithUserB() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userB = user(2L);

        stubSharedDeployment(deployment, owner, userB);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerId(1L, 2L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        ModelApiKeyResponse response = service.createApiKey(1L, userB, new ModelApiKeyCreateRequest());

        assertNotNull(response);
        assertNotNull(response.getApiKey());
        assertTrue(response.getApiKey().startsWith("mlp_"));
        assertEquals(2L, response.getOwnerId());
    }

    /*
     * Scenario 3 : User B génère clé → 200.
     */
    @Test
    void shared_userBCreatesKey_200() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userB = user(2L);

        stubSharedDeployment(deployment, owner, userB);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerId(1L, 2L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        ModelApiKeyResponse response = service.createApiKey(1L, userB, null);

        assertNotNull(response);
        assertNotNull(response.getApiKey());
    }

    /*
     * Scenario 4 : User B obtient sa clé brute une seule fois.
     */
    @Test
    void shared_userBCreatesKey_rawKeyOnlyOnce() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userB = user(2L);

        stubSharedDeployment(deployment, owner, userB);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerId(1L, 2L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        ModelApiKeyResponse response = service.createApiKey(1L, userB, null);

        assertNotNull(response.getApiKey(), "Clé brute présente à la création");

        ArgumentCaptor<ModelApiKey> captor = ArgumentCaptor.forClass(ModelApiKey.class);
        org.mockito.Mockito.verify(modelApiKeyRepository).save(captor.capture());
        ModelApiKey saved = captor.getValue();

        assertNotEquals(response.getApiKey(), saved.getKeyHash(), "Clé brut jamais en base");
        assertTrue(saved.getKeyHash().length() == 64, "Hash SHA-256 en base");
    }

    /*
     * Scenario 5 : User B appelle Smart Gateway → 200.
     */
    @Test
    void shared_userBCallsGateway_200() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userB = user(2L);

        String secret = randomSecret();
        String prefix = "mlp_aabbccdd";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setOwnerId(2L);
        key.setEnabled(true);

        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userB));
        when(modelAccessControlService.canUseApiKey(userB, deployment)).thenReturn(true);

        ModelApiKey validated = service.validateApiKey(prefix + "_" + secret, "wind_speed_model");

        assertNotNull(validated);
        assertEquals(prefix, validated.getKeyPrefix());
    }

    /*
     * Scenario 6 : User C non autorisé génère clé → 403.
     */
    @Test
    void shared_userCNotAuthorized_403() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userC = user(3L);

        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.isOwner(userC, deployment)).thenReturn(false);
        when(modelAccessControlService.canAccessDeployment(userC, deployment)).thenReturn(false);

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.createApiKey(1L, userC, new ModelApiKeyCreateRequest())
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("NOT_AUTHORIZED", ex.getCode());
    }

    /*
     * Scenario 7+8 : Owner retire User B → ancienne clé → 403.
     */
    @Test
    void shared_ownerRevokesAccess_oldKey403() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userB = user(2L);

        String secret = randomSecret();
        String prefix = "mlp_ccddee11";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setOwnerId(2L);
        key.setEnabled(true);

        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userB));
        when(modelAccessControlService.canUseApiKey(userB, deployment)).thenReturn(false);

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey(prefix + "_" + secret, "wind_speed_model")
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("SHARED_ACCESS_REVOKED", ex.getCode());
    }

    /*
     * Scenario 10 : User A et User B ont des clés différentes.
     */
    @Test
    void shared_userAAndUserBHaveDifferentKeys() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User owner = user(1L);
        User userA = user(2L);
        User userB = user(3L);

        stubSharedDeployment(deployment, owner, userA);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerId(1L, 2L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        ModelApiKeyResponse responseA = service.createApiKey(1L, userA, null);

        stubSharedDeployment(deployment, owner, userB);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerId(1L, 3L)).thenReturn(Optional.empty());

        ModelApiKeyResponse responseB = service.createApiKey(1L, userB, null);

        assertNotEquals(responseA.getApiKey(), responseB.getApiKey(), "Clés différentes");
        assertNotEquals(responseA.getKeyPrefix(), responseB.getKeyPrefix(), "Prefixes différents");
    }

    /*
     * Test : Ancienne clé révoquée dans un contexte SHARED — on ne vérifie plus
     * revokedAt. La clé est acceptée (hard delete remplace révocation).
     */
    @Test
    void shared_revokedKey_accepted() {
        ModelDeployment deployment = sharedDeployment("wind_speed_model");
        User userB = user(2L);

        String secret = randomSecret();
        String prefix = "mlp_eeff0011";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setOwnerId(2L);
        key.setRevokedAt(LocalDateTime.now());

        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userB));
        when(modelAccessControlService.canUseApiKey(any(User.class), any(ModelDeployment.class))).thenReturn(true);

        ModelApiKey result = service.validateApiKey(prefix + "_" + secret, "wind_speed_model");
        assertNotNull(result, "La clé doit être acceptée même si revokedAt est défini");
    }

    /*
     * =========================================================
     * TESTS STATISTIQUES (getStats / recordUsage / incrementUsage)
     * =========================================================
     */

    /*
     * Test : getStats retourne des compteurs nuls quand il n'y a pas d'usage.
     */
    @Test
    void getStats_noUsage_returnsZeros() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class))).thenReturn(true);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerIdOrderByCreatedAtDesc(anyLong(), anyLong())).thenReturn(List.of());

        ModelApiKeyStatsResponse stats = service.getStats(1L, owner());

        assertNotNull(stats);
        assertEquals(0L, stats.getTotalToday());
        assertEquals(0L, stats.getSuccessToday());
        assertEquals(0L, stats.getFailedToday());
        assertEquals(0.0, stats.getSuccessRate());
        assertEquals(0.0, stats.getAvgLatencyMs());
        assertNull(stats.getLastUsedAt());
        assertTrue(stats.getKeys().isEmpty());
    }

    /*
     * Test : getStats calcule correctement les stats agrégées.
     */
    @Test
    void getStats_withUsage_calculatesCorrectly() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class))).thenReturn(true);

        ModelApiKey key1 = new ModelApiKey();
        setField(key1, "id", 100L);
        key1.setKeyPrefix("mlp_aabbccdd");
        key1.setDeploymentId(1L);
        ModelApiKey key2 = new ModelApiKey();
        setField(key2, "id", 101L);
        key2.setKeyPrefix("mlp_11223344");
        key2.setDeploymentId(1L);
        when(modelApiKeyRepository.findByDeploymentIdAndOwnerIdOrderByCreatedAtDesc(eq(1L), anyLong()))
                .thenReturn(List.of(key1, key2));

        Map<String, Object> agg1 = new HashMap<>();
        agg1.put("total", 6L);
        agg1.put("successCount", 5L);
        agg1.put("avgLatency", 100.0);
        agg1.put("lastUsedAt", LocalDateTime.of(2026, 8, 30, 15, 30, 0));
        when(modelApiUsageRepository.getAggregatedStatsForKey(eq(100L), any(LocalDateTime.class))).thenReturn(agg1);

        Map<String, Object> agg2 = new HashMap<>();
        agg2.put("total", 4L);
        agg2.put("successCount", 3L);
        agg2.put("avgLatency", 200.0);
        agg2.put("lastUsedAt", LocalDateTime.of(2026, 8, 30, 16, 0, 0));
        when(modelApiUsageRepository.getAggregatedStatsForKey(eq(101L), any(LocalDateTime.class))).thenReturn(agg2);

        ModelApiKeyStatsResponse stats = service.getStats(1L, owner());

        assertEquals(10L, stats.getTotalToday());
        assertEquals(8L, stats.getSuccessToday());
        assertEquals(2L, stats.getFailedToday());
        assertEquals(80.0, stats.getSuccessRate(), 0.01);
        assertEquals(300.0, stats.getAvgLatencyMs(), 0.01);
        assertNotNull(stats.getLastUsedAt());
        assertEquals(2, stats.getKeys().size());
    }

    /*
     * Test : recordUsage insère bien un ModelApiUsage en base.
     */
    @Test
    void recordUsage_savesModelApiUsage() {
        ArgumentCaptor<ModelApiUsage> captor = ArgumentCaptor.forClass(ModelApiUsage.class);

        service.recordUsage(1L, 10L, "fraud_model", "v1.0", 200, 150L, true, "external", null);

        org.mockito.Mockito.verify(modelApiUsageRepository).save(captor.capture());
        ModelApiUsage saved = captor.getValue();

        assertEquals(1L, saved.getApiKeyId());
        assertEquals(10L, saved.getModelId());
        assertEquals("fraud_model", saved.getRegisteredModelName());
        assertEquals("v1.0", saved.getModelVersion());
        assertEquals(200, saved.getStatusCode());
        assertEquals(150L, saved.getLatencyMs());
        assertTrue(saved.getSuccess());
        assertEquals("external", saved.getEndpointType());
        assertNull(saved.getErrorCode());
        assertNotNull(saved.getRequestedAt());
    }

    /*
     * Test : recordUsage avec erreur enregistre le code d'erreur.
     */
    @Test
    void recordUsage_withError_savesErrorCode() {
        ArgumentCaptor<ModelApiUsage> captor = ArgumentCaptor.forClass(ModelApiUsage.class);

        service.recordUsage(1L, 10L, "fraud_model", "v1.0", 500, 50L, false, "external", "INTERNAL_ERROR");

        org.mockito.Mockito.verify(modelApiUsageRepository).save(captor.capture());
        ModelApiUsage saved = captor.getValue();

        assertFalse(saved.getSuccess());
        assertEquals(500, saved.getStatusCode());
        assertEquals("INTERNAL_ERROR", saved.getErrorCode());
    }

    /*
     * Test : incrementUsage incrémente totalRequests et met à jour lastUsedAt
     * via la requête atomique incrementUsageAtomic.
     */
    @Test
    void incrementUsage_incrementsTotalRequests() {
        when(modelApiKeyRepository.incrementUsageAtomic(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        service.incrementUsage(1L);

        org.mockito.Mockito.verify(modelApiKeyRepository).incrementUsageAtomic(eq(1L), any(LocalDateTime.class));
    }

    /*
     * Test : incrementUsage ne fait pas planter si incrementUsageAtomic retourne 0 (clé inexistante).
     */
    @Test
    void incrementUsage_noExceptionOnZeroRowsUpdated() {
        when(modelApiKeyRepository.incrementUsageAtomic(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        assertDoesNotThrow(() -> service.incrementUsage(1L));
    }

    /*
     * Test : getStats sans accès → 403 NOT_AUTHORIZED.
     */
    @Test
    void getStats_notOwner_forbidden() {
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment("fraud_model", DeploymentVisibility.PUBLIC_API));
        when(modelAccessControlService.canAccessDeployment(any(User.class), any(ModelDeployment.class))).thenReturn(false);

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.getStats(1L, owner())
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("NOT_AUTHORIZED", ex.getCode());
    }
}
