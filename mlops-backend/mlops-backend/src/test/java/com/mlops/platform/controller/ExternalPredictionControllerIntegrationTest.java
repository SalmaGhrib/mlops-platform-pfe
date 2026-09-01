package com.mlops.platform.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.service.ModelGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/*
 * Tests d'intégration du SMART MODEL GATEWAY (contrôleur réel, model server mocké).
 *
 * Vérifie que le client externe ne fournit JAMAIS model_name / model_alias :
 * le modèle est identifié par l'URL /models/{registeredModelName}/predict et le
 * Gateway injecte automatiquement model_name + model_alias="production" (version
 * active résolue côté backend) avant l'appel au model server, sans écraser les
 * features métier ni fuiter les métadonnées internes (modelVersion/deploymentId).
 */
class ExternalPredictionControllerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ModelGatewayService modelGatewayService;
    private RestTemplate restTemplate;
    private MockRestServiceServer modelServer;
    private ExternalPredictionController controller;

    @BeforeEach
    void setUp() {
        modelGatewayService = mock(ModelGatewayService.class);
        restTemplate = new RestTemplate();
        modelServer = MockRestServiceServer.bindTo(restTemplate).build();
        controller = new ExternalPredictionController(modelGatewayService, restTemplate);
    }

    private ModelApiKey apiKey() {
        ModelApiKey key = new ModelApiKey();
        key.setDeploymentId(1L);
        key.setKeyPrefix("mlops_live_test");
        key.setEnabled(true);
        return key;
    }

    private ModelDeployment deployment(String modelName, String version, String endpoint) {
        ModelDeployment deployment = new ModelDeployment();
        deployment.setRegisteredModelName(modelName);
        deployment.setModelVersion(version);
        deployment.setPredictionEndpoint(endpoint);
        deployment.setDeploymentName(modelName + "-deployment");
        deployment.setVisibility(DeploymentVisibility.PUBLIC_API);
        deployment.setApiEnabled(true);
        deployment.setActive(true);
        return deployment;
    }

    private void stubGateway(String modelName, String version, String endpoint) {
        ModelDeployment deployment = deployment(modelName, version, endpoint);
        when(modelGatewayService.validateApiKey(any(), any())).thenReturn(apiKey());
        when(modelGatewayService.resolveGatewayDeployment(any())).thenReturn(deployment);
        org.mockito.Mockito.doNothing().when(modelGatewayService).incrementUsage(any());
    }

    private static String bodyOf(ClientHttpRequest request) {
        return ((MockClientHttpRequest) request).getBodyAsString();
    }

    private static Map<String, Object> parse(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON body: " + json, e);
        }
    }

    /*
     * Test 1 : appel tabulaire "simple" (features plates) SANS model_name ni model_alias.
     * - le Gateway enveloppe dans rows
     * - il injecte model_name = le modèle de l'URL et model_alias = "production"
     * - les features métier sont préservées telles quelles
     * - aucune métadonnée interne (modelVersion/deploymentId) n'est révélée au model server
     */
    @Test
    void tabularSimpleJson_withoutModelName_forwardsFeaturesWithInjectedMetadata() {
        String endpoint = "http://energy-model-svc.mlops.svc.cluster.local:8000/predict";
        stubGateway("energy_model", "7", endpoint);

        modelServer.expect(requestTo(endpoint))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    Map<String, Object> sent = parse(bodyOf(request));

                    assertEquals("energy_model", sent.get("model_name"), "model_name doit etre injecte depuis l'URL");
                    assertEquals("production", sent.get("model_alias"), "model_alias doit etre la version active");

                    List<Map<String, Object>> rows = (List<Map<String, Object>>) sent.get("rows");
                    assertNotNull(rows, "Les features doivent etre enveloppees dans rows");
                    assertEquals(1, rows.size());
                    assertEquals("2024-01-01", rows.get(0).get("date"));
                    assertEquals(10.5, ((Number) rows.get(0).get("consumption")).doubleValue());

                    assertFalse(sent.containsKey("modelVersion"), "modelVersion ne doit pas fuiter dans le payload");
                    assertFalse(sent.containsKey("deploymentId"), "deploymentId ne doit pas fuiter dans le payload");
                    assertFalse(sent.containsKey("registeredModelName"), "registeredModelName ne doit pas fuiter dans le payload");
                })
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"model_name\":\"energy_model\",\"predictions\":[[0.9]]}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> body = Map.of("date", "2024-01-01", "consumption", 10.5);

        var response = controller.predictExternal("energy_model", "mlops_live_test_key", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("success", payload.get("status"));
        assertNotNull(payload.get("modelResponse"));
        modelServer.verify();
    }

    /*
     * Test 2 : appel tabulaire déjà enveloppé {"rows":[...]} SANS model_name.
     * - le client peut même tenter d'envoyer model_name/model_alias : ils sont
     *   écrasés par les valeurs autoritatives du backend (pas de spoofing)
     * - les features dans rows sont intactes
     */
    @Test
    void tabularWrappedRows_withSpoofedMetadata_isOverwrittenByBackendValues() {
        String endpoint = "http://energy-model-svc.mlops.svc.cluster.local:8000/predict";
        stubGateway("energy_model", "7", endpoint);

        modelServer.expect(requestTo(endpoint))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    Map<String, Object> sent = parse(bodyOf(request));

                    assertEquals("energy_model", sent.get("model_name"), "model_name du client doit etre ecrase");
                    assertEquals("production", sent.get("model_alias"), "model_alias du client doit etre ecrase");

                    List<Map<String, Object>> rows = (List<Map<String, Object>>) sent.get("rows");
                    assertEquals(22.0, ((Number) rows.get(0).get("temperature")).doubleValue());
                    assertEquals(55, ((Number) rows.get(0).get("humidity")).intValue());
                })
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"predictions\":[[0.8]]}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> body = Map.of(
                "rows", List.of(Map.of("temperature", 22.0, "humidity", 55)),
                "model_name", "spoofed_model",
                "model_alias", "champion"
        );

        var response = controller.predictExternal("energy_model", "mlops_live_test_key", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        modelServer.verify();
    }

    /*
     * Test 3 : appel NLP (predict-text) SANS model_name.
     * - le Gateway injecte model_name + model_alias côté texte
     * - le text métier est préservé
     */
    @Test
    void text_withoutModelName_forwardsTextWithInjectedMetadata() {
        String endpoint = "http://nlp-model-svc.mlops.svc.cluster.local:8000/predict-text";
        stubGateway("sentiment_model", "3", endpoint);

        modelServer.expect(requestTo(endpoint))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    Map<String, Object> sent = parse(bodyOf(request));

                    assertEquals("sentiment_model", sent.get("model_name"));
                    assertEquals("production", sent.get("model_alias"));
                    assertEquals("commande livree", sent.get("text"));

                    assertFalse(sent.containsKey("modelVersion"));
                    assertFalse(sent.containsKey("deploymentId"));
                })
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"predictions\":[[1]]}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> body = Map.of("text", "commande livree");

        var response = controller.predictExternal("sentiment_model", "mlops_live_test_key", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        modelServer.verify();
    }

    /*
     * Test 4 : appel image (multipart) SANS model_name.
     * - le multipart vers le model server contient toujours model_name + model_alias
     *   (comme l'exige le /predict-image généré)
     */
    @Test
    void image_withoutModelName_forwardsMultipartWithModelMetadata() {
        String endpoint = "http://vision-model-svc.mlops.svc.cluster.local:8000/predict-image";
        stubGateway("vision_model", "5", endpoint);

        modelServer.expect(requestTo(endpoint))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = bodyOf(request);

                    assertTrue(body.contains("name=\"model_name\""), "le multipart doit contenir model_name");
                    assertTrue(body.contains("vision_model"), "model_name injecte depuis l'URL");
                    assertTrue(body.contains("name=\"model_alias\""), "le multipart doit contenir model_alias");
                    assertTrue(body.contains("production"), "model_alias doit etre production");
                    assertTrue(body.contains("name=\"image_file\""), "le multipart doit contenir image_file");
                })
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"predictions\":[[0]]}",
                        MediaType.APPLICATION_JSON
                ));

        String pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
        Map<String, Object> body = Map.of("imageBase64", pngBase64);

        var response = controller.predictImageExternal("vision_model", "mlops_live_test_key", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        modelServer.verify();
    }
}
