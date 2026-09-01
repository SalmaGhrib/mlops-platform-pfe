package com.mlops.platform.service;

import com.mlops.platform.config.UploadsStorageProperties;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service central de preparation du workspace pour CUSTOM_DOCKERFILE.
 *
 * Responsabilites :
 *  - Creer un repertoire isole par experiment sous /app/uploads/workspaces/{experimentId}/
 *  - Copier les 4 fichiers uploades avec des noms canoniques
 *  - Copier les fichiers platform (platform_runner.py, src/)
 *  - Extraire les archives ZIP de dataset
 *  - Valider que tous les fichiers requis sont presents
 *
 * Ce service est la SEULE source de verite pour les paths du build context.
 * KubernetesBuildService et KubernetesJobService consomment cet objet.
 */
@Service
public class CustomDockerWorkspaceService {

    private static final String WORKSPACES_SUBDIR = "workspaces";

    private static final String[] PLATFORM_SRC_FILES = {
            "src/__init__.py",
            "src/custom_models/__init__.py",
            "src/custom_models/generic_pyfunc_wrapper.py",
            "src/custom_models/import_detector.py",
            "src/custom_models/model_artifact_detector.py"
    };

    private static final String[] DATASET_CANDIDATES = {
            "dataset.csv", "data.csv", "train.csv", "input.csv", "uploaded_dataset.csv"
    };

    @Value("${app.platform-runner.path:/app/mini-mlops/src/custom_models/platform_runner.py}")
    private String platformRunnerPath;

    @Value("${app.platform-runtime-root:/app/mini-mlops}")
    private String platformRuntimeRoot;

    private final UploadsStorageProperties uploadsStorageProperties;
    private final DatasetFileResolverService datasetFileResolverService;

    @Autowired
    public CustomDockerWorkspaceService(
            UploadsStorageProperties uploadsStorageProperties,
            DatasetFileResolverService datasetFileResolverService
    ) {
        this.uploadsStorageProperties = uploadsStorageProperties;
        this.datasetFileResolverService = datasetFileResolverService;
    }

    /**
     * Prepare le workspace normalise pour un experiment CUSTOM_DOCKERFILE.
     *
     * Le workspace est cree sous /app/uploads/workspaces/{experimentId}/
     * avec exactement les fichiers requis, sous des noms canoniques.
     */
    public CustomDockerWorkspace prepareWorkspace(MlProject project, MlExperiment experiment) {
        Long experimentId = experiment.getId();
        Long projectId = project.getId();

        Path workspacePath = Path.of(
                uploadsStorageProperties.getMountPath(),
                WORKSPACES_SUBDIR,
                String.valueOf(experimentId)
        ).toAbsolutePath().normalize();

        try {
            Files.createDirectories(workspacePath);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create workspace directory: " + workspacePath + " | " + e.getMessage(), e);
        }

        System.out.println("[CUSTOM_WORKSPACE] experimentId=" + experimentId
                + ", projectId=" + projectId
                + ", workspacePath=" + workspacePath);

        // 1. Dockerfile
        Path dockerfilePath = safeCopy(
                experiment.getDockerfilePath(),
                workspacePath.resolve("Dockerfile"),
                "Dockerfile"
        );

        // 2. train.py (from model file)
        Path trainScriptPath = resolveAndCopyTrainScript(experiment, workspacePath);

        // 3. requirements.txt
        Path requirementsPath = safeCopy(
                experiment.getRequirementsPath(),
                workspacePath.resolve("requirements.txt"),
                "requirements.txt"
        );

        // 4. Dataset
        DatasetResult datasetResult = resolveAndCopyDataset(experiment, project, workspacePath);

        // 5. Platform files
        copyPlatformFiles(workspacePath);

        System.out.println("[CUSTOM_WORKSPACE] files:"
                + "\n  Dockerfile=" + dockerfilePath.getFileName()
                + "\n  train.py=" + trainScriptPath.getFileName()
                + "\n  requirements.txt=" + requirementsPath.getFileName()
                + "\n  dataset=" + (datasetResult.path != null ? datasetResult.path.getFileName() : "none")
                + "\n  datasetIsArchive=" + datasetResult.isArchive
                + "\n  platform_runner.py=present"
                + "\n  src/=present");

        return new CustomDockerWorkspace(
                experimentId,
                projectId,
                workspacePath,
                dockerfilePath,
                trainScriptPath,
                requirementsPath,
                datasetResult.path,
                datasetResult.isArchive,
                datasetResult.extractedDir
        );
    }

    /**
     * Valide que tous les fichiers requis sont presents dans le workspace.
     * Lance RuntimeException avec un code clair si un fichier manque.
     */
    public void validateWorkspace(CustomDockerWorkspace workspace) {
        validateExists(workspace.dockerfilePath(), "BUILD_CONTEXT_FILE_MISSING: Dockerfile");
        validateExists(workspace.trainScriptPath(), "BUILD_CONTEXT_FILE_MISSING: train.py");
        validateExists(workspace.requirementsPath(), "BUILD_CONTEXT_FILE_MISSING: requirements.txt");

        if (workspace.datasetPath() != null) {
            validateExists(workspace.datasetPath(),
                    "BUILD_CONTEXT_FILE_MISSING: " + workspace.datasetPath().getFileName());
        }

        Path platformRunner = workspace.workspacePath().resolve("platform_runner.py");
        validateExists(platformRunner, "BUILD_CONTEXT_FILE_MISSING: platform_runner.py");

        Path srcInit = workspace.workspacePath().resolve("src").resolve("__init__.py");
        validateExists(srcInit, "BUILD_CONTEXT_FILE_MISSING: src/__init__.py");

        System.out.println("[CUSTOM_WORKSPACE] VALIDATION_SUCCESS experimentId=" + workspace.experimentId());
    }

    /**
     * Valide que les sources COPY/ADD du Dockerfile existent dans le workspace.
     */
    public void validateDockerfileSources(CustomDockerWorkspace workspace, String dockerfileContent) {
        if (dockerfileContent == null || dockerfileContent.isBlank()) {
            return;
        }

        java.util.regex.Pattern copyPattern = java.util.regex.Pattern.compile(
                "(?im)^[\\s]*(COPY|ADD)\\s+(.+?)\\s+(.+)$",
                java.util.regex.Pattern.MULTILINE
        );

        java.util.regex.Matcher matcher = copyPattern.matcher(dockerfileContent);

        while (matcher.find()) {
            String srcPart = matcher.group(2);

            for (String token : srcPart.split("\\s+")) {
                String t = token.trim().replaceAll("^[\\\"']|[\\\"']$", "");

                if (t.isBlank() || t.startsWith("--") || t.startsWith("http://") || t.startsWith("https://")) {
                    continue;
                }

                if (t.contains("*") || t.contains("$") || t.contains("${")) {
                    continue;
                }

                String base = t.contains("/") ? t.substring(t.lastIndexOf('/') + 1) : t;
                if (base.isBlank() || base.equals(".") || base.equals("./")) {
                    continue;
                }

                Path resolved = workspace.workspacePath().resolve(t);
                if (!Files.exists(resolved)) {
                    Path baseResolved = workspace.workspacePath().resolve(base);
                    if (!Files.exists(baseResolved)) {
                        System.out.println("[CUSTOM_WORKSPACE] SOURCE_MISSING"
                                + " reference=" + t
                                + ", workspace=" + workspace.workspacePath());
                        throw new RuntimeException(
                                "BUILD_CONTEXT_FILE_MISSING: " + base
                                        + ". Le fichier reference dans le Dockerfile n'existe pas dans le workspace.");
                    }
                }
            }
        }

        System.out.println("[CUSTOM_WORKSPACE] DOCKERFILE_SOURCE_VALIDATION_SUCCESS experimentId=" + workspace.experimentId());
    }

    // ═══════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════

    private Path resolveAndCopyTrainScript(MlExperiment experiment, Path workspacePath) {
        if (experiment.getModelFile() == null) {
            throw new RuntimeException("No model file attached to experiment. train.py is required.");
        }

        String filePath = experiment.getModelFile().getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new RuntimeException("Model file path is empty for experiment " + experiment.getId());
        }

        Path source = Path.of(filePath).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new RuntimeException("Model file not found: " + source);
        }

        return safeCopy(source.toString(), workspacePath.resolve("train.py"), "train.py");
    }

    private DatasetResult resolveAndCopyDataset(MlExperiment experiment, MlProject project, Path workspacePath) {
        UploadedProjectFile datasetFile = datasetFileResolverService.resolveDatasetFile(project, experiment);

        if (datasetFile == null || datasetFile.getFilePath() == null || datasetFile.getFilePath().isBlank()) {
            System.out.println("[CUSTOM_WORKSPACE] No dataset resolved for experiment " + experiment.getId());
            return new DatasetResult(null, false, null);
        }

        Path source = Path.of(datasetFile.getFilePath()).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            System.out.println("[CUSTOM_WORKSPACE] Dataset file not found on disk: " + source);
            return new DatasetResult(null, false, null);
        }

        String originalName = datasetFile.getOriginalFileName() != null
                ? datasetFile.getOriginalFileName().toLowerCase(java.util.Locale.ROOT) : "";
        boolean isArchive = originalName.endsWith(".zip")
                || originalName.endsWith(".tar.gz")
                || originalName.endsWith(".tgz")
                || originalName.endsWith(".7z")
                || originalName.endsWith(".rar");

        if (isArchive) {
            Path zipDest = workspacePath.resolve("dataset.zip");
            safeCopy(source.toString(), zipDest, "dataset.zip");

            Path extractedDir = workspacePath.resolve("dataset-extracted");
            Path mainCsv = extractZipDataset(zipDest, extractedDir);

            if (mainCsv == null) {
                System.out.println("[CUSTOM_WORKSPACE] WARNING: No CSV found in ZIP archive. "
                        + "datasetPath will point to ZIP.");
                return new DatasetResult(zipDest, true, null);
            }

            System.out.println("[CUSTOM_WORKSPACE] Dataset extracted: archive=" + zipDest.getFileName()
                    + ", mainCsv=" + mainCsv.getFileName());
            return new DatasetResult(mainCsv, true, extractedDir);
        } else {
            Path csvDest = workspacePath.resolve("dataset.csv");
            safeCopy(source.toString(), csvDest, "dataset.csv");
            return new DatasetResult(csvDest, false, null);
        }
    }

    private Path extractZipDataset(Path zipPath, Path extractedDir) {
        try {
            Files.createDirectories(extractedDir);

            try (InputStream is = Files.newInputStream(zipPath);
                 ZipInputStream zis = new ZipInputStream(is)) {

                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();
                    String baseName = entryName.contains("/")
                            ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;

                    Path target = extractedDir.resolve(baseName).normalize();
                    if (!target.startsWith(extractedDir)) {
                        continue;
                    }

                    Files.createDirectories(target.getParent());
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        Files.write(target, baos.toByteArray());
                    }
                }
            }

            // Find best CSV
            Path bestCsv = null;
            for (String candidate : DATASET_CANDIDATES) {
                Path p = extractedDir.resolve(candidate);
                if (Files.exists(p)) {
                    bestCsv = p;
                    break;
                }
            }

            if (bestCsv == null) {
                // Fallback: single CSV in directory
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(extractedDir, "*.csv")) {
                    Path single = null;
                    for (Path p : stream) {
                        if (single != null) {
                            single = null;
                            break;
                        }
                        single = p;
                    }
                    bestCsv = single;
                }
            }

            return bestCsv;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract ZIP dataset: " + e.getMessage(), e);
        }
    }

    private void copyPlatformFiles(Path workspacePath) {
        // platform_runner.py
        Path runnerSource = Path.of(platformRunnerPath).toAbsolutePath().normalize();
        if (Files.exists(runnerSource)) {
            safeCopy(runnerSource.toString(), workspacePath.resolve("platform_runner.py"), "platform_runner.py");
        } else {
            System.out.println("[CUSTOM_WORKSPACE] WARNING: platform_runner.py not found at " + runnerSource);
        }

        // src/ directory
        Path srcDir = Path.of(platformRuntimeRoot).resolve("src").toAbsolutePath().normalize();
        for (String relPath : PLATFORM_SRC_FILES) {
            Path source = srcDir.resolve(relPath.replaceFirst("^src/", ""));
            Path target = workspacePath.resolve(relPath);
            try {
                Files.createDirectories(target.getParent());
                if (Files.exists(source)) {
                    safeCopy(source.toString(), target, relPath);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println("[CUSTOM_WORKSPACE] CREATED empty " + relPath + " (source missing)");
                }
            } catch (Exception e) {
                System.out.println("[CUSTOM_WORKSPACE] WARNING: Failed to copy/create " + relPath + ": " + e.getMessage());
            }
        }
    }

    private Path safeCopy(String sourcePath, Path target, String label) {
        try {
            Path source = Path.of(sourcePath).toAbsolutePath().normalize();
            if (!Files.exists(source)) {
                throw new RuntimeException("Source file not found for " + label + ": " + source);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[CUSTOM_WORKSPACE] COPIED " + label
                    + " source=" + source.getFileName()
                    + " size=" + Files.size(target) + " bytes");
            return target;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy " + label + ": " + e.getMessage(), e);
        }
    }

    private void validateExists(Path path, String errorCode) {
        if (path == null || !Files.exists(path)) {
            throw new RuntimeException(errorCode);
        }
    }

    private record DatasetResult(Path path, boolean isArchive, Path extractedDir) {}
}
