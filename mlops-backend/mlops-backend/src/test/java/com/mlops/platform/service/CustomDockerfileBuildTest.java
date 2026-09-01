package com.mlops.platform.service;

import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du service UploadedFileResolverService.
 *
 * Vérifie la résolution centralisée des fichiers pour le contexte de build
 * Docker personnalisé, incluant :
 *  1. Résolution par nom original exact
 *  2. Résolution avec suffixe navigateur (train (2).py -> train.py)
 *  3. Résolution de fichiers auxiliaires (.py non-TRAINING_SCRIPT)
 *  4. Résolution de dataset CSV référencé dans le Dockerfile
 *  5. Extraction de CSV depuis une archive ZIP
 *  6. Fichier manquant -> null (pas d'exception)
 *  7. Résolution multi-références dans un Dockerfile
 *  8. Résolution avec chemins sous-répertoires
 */
@ExtendWith(MockitoExtension.class)
class CustomDockerfileBuildTest {

    @Mock
    private UploadedProjectFileRepository uploadedProjectFileRepository;

    private UploadedFileResolverService resolver;

    private static Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new UploadedFileResolverService(uploadedProjectFileRepository);
        tempDir = Files.createTempDirectory("custom-build-test");
    }

    private UploadedProjectFile buildFile(
            Long id, String originalName, String storedName,
            String filePath, UploadedFileType fileType, UploadedFileType role
    ) {
        UploadedProjectFile f = new UploadedProjectFile();
        f.setId(id);
        f.setOriginalFileName(originalName);
        f.setStoredFileName(storedName);
        f.setFilePath(filePath);
        f.setFileType(fileType);
        f.setRole(role);
        return f;
    }

    // ─── TEST 1: Résolution par nom original exact ──────────────────────
    @Test
    void testResolveExactOriginalName() throws Exception {
        Path trainPath = tempDir.resolve("a1b2c3-train.py");
        Files.writeString(trainPath, "import mlflow\nprint('hello')");

        UploadedProjectFile trainFile = buildFile(
                1L, "train.py", "a1b2c3-train.py",
                trainPath.toString(),
                UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(trainFile));

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "train.py");

        assertNotNull(result);
        assertEquals("train.py", result.logicalName());
        assertEquals("import mlflow\nprint('hello')", result.content());
        assertEquals("train.py", result.originalName());
    }

    // ─── TEST 2: Résolution avec suffixe navigateur ─────────────────────
    @Test
    void testResolveBrowserSuffix() throws Exception {
        Path trainPath = tempDir.resolve("b2c3d4-train.py");
        Files.writeString(trainPath, "print('browser suffix test')");

        UploadedProjectFile trainFile = buildFile(
                2L, "train (2).py", "b2c3d4-train.py",
                trainPath.toString(),
                UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(trainFile));

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "train.py");

        assertNotNull(result);
        assertEquals("train.py", result.logicalName());
        assertEquals("print('browser suffix test')", result.content());
    }

    // ─── TEST 3: Résolution de fichier auxiliaire (.py non-prep) ────────
    @Test
    void testResolveAuxiliaryPythonFile() throws Exception {
        Path helperPath = tempDir.resolve("c3d4e5-helper.py");
        Files.writeString(helperPath, "def helper(): pass");

        UploadedProjectFile helperFile = buildFile(
                3L, "helper.py", "c3d4e5-helper.py",
                helperPath.toString(),
                UploadedFileType.AUXILIARY, UploadedFileType.AUXILIARY
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(helperFile));

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "helper.py");

        assertNotNull(result);
        assertEquals("helper.py", result.logicalName());
        assertEquals("def helper(): pass", result.content());
    }

    // ─── TEST 4: Résolution de dataset CSV référencé dans le Dockerfile ──
    @Test
    void testResolveDatasetCsv() throws Exception {
        Path csvPath = tempDir.resolve("d4e5f6-data.csv");
        Files.writeString(csvPath, "col1,col2\n1,2\n3,4");

        UploadedProjectFile csvFile = buildFile(
                4L, "data.csv", "d4e5f6-data.csv",
                csvPath.toString(),
                UploadedFileType.DATASET_FILE, UploadedFileType.DATASET_FILE
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(csvFile));

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "data.csv");

        assertNotNull(result);
        assertEquals("data.csv", result.logicalName());
        assertEquals("col1,col2\n1,2\n3,4", result.content());
    }

    // ─── TEST 5: Extraction de CSV depuis une archive ZIP ────────────────
    @Test
    void testResolveCsvFromZipArchive() throws Exception {
        Path zipPath = tempDir.resolve("e5f6a7-dataset.zip");
        byte[] zipBytes = createZipWithCsv("train.csv", "id,label\n1,0\n2,1");
        Files.write(zipPath, zipBytes);

        UploadedProjectFile zipFile = buildFile(
                5L, "dataset.zip", "e5f6a7-dataset.zip",
                zipPath.toString(),
                UploadedFileType.DATASET_ARCHIVE, UploadedFileType.DATASET_ARCHIVE
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(zipFile));

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "train.csv");

        assertNotNull(result);
        assertEquals("train.csv", result.logicalName());
        assertTrue(result.content().contains("id,label"));
        assertEquals("DATASET_ARCHIVE", result.role());
    }

    // ─── TEST 6: Fichier manquant -> null ───────────────────────────────
    @Test
    void testResolveMissingFileReturnsNull() {
        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of());

        UploadedFileResolverService.ResolvedBuildFile result = resolver.resolve(100L, "nonexistent.py");

        assertNull(result);
    }

    // ─── TEST 7: Résolution multi-références dans un Dockerfile ─────────
    @Test
    void testResolveMultipleReferencesFromDockerfile() throws Exception {
        Path helperPath = tempDir.resolve("f6a7b8-helper.py");
        Files.writeString(helperPath, "def helper(): pass");
        Path configPath = tempDir.resolve("a7b8c9-config.json");
        Files.writeString(configPath, "{\"key\": \"value\"}");

        UploadedProjectFile helperFile = buildFile(
                6L, "helper.py", "f6a7b8-helper.py",
                helperPath.toString(),
                UploadedFileType.AUXILIARY, UploadedFileType.AUXILIARY
        );
        UploadedProjectFile configFile = buildFile(
                7L, "config.json", "a7b8c9-config.json",
                configPath.toString(),
                UploadedFileType.OTHER, UploadedFileType.OTHER
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(helperFile, configFile));

        String dockerfile = """
                FROM python:3.11-slim
                COPY helper.py /app/helper.py
                COPY config.json /app/config.json
                RUN echo "done"
                """;

        Map<String, String> resolved = resolver.resolveDockerfileReferences(100L, dockerfile);

        assertEquals(2, resolved.size());
        assertTrue(resolved.containsKey("helper.py"));
        assertTrue(resolved.containsKey("config.json"));
        assertEquals("def helper(): pass", resolved.get("helper.py"));
        assertEquals("{\"key\": \"value\"}", resolved.get("config.json"));
    }

    // ─── TEST 8: Résolution avec chemin sous-répertoire ─────────────────
    @Test
    void testResolveSubdirectoryPath() throws Exception {
        Path helperPath = tempDir.resolve("b8c9d0-utils.py");
        Files.writeString(helperPath, "def util(): pass");

        UploadedProjectFile helperFile = buildFile(
                8L, "utils.py", "b8c9d0-utils.py",
                helperPath.toString(),
                UploadedFileType.AUXILIARY, UploadedFileType.AUXILIARY
        );

        when(uploadedProjectFileRepository.findByProjectId(100L))
                .thenReturn(List.of(helperFile));

        String dockerfile = """
                FROM python:3.11-slim
                COPY src/utils.py /app/src/utils.py
                """;

        Map<String, String> resolved = resolver.resolveDockerfileReferences(100L, dockerfile);

        assertEquals(1, resolved.size());
        assertTrue(resolved.containsKey("utils.py"));
        assertEquals("def util(): pass", resolved.get("utils.py"));
    }

    // ─── Helper: crée un ZIP contenant un fichier CSV ───────────────────
    private byte[] createZipWithCsv(String csvName, String csvContent) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(csvName);
            zos.putNextEntry(entry);
            zos.write(csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests for validateBuildContextAgainstDockerfile
    // ═══════════════════════════════════════════════════════════════════════

    private Map<String, String> minimalBuildContext() {
        return Map.of(
                "Dockerfile", "FROM python:3.11-slim\nCOPY train.py /app/train.py",
                "platform_runner.py", "import sys",
                "train.py", "print('hello')"
        );
    }

    // ─── TEST 9: Directory reference (COPY src /app/src) passes ─────────
    @Test
    void testValidationDirectoryReferencePasses() {
        Map<String, String> ctx = new java.util.HashMap<>(minimalBuildContext());
        String dockerfile = "FROM python:3.11-slim\nCOPY src /app/src\nCOPY train.py /app/train.py";

        assertDoesNotThrow(() ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
    }

    // ─── TEST 10: Missing .py reference throws ──────────────────────────
    @Test
    void testValidationMissingPythonFileThrows() {
        Map<String, String> ctx = minimalBuildContext();
        String dockerfile = "FROM python:3.11-slim\nCOPY helper.py /app/helper.py";

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
        assertTrue(ex.getMessage().contains("Script Python"));
        assertTrue(ex.getMessage().contains("helper.py"));
    }

    // ─── TEST 11: Missing requirements.txt throws ───────────────────────
    @Test
    void testValidationMissingRequirementsTxtThrows() {
        Map<String, String> ctx = minimalBuildContext();
        String dockerfile = "FROM python:3.11-slim\nCOPY requirements.txt /app/requirements.txt";

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
        assertTrue(ex.getMessage().contains("requirements.txt"));
    }

    // ─── TEST 12: Missing dataset.csv throws ────────────────────────────
    @Test
    void testValidationMissingDatasetCsvThrows() {
        Map<String, String> ctx = minimalBuildContext();
        String dockerfile = "FROM python:3.11-slim\nCOPY dataset.csv /app/dataset.csv";

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
        assertTrue(ex.getMessage().contains("dataset.csv"));
    }

    // ─── TEST 13: Existing .py file passes ──────────────────────────────
    @Test
    void testValidationExistingPythonFilePasses() {
        Map<String, String> ctx = minimalBuildContext();
        String dockerfile = "FROM python:3.11-slim\nCOPY train.py /app/train.py";

        assertDoesNotThrow(() ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
    }

    // ─── TEST 14: Subdirectory .py reference passes (src/sub.py) ────────
    @Test
    void testValidationSubdirectoryPythonFilePasses() {
        Map<String, String> ctx = new java.util.HashMap<>(minimalBuildContext());
        ctx.put("utils.py", "def util(): pass");
        String dockerfile = "FROM python:3.11-slim\nCOPY src/utils.py /app/src/utils.py";

        assertDoesNotThrow(() ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
    }

    // ─── TEST 15: User file (config.json) logs but does not throw ───────
    @Test
    void testValidationUserFileLogsButDoesNotThrow() {
        Map<String, String> ctx = minimalBuildContext();
        String dockerfile = "FROM python:3.11-slim\nCOPY config.json /app/config.json";

        assertDoesNotThrow(() ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(ctx, dockerfile));
    }

    // ─── TEST 16: Empty Dockerfile throws ───────────────────────────────
    @Test
    void testValidationEmptyDockerfileThrows() {
        assertThrows(RuntimeException.class, () ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(minimalBuildContext(), ""));
        assertThrows(RuntimeException.class, () ->
                KubernetesBuildService.validateBuildContextAgainstDockerfile(minimalBuildContext(), null));
    }
}
