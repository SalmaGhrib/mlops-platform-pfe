package com.mlops.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.dto.PredictionRequestDto;
import com.mlops.platform.dto.PredictionResponseDto;
import com.mlops.platform.dto.ProductionModelDto;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PredictionService {

    private final RestTemplate restTemplate;
    private final MlExperimentRepository mlExperimentRepository;
    private final UserRepository userRepository;
    private final ModelDeploymentService modelDeploymentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /*
     * Fallback uniquement.
     * Avant, toute prédiction partait vers custom.predict.api.url.
     * Maintenant, on utilise d'abord le predictionEndpoint réel du deployment sélectionné.
     */
    @Value("${custom.predict.api.url:http://localhost:8000}")
    private String customPredictApiUrl;

    public PredictionService(
            RestTemplate restTemplate,
            MlExperimentRepository mlExperimentRepository,
            UserRepository userRepository,
            ModelDeploymentService modelDeploymentService
    ) {
        this.restTemplate = restTemplate;
        this.mlExperimentRepository = mlExperimentRepository;
        this.userRepository = userRepository;
        this.modelDeploymentService = modelDeploymentService;
    }

    public PredictionResponseDto predict(
            PredictionRequestDto request,
            Principal principal,
            String authorizationHeader
    ) {
        validateRequest(request);

        String modelAlias = extractModelAliasFromRequest(request);

        ModelDeploymentResponse deployment = findVisiblePredictableDeploymentOrThrow(
                request.getModelName(),
                principal,
                authorizationHeader
        );

        String predictionEndpoint = extractPredictionEndpointOrFallback(deployment, "/predict");
        String url = ensureEndpointRoute(predictionEndpoint, "/predict");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", request.getRows());
        payload.put("model_name", request.getModelName());
        payload.put("model_alias", modelAlias);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            System.out.println("[PREDICTION] Calling model endpoint: " + url);
            System.out.println("[PREDICTION] modelName=" + request.getModelName() + ", alias=" + modelAlias);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            return mapFastApiResponseToDto(response.getBody());

        } catch (HttpStatusCodeException e) {
            throw new RuntimeException(
                    "Prediction API error: HTTP " + e.getStatusCode()
                            + " - endpoint=" + url
                            + " - body=" + e.getResponseBodyAsString(),
                    e
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Prediction failed against endpoint " + url + ": " + e.getMessage(),
                    e
            );
        }
    }

    public PredictionResponseDto predictImage(
            String modelName,
            String modelAlias,
            MultipartFile imageFile,
            Principal principal,
            String authorizationHeader
    ) {
        validateImagePredictionRequest(modelName, imageFile);

        String cleanAlias = normalizeAlias(modelAlias);

        ModelDeploymentResponse deployment = findVisiblePredictableDeploymentOrThrow(
                modelName,
                principal,
                authorizationHeader
        );

        String predictionEndpoint = extractPredictionEndpointOrFallback(deployment, "/predict-image");
        String url = ensureEndpointRoute(predictionEndpoint, "/predict-image");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        body.add("model_name", modelName);
        body.add("model_alias", cleanAlias);
        body.add("image_file", buildImageResource(imageFile));

        HttpEntity<MultiValueMap<String, Object>> entity =
                new HttpEntity<>(body, headers);

        try {
            System.out.println("[PREDICTION_IMAGE] Calling model endpoint: " + url);
            System.out.println("[PREDICTION_IMAGE] modelName=" + modelName + ", alias=" + cleanAlias);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            return mapFastApiResponseToDto(response.getBody());

        } catch (HttpStatusCodeException e) {
            throw new RuntimeException(
                    "Image prediction API error: HTTP " + e.getStatusCode()
                            + " - endpoint=" + url
                            + " - body=" + e.getResponseBodyAsString(),
                    e
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Image prediction failed against endpoint " + url + ": " + e.getMessage(),
                    e
            );
        }
    }

    public PredictionResponseDto predictText(
            String modelName,
            String modelAlias,
            String text,
            Principal principal,
            String authorizationHeader
    ) {
        validateTextPredictionRequest(modelName, text);

        String cleanAlias = normalizeAlias(modelAlias);

        ModelDeploymentResponse deployment = findVisiblePredictableDeploymentOrThrow(
                modelName,
                principal,
                authorizationHeader
        );

        String predictionEndpoint = extractPredictionEndpointOrFallback(deployment, "/predict-text");
        String url = ensureEndpointRoute(predictionEndpoint, "/predict-text");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_name", modelName);
        payload.put("model_alias", cleanAlias);
        payload.put("text", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            System.out.println("[PREDICTION_TEXT] Calling model endpoint: " + url);
            System.out.println("[PREDICTION_TEXT] modelName=" + modelName + ", alias=" + cleanAlias);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            return mapFastApiResponseToDto(response.getBody());

        } catch (HttpStatusCodeException e) {
            throw new RuntimeException(
                    "Text prediction API error: HTTP " + e.getStatusCode()
                            + " - endpoint=" + url
                            + " - body=" + e.getResponseBodyAsString(),
                    e
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Text prediction failed against endpoint " + url + ": " + e.getMessage(),
                    e
            );
        }
    }

    public List<ProductionModelDto> getMyProductionModels(
            Principal principal,
            String authorizationHeader
    ) {
        User connectedUser = getConnectedUser(principal, authorizationHeader);

        List<ModelDeploymentResponse> visibleDeployments =
                modelDeploymentService.getVisibleDeploymentsForUser(connectedUser);

        LinkedHashMap<String, ProductionModelDto> uniqueModels = new LinkedHashMap<>();

        for (ModelDeploymentResponse deployment : visibleDeployments) {
            if (deployment == null) {
                continue;
            }

            String registeredModelName = extractStringValue(
                    deployment,
                    "getRegisteredModelName",
                    "getModelName"
            );

            if (registeredModelName == null || registeredModelName.isBlank()) {
                continue;
            }

            if (uniqueModels.containsKey(registeredModelName)) {
                continue;
            }

            String productionVersion = firstNonBlankString(
                    extractStringValue(deployment, "getModelVersion"),
                    extractStringValue(deployment, "getProductionVersion")
            );

            if (productionVersion == null || productionVersion.isBlank()) {
                productionVersion = extractStringValue(deployment, "getMlflowProductionVersion");
            }

            if (productionVersion == null || productionVersion.isBlank()) {
                productionVersion = "-";
            }

            Long experimentId = extractLongValue(
                    deployment,
                    "getExperimentId"
            );

            Long projectId = extractLongValue(
                    deployment,
                    "getProjectId"
            );

            String projectName = firstNonBlankString(
                    extractStringValue(deployment, "getProjectName"),
                    projectId != null ? "Project " + projectId : "-"
            );

            String predictionEndpoint = extractStringValue(
                    deployment,
                    "getPredictionEndpoint"
            );

            String dataType = resolveProductionDataType(deployment, predictionEndpoint);

            String projectType = firstNonBlankString(
                    extractStringValue(deployment, "getProjectType"),
                    "classification"
            );

            Double globalScore = extractDoubleValue(
                    deployment,
                    "getGlobalScore",
                    "getScoreGlobal"
            );

            String primaryMetric = extractStringValue(
                    deployment,
                    "getPrimaryMetric"
            );

            Double primaryMetricValue = extractDoubleValue(
                    deployment,
                    "getPrimaryMetricValue"
            );

            ProductionModelDto dto = new ProductionModelDto(
                    experimentId,
                    projectId,
                    projectName,
                    dataType,
                    projectType,
                    registeredModelName,
                    productionVersion,
                    globalScore,
                    primaryMetric,
                    primaryMetricValue
            );

            uniqueModels.put(registeredModelName, dto);
        }

        return new ArrayList<>(uniqueModels.values());
    }

    private String resolveProductionDataType(ModelDeploymentResponse deployment, String predictionEndpoint) {
        String endpoint = predictionEndpoint == null ? "" : predictionEndpoint.trim().toLowerCase();

        String rawType = firstNonBlankString(
                extractStringValue(deployment, "getDataType"),
                extractStringValue(deployment, "getModelDataType")
        );

        rawType = firstNonBlankString(
                rawType,
                extractStringValue(deployment, "getInputType")
        );

        rawType = firstNonBlankString(
                rawType,
                extractStringValue(deployment, "getProjectDataType")
        );

        String normalizedRawType = rawType == null ? "" : rawType.trim().toUpperCase();

        if (normalizedRawType.contains("IMAGE") || normalizedRawType.contains("IMG")
                || endpoint.contains("/predict-image")) {
            return "IMAGE";
        }

        if (normalizedRawType.contains("TEXT") || normalizedRawType.contains("NLP")
                || endpoint.contains("/predict-text")) {
            return "TEXT";
        }

        return "TABULAR";
    }

    private PredictionResponseDto mapFastApiResponseToDto(Map<String, Object> body) {
        if (body == null) {
            throw new RuntimeException("Empty response from prediction API.");
        }

        PredictionResponseDto dto = new PredictionResponseDto();

        dto.setModelUri(
                firstNonBlankString(
                        getString(body, "model_uri"),
                        getString(body, "modelUri")
                )
        );

        dto.setModelName(
                firstNonBlankString(
                        getString(body, "model_name"),
                        getString(body, "modelName")
                )
        );

        dto.setModelAlias(
                firstNonBlankString(
                        getString(body, "model_alias"),
                        getString(body, "modelAlias")
                )
        );

        dto.setRowsCount(
                firstNonNullInteger(
                        getInteger(body, "rows_count"),
                        getInteger(body, "rowsCount")
                )
        );

        dto.setPredictions(getList(body, "predictions"));
        dto.setRawResponse(body);

        return dto;
    }

    private ByteArrayResource buildImageResource(MultipartFile imageFile) {
        try {
            byte[] bytes = imageFile.getBytes();
            String originalFilename = imageFile.getOriginalFilename();

            return new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    if (originalFilename == null || originalFilename.isBlank()) {
                        return "uploaded-image.png";
                    }
                    return originalFilename;
                }
            };

        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded image: " + e.getMessage(), e);
        }
    }

    private ModelDeploymentResponse findVisiblePredictableDeploymentOrThrow(
            String modelName,
            Principal principal,
            String authorizationHeader
    ) {
        validateModelName(modelName);

        User connectedUser = getConnectedUser(principal, authorizationHeader);

        List<ModelDeploymentResponse> visibleDeployments =
                modelDeploymentService.getVisibleDeploymentsForUser(connectedUser);

        ModelDeploymentResponse bestMatch = null;

        for (ModelDeploymentResponse deployment : visibleDeployments) {
            if (deployment == null) {
                continue;
            }

            String registeredModelName = extractStringValue(
                    deployment,
                    "getRegisteredModelName",
                    "getModelName"
            );

            if (registeredModelName == null || registeredModelName.isBlank()) {
                continue;
            }

            if (!registeredModelName.trim().equalsIgnoreCase(modelName.trim())) {
                continue;
            }

            String deploymentStatus = extractStringValue(
                    deployment,
                    "getDeploymentStatus"
            );

            Boolean active = extractBooleanValue(
                    deployment,
                    "getActive",
                    "isActive"
            );

            String predictionEndpoint = extractStringValue(
                    deployment,
                    "getPredictionEndpoint"
            );

            boolean deployed =
                    deploymentStatus != null
                            && deploymentStatus.trim().equalsIgnoreCase("DEPLOYED");

            boolean endpointAvailable = isUsableEndpoint(predictionEndpoint);

            if (deployed && Boolean.TRUE.equals(active) && endpointAvailable) {
                bestMatch = deployment;
                break;
            }
        }

        if (bestMatch == null) {
            throw new SecurityException(
                    "You are not allowed to predict with this model, or the model is not deployed/active."
            );
        }

        return bestMatch;
    }

    private String extractPredictionEndpointOrFallback(
            ModelDeploymentResponse deployment,
            String wantedRoute
    ) {
        String endpoint = extractStringValue(
                deployment,
                "getPredictionEndpoint"
        );

        if (isUsableEndpoint(endpoint)) {
            return endpoint.trim();
        }

        String fallback = normalizeApiBaseUrl(customPredictApiUrl) + normalizeRoute(wantedRoute);
        System.out.println("[PREDICTION] Deployment endpoint missing. Using fallback endpoint: " + fallback);
        return fallback;
    }

    private String ensureEndpointRoute(String endpoint, String wantedRoute) {
        String cleanEndpoint = normalizeApiBaseUrl(endpoint);
        String route = normalizeRoute(wantedRoute);

        if (route.equals("/predict")) {
            if (cleanEndpoint.endsWith("/predict")) {
                return cleanEndpoint;
            }
            if (cleanEndpoint.endsWith("/predict-image") || cleanEndpoint.endsWith("/predict-text")) {
                return replaceLastRoute(cleanEndpoint, "/predict");
            }
            return cleanEndpoint + "/predict";
        }

        if (route.equals("/predict-image")) {
            if (cleanEndpoint.endsWith("/predict-image")) {
                return cleanEndpoint;
            }
            if (cleanEndpoint.endsWith("/predict")) {
                return replaceLastRoute(cleanEndpoint, "/predict-image");
            }
            if (cleanEndpoint.endsWith("/predict-text")) {
                return replaceLastRoute(cleanEndpoint, "/predict-image");
            }
            return cleanEndpoint + "/predict-image";
        }

        if (route.equals("/predict-text")) {
            if (cleanEndpoint.endsWith("/predict-text")) {
                return cleanEndpoint;
            }
            if (cleanEndpoint.endsWith("/predict")) {
                return replaceLastRoute(cleanEndpoint, "/predict-text");
            }
            if (cleanEndpoint.endsWith("/predict-image")) {
                return replaceLastRoute(cleanEndpoint, "/predict-text");
            }
            return cleanEndpoint + "/predict-text";
        }

        if (cleanEndpoint.endsWith(route)) {
            return cleanEndpoint;
        }

        return cleanEndpoint + route;
    }

    private String replaceLastRoute(String endpoint, String newRoute) {
        if (endpoint == null || endpoint.isBlank()) {
            return normalizeRoute(newRoute);
        }

        String clean = endpoint.trim();
        int index = clean.lastIndexOf('/');

        if (index <= "http://x".length()) {
            return clean + normalizeRoute(newRoute);
        }

        return clean.substring(0, index) + normalizeRoute(newRoute);
    }

    private boolean isUsableEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }

        String clean = endpoint.trim();

        return !clean.equalsIgnoreCase("null")
                && !clean.equalsIgnoreCase("undefined")
                && !clean.equals("-");
    }

    private String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return "/predict";
        }

        String clean = route.trim();
        if (!clean.startsWith("/")) {
            clean = "/" + clean;
        }
        return clean;
    }

    private void validateImagePredictionRequest(String modelName, MultipartFile imageFile) {
        validateModelName(modelName);

        if (imageFile == null || imageFile.isEmpty()) {
            throw new IllegalArgumentException("imageFile is required.");
        }

        String fileName = imageFile.getOriginalFilename();

        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Image filename is required.");
        }

        String extension = getExtension(fileName);

        List<String> allowedExtensions = List.of(
                "jpg",
                "jpeg",
                "png",
                "bmp",
                "gif",
                "tiff",
                "tif",
                "webp"
        );

        if (!allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported image format: ." + extension
                            + ". Allowed formats: .jpg, .jpeg, .png, .bmp, .gif, .tiff, .webp"
            );
        }
    }

    private void validateTextPredictionRequest(String modelName, String text) {
        validateModelName(modelName);

        if (text == null || text.trim().isBlank()) {
            throw new IllegalArgumentException("text is required.");
        }
    }

    private void validateModelName(String modelName) {
        if (modelName == null || modelName.trim().isBlank()) {
            throw new IllegalArgumentException("modelName is required.");
        }
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');

        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(lastDot + 1).trim().toLowerCase();
    }

    private String normalizeApiBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8000";
        }

        String normalized = value.trim();

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private String normalizeAlias(String modelAlias) {
        if (modelAlias == null || modelAlias.trim().isBlank()) {
            return "production";
        }

        return modelAlias.trim();
    }

    private String extractModelAliasFromRequest(PredictionRequestDto request) {
        if (request == null) {
            return "production";
        }

        String alias = extractStringValue(
                request,
                "getModelAlias",
                "getModel_alias",
                "getAlias"
        );

        return normalizeAlias(alias);
    }

    /*
     * Ancienne méthode gardée pour compatibilité éventuelle.
     * Elle n'est plus utilisée par predict/predictImage/predictText,
     * car findVisiblePredictableDeploymentOrThrow retourne déjà le bon deployment
     * avec le vrai predictionEndpoint.
     */
    private void verifyModelIsVisibleAndPredictableForConnectedUser(
            String modelName,
            Principal principal,
            String authorizationHeader
    ) {
        findVisiblePredictableDeploymentOrThrow(modelName, principal, authorizationHeader);
    }

    /*
     * Ancienne méthode gardée pour compatibilité éventuelle.
     * Elle n'est plus utilisée par predict/predictImage/predictText,
     * car elle ne vérifie que les modèles propriétaires.
     */
    private void verifyModelBelongsToConnectedUser(
            String modelName,
            Principal principal,
            String authorizationHeader
    ) {
        User connectedUser = getConnectedUser(principal, authorizationHeader);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOwnerOrderByCreatedAtDesc(connectedUser);

        boolean allowed = experiments.stream()
                .anyMatch(exp ->
                        exp != null
                                && Boolean.TRUE.equals(exp.getProduction())
                                && exp.getRegisteredModelName() != null
                                && exp.getRegisteredModelName().equals(modelName)
                );

        if (!allowed) {
            throw new SecurityException(
                    "You are not allowed to predict with this model, or the model is not in production."
            );
        }
    }

    private User getConnectedUser(Principal principal, String authorizationHeader) {
        String email = extractEmailFromPrincipal(principal);

        if (email == null || email.isBlank()) {
            email = extractEmailFromSecurityContext();
        }

        if (email == null || email.isBlank()) {
            email = extractEmailFromAuthorizationHeader(authorizationHeader);
        }

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Connected user is required. Authorization Bearer token is missing or invalid."
            );
        }

        final String userEmail = email;

        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("Connected user not found: " + userEmail));
    }

    private String extractEmailFromPrincipal(Principal principal) {
        if (principal == null) {
            return null;
        }

        String name = principal.getName();

        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }

        return name;
    }

    private String extractEmailFromSecurityContext() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String name = authentication.getName();

        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }

        return name;
    }

    private String extractEmailFromAuthorizationHeader(String authorizationHeader) {
        try {
            if (authorizationHeader == null || authorizationHeader.isBlank()) {
                return null;
            }

            String token = authorizationHeader.trim();

            if (token.startsWith("Bearer ")) {
                token = token.substring(7).trim();
            }

            String[] parts = token.split("\\.");

            if (parts.length < 2) {
                return null;
            }

            byte[] decodedPayload =
                    Base64.getUrlDecoder().decode(parts[1]);

            String payloadJson =
                    new String(decodedPayload, StandardCharsets.UTF_8);

            Map<String, Object> payload =
                    objectMapper.readValue(
                            payloadJson,
                            new TypeReference<Map<String, Object>>() {
                            }
                    );

            Object sub = payload.get("sub");
            if (sub != null && !String.valueOf(sub).isBlank()) {
                return String.valueOf(sub);
            }

            Object email = payload.get("email");
            if (email != null && !String.valueOf(email).isBlank()) {
                return String.valueOf(email);
            }

            Object username = payload.get("username");
            if (username != null && !String.valueOf(username).isBlank()) {
                return String.valueOf(username);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private Double extractDoubleValue(Object source, String... methodNames) {
        if (source == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            try {
                Method method = source.getClass().getMethod(methodName);
                Object value = method.invoke(source);

                if (value == null) {
                    continue;
                }

                if (value instanceof Number numberValue) {
                    return numberValue.doubleValue();
                }

                return Double.parseDouble(String.valueOf(value));

            } catch (Exception ignored) {
                // On essaie le getter suivant.
            }
        }

        return null;
    }

    private Long extractLongValue(Object source, String... methodNames) {
        if (source == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            try {
                Method method = source.getClass().getMethod(methodName);
                Object value = method.invoke(source);

                if (value == null) {
                    continue;
                }

                if (value instanceof Number numberValue) {
                    return numberValue.longValue();
                }

                return Long.parseLong(String.valueOf(value));

            } catch (Exception ignored) {
                // On essaie le getter suivant.
            }
        }

        return null;
    }

    private Boolean extractBooleanValue(Object source, String... methodNames) {
        if (source == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            try {
                Method method = source.getClass().getMethod(methodName);
                Object value = method.invoke(source);

                if (value == null) {
                    continue;
                }

                if (value instanceof Boolean booleanValue) {
                    return booleanValue;
                }

                if (value instanceof Number numberValue) {
                    return numberValue.intValue() == 1;
                }

                String clean = String.valueOf(value).trim();

                if (clean.equalsIgnoreCase("true") || clean.equals("1")) {
                    return true;
                }

                if (clean.equalsIgnoreCase("false") || clean.equals("0")) {
                    return false;
                }

            } catch (Exception ignored) {
                // On essaie le getter suivant.
            }
        }

        return null;
    }

    private String extractStringValue(Object source, String... methodNames) {
        if (source == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            try {
                Method method = source.getClass().getMethod(methodName);
                Object value = method.invoke(source);

                if (value == null) {
                    continue;
                }

                String result = String.valueOf(value);

                if (!result.isBlank()) {
                    return result;
                }

            } catch (Exception ignored) {
                // On essaie le getter suivant.
            }
        }

        return null;
    }

    private void validateRequest(PredictionRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Prediction request is required.");
        }

        if (request.getRows() == null || request.getRows().isEmpty()) {
            throw new IllegalArgumentException("rows must contain at least one input row.");
        }

        if (request.getModelName() == null || request.getModelName().isBlank()) {
            throw new IllegalArgumentException("modelName is required.");
        }
    }

    private String firstNonBlankString(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        if (second != null && !second.isBlank()) {
            return second;
        }

        return null;
    }

    private Integer firstNonNullInteger(Integer first, Integer second) {
        if (first != null) {
            return first;
        }

        return second;
    }

    private String getString(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }

        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }

        Object value = map.get(key);

        if (value == null) {
            return null;
        }

        if (value instanceof Integer integerValue) {
            return integerValue;
        }

        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> getList(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }

        Object value = map.get(key);

        if (value instanceof List<?>) {
            return (List<Object>) value;
        }

        return null;
    }
}
