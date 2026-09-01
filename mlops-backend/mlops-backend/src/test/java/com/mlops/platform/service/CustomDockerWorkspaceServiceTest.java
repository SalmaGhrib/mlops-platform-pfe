package com.mlops.platform.service;

import com.mlops.platform.config.UploadsStorageProperties;
import com.mlops.platform.entity.*;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Tests du CustomDockerWorkspaceService.
 *
 * CAS 1: stored train file = uuid.py → workspace/train.py existe
 * CAS 2: stored requirements = uuid.txt → workspace/requirements.txt existe
 * CAS 3: Dockerfile COPY requirements.txt → validation SUCCESS
 * CAS 4: Dockerfile COPY train.py → validation SUCCESS
 * CAS 5: CSV direct → datasetPath=workspace/dataset.csv
 * CAS 6: ZIP avec HTRU_2.csv → extraction → datasetPath pointe vers HTRU_2.csv
 * CAS 7: fichier réellement absent → erreur claire avant Kaniko
 * CAS 8: deux expériences simultanées → workspaces différents → aucune collision
 */
@ExtendWith(MockitoExtension.class)
class CustomDockerWorkspaceServiceTest {

    @Mock
    private UploadedProjectFileRepository uploadedProjectFileRepository;

    private DatasetFileResolverService datasetFileResolverService;
    private CustomDockerWorkspaceService workspaceService;

    @TempDir
    Path tempDir;

    private UploadsStorageProperties uploadsStorageProperties;

    @BeforeEach
    void setUp() {
        uploadsStorageProperties = new UploadsStorageProperties();
        uploadsStorageProperties.setMountPath(tempDir.toString());
        uploadsStorageProperties.setPvcName("test-pvc");

        datasetFileResolverService = new DatasetFileResolverService(uploadedProjectFileRepository);
        workspaceService = new CustomDockerWorkspaceService(uploadsStorageProperties, datasetFileResolverService);

        try {
            var runnerField = CustomDockerWorkspaceService.class.getDeclaredField("platformRunnerPath");
            runnerField.setAccessible(true);
            runnerField.set(workspaceService, tempDir.resolve("platform_runner.py").toString());

            var rootField = CustomDockerWorkspaceService.class.getDeclaredField("platformRuntimeRoot");
            rootField.setAccessible(true);
            rootField.set(workspaceService, tempDir.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MlProject buildProject(Long id) {
        MlProject project = new MlProject();
        project.setId(id);
        project.setName("test-project-" + id);
        return project;
    }

    private UploadedProjectFile buildFile(Long id, String originalName, String storedName,
            String filePath, UploadedFileType fileType, UploadedFileType role) {
        UploadedProjectFile f = new UploadedProjectFile();
        f.setId(id);
        f.setOriginalFileName(originalName);
        f.setStoredFileName(storedName);
        f.setFilePath(filePath);
        f.setFileType(fileType);
        f.setRole(role);
        return f;
    }

    private MlExperiment buildExperiment(Long id, MlProject project,
            UploadedProjectFile modelFile, UploadedProjectFile datasetFile,
            String dockerfilePath, String requirementsPath) {
        MlExperiment exp = new MlExperiment();
        exp.setId(id);
        exp.setProject(project);
        exp.setModelFile(modelFile);
        exp.setDatasetFile(datasetFile);
        exp.setDockerfilePath(dockerfilePath);
        exp.setRequirementsPath(requirementsPath);
        exp.setRuntimeMode("CUSTOM_DOCKERFILE");
        return exp;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 1: stored train file = uuid.py → workspace/train.py
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas1_trainFileUuidToCanonical() throws Exception {
        Path trainPath = tempDir.resolve("a1b2c3-train.py");
        Files.writeString(trainPath, "import mlflow\nprint('hello')");

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        Files.createDirectories(dockerfilePath.getParent());
        Files.writeString(dockerfilePath, "FROM python:3.11-slim\nCOPY train.py /app/train.py");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.createDirectories(reqPath.getParent());
        Files.writeString(reqPath, "mlflow==2.17.2");

        UploadedProjectFile modelFile = buildFile(1L, "train.py", "a1b2c3-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);

        when(uploadedProjectFileRepository.findByProjectId(100L)).thenReturn(List.of());

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(1L, project, modelFile, null,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertTrue(Files.exists(ws.trainScriptPath()), "workspace/train.py must exist");
        assertEquals("train.py", ws.trainScriptPath().getFileName().toString());
        assertEquals("import mlflow\nprint('hello')", Files.readString(ws.trainScriptPath()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 2: stored requirements = uuid.txt → workspace/requirements.txt
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas2_requirementsFileUuidToCanonical() throws Exception {
        Path trainPath = tempDir.resolve("b2c3d4-train.py");
        Files.writeString(trainPath, "print('ok')");

        Path reqPath = tempDir.resolve("docker/91ab2-requirements.txt");
        Files.createDirectories(reqPath.getParent());
        Files.writeString(reqPath, "pandas\nnumpy");

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        Files.writeString(dockerfilePath, "FROM python:3.11-slim\nCOPY requirements.txt /app/requirements.txt");

        UploadedProjectFile modelFile = buildFile(2L, "train.py", "b2c3d4-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);

        when(uploadedProjectFileRepository.findByProjectId(100L)).thenReturn(List.of());

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(2L, project, modelFile, null,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertTrue(Files.exists(ws.requirementsPath()), "workspace/requirements.txt must exist");
        assertEquals("requirements.txt", ws.requirementsPath().getFileName().toString());
        assertTrue(Files.readString(ws.requirementsPath()).contains("pandas"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 3: Dockerfile COPY requirements.txt → validation SUCCESS
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas3_dockerfileCopyRequirementsValidation() throws Exception {
        Path trainPath = tempDir.resolve("c3d4e5-train.py");
        Files.writeString(trainPath, "print('ok')");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.createDirectories(reqPath.getParent());
        Files.writeString(reqPath, "mlflow");

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        String dockerfileContent = "FROM python:3.11-slim\nCOPY requirements.txt /app/requirements.txt\nRUN pip install -r /app/requirements.txt";
        Files.writeString(dockerfilePath, dockerfileContent);

        UploadedProjectFile modelFile = buildFile(3L, "train.py", "c3d4e5-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);

        when(uploadedProjectFileRepository.findByProjectId(100L)).thenReturn(List.of());

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(3L, project, modelFile, null,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertDoesNotThrow(() -> workspaceService.validateDockerfileSources(ws, dockerfileContent));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 4: Dockerfile COPY train.py → validation SUCCESS
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas4_dockerfileCopyTrainPyValidation() throws Exception {
        Path trainPath = tempDir.resolve("d4e5f6-train.py");
        Files.writeString(trainPath, "print('ok')");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.createDirectories(reqPath.getParent());
        Files.writeString(reqPath, "mlflow");

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        String dockerfileContent = "FROM python:3.11-slim\nCOPY train.py /app/train.py";
        Files.writeString(dockerfilePath, dockerfileContent);

        UploadedProjectFile modelFile = buildFile(4L, "train.py", "d4e5f6-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);

        when(uploadedProjectFileRepository.findByProjectId(100L)).thenReturn(List.of());

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(4L, project, modelFile, null,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertDoesNotThrow(() -> workspaceService.validateDockerfileSources(ws, dockerfileContent));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 5: CSV direct → datasetPath=workspace/dataset.csv
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas5_csvDirectDataset() throws Exception {
        Path trainPath = tempDir.resolve("e5f6a7-train.py");
        Files.writeString(trainPath, "import pandas as pd");

        Path csvPath = tempDir.resolve("f6a7b8-data.csv");
        Files.writeString(csvPath, "col1,col2\n1,2\n3,4");

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        Files.createDirectories(dockerfilePath.getParent());
        Files.writeString(dockerfilePath, "FROM python:3.11-slim\nCOPY train.py /app/train.py");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.writeString(reqPath, "pandas");

        UploadedProjectFile modelFile = buildFile(5L, "train.py", "e5f6a7-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);
        UploadedProjectFile datasetFile = buildFile(6L, "data.csv", "f6a7b8-data.csv",
                csvPath.toString(), UploadedFileType.DATASET_FILE, UploadedFileType.DATASET_FILE);

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(5L, project, modelFile, datasetFile,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertNotNull(ws.datasetPath());
        assertEquals("dataset.csv", ws.datasetPath().getFileName().toString());
        assertTrue(Files.exists(ws.datasetPath()));
        assertFalse(ws.datasetIsArchive());
        assertTrue(Files.readString(ws.datasetPath()).contains("col1,col2"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 6: ZIP avec HTRU_2.csv → extraction → datasetPath pointe vers HTRU_2.csv
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas6_zipDatasetExtraction() throws Exception {
        Path trainPath = tempDir.resolve("g7h8i9-train.py");
        Files.writeString(trainPath, "import pandas as pd");

        Path zipPath = tempDir.resolve("h8i9j0-dataset.zip");
        byte[] zipBytes = createZipWithCsv("HTRU_2.csv", "col1,col2\n1,2\n3,4");
        Files.write(zipPath, zipBytes);

        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        Files.createDirectories(dockerfilePath.getParent());
        Files.writeString(dockerfilePath, "FROM python:3.11-slim\nCOPY train.py /app/train.py");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.writeString(reqPath, "pandas");

        UploadedProjectFile modelFile = buildFile(7L, "train.py", "g7h8i9-train.py",
                trainPath.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);
        UploadedProjectFile datasetFile = buildFile(8L, "dataset.zip", "h8i9j0-dataset.zip",
                zipPath.toString(), UploadedFileType.DATASET_ARCHIVE, UploadedFileType.DATASET_ARCHIVE);

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(6L, project, modelFile, datasetFile,
                dockerfilePath.toString(), reqPath.toString());

        CustomDockerWorkspace ws = workspaceService.prepareWorkspace(project, experiment);

        assertTrue(ws.datasetIsArchive());
        assertNotNull(ws.datasetPath());
        assertTrue(Files.exists(ws.datasetPath()));
        assertEquals("HTRU_2.csv", ws.datasetPath().getFileName().toString());
        assertTrue(Files.readString(ws.datasetPath()).contains("col1,col2"));
        assertTrue(Files.exists(ws.datasetExtractedDir()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 7: fichier réellement absent → erreur claire avant Kaniko
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas7_missingFileThrowsClearError() throws Exception {
        Path dockerfilePath = tempDir.resolve("docker/Dockerfile");
        Files.createDirectories(dockerfilePath.getParent());
        Files.writeString(dockerfilePath, "FROM python:3.11-slim\nCOPY train.py /app/train.py");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.writeString(reqPath, "pandas");

        MlProject project = buildProject(100L);
        MlExperiment experiment = buildExperiment(7L, project, null, null,
                dockerfilePath.toString(), reqPath.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> workspaceService.prepareWorkspace(project, experiment));
        assertTrue(ex.getMessage().contains("model file"),
                "Error should mention model file: " + ex.getMessage());
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAS 8: deux expériences simultanées → workspaces différents → aucune collision
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testCas8_concurrentExperimentsNoCollision() throws Exception {
        Path trainPath1 = tempDir.resolve("x1-train.py");
        Files.writeString(trainPath1, "print('exp1')");
        Path trainPath2 = tempDir.resolve("x2-train.py");
        Files.writeString(trainPath2, "print('exp2')");

        Path dockerPath = tempDir.resolve("docker/Dockerfile");
        Files.createDirectories(dockerPath.getParent());
        Files.writeString(dockerPath, "FROM python:3.11-slim\nCOPY train.py /app/train.py");

        Path reqPath = tempDir.resolve("docker/requirements.txt");
        Files.writeString(reqPath, "pandas");

        UploadedProjectFile model1 = buildFile(10L, "train.py", "x1-train.py",
                trainPath1.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);
        UploadedProjectFile model2 = buildFile(11L, "train.py", "x2-train.py",
                trainPath2.toString(), UploadedFileType.TRAINING_SCRIPT, UploadedFileType.TRAINING_SCRIPT);

        when(uploadedProjectFileRepository.findByProjectId(100L)).thenReturn(List.of());

        MlProject project = buildProject(100L);
        MlExperiment exp1 = buildExperiment(100L, project, model1, null, dockerPath.toString(), reqPath.toString());
        MlExperiment exp2 = buildExperiment(200L, project, model2, null, dockerPath.toString(), reqPath.toString());

        CustomDockerWorkspace ws1 = workspaceService.prepareWorkspace(project, exp1);
        CustomDockerWorkspace ws2 = workspaceService.prepareWorkspace(project, exp2);

        assertNotEquals(ws1.workspacePath(), ws2.workspacePath(),
                "Two experiments must have different workspace paths");
        assertTrue(Files.exists(ws1.trainScriptPath()));
        assertTrue(Files.exists(ws2.trainScriptPath()));
        assertEquals("print('exp1')", Files.readString(ws1.trainScriptPath()));
        assertEquals("print('exp2')", Files.readString(ws2.trainScriptPath()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helper: crée un ZIP contenant un fichier CSV
    // ═══════════════════════════════════════════════════════════════════
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
}
