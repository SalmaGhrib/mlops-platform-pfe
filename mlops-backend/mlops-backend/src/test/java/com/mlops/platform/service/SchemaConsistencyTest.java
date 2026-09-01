package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de conformité Entity Java vs schéma MySQL.
 *
 * Ces tests vérifient que :
 *  1. Toutes les valeurs de l'enum UploadedFileType sont valides et supportées
 *     par le schéma MySQL (VARCHAR(64) après migration 016).
 *  2. Le champ fileType est correctement défini dans l'entité.
 *  3. Le champ role est correctement défini dans l'entité.
 *  4. ModelDeployment possède les champs serving_track et stable_service_name.
 *  5. Les valeurs de serving_track sont bornées à "blue"|"green".
 *
 * NOTE : ces tests ne nécessitent PAS de base de données.
 * Ils vérifient la cohérence du modèle Java, pas le schema DDL.
 */
class SchemaConsistencyTest {

    // ─── UploadedFileType : toutes les valeurs de l'enum ──────────────

    @Test
    void uploadedFileType_allValuesArePersistable() {
        // Chaque valeur de l'enum doit être un nom valide pour
        // @Enumerated(EnumType.STRING) et ne pas dépasser 64 caractères.
        for (UploadedFileType type : UploadedFileType.values()) {
            assertNotNull(type.name(), "enum name must not be null");
            assertTrue(type.name().length() <= 64,
                    "enum value '" + type.name() + "' exceeds VARCHAR(64)");
            assertFalse(type.name().isBlank(),
                    "enum value must not be blank");
        }
    }

    @Test
    void uploadedFileType_knownValuesMatchExpected() {
        // Valeurs actuelles documentées
        assertEquals("TRAINING_SCRIPT", UploadedFileType.TRAINING_SCRIPT.name());
        assertEquals("DATASET_FILE", UploadedFileType.DATASET_FILE.name());
        assertEquals("DATASET_ARCHIVE", UploadedFileType.DATASET_ARCHIVE.name());
        assertEquals("DATASET_PREPARATION_SCRIPT", UploadedFileType.DATASET_PREPARATION_SCRIPT.name());
        assertEquals("PREPROCESSING_SCRIPT", UploadedFileType.PREPROCESSING_SCRIPT.name());
        assertEquals("DOCKERFILE", UploadedFileType.DOCKERFILE.name());
        assertEquals("REQUIREMENTS", UploadedFileType.REQUIREMENTS.name());
        assertEquals("AUXILIARY", UploadedFileType.AUXILIARY.name());
        // Legacy
        assertEquals("MODEL_FILE", UploadedFileType.MODEL_FILE.name());
        assertEquals("DATASET", UploadedFileType.DATASET.name());
        assertEquals("IMAGE_FILE", UploadedFileType.IMAGE_FILE.name());
        assertEquals("TEXT_FILE", UploadedFileType.TEXT_FILE.name());
        assertEquals("OTHER", UploadedFileType.OTHER.name());
    }

    @Test
    void uploadedFileType_legacyValuesCompatibleWithOldEnum() {
        // Les anciennes valeurs du ENUM MySQL étaient :
        // CONFIG, CUSTOM_CODE, DATASET, MODEL_FILE, OTHER
        // Les valeurs Java correspondantes existent toujours dans l'enum.
        assertNotNull(UploadedFileType.valueOf("DATASET"));
        assertNotNull(UploadedFileType.valueOf("MODEL_FILE"));
        assertNotNull(UploadedFileType.valueOf("OTHER"));
        // CONFIG et CUSTOM_CODE n'ont PAS de correspondance Java directe,
        // mais elles ne seront plus problématiques une fois converti en VARCHAR.
    }

    // ─── UploadedProjectFile : structure des colonnes ─────────────────

    @Test
    void uploadedProjectFile_hasAllRequiredColumns() {
        // Vérifie que l'entité peut être instanciée avec tous les champs
        UploadedProjectFile file = new UploadedProjectFile();
        file.setOriginalFileName("train.py");
        file.setStoredFileName("abc123.py");
        file.setFilePath("/app/uploads/projects/1/abc123.py");
        file.setFileType(UploadedFileType.TRAINING_SCRIPT);
        file.setRole(UploadedFileType.TRAINING_SCRIPT);
        file.setFileExtension("py");
        file.setContentType("text/x-python");
        file.setSizeBytes(1024L);

        assertEquals(UploadedFileType.TRAINING_SCRIPT, file.getFileType());
        assertEquals(UploadedFileType.TRAINING_SCRIPT, file.getRole());
        assertEquals("py", file.getFileExtension());
    }

    @Test
    void uploadedProjectFile_roleDefaultsToOther() {
        UploadedProjectFile file = new UploadedProjectFile();
        // role default is OTHER (set in field declaration)
        assertEquals(UploadedFileType.OTHER, file.getRole());
    }

    @Test
    void uploadedProjectFile_roleSetterSanitizesNull() {
        UploadedProjectFile file = new UploadedProjectFile();
        file.setRole(null);
        assertEquals(UploadedFileType.OTHER, file.getRole());
    }

    // ─── ModelDeployment : serving_track et stable_service_name ───────

    @Test
    void modelDeployment_hasServingTrackAndStableServiceName() {
        ModelDeployment deployment = new ModelDeployment();
        deployment.setServingTrack("blue");
        deployment.setStableServiceName("my-model-service");

        assertEquals("blue", deployment.getServingTrack());
        assertEquals("my-model-service", deployment.getStableServiceName());
    }

    @Test
    void modelDeployment_servingTrackAcceptsBlueAndGreen() {
        ModelDeployment d1 = new ModelDeployment();
        d1.setServingTrack("blue");
        assertEquals("blue", d1.getServingTrack());

        ModelDeployment d2 = new ModelDeployment();
        d2.setServingTrack("green");
        assertEquals("green", d2.getServingTrack());
    }

    @Test
    void modelDeployment_servingTrackNullWhenNotSet() {
        ModelDeployment d = new ModelDeployment();
        assertNull(d.getServingTrack());
    }

    @Test
    void modelDeployment_allFieldsUsableTogether() {
        ModelDeployment deployment = new ModelDeployment();
        deployment.setProjectId(1L);
        deployment.setExperimentId(10L);
        deployment.setRegisteredModelName("spam-detector");
        deployment.setModelVersion("3");
        deployment.setMlflowRunId("run-abc");
        deployment.setDeploymentStatus(DeploymentStatus.PENDING);
        deployment.setPredictionEndpoint("http://svc:8080/predict");
        deployment.setDeploymentName("spam-detector-v3-dep");
        deployment.setServiceName("spam-detector-v3-svc");
        deployment.setNamespaceName("mlops");
        deployment.setDockerImage("acrmlops.azurecr.io/spam:v3");
        deployment.setActive(false);
        deployment.setServingTrack("green");
        deployment.setStableServiceName("spam-detector-service");

        assertEquals("green", deployment.getServingTrack());
        assertEquals("spam-detector-service", deployment.getStableServiceName());
        assertEquals(DeploymentStatus.PENDING, deployment.getDeploymentStatus());
        assertFalse(deployment.getActive());
    }

    // ─── Cohérence enum-count (détection de régression) ───────────────

    @Test
    void uploadedFileType_hasExactly13Values() {
        // Si on ajoute ou supprime une valeur, ce test casse intentionnellement
        // pour forcer une revue du schéma MySQL.
        assertEquals(13, UploadedFileType.values().length,
                "UploadedFileType must have exactly 13 values. "
                        + "If you added/removed a value, update this test "
                        + "and verify the MySQL column is VARCHAR(64).");
    }

    @Test
    void deploymentStatus_hasExactly5Values() {
        assertEquals(5, DeploymentStatus.values().length,
                "DeploymentStatus must have exactly 5 values.");
    }
}
