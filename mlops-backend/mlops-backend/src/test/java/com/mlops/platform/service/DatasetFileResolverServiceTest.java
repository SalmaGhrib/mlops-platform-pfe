package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/*
 * Tests unitaires du DatasetFileResolverService.
 *
 * Garantit que le dataset transmis (DATASET_PATH / DATA_PATH) pointe toujours
 * vers un vrai fichier de données et JAMAIS vers un script Python, quelle que
 * soit la mauvaise association héritée en base.
 */
@ExtendWith(MockitoExtension.class)
class DatasetFileResolverServiceTest {

    @Mock
    private UploadedProjectFileRepository uploadedProjectFileRepository;

    private DatasetFileResolverService resolver;

    @TempDir
    Path tempDir;

    private MlProject project;
    private MlExperiment experiment;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new DatasetFileResolverService(uploadedProjectFileRepository);
        project = new MlProject();
        ReflectionTestUtils.setField(project, "id", 1L);
        experiment = new MlExperiment();
        experiment.setProject(project);
    }

    @Test
    void deriveRoleFromFileName_classifiesPythonAsPreparationScript() {
        assertEquals(UploadedFileType.DATASET_PREPARATION_SCRIPT,
                resolver.deriveRoleFromFileName("prepare.py"));
        assertEquals(UploadedFileType.DATASET_PREPARATION_SCRIPT,
                resolver.deriveRoleFromFileName("dataset.PY3"));
    }

    @Test
    void deriveRoleFromFileName_classifiesArchivesAndTabular() {
        assertEquals(UploadedFileType.DATASET_ARCHIVE,
                resolver.deriveRoleFromFileName("images.zip"));
        assertEquals(UploadedFileType.DATASET_ARCHIVE,
                resolver.deriveRoleFromFileName("data.tar.gz"));
        assertEquals(UploadedFileType.DATASET_FILE,
                resolver.deriveRoleFromFileName("dataset.csv"));
        assertEquals(UploadedFileType.DATASET_FILE,
                resolver.deriveRoleFromFileName("data.parquet"));
    }

    @Test
    void validAttachedCsv_isUsedWithoutQueryingProjectFiles() throws IOException {
        UploadedProjectFile csv = file("dataset.csv", ".csv", UploadedFileType.DATASET_FILE);
        experiment.setDatasetFile(csv);

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertSame(csv, resolved);
    }

    @Test
    void preparationScript_attachedAsDataset_isNeverUsedAndFallsBackToProjectCsv() throws IOException {
        UploadedProjectFile prep = file("prepare.py", "py", UploadedFileType.DATASET_PREPARATION_SCRIPT);
        experiment.setDatasetFile(prep);

        UploadedProjectFile realCsv = file("dataset.csv", "csv", UploadedFileType.DATASET_FILE);
        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(prep, realCsv));

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertNotSame(prep, resolved);
        assertSame(realCsv, resolved);
        assertEquals("csv", resolved.getFileExtension());
    }

    @Test
    void pythonAttachedWithDatasetFileRole_isRejectedAndFallsBackToProjectCsv() throws IOException {
        UploadedProjectFile badPy = file("preprocessing.py", "py", UploadedFileType.DATASET_FILE);
        experiment.setDatasetFile(badPy);

        UploadedProjectFile realCsv = file("dataset.csv", "csv", UploadedFileType.DATASET_FILE);
        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(badPy, realCsv));

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertNotSame(badPy, resolved);
        assertSame(realCsv, resolved);
        assertEquals("csv", resolved.getFileExtension());
    }

    @Test
    void trainingScriptAttachedAsDataset_isNeverUsed_andFallsBack() throws IOException {
        UploadedProjectFile trainPy = file("train.py", "py", UploadedFileType.TRAINING_SCRIPT);
        experiment.setDatasetFile(trainPy);

        UploadedProjectFile archive = file("images.zip", "zip", UploadedFileType.DATASET_ARCHIVE);
        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(trainPy, archive));

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertSame(archive, resolved);
    }

    @Test
    void projectFallback_prefersArchiveOverTabular() throws IOException {
        UploadedProjectFile csv = file("dataset.csv", "csv", UploadedFileType.DATASET_FILE);
        UploadedProjectFile zip = file("images.zip", "zip", UploadedFileType.DATASET_ARCHIVE);
        experiment.setDatasetFile(null);

        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(csv, zip));

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertSame(zip, resolved);
    }

    @Test
    void onlyScriptsInProject_returnsNullDataset() throws IOException {
        UploadedProjectFile trainPy = file("train.py", "py", UploadedFileType.TRAINING_SCRIPT);
        UploadedProjectFile prepPy = file("prep.py", "py", UploadedFileType.DATASET_PREPARATION_SCRIPT);
        experiment.setDatasetFile(null);

        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(trainPy, prepPy));

        assertNull(resolver.resolveDatasetFile(project, experiment));
    }

    @Test
    void findPreprocessingScript_returnsPreparationOrAuxiliaryPython() throws IOException {
        UploadedProjectFile prep = file("prep.py", "py", UploadedFileType.DATASET_PREPARATION_SCRIPT);
        UploadedProjectFile aux = file("extra.py", "py", UploadedFileType.AUXILIARY);
        UploadedProjectFile csv = file("dataset.csv", "csv", UploadedFileType.DATASET_FILE);

        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(csv, prep, aux));

        UploadedProjectFile found = resolver.findPreprocessingScript(project);
        assertNotNull(found);
        assertTrue(found.getOriginalFileName().equals("prep.py") || found.getOriginalFileName().equals("extra.py"));
    }

    @Test
    void findPreprocessingScript_returnsNullWhenOnlyDatasetFiles() throws IOException {
        UploadedProjectFile csv = file("dataset.csv", "csv", UploadedFileType.DATASET_FILE);

        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(csv));

        assertNull(resolver.findPreprocessingScript(project));
    }

    @Test
    void attachedMissingOnDisk_isRejectedAndFallsBack() {
        UploadedProjectFile ghost = new UploadedProjectFile();
        ghost.setOriginalFileName("ghost.csv");
        ghost.setFileExtension("csv");
        ghost.setFilePath(Path.of(tempDir.toString(), "does-not-exist.csv").toString());
        ghost.setRole(UploadedFileType.DATASET_FILE);
        ghost.setFileType(UploadedFileType.DATASET_FILE);
        experiment.setDatasetFile(ghost);

        UploadedProjectFile real = new UploadedProjectFile();
        real.setOriginalFileName("real.csv");
        real.setFileExtension("csv");
        real.setFilePath(Path.of(tempDir.toString(), "real.csv").toString());
        real.setRole(UploadedFileType.DATASET_FILE);
        real.setFileType(UploadedFileType.DATASET_FILE);
        try {
            Files.write(Path.of(tempDir.toString(), "real.csv"), "a,b\n1,2\n".getBytes());
        } catch (IOException ignored) {
        }

        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of(ghost, real));

        UploadedProjectFile resolved = resolver.resolveDatasetFile(project, experiment);

        assertNotSame(ghost, resolved);
        assertSame(real, resolved);
    }

    @Test
    void noDatasetAttachedAndNoProjectDataset_returnsNull() {
        experiment.setDatasetFile(null);
        when(uploadedProjectFileRepository.findByProjectId(1L)).thenReturn(List.of());
        assertNull(resolver.resolveDatasetFile(project, experiment));
    }

    private UploadedProjectFile file(String name, String ext, UploadedFileType role) throws IOException {
        Path target = Path.of(tempDir.toString(), name);
        Files.write(target, "content\n".getBytes());

        UploadedProjectFile uploadedFile = new UploadedProjectFile();
        uploadedFile.setOriginalFileName(name);
        uploadedFile.setStoredFileName(name);
        uploadedFile.setFilePath(target.toString());
        uploadedFile.setRole(role);
        uploadedFile.setFileType(role);
        uploadedFile.setFileExtension(ext);
        return uploadedFile;
    }
}