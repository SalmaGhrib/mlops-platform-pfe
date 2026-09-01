package com.mlops.platform.service;

import java.nio.file.Path;

/**
 * Workspace normalise pour un experiment CUSTOM_DOCKERFILE.
 *
 * Tous les fichiers sont copies depuis leurs chemins physiques de stockage
 * (UUID timestamps) vers des noms canoniques dans un repertoire isolé
 * par experiment.
 *
 * Le reste du pipeline (KubernetesBuildService, KubernetesJobService)
 * utilise UNIQUEMENT ceobjet pour les paths.
 */
public record CustomDockerWorkspace(
        Long experimentId,
        Long projectId,
        Path workspacePath,
        Path dockerfilePath,
        Path trainScriptPath,
        Path requirementsPath,
        Path datasetPath,
        boolean datasetIsArchive,
        Path datasetExtractedDir
) {
    /**
     * Chemin container pour Kaniko : /app/uploads/workspaces/{experimentId}
     */
    public String containerWorkspacePath() {
        return workspacePath.toString();
    }

    /**
     * Chemin du workspace tel que monte dans les pods Kaniko / training.
     */
    public String kanikoContextUri() {
        return "dir://" + workspacePath;
    }
}
