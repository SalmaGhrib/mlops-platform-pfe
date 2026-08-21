package com.mlops.platform.service;

import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Vérifie que la synchronisation des runs ne détruit plus le marqueur de
 * standardisation contenu dans le message de l'expérience.
 *
 * Cause racine historique : syncSingleModelVersionToDatabase écrasait message
 * avec "Synchronisation MLflow Registry terminée...", ce qui faisait perdre au
 * standardizer son garde-fou (marqueur "model artifact standardized") et
 * déclenchait un nouveau log_model -> une NOUVELLE version MLflow à chaque cycle.
 * appendSafeMessage() préserve l'historique et borne à 3000 caractères.
 */
@ExtendWith(MockitoExtension.class)
class MlflowTrackingClientAppendMessageTest {

    @Mock
    private MlExperimentRepository mlExperimentRepository;

    @Mock
    private MlExperimentMetricRepository mlExperimentMetricRepository;

    private MlflowTrackingClientService service;

    @BeforeEach
    void setUp() {
        service = new MlflowTrackingClientService(
                mlExperimentRepository,
                mlExperimentMetricRepository
        );
    }

    @Test
    void appendSafeMessage_keepsExistingMarker_whenSyncing() {
        String oldMessage = "model artifact standardized automatically newversion=5 "
                + "[MODEL_ARTIFACT_STANDARDIZED_VERSION=5]";

        String result = invokeAppendSafeMessage(
                oldMessage,
                "Synchronisation MLflow Registry terminée pour un run réussi."
        );

        assertNotNull(result);
        assertTrue(result.contains("model artifact standardized automatically"),
                "le marqueur de standardisation doit être conservé");
        assertTrue(result.contains("[MODEL_ARTIFACT_STANDARDIZED_VERSION=5]"),
                "le marqueur de version doit être conservé");
        assertTrue(result.contains("Synchronisation MLflow Registry terminée"),
                "le message de sync doit être ajouté sans écraser l'existant");
    }

    @Test
    void appendSafeMessage_isIdempotent_whenAdditionAlreadyPresent() {
        String oldMessage = "Déjà synchronisé. Synchronisation MLflow Registry terminée pour un run réussi.";

        String result = invokeAppendSafeMessage(
                oldMessage,
                "Synchronisation MLflow Registry terminée pour un run réussi."
        );

        assertEquals(oldMessage.trim(), result);
    }

    @Test
    void appendSafeMessage_truncatesTo3000Chars() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append('x');
        }

        String result = invokeAppendSafeMessage(
                huge.toString(),
                "ajout après le marqueur"
        );

        assertNotNull(result);
        assertTrue(result.length() <= 3000, "le message doit être borné à 3000 caractères");
        assertTrue(result.endsWith("... [truncated]"));
        assertTrue(result.length() >= 2984);
    }

    private String invokeAppendSafeMessage(String oldMessage, String addition) {
        return ReflectionTestUtils.invokeMethod(
                service,
                "appendSafeMessage",
                oldMessage,
                addition
        );
    }
}
