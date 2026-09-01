package com.mlops.platform.service;

import com.mlops.platform.dto.GitHubConfigRequest;
import com.mlops.platform.dto.GitHubKeyResponse;
import com.mlops.platform.dto.GitHubRetrainingConfigDto;
import com.mlops.platform.dto.GitHubTriggerRequest;
import com.mlops.platform.dto.GitHubTriggerResponse;
import com.mlops.platform.dto.GitHubWorkflowResponse;
import com.mlops.platform.entity.GitHubRetrainingConfig;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingFrequencyType;
import com.mlops.platform.entity.RetrainingStrategy;
import com.mlops.platform.exception.GitHubWebhookException;
import com.mlops.platform.exception.RetrainingConflictException;
import com.mlops.platform.repository.GitHubRetrainingConfigRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Intégration GitHub → Réentraînement.
 *
 * La clé est strictement liée au projectId de la config : le projectId envoyé
 * dans le corps du webhook n'est JAMAIS approuvé seul, seule la clé
 * l'autorise. La clé complète n'est jamais stockée ni loggée.
 */
@Service
public class GitHubRetrainingService {

    private static final int KEY_BYTES = 32;

    private final GitHubRetrainingConfigRepository configRepository;
    private final RetrainingConfigRepository retrainingConfigRepository;
    private final AutoRetrainingService autoRetrainingService;
    private final SecureRandom secureRandom = new SecureRandom();

    public GitHubRetrainingService(
            GitHubRetrainingConfigRepository configRepository,
            RetrainingConfigRepository retrainingConfigRepository,
            AutoRetrainingService autoRetrainingService
    ) {
        this.configRepository = configRepository;
        this.retrainingConfigRepository = retrainingConfigRepository;
        this.autoRetrainingService = autoRetrainingService;
    }

    @Transactional(readOnly = true)
    public GitHubRetrainingConfigDto getConfig(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required.");
        }
        GitHubRetrainingConfig config = loadOrNull(projectId);
        return config != null
                ? GitHubRetrainingConfigDto.from(config)
                : new GitHubRetrainingConfigDto();
    }

    @Transactional
    public GitHubRetrainingConfigDto upsertConfig(
            Long projectId,
            GitHubConfigRequest request
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required.");
        }

        ensureRetrainingConfigExists(projectId);

        GitHubRetrainingConfig config = upsertRow(projectId);

        String repository = normalizeRepository(request.getRepository());
        String branch = normalizeBranch(request.getBranch());

        boolean requiresEnabled = request.isEnabled();

        if (requiresEnabled
                && !hasText(repository)) {
            throw new IllegalArgumentException(
                    "Repository (owner/repository) is required when GitHub is enabled."
            );
        }

        if (requiresEnabled
                && !hasText(branch)) {
            throw new IllegalArgumentException(
                    "Branch is required when GitHub is enabled."
            );
        }

        boolean scopeChanged = repositoryChanged(config, repository);
        config.setEnabled(requiresEnabled);
        config.setRepository(repository);
        config.setBranch(branch);

        /*
         * Tout changement de périmètre (repo/branche) réinitialise le commit de
         * dédoublonnage : le même SHA sur une autre branche/repo est un nouvel
         * événement.
         */
        if (scopeChanged) {
            config.setLastCommitSha(null);
            config.setLastTriggerStatus(null);
        }

        if (!requiresEnabled) {
            if (!hasText(repository)) {
                config.setRepository(null);
            }
            if (!hasText(branch)) {
                config.setBranch(null);
            }
        }

        return GitHubRetrainingConfigDto.from(configRepository.save(config));
    }

    /**
     * Génère une nouvelle clé pour le projet. Renvoyée en clair UNE seule fois,
     * puis seule son empreinte SHA-256 (et ses 4 derniers caractères) est
     * conservée. La régénération invalide l'ancienne clé.
     */
    @Transactional
    public GitHubKeyResponse generateKey(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required.");
        }

        ensureRetrainingConfigExists(projectId);

        GitHubRetrainingConfig config = upsertRow(projectId);

        byte[] keyBytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(keyBytes);
        String secretKey = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(keyBytes);

        config.setSecretHash(sha256Hex(secretKey));
        config.setSecretSuffix(lastCharacters(secretKey, 4));
        config.setLastCommitSha(null);
        config.setLastTriggerStatus(null);
        configRepository.save(config);

        System.out.println(
                "[GITHUB_RETRAIN_RECEIVED] Key generated. projectId="
                        + projectId
                        + ", configId="
                        + config.getId()
                        + ", keySuffix=..."
                        + config.getSecretSuffix()
        );

        return new GitHubKeyResponse(secretKey, config.getSecretSuffix());
    }

    @Transactional
    public GitHubRetrainingConfigDto revokeKey(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required.");
        }

        configRepository.revokeKey(projectId);

        System.out.println(
                "[GITHUB_RETRAIN_RECEIVED] Key revoked. projectId="
                        + projectId
        );

        GitHubRetrainingConfig config = loadOrNull(projectId);
        return config != null
                ? GitHubRetrainingConfigDto.from(config)
                : new GitHubRetrainingConfigDto();
    }

    @Transactional(readOnly = true)
    public GitHubWorkflowResponse getWorkflow(Long projectId) {
        GitHubRetrainingConfig config = load(projectId);

        String branch = hasText(config.getBranch())
                ? config.getBranch()
                : "main";

        return new GitHubWorkflowResponse(buildWorkflowYaml(projectId, branch));
    }

    /**
     * Point d'entrée du webhook GitHub.
     *
     * Ordre de validation imposé : clé → enabled → repository → branch →
     * projectId → doublon → anti-concurrence → source Production → lancement.
     */
    @Transactional
    public GitHubTriggerResponse handleExternalTrigger(
            String rawKey,
            GitHubTriggerRequest request
    ) {
        Long bodyProjectId = request.getProjectId();
        String repository = normalizeRepository(request.getRepository());
        String branch = normalizeBranch(request.getBranch());
        String commitSha = normalizeCommitSha(request.getCommitSha());

        logReceived(bodyProjectId, repository, branch, commitSha);

        if (!hasText(rawKey)) {
            throw new GitHubWebhookException(
                    "INVALID_KEY",
                    HttpStatus.UNAUTHORIZED,
                    bodyProjectId,
                    commitSha,
                    "Missing retraining key."
            );
        }

        String secretHash = sha256Hex(rawKey.trim());

        GitHubRetrainingConfig config = configRepository
                .findBySecretHash(secretHash)
                .orElse(null);

        if (config == null) {
            throw new GitHubWebhookException(
                    "INVALID_KEY",
                    HttpStatus.UNAUTHORIZED,
                    bodyProjectId,
                    commitSha,
                    "Unknown retraining key. Check MLOPS_RETRAINING_KEY."
            );
        }

        System.out.println(
                "[GITHUB_RETRAIN_AUTHENTICATED] projectId="
                        + config.getProjectId()
                        + ", keySuffix=..."
                        + config.getSecretSuffix()
        );

        Long authorizedProjectId = config.getProjectId();

        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return rejectAndStore(
                    config,
                    bodyProjectId,
                    repository,
                    branch,
                    commitSha,
                    "GITHUB_DISABLED",
                    HttpStatus.FORBIDDEN,
                    "GitHub retraining is disabled for this project."
            );
        }

        if (!hasText(config.getRepository())
                || !config.getRepository().equalsIgnoreCase(repository)) {
            return rejectAndStore(
                    config,
                    bodyProjectId,
                    repository,
                    branch,
                    commitSha,
                    "INVALID_REPOSITORY",
                    HttpStatus.FORBIDDEN,
                    "Repository does not match the configured GitHub repository."
            );
        }

        if (!hasText(config.getBranch())
                || !config.getBranch().equalsIgnoreCase(branch)) {
            return rejectAndStore(
                    config,
                    bodyProjectId,
                    repository,
                    branch,
                    commitSha,
                    "INVALID_BRANCH",
                    HttpStatus.BAD_REQUEST,
                    "Branch does not match the configured branch."
            );
        }

        /*
         * La clé est liée au projet : le projectId du body doit correspondre,
         * sinon REFUS (clé projet 44 + body projectId 31 -> refusé).
         */
        if (bodyProjectId == null
                || !authorizedProjectId.equals(bodyProjectId)) {
            throw new GitHubWebhookException(
                    "PROJECT_MISMATCH",
                    HttpStatus.FORBIDDEN,
                    authorizedProjectId,
                    commitSha,
                    "projectId does not match the project linked to the retraining key."
            );
        }

        /*
         * Anti-doublon : même commit déjà accepté pour ce projet -> aucun
         * nouveau retraining.
         */
        if (hasText(config.getLastCommitSha())
                && config.getLastCommitSha().equals(commitSha)) {
            System.out.println(
                    "[GITHUB_RETRAIN_DUPLICATE] projectId="
                            + authorizedProjectId
                            + ", repository="
                            + repository
                            + ", branch="
                            + branch
                            + ", commitSha="
                            + shortSha(commitSha)
            );

            config.setLastTriggerAt(now());
            config.setLastTriggerStatus("ALREADY_PROCESSED");
            config.setLastTriggerMessage("No new retraining created (duplicate commit).");
            configRepository.save(config);

            return GitHubTriggerResponse.duplicate(
                    authorizedProjectId,
                    commitSha
            );
        }

        /*
         * Anti-concurrence + source Production + lancement : mêmes garanties
         * que le retraining manuel (claim atomique, BLOCKED_NO_SOURCE si aucune
         * source valide).
         */
        String triggerMetadata = buildTriggerMetadata(repository, branch, commitSha);

        com.mlops.platform.dto.MlExperimentResponse experiment =
                autoRetrainingService.launchGitHubRetraining(
                        authorizedProjectId,
                        repository,
                        branch,
                        commitSha,
                        triggerMetadata
                );

        Long experimentId = experiment != null
                ? experiment.getId()
                : null;

        if (experimentId != null) {
            config.setLastCommitSha(commitSha);
            config.setLastTriggerAt(now());
            config.setLastTriggerStatus("ACCEPTED");
            config.setLastTriggerMessage(
                    "Retraining launched (experimentId=" + experimentId + ")."
            );
            configRepository.save(config);
        }

        return GitHubTriggerResponse.accepted(
                authorizedProjectId,
                repository,
                branch,
                commitSha,
                experimentId
        );
    }

    /**
     * Capture un rejet sans enregistrer le commitSha comme « traité » : le même
     * push pourra être réessayé plus tard (ex. source devenue disponible).
     */
    private GitHubTriggerResponse rejectAndStore(
            GitHubRetrainingConfig config,
            Long bodyProjectId,
            String repository,
            String branch,
            String commitSha,
            String code,
            HttpStatus httpStatus,
            String message
    ) {
        System.out.println(
                "[GITHUB_RETRAIN_REJECTED] projectId="
                        + config.getProjectId()
                        + ", repository="
                        + repository
                        + ", branch="
                        + branch
                        + ", commitSha="
                        + shortSha(commitSha)
                        + ", reason="
                        + code
        );

        config.setLastTriggerAt(now());
        config.setLastTriggerStatus(code);
        config.setLastTriggerMessage(message);
        configRepository.save(config);

        throw new GitHubWebhookException(
                code,
                httpStatus,
                config.getProjectId(),
                commitSha,
                message
        );
    }

    private void ensureRetrainingConfigExists(Long projectId) {
        retrainingConfigRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    RetrainingConfig created = new RetrainingConfig();
                    created.setProjectId(projectId);
                    created.setEnabled(true);
                    created.setStrategy(RetrainingStrategy.MONITORING_SMART);
                    return retrainingConfigRepository.save(created);
                });
    }

    private GitHubRetrainingConfig upsertRow(Long projectId) {
        return configRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    GitHubRetrainingConfig created = new GitHubRetrainingConfig();
                    created.setProjectId(projectId);
                    created.setEnabled(false);
                    return created;
                });
    }

    private boolean repositoryChanged(
            GitHubRetrainingConfig config,
            String repository
    ) {
        String previous = normalizeRepository(config.getRepository());
        return previous == null
                ? repository != null
                : !previous.equalsIgnoreCase(normalizeRepository(repository));
    }

    private GitHubRetrainingConfig load(Long projectId) {
        GitHubRetrainingConfig config = configRepository
                .findByProjectId(projectId)
                .orElse(null);
        if (config == null) {
            throw new GitHubWebhookException(
                    "GITHUB_NOT_CONFIGURED",
                    HttpStatus.NOT_FOUND,
                    projectId,
                    null,
                    "GitHub integration is not configured for this project."
            );
        }
        return config;
    }

    private GitHubRetrainingConfig loadOrNull(Long projectId) {
        return configRepository.findByProjectId(projectId).orElse(null);
    }

    static String buildWorkflowYaml(Long projectId, String branch) {
        return """
                name: MLOps Retraining

                on:
                  push:
                    branches:
                      - %BRANCH%
                    paths:
                      - "train.py"
                      - "src/ml/**"
                      - "preprocessing/**"
                      - "requirements.txt"
                      - "Dockerfile"
                      - "data/**"
                      - "config/**"

                jobs:
                  retraining:
                    runs-on: ubuntu-latest

                    steps:
                      - name: Checkout
                        uses: actions/checkout@v4

                      - name: Run tests
                        run: |
                          echo "Run project tests here"

                      - name: Trigger MLOps retraining
                        run: |
                          curl --fail-with-body -X POST \\
                            "${{ secrets.MLOPS_API_URL }}/api/retraining/external/trigger" \\
                            -H "Content-Type: application/json" \\
                            -H "X-RETRAINING-KEY: ${{ secrets.MLOPS_RETRAINING_KEY }}" \\
                            -d '{
                              "projectId": %PROJECT_ID%,
                              "triggerType": "GITHUB_PUSH",
                              "repository": "${{ github.repository }}",
                              "branch": "${{ github.ref_name }}",
                              "commitSha": "${{ github.sha }}"
                            }'
                """
                .replace("%BRANCH%", branch)
                .replace("%PROJECT_ID%", String.valueOf(projectId));
    }

    private String buildTriggerMetadata(
            String repository,
            String branch,
            String commitSha
    ) {
        return "{\"repository\":\"" + escapeJson(repository)
                + "\",\"branch\":\"" + escapeJson(branch)
                + "\",\"commitSha\":\"" + escapeJson(commitSha)
                + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String normalizeRepository(String repository) {
        if (repository == null) {
            return null;
        }
        String value = repository.trim();
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.startsWith("https://github.com/")) {
            value = value.substring("https://github.com/".length());
        } else if (value.startsWith("git@github.com:")) {
            value = value.substring("git@github.com:".length());
        }
        value = value.replaceAll("/+$", "");
        if (value.isBlank()) {
            return null;
        }
        return value;
    }

    private String normalizeBranch(String branch) {
        return branch == null ? null : branch.trim();
    }

    private String normalizeCommitSha(String commitSha) {
        return commitSha == null ? null : commitSha.trim();
    }

    private void logReceived(
            Long projectId,
            String repository,
            String branch,
            String commitSha
    ) {
        System.out.println(
                "[GITHUB_RETRAIN_RECEIVED] projectId="
                        + projectId
                        + ", repository="
                        + repository
                        + ", branch="
                        + branch
                        + ", commitSha="
                        + shortSha(commitSha)
        );
    }

    private String shortSha(String commitSha) {
        if (commitSha == null) {
            return "null";
        }
        return commitSha.length() <= 8
                ? commitSha
                : commitSha.substring(0, 8);
    }

    private String lastCharacters(String value, int count) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(Math.max(0, value.length() - count));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 not available.",
                    impossible
            );
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}