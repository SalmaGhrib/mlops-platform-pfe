package com.mlops.platform.service;

import com.mlops.platform.dto.ModelApiKeyCreateRequest;
import com.mlops.platform.dto.ModelApiKeyResponse;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelApiUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du SMART MODEL GATEWAY (Mockito, sans contexte Spring).
 *
 * Couvre les scénarios de non-régression demandés :
 *  1. création sans email → clé affichée une seule fois, hash stocké, aucun secret en clair
 *  2. modèle PRIVATE → 403 EXTERNAL_ACCESS_DISABLED
 *  3. clé valide → prédiction (validation) réussie
 *  4. clé invalide → 401
 *  5. clé désactivée → 403
 *  6. clé expirée → 410
 *  7. clé révoquée → 410
 *  8. clé du modèle A utilisée sur le modèle B → 403
 *  9. quota dépassé → 429 (refus)
 * 10. deux clés du même modèle → compteurs séparés
 * 11. ancienne clé livrée par email → continue de fonctionner
 */
@ExtendWith(MockitoExtension.class)
class ModelGatewayServiceTest {

    @Mock
    private ModelApiKeyRepository modelApiKeyRepository;

    @Mock
    private ModelApiUsageRepository modelApiUsageRepository;

    @Mock
    private ModelDeploymentService modelDeploymentService;

    private ModelGatewayService service;

    @BeforeEach
    void setUp() {
        service = new ModelGatewayService(
                modelApiKeyRepository,
                modelApiUsageRepository,
                modelDeploymentService
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
        when(modelDeploymentService.isDeploymentOwnedByUser(anyLong(), any(User.class))).thenReturn(true);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);
    }

    private void stubSaveReturnsArgument() {
        when(modelApiKeyRepository.save(any(ModelApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
        request.setDailyQuota(1000L);

        ModelApiKeyResponse response = service.createApiKey(1L, owner(), request);

        assertNotNull(response.getApiKey(), "La clé complète doit être affichée une seule fois");
        assertTrue(response.getApiKey().startsWith("mlops_live_"), "La clé doit commencer par mlops_live_");
        assertNotNull(response.getKeyPrefix());

        ArgumentCaptor<ModelApiKey> captor = ArgumentCaptor.forClass(ModelApiKey.class);
        org.mockito.Mockito.verify(modelApiKeyRepository).save(captor.capture());
        ModelApiKey saved = captor.getValue();

        assertNotNull(saved.getKeyHash(), "Le hash doit être stocké");
        assertEquals(64, saved.getKeyHash().length(), "Le hash doit être un SHA-256 hex (64 caractères)");
        assertNotEquals(response.getApiKey(), saved.getKeyHash(), "Aucun secret en clair en base");
        assertEquals("mlops_live_", saved.getKeyPrefix().substring(0, "mlops_live_".length()));
        assertFalse(
                saved.getKeyHash().contains(response.getApiKey()),
                "Le hash ne doit jamais contenir la clé complète"
        );
        assertEquals("Application bancaire", saved.getName());
        assertEquals(1000L, saved.getDailyQuota());
    }

    /*
     * Test 2 : modèle PRIVATE → création bloquée en 403 EXTERNAL_ACCESS_DISABLED.
     */
    @Test
    void createApiKey_privateModel_forbidden() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PRIVATE);
        stubOwnerAndDeployment(1L, deployment);

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.createApiKey(1L, owner(), new ModelApiKeyCreateRequest())
        );

        assertEquals(403, ex.getStatus().value());
        assertEquals("EXTERNAL_ACCESS_DISABLED", ex.getCode());
    }

    /*
     * Test 3 : clé valide → validation réussie (prédiction autorisée).
     */
    @Test
    void validateApiKey_validKey_succeeds() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlops_live_7a3f9c21";
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
        when(modelApiKeyRepository.findByKeyPrefix("mlops_live_00000000"))
                .thenReturn(Optional.empty());

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey("mlops_live_00000000_zzzz", "fraud_model")
        );

        assertEquals(401, ex.getStatus().value());
        assertEquals("INVALID_API_KEY", ex.getCode());
    }

    /*
     * Test 5 : clé désactivée → 403 KEY_DISABLED.
     */
    @Test
    void validateApiKey_disabledKey_forbidden() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);

        String secret = randomSecret();
        String prefix = "mlops_live_11aabbcc";
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

        String secret = randomSecret();
        String prefix = "mlops_live_22bbccdd";
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
     * Test 7 : clé révoquée → 410 KEY_REVOKED.
     */
    @Test
    void validateApiKey_revokedKey_gone() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);

        String secret = randomSecret();
        String prefix = "mlops_live_33ccddee";
        ModelApiKey key = key(prefix, secret, deployment);
        key.setRevokedAt(LocalDateTime.now());
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> service.validateApiKey(prefix + "_" + secret, "fraud_model")
        );

        assertEquals(410, ex.getStatus().value());
        assertEquals("KEY_REVOKED", ex.getCode());
    }

    /*
     * Test 8 : clé du modèle A utilisée sur le modèle B → 403 KEY_NOT_FOR_MODEL.
     */
    @Test
    void validateApiKey_keyNotForRequestedModel_forbidden() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlops_live_44ddeeff";
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
     * Test 9 : quota journalier dépassé → refus (HTTP 429 au niveau contrôleur).
     */
    @Test
    void reserveQuota_quotaExceeded_denied() {
        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(1L);
        key.setDailyQuota(1L);
        key.setRequestsToday(1L);
        key.setUsageDate(LocalDate.now());
        key.setEnabled(true);
        when(modelApiKeyRepository.findById(1L)).thenReturn(Optional.of(key));

        ModelGatewayService.QuotaResult result = service.reserveQuota(1L);

        assertFalse(result.isAllowed());
        assertEquals(1L, result.getLimit());
        assertEquals(0L, result.getRemaining());
    }

    /*
     * Test 10 : deux clés du même modèle → compteurs indépendants.
     */
    @Test
    void reserveQuota_twoKeysSameModel_separateCounters() {
        ModelApiKey key1 = new ModelApiKey();
        key1.setDeploymentId(1L);
        key1.setDailyQuota(10L);
        key1.setRequestsToday(0L);
        key1.setTotalRequests(0L);
        key1.setUsageDate(LocalDate.now());
        key1.setEnabled(true);

        ModelApiKey key2 = new ModelApiKey();
        key2.setDeploymentId(1L);
        key2.setDailyQuota(10L);
        key2.setRequestsToday(5L);
        key2.setTotalRequests(5L);
        key2.setUsageDate(LocalDate.now());
        key2.setEnabled(true);

        when(modelApiKeyRepository.findById(1L)).thenReturn(Optional.of(key1));
        when(modelApiKeyRepository.findById(2L)).thenReturn(Optional.of(key2));
        stubSaveReturnsArgument();

        assertTrue(service.reserveQuota(1L).isAllowed());
        assertTrue(service.reserveQuota(2L).isAllowed());

        assertEquals(1L, key1.getRequestsToday(), "Le compteur de la clé 1 doit être incrémenté seul");
        assertEquals(6L, key2.getRequestsToday(), "Le compteur de la clé 2 doit être incrémenté seul");
        assertEquals(1L, key1.getTotalRequests());
        assertEquals(6L, key2.getTotalRequests());
    }

    /*
     * Test 11 : ancienne clé livrée par email → continue de fonctionner.
     * Les clés historiques sont validées par key_prefix + key_hash uniquement,
     * indépendamment de toute logique de livraison.
     */
    @Test
    void validateApiKey_legacyEmailDeliveredKey_stillWorks() {
        ModelDeployment deployment = deployment("fraud_model", DeploymentVisibility.PUBLIC_API);
        when(modelDeploymentService.findDeploymentOrThrowPublic(anyLong())).thenReturn(deployment);

        String secret = randomSecret();
        String prefix = "mlops_live_55eeff00";
        ModelApiKey legacyKey = key(prefix, secret, deployment);
        legacyKey.setEnabled(true);
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(legacyKey));

        ModelApiKey validated = service.validateApiKey(prefix + "_" + secret, "fraud_model");

        assertNotNull(validated, "L'ancienne clé doit rester utilisable sans logique de livraison");
        assertEquals(prefix, validated.getKeyPrefix());
    }
}
