package com.mlops.platform.controller;

import com.mlops.platform.entity.ModelApiKey;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.exception.GatewayException;
import com.mlops.platform.service.ModelGatewayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
 * SMART MODEL GATEWAY — endpoint externe stable.
 *
 * POST /api/external/models/{registeredModelName}/predict
 * POST /api/external/models/{registeredModelName}/predict-image
 * POST /api/external/models/{registeredModelName}/predict-text
 *
 * Header obligatoire : X-API-KEY (la clé identifie modèle, propriétaire et usage).
 *
 * Le client externe ne fournit JAMAIS model_name / model_alias : le modèle est
 * identifié par l'URL et le Gateway injecte automatiquement model_name +
 * model_alias="production" (résolution de la version active côté backend) avant
 * d'appeler le model server, sans écraser les features métier.
 *
 * Codes d'erreur :
 * - 401 MISSING_API_KEY / INVALID_API_KEY
 * - 403 KEY_DISABLED / KEY_NOT_FOR_MODEL / EXTERNAL_ACCESS_DISABLED
 * - 404 MODEL_NOT_FOUND
 * - 410 KEY_REVOKED / KEY_EXPIRED
 * - 503 MODEL_NOT_DEPLOYED
 *
 * Le URL Kubernetes interne du modèle n'est JAMAIS exposée dans la réponse.
 */
@RestController
@RequestMapping("/api/external")
@CrossOrigin(origins = "*")
public class ExternalPredictionController {

    private final ModelGatewayService modelGatewayService;
    private final RestTemplate restTemplate;

    @Autowired
    public ExternalPredictionController(ModelGatewayService modelGatewayService) {
        this(modelGatewayService, new RestTemplate());
    }

    ExternalPredictionController(
            ModelGatewayService modelGatewayService,
            RestTemplate restTemplate
    ) {
        this.modelGatewayService = modelGatewayService;
        this.restTemplate = restTemplate;
    }

    @PostMapping("/models/{registeredModelName}/predict")
    public ResponseEntity<?> predictExternal(
            @PathVariable String registeredModelName,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestBody(required = false) Map<String, Object> requestBody
    ) {
        return handlePrediction(registeredModelName, apiKey, requestBody, null);
    }

    @PostMapping("/models/{registeredModelName}/predict-image")
    public ResponseEntity<?> predictImageExternal(
            @PathVariable String registeredModelName,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestBody(required = false) Map<String, Object> requestBody
    ) {
        return handlePrediction(registeredModelName, apiKey, requestBody, "IMAGE");
    }

    private ResponseEntity<?> handlePrediction(
            String registeredModelName,
            String apiKey,
            Map<String, Object> requestBody,
            String forcedFormat
    ) {
        String requestId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long start = System.currentTimeMillis();

        /*
         * 1. AUTHENTIFICATION — validation de la clé SMART.
         */
        ModelApiKey key;

        try {
            key = modelGatewayService.validateApiKey(apiKey, registeredModelName);
        } catch (GatewayException e) {
            long latency = System.currentTimeMillis() - start;

            System.out.println("[SMART_GATEWAY_AUTH] requestId=" + requestId
                    + " modelName=" + registeredModelName
                    + " status=" + e.getStatus().value()
                    + " code=" + e.getCode()
                    + " latencyMs=" + latency);

            return ResponseEntity.status(e.getStatus()).body(errorResponse(e.getCode(), e.getMessage()));
        }

        /*
         * 2. RÉSOLUTION DU DÉPLOIEMENT ACTIF (403 / 404 / 503).
         */
        ModelDeployment deployment;

        try {
            deployment = modelGatewayService.resolveGatewayDeployment(key);
        } catch (GatewayException e) {
            long latency = System.currentTimeMillis() - start;

            recordAndLog(key, null, registeredModelName, e.getStatus().value(), e.getCode(), latency, null);

            System.out.println("[SMART_GATEWAY_ROUTE] requestId=" + requestId
                    + " keyId=" + key.getId()
                    + " modelName=" + registeredModelName
                    + " status=" + e.getStatus().value()
                    + " code=" + e.getCode()
                    + " latencyMs=" + latency);

            return ResponseEntity.status(e.getStatus()).body(errorResponse(e.getCode(), e.getMessage()));
        }

        /*
         * 3. INCREMENT USAGE (compteur atomique, ne bloque jamais).
         *    On incrementera success/failure apres le resultat.
         */

        if (requestBody == null || requestBody.isEmpty()) {
            long latency = System.currentTimeMillis() - start;

            recordAndLog(key, deployment, registeredModelName, 400, "EMPTY_REQUEST_BODY", latency, null);

            return ResponseEntity.badRequest().body(errorResponse(
                    "EMPTY_REQUEST_BODY",
                    "Le body JSON est obligatoire pour lancer une prédiction."
            ));
        }

        String predictionEndpoint = deployment.getPredictionEndpoint();

        if (predictionEndpoint == null || predictionEndpoint.isBlank()) {
            long latency = System.currentTimeMillis() - start;

            recordAndLog(key, deployment, registeredModelName, 400, "PREDICTION_ENDPOINT_MISSING", latency, null);

            return ResponseEntity.badRequest().body(errorResponse(
                    "PREDICTION_ENDPOINT_MISSING",
                    "Aucun endpoint de prédiction n'est disponible pour ce modèle."
            ));
        }

        /*
         * 4. APPEL AU MODEL SERVER.
         */
        try {
            String endpointType = detectRequestFormat(predictionEndpoint, requestBody, forcedFormat);

            HttpEntity<?> entity = buildEntityForModelServer(
                    deployment,
                    predictionEndpoint,
                    requestBody,
                    forcedFormat,
                    endpointType
            );

            ResponseEntity<String> modelResponse = restTemplate.exchange(
                    predictionEndpoint,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            long latency = System.currentTimeMillis() - start;
            int status = modelResponse.getStatusCode().value();
            boolean ok = modelResponse.getStatusCode().is2xxSuccessful();

            recordAndLog(
                    key,
                    deployment,
                    registeredModelName,
                    status,
                    ok ? null : "MODEL_SERVER_ERROR",
                    latency,
                    endpointType
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", ok ? "success" : "error");
            response.put("registeredModelName", deployment.getRegisteredModelName());
            response.put("modelVersion", deployment.getModelVersion());
            response.put("deploymentId", deployment.getId());
            response.put("requestId", requestId);
            response.put("requestFormat", endpointType);
            response.put("modelResponse", modelResponse.getBody());
            response.put("timestamp", LocalDateTime.now().toString());

            return ResponseEntity.status(modelResponse.getStatusCode())
                    .body(response);

        } catch (HttpStatusCodeException e) {
            long latency = System.currentTimeMillis() - start;

            recordAndLog(key, deployment, registeredModelName, 502, "MODEL_SERVER_ERROR", latency, null);

            Map<String, Object> error = errorResponse(
                    "MODEL_SERVER_ERROR",
                    "Erreur retournée par le serveur du modèle."
            );

            error.put("modelStatusCode", e.getStatusCode().value());
            error.put("modelResponse", e.getResponseBodyAsString());
            error.put("registeredModelName", deployment.getRegisteredModelName());
            error.put("modelVersion", deployment.getModelVersion());
            error.put("deploymentId", deployment.getId());
            error.put("requestId", requestId);

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;

            recordAndLog(key, deployment, registeredModelName, 500, "EXTERNAL_PREDICTION_FAILED", latency, null);

            Map<String, Object> error = errorResponse(
                    "EXTERNAL_PREDICTION_FAILED",
                    e.getMessage()
            );

            error.put("registeredModelName", deployment.getRegisteredModelName());
            error.put("modelVersion", deployment.getModelVersion());
            error.put("deploymentId", deployment.getId());
            error.put("requestId", requestId);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /*
     * Enregistre une ligne d'usage + log [SMART_GATEWAY_REQUEST].
     * Ne doit JAMAIS bloquer la prédiction ni propager d'exception.
     */
    private void recordAndLog(
            ModelApiKey key,
            ModelDeployment deployment,
            String registeredModelName,
            int statusCode,
            String errorCode,
            long latencyMs,
            String endpointType
    ) {
        Long modelId = deployment == null ? key.getDeploymentId() : deployment.getId();
        String modelVersion = deployment == null ? null : deployment.getModelVersion();
        String modelName = registeredModelName == null ? null : registeredModelName.trim();
        boolean success = statusCode >= 200 && statusCode < 300 && errorCode == null;

        System.out.println("[SMART_GATEWAY_REQUEST] keyId=" + key.getId()
                + " modelName=" + modelName
                + " modelVersion=" + modelVersion
                + " status=" + statusCode
                + " code=" + errorCode
                + " latencyMs=" + latencyMs);

        try {
            if (success) {
                modelGatewayService.incrementUsageSuccess(key.getId());
            } else {
                modelGatewayService.incrementUsageFailure(key.getId());
            }
        } catch (Exception e) {
            System.out.println("[SMART_GATEWAY_USAGE] incrementUsage failed: " + e.getMessage());
        }

        try {
            modelGatewayService.recordUsage(
                    key.getId(),
                    modelId,
                    modelName,
                    modelVersion,
                    statusCode,
                    latencyMs,
                    success,
                    endpointType,
                    errorCode
            );
        } catch (Exception e) {
            System.err.println("[SMART_GATEWAY_USAGE] recordAndLog FAILED for keyId=" + key.getId()
                    + " modelName=" + modelName
                    + " status=" + statusCode
                    + " error=" + e);
            e.printStackTrace();
        }
    }

    /*
     * Normalisation automatique du body selon l'endpoint réel du modèle.
     */
    private Map<String, Object> normalizeRequestBodyForModel(
            String predictionEndpoint,
            Map<String, Object> originalBody,
            String forcedFormat
    ) {
        if ("IMAGE".equals(forcedFormat)) {
            return normalizeImageBody(originalBody);
        }

        if ("TEXT".equals(forcedFormat)) {
            return normalizeTextBody(originalBody);
        }

        String endpoint = predictionEndpoint == null
                ? ""
                : predictionEndpoint.trim().toLowerCase();

        if (endpoint.endsWith("/predict-text") || endpoint.contains("/predict-text")) {
            return normalizeTextBody(originalBody);
        }

        if (endpoint.endsWith("/predict-image") || endpoint.contains("/predict-image")) {
            return normalizeImageBody(originalBody);
        }

        return normalizeTabularBody(originalBody);
    }

    /*
     * TABULAR : on garde {"rows":[...]} / {"data":[...]}, sinon on enveloppe.
     */
    private Map<String, Object> normalizeTabularBody(Map<String, Object> originalBody) {
        if (originalBody.containsKey("rows") || originalBody.containsKey("data")) {
            return originalBody;
        }

        Map<String, Object> cleanedRow = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : originalBody.entrySet()) {
            String key = entry.getKey();

            if (key == null || key.isBlank()) {
                continue;
            }

            if (isMetadataKey(key)) {
                continue;
            }

            cleanedRow.put(key, entry.getValue());
        }

        if (cleanedRow.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucune feature tabulaire valide trouvée. Envoyez soit {\"rows\":[{...}]}, soit un JSON simple de features."
            );
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("rows", List.of(cleanedRow));

        return normalized;
    }

    private Map<String, Object> normalizeTextBody(Map<String, Object> originalBody) {
        if (originalBody.containsKey("text")
                || originalBody.containsKey("texts")
                || originalBody.containsKey("data")) {
            return originalBody;
        }

        Object input = firstExistingValue(originalBody, "input", "message", "sentence", "content");

        if (input != null) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("text", input);
            return normalized;
        }

        throw new IllegalArgumentException(
                "Format texte invalide. Envoyez par exemple {\"text\":\"votre texte\"}."
        );
    }

    private Map<String, Object> normalizeImageBody(Map<String, Object> originalBody) {
        if (originalBody.containsKey("imageBase64")
                || originalBody.containsKey("image")
                || originalBody.containsKey("data")
                || originalBody.containsKey("file")) {
            return originalBody;
        }

        Object input = firstExistingValue(originalBody, "base64", "image_base64", "content");

        if (input != null) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("imageBase64", input);
            return normalized;
        }

        throw new IllegalArgumentException(
                "Format image invalide. Envoyez par exemple {\"imageBase64\":\"...\"}."
        );
    }

    /*
     * Enrichit le payload JSON envoyé au model server avec les métadonnées
     * internes résolues côté backend (le client externe ne doit JAMAIS les fournir) :
     *   model_name  = le registeredModelName extrait de l'URL /models/{name}/predict
     *   model_alias = "production" (l'alias MLflow @production est maintenu par
     *                 le reconciler AutoProductionDeploymentReconcilerService)
     *
     * Les features métier (rows / data / text) ne sont PAS écrasées : l'injection
     * se fait uniquement au niveau racine, jamais à l'intérieur des lignes.
     */
    private Map<String, Object> enrichWithModelMetadata(
            Map<String, Object> normalizedBody,
            ModelDeployment deployment
    ) {
        Map<String, Object> enriched = new LinkedHashMap<>(normalizedBody);

        String modelName = deployment == null ? null : deployment.getRegisteredModelName();

        if (modelName != null && !modelName.isBlank()) {
            enriched.put("model_name", modelName.trim());
        }

        enriched.put("model_alias", "production");

        return enriched;
    }

    private Object firstExistingValue(Map<String, Object> body, String... keys) {
        if (body == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (body.containsKey(key)) {
                Object value = body.get(key);

                if (value != null) {
                    return value;
                }
            }
        }

        return null;
    }

    private boolean isMetadataKey(String key) {
        if (key == null) {
            return false;
        }

        String clean = key.trim();

        return clean.equalsIgnoreCase("modelName")
                || clean.equalsIgnoreCase("modelAlias")
                || clean.equalsIgnoreCase("registeredModelName")
                || clean.equalsIgnoreCase("modelVersion")
                || clean.equalsIgnoreCase("deploymentId")
                || clean.equalsIgnoreCase("target")
                || clean.equalsIgnoreCase("label")
                || clean.equalsIgnoreCase("prediction")
                || clean.equalsIgnoreCase("dataType");
    }

    private String detectRequestFormat(
            String predictionEndpoint,
            Map<String, Object> originalBody,
            String forcedFormat
    ) {
        if ("IMAGE".equals(forcedFormat)) {
            return "IMAGE";
        }

        if ("TEXT".equals(forcedFormat)) {
            return "TEXT";
        }

        String endpoint = predictionEndpoint == null
                ? ""
                : predictionEndpoint.trim().toLowerCase();

        if (endpoint.contains("/predict-text")) {
            return "TEXT";
        }

        if (endpoint.contains("/predict-image")) {
            return "IMAGE";
        }

        if (originalBody != null && (originalBody.containsKey("rows") || originalBody.containsKey("data"))) {
            return "TABULAR_ALREADY_WRAPPED";
        }

        return "TABULAR_SIMPLE_JSON_WRAPPED_TO_ROWS";
    }

    /*
     * Construit le corps de la requête envoyée au model server :
     * - IMAGE  : multipart (image_file + model_name + model_alias),
     *            comme l'exige le FastAPI généré par ModelServingImageBuildService.
     * - sinon  : JSON normalisé (TEXT / TABULAR).
     */
    private HttpEntity<?> buildEntityForModelServer(
            ModelDeployment deployment,
            String predictionEndpoint,
            Map<String, Object> requestBody,
            String forcedFormat,
            String endpointType
    ) {
        if ("IMAGE".equals(endpointType)) {
            return buildImageMultipartEntity(deployment, requestBody);
        }

        Map<String, Object> normalizedBody = normalizeRequestBodyForModel(
                predictionEndpoint,
                requestBody,
                forcedFormat
        );

        Map<String, Object> enrichedBody = enrichWithModelMetadata(
                normalizedBody,
                deployment
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        return new HttpEntity<>(enrichedBody, headers);
    }

    /*
     * IMAGE -> multipart avec l'image décodée depuis le base64 reçu.
     * Compatible avec le /predict-image généré (UploadFile + Form).
     */
    private HttpEntity<MultiValueMap<String, Object>> buildImageMultipartEntity(
            ModelDeployment deployment,
            Map<String, Object> requestBody
    ) {
        Object raw = firstExistingValue(requestBody, "imageBase64", "image", "data", "file", "base64", "image_base64");

        if (raw == null) {
            raw = firstExistingValue(requestBody, "content");
        }

        if (raw == null) {
            throw new IllegalArgumentException(
                    "Format image invalide. Envoyez par exemple {\"imageBase64\":\"...\"}."
            );
        }

        String data = String.valueOf(raw).trim();

        if (data.contains(",")) {
            String[] parts = data.split(",", 2);
            if (parts[0].contains("base64")) {
                data = parts[1];
            }
        }

        byte[] imageBytes;

        try {
            imageBytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "imageBase64 invalide : le contenu n'est pas du base64 valide."
            );
        }

        if (imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBase64 invalide : image vide.");
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();

        String modelName = deployment == null ? null : deployment.getRegisteredModelName();

        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Nom de modèle introuvable pour la requête image.");
        }

        form.add("model_name", modelName.trim());
        form.add("model_alias", "production");
        form.add("image_file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "input.png";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        return new HttpEntity<>(form, headers);
    }

    private Map<String, Object> errorResponse(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "error");
        error.put("code", code);
        error.put("message", message);
        error.put("timestamp", LocalDateTime.now().toString());
        return error;
    }
}
