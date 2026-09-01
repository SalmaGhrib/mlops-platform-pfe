package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelAccessControlServiceTest {

    @Mock
    private ModelDeploymentRepository modelDeploymentRepository;

    @Mock
    private MlProjectRepository mlProjectRepository;

    @Mock
    private ModelDeploymentShareRepository modelDeploymentShareRepository;

    private ModelAccessControlService service;

    private static final Long OWNER_ID = 1L;
    private static final Long SHARED_USER_ID = 2L;
    private static final Long STRANGER_ID = 3L;
    private static final Long DEPLOYMENT_ID = 100L;
    private static final Long PROJECT_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new ModelAccessControlService(
                modelDeploymentRepository,
                mlProjectRepository,
                modelDeploymentShareRepository
        );
    }

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ModelDeployment deploymentWithId(Long id, DeploymentVisibility visibility) {
        ModelDeployment d = new ModelDeployment();
        setId(d, id);
        d.setProjectId(PROJECT_ID);
        d.setRegisteredModelName("test-model");
        d.setVisibility(visibility);
        return d;
    }

    private ModelDeployment deployment(DeploymentVisibility visibility) {
        ModelDeployment d = new ModelDeployment();
        d.setProjectId(PROJECT_ID);
        d.setRegisteredModelName("test-model");
        d.setVisibility(visibility);
        return d;
    }

    private void stubOwner(User owner) {
        MlProject project = new MlProject();
        project.setOwner(owner);
        when(mlProjectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
    }

    private void stubNoOwner() {
        when(mlProjectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
    }

    private void stubSharedDirectly(Long deploymentId, Long userId, boolean isShared) {
        lenient().when(modelDeploymentShareRepository
                .existsByDeploymentIdAndSharedWithUserId(deploymentId, userId))
                .thenReturn(isShared);
    }

    private void stubDeploymentSharedOnSameName(boolean isShared) {
        ModelDeployment other = new ModelDeployment();
        setId(other, 101L);
        other.setProjectId(PROJECT_ID);
        other.setRegisteredModelName("test-model");
        lenient().when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("test-model"))
                .thenReturn(List.of(other));
        lenient().when(modelDeploymentShareRepository
                .existsByDeploymentIdAndSharedWithUserId(101L, SHARED_USER_ID))
                .thenReturn(isShared);
    }

    private void stubNoSameModelDeployments() {
        lenient().when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("test-model"))
                .thenReturn(Collections.emptyList());
    }

    // --- isOwner tests ---

    @Test
    void isOwner_returnsTrueForOwner() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.isOwner(user(OWNER_ID), deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void isOwner_returnsFalseForNonOwner() {
        stubOwner(user(OWNER_ID));
        assertFalse(service.isOwner(user(STRANGER_ID), deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void isOwner_returnsFalseForNullUser() {
        assertFalse(service.isOwner(null, deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void isOwner_returnsFalseForNullDeployment() {
        assertFalse(service.isOwner(user(OWNER_ID), null));
    }

    @Test
    void isOwner_returnsFalseForNullProjectId() {
        ModelDeployment d = new ModelDeployment();
        d.setRegisteredModelName("test-model");
        d.setVisibility(DeploymentVisibility.PRIVATE);
        assertFalse(service.isOwner(user(OWNER_ID), d));
    }

    @Test
    void isOwner_returnsFalseWhenProjectNotFound() {
        stubNoOwner();
        assertFalse(service.isOwner(user(OWNER_ID), deployment(DeploymentVisibility.PRIVATE)));
    }

    // --- isSharedWithUser tests ---

    @Test
    void isSharedWithUser_returnsTrueForDirectShare() {
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, true);
        assertTrue(service.isSharedWithUser(user(SHARED_USER_ID), d));
    }

    @Test
    void isSharedWithUser_returnsFalseForDirectNoShare() {
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);
        stubNoSameModelDeployments();
        assertFalse(service.isSharedWithUser(user(SHARED_USER_ID), d));
    }

    @Test
    void isSharedWithUser_returnsTrueViaSameModelFallback() {
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);
        stubDeploymentSharedOnSameName(true);
        assertTrue(service.isSharedWithUser(user(SHARED_USER_ID), d));
    }

    @Test
    void isSharedWithUser_returnsFalseWhenNoSharesAnywhere() {
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("test-model"))
                .thenReturn(List.of());
        assertFalse(service.isSharedWithUser(user(SHARED_USER_ID), d));
    }

    @Test
    void isSharedWithUser_returnsFalseForNullUser() {
        assertFalse(service.isSharedWithUser(null, deployment(DeploymentVisibility.SHARED)));
    }

    @Test
    void isSharedWithUser_returnsFalseForNullDeployment() {
        assertFalse(service.isSharedWithUser(user(SHARED_USER_ID), null));
    }

    @Test
    void isSharedWithUser_skipsNullIdDeploymentsInFallback() {
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);

        ModelDeployment other = new ModelDeployment();
        setId(other, 101L);
        other.setProjectId(PROJECT_ID);
        other.setRegisteredModelName("test-model");
        when(modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc("test-model"))
                .thenReturn(List.of(other));
        when(modelDeploymentShareRepository
                .existsByDeploymentIdAndSharedWithUserId(101L, SHARED_USER_ID))
                .thenReturn(false);

        assertFalse(service.isSharedWithUser(user(SHARED_USER_ID), d));
    }

    // --- canAccessDeployment tests ---

    @Test
    void canAccessDeployment_ownerAlwaysAllowed() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.canAccessDeployment(user(OWNER_ID), deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void canAccessDeployment_privateOnlyOwner() {
        stubOwner(user(OWNER_ID));
        assertFalse(service.canAccessDeployment(user(STRANGER_ID), deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void canAccessDeployment_shared_allowedIfInShareList() {
        stubOwner(user(OWNER_ID));
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, true);
        assertTrue(service.canAccessDeployment(user(SHARED_USER_ID), d));
    }

    @Test
    void canAccessDeployment_shared_deniedIfNotInShareList() {
        stubOwner(user(OWNER_ID));
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);
        stubNoSameModelDeployments();
        assertFalse(service.canAccessDeployment(user(STRANGER_ID), d));
    }

    @Test
    void canAccessDeployment_publicApi_allAllowed() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.canAccessDeployment(user(STRANGER_ID), deployment(DeploymentVisibility.PUBLIC_API)));
    }

    @Test
    void canAccessDeployment_nullUserDenied() {
        assertFalse(service.canAccessDeployment(null, deployment(DeploymentVisibility.PUBLIC_API)));
    }

    @Test
    void canAccessDeployment_nullDeploymentDenied() {
        assertFalse(service.canAccessDeployment(user(OWNER_ID), null));
    }

    // --- canManageDeployment tests ---

    @Test
    void canManageDeployment_onlyOwner() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.canManageDeployment(user(OWNER_ID), deployment(DeploymentVisibility.SHARED)));
    }

    @Test
    void canManageDeployment_sharedUserDenied() {
        stubOwner(user(OWNER_ID));
        assertFalse(service.canManageDeployment(user(SHARED_USER_ID), deployment(DeploymentVisibility.SHARED)));
    }

    // --- canUseApiKey tests ---

    @Test
    void canUseApiKey_ownerAllowed() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.canUseApiKey(user(OWNER_ID), deployment(DeploymentVisibility.SHARED)));
    }

    @Test
    void canUseApiKey_sharedUserAllowedWhenInList() {
        stubOwner(user(OWNER_ID));
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, true);
        assertTrue(service.canUseApiKey(user(SHARED_USER_ID), d));
    }

    @Test
    void canUseApiKey_sharedUserDeniedWhenNotInList() {
        stubOwner(user(OWNER_ID));
        ModelDeployment d = deploymentWithId(DEPLOYMENT_ID, DeploymentVisibility.SHARED);
        stubSharedDirectly(DEPLOYMENT_ID, SHARED_USER_ID, false);
        stubNoSameModelDeployments();
        assertFalse(service.canUseApiKey(user(STRANGER_ID), d));
    }

    @Test
    void canUseApiKey_publicApi_allAllowed() {
        stubOwner(user(OWNER_ID));
        assertTrue(service.canUseApiKey(user(STRANGER_ID), deployment(DeploymentVisibility.PUBLIC_API)));
    }

    @Test
    void canUseApiKey_nullKeyOwnerDenied() {
        assertFalse(service.canUseApiKey(null, deployment(DeploymentVisibility.PUBLIC_API)));
    }

    @Test
    void canUseApiKey_nullDeploymentDenied() {
        assertFalse(service.canUseApiKey(user(OWNER_ID), null));
    }

    // --- getOwner tests ---

    @Test
    void getOwner_returnsOwner() {
        User owner = user(OWNER_ID);
        stubOwner(owner);
        assertSame(owner, service.getOwner(deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void getOwner_returnsNullWhenNoProject() {
        when(mlProjectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
        assertNull(service.getOwner(deployment(DeploymentVisibility.PRIVATE)));
    }

    @Test
    void getOwner_returnsNullForNullDeployment() {
        assertNull(service.getOwner(null));
    }

    @Test
    void getOwner_returnsNullForDeploymentWithNullProjectId() {
        ModelDeployment d = new ModelDeployment();
        d.setRegisteredModelName("test-model");
        d.setVisibility(DeploymentVisibility.PRIVATE);
        assertNull(service.getOwner(d));
    }
}
