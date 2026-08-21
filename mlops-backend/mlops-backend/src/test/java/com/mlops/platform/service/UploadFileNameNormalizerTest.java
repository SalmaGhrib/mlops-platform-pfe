package com.mlops.platform.service;

import com.mlops.platform.entity.UploadedFileType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires du normaliseur centralisé des noms de fichiers uploadés.
 *
 * Garantit que les suffixes de duplication navigateur/Windows
 * ("train (6).py", "data (2).csv", "Dockerfile (7)", "requirements (6).txt")
 * ne cassent JAMAIS l'association rôle -> fichier, et que la plateforme
 * utilise toujours des noms d'exécution canoniques.
 */
class UploadFileNameNormalizerTest {

    @Test
    void stripBrowserSuffix_removesWindowsDuplicationSuffix() {
        assertEquals("train.py", UploadFileNameNormalizer.stripBrowserSuffix("train (6).py"));
        assertEquals("train.py", UploadFileNameNormalizer.stripBrowserSuffix("train(1).py"));
        assertEquals("data.csv", UploadFileNameNormalizer.stripBrowserSuffix("data (2).csv"));
        assertEquals("Dockerfile", UploadFileNameNormalizer.stripBrowserSuffix("Dockerfile (7)"));
        assertEquals("requirements.txt", UploadFileNameNormalizer.stripBrowserSuffix("requirements (6).txt"));
        assertEquals("prepare_data.py", UploadFileNameNormalizer.stripBrowserSuffix("prepare_data (0).py"));
    }

    @Test
    void stripBrowserSuffix_doesNotTouchNonNumericSuffixes() {
        assertEquals("model v2 (final).py", UploadFileNameNormalizer.stripBrowserSuffix("model v2 (final).py"));
        assertEquals("data.parquet", UploadFileNameNormalizer.stripBrowserSuffix("data.parquet"));
    }

    @Test
    void runtimeName_trainingScript_isAlwaysTrainPy() {
        assertEquals("train.py",
                UploadFileNameNormalizer.runtimeName("train (6).py", UploadedFileType.TRAINING_SCRIPT));
        assertEquals("train.py",
                UploadFileNameNormalizer.runtimeName("modelo.py", UploadedFileType.TRAINING_SCRIPT));
    }

    @Test
    void runtimeName_dockerfileAndRequirements_areCanonical() {
        assertEquals("Dockerfile",
                UploadFileNameNormalizer.runtimeName("Dockerfile (7)", UploadedFileType.DOCKERFILE));
        assertEquals("Dockerfile",
                UploadFileNameNormalizer.runtimeName("dockerfile", UploadedFileType.DOCKERFILE));
        assertEquals("requirements.txt",
                UploadFileNameNormalizer.runtimeName("requirements (6).txt", UploadedFileType.REQUIREMENTS));
    }

    @Test
    void runtimeName_dataset_keepsRealNameAndExtension() {
        assertEquals("data.csv",
                UploadFileNameNormalizer.runtimeName("data (2).csv", UploadedFileType.DATASET_FILE));
        assertEquals("data.zip",
                UploadFileNameNormalizer.runtimeName("data (2).zip", UploadedFileType.DATASET_ARCHIVE));
        assertEquals("images.parquet",
                UploadFileNameNormalizer.runtimeName("images (1).parquet", UploadedFileType.DATASET_FILE));
    }

    @Test
    void runtimeName_preprocessingScript_isCanonical() {
        assertEquals("preprocess.py",
                UploadFileNameNormalizer.runtimeName("prepare_data (0).py", UploadedFileType.DATASET_PREPARATION_SCRIPT));
        assertEquals("preprocess.py",
                UploadFileNameNormalizer.runtimeName("prepare_data (0).py", UploadedFileType.PREPROCESSING_SCRIPT));
    }

    @Test
    void validate_rejectsPythonDataset() {
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileNameNormalizer.validate("data (2).py", UploadedFileType.DATASET_FILE));
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileNameNormalizer.validate("data.py3", UploadedFileType.DATASET_ARCHIVE));
    }

    @Test
    void validate_rejectsTrainingScriptWithoutPython() {
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileNameNormalizer.validate("train (6).txt", UploadedFileType.TRAINING_SCRIPT));
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileNameNormalizer.validate("script", UploadedFileType.TRAINING_SCRIPT));
    }

    @Test
    void validate_rejectsUnknownDatasetExtension() {
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileNameNormalizer.validate("notes.docx", UploadedFileType.DATASET_FILE));
    }

    @Test
    void validate_acceptsValidFiles() {
        UploadFileNameNormalizer.validate("train (6).py", UploadedFileType.TRAINING_SCRIPT);
        UploadFileNameNormalizer.validate("data (2).csv", UploadedFileType.DATASET_FILE);
        UploadFileNameNormalizer.validate("data (2).zip", UploadedFileType.DATASET_ARCHIVE);
        UploadFileNameNormalizer.validate("Dockerfile (7)", UploadedFileType.DOCKERFILE);
        UploadFileNameNormalizer.validate("requirements (6).txt", UploadedFileType.REQUIREMENTS);
        UploadFileNameNormalizer.validate("prepare_data (0).py", UploadedFileType.PREPROCESSING_SCRIPT);
    }

    @Test
    void isDatasetFileName_detectsAllDatasetExtensions() {
        assertTrue(UploadFileNameNormalizer.isDatasetFileName("data.csv"));
        assertTrue(UploadFileNameNormalizer.isDatasetFileName("images.zip"));
        assertTrue(UploadFileNameNormalizer.isDatasetFileName("dataset.parquet"));
        assertTrue(UploadFileNameNormalizer.isDatasetFileName("text.txt"));
        assertTrue(UploadFileNameNormalizer.isDatasetFileName("photo.jpg"));
    }
}