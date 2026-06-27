package com.mlops.platform.controller;

import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.service.ModelDeploymentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/external")
@CrossOrigin(origins = "*")
public class ExternalPredictionController {

    private final ModelDeploymentService modelDeploymentService;
    private final RestTemplate restTemplate;

    public ExternalPredictionController(ModelDeploymentService modelDeploymentService) {
        this.modelDeploymentService = modelDeploymentService;
        this.restTemplate = new RestTemplate();
    }

    /*
     * Endpoint externe sécurisé.
     *
     * Exemple :
     * POST /api/external/models/{registeredModelName}/predict
     *
     * Headers :
     * X-API-KEY: mlops_xxxxx
     *
     * Pour les modèles SHARED :
     * X-USER-EMAIL: user@email.com
     *
     * Règles :
     * - PUBLIC_API : clé API valide suffit.
     * - SHARED : clé API valide + X-USER-EMAIL autorisé.
     * - PRIVATE : accès externe refusé.
     *
     * Formats acceptés :
     *
     * TABULAR simple :
     * {
     *   "feature_1": 1.2,
     *   "feature_2": 0.4
     * }
     *
     * TABULAR avancé :
     * {
     *   "rows": [
     *     {
     *       "feature_1": 1.2,
     *       "feature_2": 0.4
     *     }
     *   ]
     * }
     *
     * TABULAR data :
     * {
     *   "data": [
     *     [1.2, 0.4, 3.1]
     *   ]
     * }
     *
     * TEXT / NLP :
     * {
     *   "text": "le service est excellent et rapide"
     * }
     *
     * IMAGE JSON :
     * {
     *   "imageBase64": "..."
     * }
     */
    @PostMapping("/models/{registeredModelName}/predict")
    public ResponseEntity<?> predictExternal(
            @PathVariable String registeredModelName,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestHeader(value = "X-USER-EMAIL", required = false) String userEmail,
            @RequestBody(required = false) Map<String, Object> requestBody
    ) {
        ModelDeployment deployment;

        try {
            deployment = modelDeploymentService.findDeploymentForExternalApiOrThrow(
                    registeredModelName,
                    apiKey,
                    userEmail
            );
        } catch (Exception e) {
            HttpStatus status = resolveAccessDeniedStatus(e.getMessage());

            return ResponseEntity.status(status).body(
                    errorResponse("EXTERNAL_PREDICTION_DENIED", e.getMessage())
            );
        }

        String predictionEndpoint = deployment.getPredictionEndpoint();

        if (predictionEndpoint == null || predictionEndpoint.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    errorResponse(
                            "PREDICTION_ENDPOINT_MISSING",
                            "Aucun endpoint de prédiction n'est disponible pour ce modèle."
                    )
            );
        }

        if (requestBody == null || requestBody.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    errorResponse(
                            "EMPTY_REQUEST_BODY",
                            "Le body JSON est obligatoire pour lancer une prédiction."
                    )
            );
        }

        try {
            Map<String, Object> normalizedBody = normalizeRequestBodyForModel(
                    predictionEndpoint,
                    requestBody
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(normalizedBody, headers);

            ResponseEntity<String> modelResponse = restTemplate.exchange(
                    predictionEndpoint,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("registeredModelName", deployment.getRegisteredModelName());
            response.put("modelVersion", deployment.getModelVersion());
            response.put("deploymentId", deployment.getId());
            response.put("predictionEndpoint", predictionEndpoint);
            response.put("requestFormat", detectRequestFormat(predictionEndpoint, requestBody, normalizedBody));
            response.put("modelResponse", modelResponse.getBody());
            response.put("timestamp", LocalDateTime.now().toString());

            return ResponseEntity.status(modelResponse.getStatusCode()).body(response);

        } catch (HttpStatusCodeException e) {
            Map<String, Object> error = errorResponse(
                    "MODEL_SERVER_ERROR",
                    "Erreur retournée par le serveur du modèle."
            );

            error.put("modelStatusCode", e.getStatusCode().value());
            error.put("modelResponse", e.getResponseBodyAsString());
            error.put("registeredModelName", deployment.getRegisteredModelName());
            error.put("modelVersion", deployment.getModelVersion());
            error.put("deploymentId", deployment.getId());
            error.put("predictionEndpoint", predictionEndpoint);

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);

        } catch (Exception e) {
            Map<String, Object> error = errorResponse(
                    "EXTERNAL_PREDICTION_FAILED",
                    e.getMessage()
            );

            error.put("registeredModelName", deployment.getRegisteredModelName());
            error.put("modelVersion", deployment.getModelVersion());
            error.put("deploymentId", deployment.getId());
            error.put("predictionEndpoint", predictionEndpoint);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /*
     * Normalisation automatique du body selon l'endpoint réel du modèle.
     *
     * Objectif :
     * - ne pas obliger l'utilisateur externe à connaître le format interne FastAPI
     * - garder la compatibilité avec les anciens formats déjà fonctionnels
     */
    private Map<String, Object> normalizeRequestBodyForModel(
            String predictionEndpoint,
            Map<String, Object> originalBody
    ) {
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
     * TABULAR :
     *
     * Si l'utilisateur envoie déjà :
     * {"rows":[...]} ou {"data":[...]}
     * on garde tel quel.
     *
     * Sinon :
     * {"feature_1":1.2,"feature_2":0.4}
     * devient :
     * {"rows":[{"feature_1":1.2,"feature_2":0.4}]}
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

            /*
             * On évite d'envoyer des champs de métadonnées comme features.
             */
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

    /*
     * TEXT / NLP :
     *
     * Format recommandé :
     * {"text":"..."}
     *
     * On garde aussi les formats déjà avancés :
     * {"texts":[...]}
     * {"data":...}
     */
    private Map<String, Object> normalizeTextBody(Map<String, Object> originalBody) {
        if (originalBody.containsKey("text")
                || originalBody.containsKey("texts")
                || originalBody.containsKey("data")) {
            return originalBody;
        }

        /*
         * Petit confort :
         * si le client envoie {"input":"..."} ou {"message":"..."},
         * on transforme en {"text":"..."}.
         */
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

    /*
     * IMAGE :
     *
     * Pour l'API externe JSON, on accepte les bodies déjà préparés.
     * Exemples possibles :
     * {"imageBase64":"..."}
     * {"image":"..."}
     * {"data":"..."}
     *
     * Si ton FastAPI image attend un nom précis, on pourra l'adapter ici.
     */
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
            Map<String, Object> normalizedBody
    ) {
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

        if (normalizedBody != null && normalizedBody.containsKey("rows")) {
            return "TABULAR_SIMPLE_JSON_WRAPPED_TO_ROWS";
        }

        return "UNKNOWN";
    }

    private HttpStatus resolveAccessDeniedStatus(String message) {
        if (message == null || message.isBlank()) {
            return HttpStatus.UNAUTHORIZED;
        }

        String clean = message.toLowerCase();

        /*
         * 401 : problème d'authentification
         * - clé manquante
         * - clé invalide
         * - email obligatoire absent
         */
        if (clean.contains("api key")
                || clean.contains("clé")
                || clean.contains("manquante")
                || clean.contains("invalide")
                || clean.contains("x-user-email est obligatoire")
                || clean.contains("user email")
                || clean.contains("email is required")) {
            return HttpStatus.UNAUTHORIZED;
        }

        /*
         * 403 : utilisateur authentifié mais non autorisé
         * - modèle privé
         * - utilisateur non autorisé
         * - modèle non public/partagé
         */
        if (clean.contains("pas autorisé")
                || clean.contains("non autorisé")
                || clean.contains("not authorized")
                || clean.contains("forbidden")
                || clean.contains("privé")
                || clean.contains("private")
                || clean.contains("shared")
                || clean.contains("partagé")
                || clean.contains("n'est pas exposé")) {
            return HttpStatus.FORBIDDEN;
        }

        return HttpStatus.UNAUTHORIZED;
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