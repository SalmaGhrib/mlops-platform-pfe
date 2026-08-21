package com.mlops.platform.service;

import com.mlops.platform.dto.GitHubConfigRequest;
import com.mlops.platform.dto.GitHubKeyResponse;
import com.mlops.platform.dto.GitHubRetrainingConfigDto;
import com.mlops.platform.dto.GitHubTriggerRequest;
import com.mlops.platform.dto.GitHubTriggerResponse;
import com.mlops.platform.dto.GitHubWorkflowResponse;
import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.entity.GitHubRetrainingConfig;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.exception.GitHubWebhookException;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.repository.GitHubRetrainingConfigRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/*
 * Tests unitaires de l'intégration GitHub -> Réentraînement
 * (Mockito pur, sans Spring, sans base de données).
 *
 * Scénarios couverts :
 *  1. push touchant train.py -> ACCEPTED et expérience créée ;
 *  2. push (README) NON déclencheur -> workflow filtré, aucun appel API ;
 *  3. doublon de commit -> ALREADY_PROCESSED, sans nouveau retraining ;
 *  4. mauvaise clé -> INVALID_KEY ;
 *  5. mauvaise clé pour un autre projectId -> PROJECT_MISMATCH ;
 *  6. mauvaise branche -> INVALID_BRANCH ;
 *  7. repo non configuré -> INVALID_REPOSITORY ;
 *  8. aucune source Production valide -> BLOCKED_NO_SOURCE propagé ;
 *  9. retraining déjà en cours -> RETRAINING_ALREADY_RUNNING propagé.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubRetrainingServiceTest {

    @Mock
    private GitHubRetrainingConfigRepository configRepository;

    @Mock
    private RetrainingConfigRepository retrainingConfigRepository;

    @Mock
    private AutoRetrainingService autoRetrainingService;

    private GitHubRetrainingService service;

    private static final Long PROJECT_ID = 44L;
    private static final String REPOSITORY = "SalmaGhrib/mlops-platform-pfe";
    private static final String BRANCH = "main";
    private static final String COMMIT_SHA = "abcdef1234567890abcdef1234567890abcdef12";

    @BeforeEach
    void setUp() {
        service = new GitHubRetrainingService(
                configRepository,
                retrainingConfigRepository,
                autoRetrainingService
        );

        when(retrainingConfigRepository.findByProjectId(anyLong()))
                .thenReturn(Optional.of(new RetrainingConfig()));
        when(configRepository.findByProjectId(anyLong()))
                .thenReturn(Optional.of(new GitHubRetrainingConfig()));
        when(configRepository.save(any(GitHubRetrainingConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private GitHubRetrainingConfig enabledConfigWithKey() {
        GitHubRetrainingConfig config = new GitHubRetrainingConfig();
        config.setProjectId(PROJECT_ID);
        config.setEnabled(true);
        config.setRepository(REPOSITORY);
        config.setBranch(BRANCH);
        config.setSecretHash(sha256Hex("ghrt-secret-key-abc"));
        config.setSecretSuffix("wxyz");
        return config;
    }

    private GitHubTriggerRequest request(
            Long projectId,
            String repository,
            String branch,
            String commitSha
    ) {
        GitHubTriggerRequest req = new GitHubTriggerRequest();
        req.setProjectId(projectId);
        req.setTriggerType("GITHUB_PUSH");
        req.setRepository(repository);
        req.setBranch(branch);
        req.setCommitSha(commitSha);
        return req;
    }

    // ------------------------------------------------------------------
    // 1. Push touchant train.py -> ACCEPTED
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withValidKeyAndSource_acceptsAndCreatesExperiment() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));

        MlExperimentResponse created = new MlExperimentResponse();
        created.setId(99L);
        created.setStatus("CREATED");
        when(autoRetrainingService.launchGitHubRetraining(
                eq(PROJECT_ID),
                eq(REPOSITORY),
                eq(BRANCH),
                eq(COMMIT_SHA),
                any())
        ).thenReturn(created);

        GitHubTriggerResponse response = service.handleExternalTrigger(
                "ghrt-secret-key-abc",
                request(PROJECT_ID, REPOSITORY, BRANCH, COMMIT_SHA)
        );

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals(99L, response.getExperimentId());
        verify(autoRetrainingService).launchGitHubRetraining(
                eq(PROJECT_ID),
                eq(REPOSITORY),
                eq(BRANCH),
                eq(COMMIT_SHA),
                any()
        );
        assertEquals(COMMIT_SHA, config.getLastCommitSha());
        assertEquals("ACCEPTED", config.getLastTriggerStatus());
    }

    // ------------------------------------------------------------------
    // 2. Push README (non déclencheur) -> workflow filtré, pas d'appel API
    // ------------------------------------------------------------------

    @Test
    void workflowYaml_filtersToTrainPaths_excludesReadmeOnlyPushes() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config));

        GitHubWorkflowResponse workflow = service.getWorkflow(PROJECT_ID);

        assertTrue(workflow.getWorkflow().contains("paths:"));
        assertTrue(workflow.getWorkflow().contains("train.py"));
        assertTrue(workflow.getWorkflow().contains("src/ml/**"));
        assertTrue(workflow.getWorkflow().contains("\"projectId\": 44"));
        assertTrue(workflow.getWorkflow().contains("- main"));
        assertTrue(workflow.getWorkflow().contains("X-RETRAINING-KEY"));
    }

    // ------------------------------------------------------------------
    // 3. Commit identique -> ALREADY_PROCESSED (anti-doublon)
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withSameCommit_returnsAlreadyProcessed() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        config.setLastCommitSha(COMMIT_SHA);
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));

        GitHubTriggerResponse response = service.handleExternalTrigger(
                "ghrt-secret-key-abc",
                request(PROJECT_ID, REPOSITORY, BRANCH, COMMIT_SHA)
        );

        assertEquals("ALREADY_PROCESSED", response.getStatus());
        verify(autoRetrainingService, never()).launchGitHubRetraining(
                anyLong(), any(), any(), any(), any()
        );
    }

    // ------------------------------------------------------------------
    // 4. Mauvaise clé -> INVALID_KEY
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withUnknownKey_rejectsInvalidKey() {
        when(configRepository.findBySecretHash(any()))
                .thenReturn(Optional.empty());

        GitHubWebhookException thrown = assertThrows(
                GitHubWebhookException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-wrong-key",
                        request(PROJECT_ID, REPOSITORY, BRANCH, COMMIT_SHA)
                )
        );

        assertEquals("INVALID_KEY", thrown.getCode());
        verify(autoRetrainingService, never()).launchGitHubRetraining(
                anyLong(), any(), any(), any(), any()
        );
    }

    // ------------------------------------------------------------------
    // 5. Clé d'un AUTRE projet que le projectId du body -> PROJECT_MISMATCH
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withKeyOfAnotherProject_rejectsProjectMismatch() {
        // La clé est liée au projet 44 : un body projectId=31 doit être refusé.
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));

        GitHubWebhookException thrown = assertThrows(
                GitHubWebhookException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-secret-key-abc",
                        request(31L, REPOSITORY, BRANCH, COMMIT_SHA)
                )
        );

        assertEquals("PROJECT_MISMATCH", thrown.getCode());
        assertEquals(PROJECT_ID, thrown.getProjectId());
        verify(autoRetrainingService, never()).launchGitHubRetraining(
                anyLong(), any(), any(), any(), any()
        );
    }

    // ------------------------------------------------------------------
    // 6. Mauvaise branche -> INVALID_BRANCH
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withWrongBranch_rejectsInvalidBranch() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));

        GitHubWebhookException thrown = assertThrows(
                GitHubWebhookException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-secret-key-abc",
                        request(PROJECT_ID, REPOSITORY, "develop", COMMIT_SHA)
                )
        );

        assertEquals("INVALID_BRANCH", thrown.getCode());
        verify(autoRetrainingService, never()).launchGitHubRetraining(
                anyLong(), any(), any(), any(), any()
        );
    }

    // ------------------------------------------------------------------
    // 7. Repository non configuré / divergent -> INVALID_REPOSITORY
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withWrongRepository_rejectsInvalidRepository() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));

        GitHubWebhookException thrown = assertThrows(
                GitHubWebhookException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-secret-key-abc",
                        request(PROJECT_ID, "Someone/else", BRANCH, COMMIT_SHA)
                )
        );

        assertEquals("INVALID_REPOSITORY", thrown.getCode());
        verify(autoRetrainingService, never()).launchGitHubRetraining(
                anyLong(), any(), any(), any(), any()
        );
    }

    // ------------------------------------------------------------------
    // 8. Aucune source Production valide -> BLOCKED_NO_SOURCE
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_withOutProductiveSource_propagatesBlockedNoSource() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));
        when(autoRetrainingService.launchGitHubRetraining(
                eq(PROJECT_ID),
                eq(REPOSITORY),
                eq(BRANCH),
                eq(COMMIT_SHA),
                any())
        ).thenThrow(new IllegalStateException(
                "No valid production source found for projectId=" + PROJECT_ID
        ));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-secret-key-abc",
                        request(PROJECT_ID, REPOSITORY, BRANCH, COMMIT_SHA)
                )
        );

        assertTrue(thrown.getMessage().contains("No valid production source"));
        assertNull(config.getLastCommitSha());
    }

    // ------------------------------------------------------------------
    // 9. Retraining déjà en cours -> RETRAINING_ALREADY_RUNNING
    // ------------------------------------------------------------------

    @Test
    void externalTrigger_whenRetrainingAlreadyRunning_propagatesConflict() {
        GitHubRetrainingConfig config = enabledConfigWithKey();
        when(configRepository.findBySecretHash(sha256Hex("ghrt-secret-key-abc")))
                .thenReturn(Optional.of(config));
        when(autoRetrainingService.launchGitHubRetraining(
                eq(PROJECT_ID),
                eq(REPOSITORY),
                eq(BRANCH),
                eq(COMMIT_SHA),
                any())
        ).thenThrow(new RetrainingConflictException(
                "RETRAINING_ALREADY_RUNNING",
                "Un réentraînement est déjà en cours pour ce projet."
        ));

        RetrainingConflictException thrown = assertThrows(
                RetrainingConflictException.class,
                () -> service.handleExternalTrigger(
                        "ghrt-secret-key-abc",
                        request(PROJECT_ID, REPOSITORY, BRANCH, COMMIT_SHA)
                )
        );

        assertEquals("RETRAINING_ALREADY_RUNNING", thrown.getCode());
        assertNull(config.getLastCommitSha());
    }

    // ------------------------------------------------------------------
    // Sécurité : la clé n'est montrée qu'une fois, seul le hash est conservé
    // ------------------------------------------------------------------

    @Test
    void generateKey_returnsFullKeyOnce_storesOnlyHashAndSuffix() {
        GitHubRetrainingConfig config = new GitHubRetrainingConfig();
        config.setProjectId(PROJECT_ID);
        when(configRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config));

        GitHubKeyResponse key = service.generateKey(PROJECT_ID);

        assertNotNull(key.getSecretKey());
        assertFalse(key.getSecretKey().isBlank());
        assertEquals(4, key.getSecretSuffix().length());
        assertTrue(key.getSecretKey().endsWith(key.getSecretSuffix()));
        assertEquals(sha256Hex(key.getSecretKey()), config.getSecretHash());
        assertFalse(config.isKeyConfigured() == false);
    }

    @Test
    void upsertConfig_changesRepository_resetsLastCommitSha() {
        GitHubRetrainingConfig config = new GitHubRetrainingConfig();
        config.setProjectId(PROJECT_ID);
        config.setRepository(REPOSITORY);
        config.setBranch(BRANCH);
        config.setLastCommitSha(COMMIT_SHA);
        when(configRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(config));

        GitHubRetrainingConfigDto dto = service.upsertConfig(
                PROJECT_ID,
                new GitHubConfigRequest(true, "SalmaGhrib/other-repo", BRANCH)
        );

        assertTrue(dto.isEnabled());
        assertNull(config.getLastCommitSha());
        assertNull(config.getLastTriggerStatus());
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}