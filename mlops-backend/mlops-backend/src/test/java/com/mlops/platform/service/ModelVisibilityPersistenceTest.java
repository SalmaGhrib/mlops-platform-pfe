package com.mlops.platform.service;

import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.ModelDeploymentShare;
import com.mlops.platform.entity.User;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelApiKeyRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import com.mlops.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelVisibilityPersistenceTest {

    @Mock
    private ModelDeploymentRepository modelDeploymentRepository;
    @Mock
    private ModelApiKeyRepository modelApiKeyRepository;
    @Mock
    private ModelDeploymentShareRepository modelDeploymentShareRepository;
    @Mock
    private MlProjectRepository mlProjectRepository;
    @Mock
    private UserRepository userRepository;

    private ModelDeploymentService deploymentService;

    private User owner;
    private User otherUser;
    private MlProject project;
    private ModelDeployment deployment;

    @BeforeEach
    void setUp() {
        deploymentService = new ModelDeploymentService(
                modelDeploymentRepository,
                null, null, null,
                modelDeploymentShareRepository,
                userRepository,
                null,
                mlProjectRepository,
                null, null, null,
                modelApiKeyRepository
        );

        owner = new User();
        owner.setId(1L);

        otherUser = new User();
        otherUser.setId(2L);

        project = new MlProject();
        project.setId(10L);
        project.setOwner(owner);

        deployment = new ModelDeployment();
        deployment.setProjectId(10L);
        deployment.setRegisteredModelName("fraud_model");
        deployment.setVisibility(DeploymentVisibility.PUBLIC_API);
        deployment.setApiEnabled(true);
        setField(deployment, "id", 100L);

        when(mlProjectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(modelDeploymentRepository.findById(100L)).thenReturn(Optional.of(deployment));
        when(modelDeploymentRepository.saveAndFlush(any(ModelDeployment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set " + fieldName, e);
        }
    }

    // ─────────────────────────────────────────────────
    // 1. PUBLIC_API → PRIVATE → reconcile → reste PRIVATE
    // ─────────────────────────────────────────────────

    @Test
    void publicApi_to_private_stays_private_after_reconciliation() {
        ModelDeploymentResponse result = deploymentService.updateDeploymentVisibility(100L, "PRIVATE");

        assertEquals("PRIVATE", result.getVisibility());
        assertFalse(result.getApiEnabled());
        assertEquals(DeploymentVisibility.PRIVATE, deployment.getVisibility());
    }

    // ─────────────────────────────────────────────────
    // 2. Refresh → reste PRIVATE
    // ─────────────────────────────────────────────────

    @Test
    void private_stays_private_after_refresh() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);
        deployment.setApiEnabled(false);

        ModelDeploymentResponse result = deploymentService.updateDeploymentVisibility(100L, "PRIVATE");
        assertEquals("PRIVATE", result.getVisibility());

        ModelDeploymentResponse result2 = deploymentService.updateDeploymentVisibility(100L, "PRIVATE");
        assertEquals("PRIVATE", result2.getVisibility());
    }

    // ─────────────────────────────────────────────────
    // 3. Autre user → clé supprimée au passage PRIVATE
    // ─────────────────────────────────────────────────

    @Test
    void switching_to_private_deletes_other_users_keys() {
        ModelApiKey ownerKey = new ModelApiKey();
        setField(ownerKey, "id", 10L);
        ownerKey.setDeploymentId(100L);
        ownerKey.setOwnerId(1L);

        ModelApiKey otherKey = new ModelApiKey();
        setField(otherKey, "id", 11L);
        otherKey.setDeploymentId(100L);
        otherKey.setOwnerId(2L);

        when(modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of(ownerKey, otherKey));
        when(modelDeploymentShareRepository.findByDeploymentId(100L))
                .thenReturn(List.of());

        deploymentService.updateDeploymentVisibility(100L, "PRIVATE");

        verify(modelApiKeyRepository).deleteById(11L);
        verify(modelApiKeyRepository, never()).deleteById(10L);
    }

    // ─────────────────────────────────────────────────
    // 4. Owner → modèle visible et ses clés fonctionnent
    // ─────────────────────────────────────────────────

    @Test
    void owner_key_works_on_private_model() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);

        ModelDeploymentService mockDeploymentService = mock(ModelDeploymentService.class);
        when(mockDeploymentService.findDeploymentOrThrowPublic(100L)).thenReturn(deployment);

        ModelGatewayService gw = new ModelGatewayService(
                modelApiKeyRepository, null, mockDeploymentService, null,
                createAccessControlService(), userRepository
        );

        String secret = randomSecret();
        String prefix = "mlp_aabbccdd";
        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(100L);
        key.setOwnerId(1L);
        key.setKeyPrefix(prefix);
        key.setKeyHash(sha256Hex(secret));
        key.setEnabled(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        ModelApiKey validated = gw.validateApiKey(prefix + "_" + secret, "fraud_model");
        assertNotNull(validated);
    }

    // ─────────────────────────────────────────────────
    // 5. Ancienne clé d'un autre user → refusée
    // ─────────────────────────────────────────────────

    @Test
    void other_users_key_rejected_on_private_model() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);

        ModelDeploymentService mockDeploymentService = mock(ModelDeploymentService.class);
        when(mockDeploymentService.findDeploymentOrThrowPublic(100L)).thenReturn(deployment);

        ModelGatewayService gw = new ModelGatewayService(
                modelApiKeyRepository, null, mockDeploymentService, null,
                createAccessControlService(), userRepository
        );

        String secret = randomSecret();
        String prefix = "mlp_ccddee11";
        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(100L);
        key.setOwnerId(2L);
        key.setKeyPrefix(prefix);
        key.setKeyHash(sha256Hex(secret));
        key.setEnabled(true);

        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
        when(modelApiKeyRepository.findByKeyPrefix(prefix)).thenReturn(Optional.of(key));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> gw.validateApiKey(prefix + "_" + secret, "fraud_model")
        );
        assertEquals("PRIVATE_NOT_OWNER", ex.getCode());
    }

    // ─────────────────────────────────────────────────
    // 6. PRIVATE → PUBLIC_API → reste PUBLIC_API
    // ─────────────────────────────────────────────────

    @Test
    void private_to_publicApi_stays_publicApi_after_reconciliation() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);
        deployment.setApiEnabled(false);

        ModelDeploymentResponse result = deploymentService.updateDeploymentVisibility(100L, "PUBLIC_API");

        assertEquals("PUBLIC_API", result.getVisibility());
        assertTrue(result.getApiEnabled());
    }

    // ─────────────────────────────────────────────────
    // 7. PRIVATE → supprime les shares
    // ─────────────────────────────────────────────────

    @Test
    void switching_to_private_deletes_shares() {
        ModelDeploymentShare share = new ModelDeploymentShare();
        setField(share, "id", 200L);
        share.setDeploymentId(100L);
        share.setSharedWithUserId(2L);

        when(modelApiKeyRepository.findByDeploymentIdOrderByCreatedAtDesc(100L))
                .thenReturn(List.of());
        when(modelDeploymentShareRepository.findByDeploymentId(100L))
                .thenReturn(List.of(share));

        deploymentService.updateDeploymentVisibility(100L, "PRIVATE");

        verify(modelDeploymentShareRepository).deleteByDeploymentId(100L);
    }

    // ─────────────────────────────────────────────────
    // 8. resolveGatewayDeployment: blocks non-owner, allows owner
    // ─────────────────────────────────────────────────

    @Test
    void resolveGatewayDeployment_blocks_non_owner_on_private() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);

        ModelDeploymentService mockDeploymentService = mock(ModelDeploymentService.class);
        when(mockDeploymentService.findGatewayDeploymentForExternalApiOrThrow(100L)).thenReturn(deployment);

        ModelGatewayService gw = new ModelGatewayService(
                modelApiKeyRepository, null, mockDeploymentService, null,
                createAccessControlService(), userRepository
        );

        ModelApiKey otherKey = new ModelApiKey();
        otherKey.setDeploymentId(100L);
        otherKey.setOwnerId(2L);

        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));

        GatewayException ex = assertThrows(
                GatewayException.class,
                () -> gw.resolveGatewayDeployment(otherKey)
        );
        assertEquals("EXTERNAL_ACCESS_DISABLED", ex.getCode());
    }

    @Test
    void resolveGatewayDeployment_allows_owner_on_private() {
        deployment.setVisibility(DeploymentVisibility.PRIVATE);

        ModelDeploymentService mockDeploymentService = mock(ModelDeploymentService.class);
        when(mockDeploymentService.findGatewayDeploymentForExternalApiOrThrow(100L)).thenReturn(deployment);

        ModelGatewayService gw = new ModelGatewayService(
                modelApiKeyRepository, null, mockDeploymentService, null,
                createAccessControlService(), userRepository
        );

        ModelApiKey ownerKey = new ModelApiKey();
        ownerKey.setDeploymentId(100L);
        ownerKey.setOwnerId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        ModelDeployment resolved = gw.resolveGatewayDeployment(ownerKey);
        assertNotNull(resolved);
        assertEquals(DeploymentVisibility.PRIVATE, resolved.getVisibility());
    }

    // ─────────────────────────────────────────────────

    private ModelAccessControlService createAccessControlService() {
        return new ModelAccessControlService(
                modelDeploymentRepository,
                mlProjectRepository,
                modelDeploymentShareRepository
        );
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) hex.append(String.format("%02x", b));
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
}
