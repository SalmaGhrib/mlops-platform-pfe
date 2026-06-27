package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MlflowRegistryClientService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.mlflow.tracking-url:http://127.0.0.1:5000}")
    private String mlflowTrackingUrl;

    @Value("${app.mlflow.production-alias:production}")
    private String productionAlias;

    public MlflowRegistryClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Rôle :
     * Récupérer la version actuellement en production pour un modèle MLflow.
     *
     * Méthode 1 : chercher par alias MLflow.
     * Méthode 2 : fallback par recherche des versions.
     */
    public ProductionModelInfo getProductionVersion(String registeredModelName) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return null;
        }

        ProductionModelInfo byAlias = getProductionVersionByAlias(registeredModelName);

        if (byAlias != null) {
            return byAlias;
        }

        return getProductionVersionBySearch(registeredModelName);
    }

    /**
     * Rôle :
     * Définir l'alias "production" sur une version précise d'un modèle MLflow.
     *
     * Correction importante :
     * MLflow exige un body JSON contenant :
     * {
     *   "name": "...",
     *   "alias": "production",
     *   "version": "..."
     * }
     */
    public void setProductionAlias(String registeredModelName, String version) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new RuntimeException("registeredModelName is required");
        }

        if (version == null || version.isBlank()) {
            throw new RuntimeException("version is required");
        }

        try {
            String cleanName = registeredModelName.trim();
            String cleanVersion = version.trim();
            String cleanAlias = normalizeAlias(productionAlias);

            String baseUrl = normalizeBaseUrl(mlflowTrackingUrl);
            String url = baseUrl + "/api/2.0/mlflow/registered-models/alias";

            Map<String, String> body = new HashMap<>();
            body.put("name", cleanName);
            body.put("alias", cleanAlias);
            body.put("version", cleanVersion);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(body, headers);

            System.out.println("===== SET MLFLOW PRODUCTION ALIAS =====");
            System.out.println("URL = " + url);
            System.out.println("name = " + cleanName);
            System.out.println("alias = " + cleanAlias);
            System.out.println("version = " + cleanVersion);
            System.out.println("body = " + objectMapper.writeValueAsString(body));

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            System.out.println("MLflow set alias status = " + response.getStatusCode());
            System.out.println("MLflow set alias body = " + response.getBody());

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException(
                        "MLflow returned non-success status: " + response.getStatusCode()
                );
            }

        } catch (RestClientResponseException e) {
            throw new RuntimeException(
                    "Failed to set MLflow production alias. HTTP "
                            + e.getRawStatusCode()
                            + " - "
                            + e.getResponseBodyAsString(),
                    e
            );

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to set MLflow production alias: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Rôle :
     * Récupérer la version production directement par l'alias MLflow.
     */
    private ProductionModelInfo getProductionVersionByAlias(String registeredModelName) {
        try {
            String cleanName = registeredModelName.trim();
            String cleanAlias = normalizeAlias(productionAlias);
            String baseUrl = normalizeBaseUrl(mlflowTrackingUrl);

            String url = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + "/api/2.0/mlflow/registered-models/alias")
                    .queryParam("name", cleanName)
                    .queryParam("alias", cleanAlias)
                    .build()
                    .encode()
                    .toUriString();

            System.out.println("===== GET MLFLOW PRODUCTION ALIAS =====");
            System.out.println("URL = " + url);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    String.class
            );

            String responseBody = response.getBody();

            System.out.println("MLflow get alias status = " + response.getStatusCode());
            System.out.println("MLflow get alias body = " + responseBody);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }

            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode modelVersion = root.path("model_version");

            if (modelVersion.isMissingNode() || modelVersion.isNull()) {
                return null;
            }

            return mapModelVersionNode(modelVersion, cleanAlias);

        } catch (RestClientResponseException e) {
            System.out.println("No production alias found by alias. HTTP "
                    + e.getRawStatusCode()
                    + " - "
                    + e.getResponseBodyAsString());
            return null;

        } catch (Exception e) {
            System.out.println("Error getProductionVersionByAlias: " + e.getMessage());
            return null;
        }
    }

    /**
     * Rôle :
     * Fallback si l'endpoint alias ne retourne rien.
     * On cherche toutes les versions du modèle et on détecte celle qui a :
     * - alias production
     * - ou current_stage = Production
     */
    private ProductionModelInfo getProductionVersionBySearch(String registeredModelName) {
        try {
            String cleanName = registeredModelName.trim();
            String cleanAlias = normalizeAlias(productionAlias);
            String baseUrl = normalizeBaseUrl(mlflowTrackingUrl);

            String filter = "name = '" + escapeMlflowFilterValue(cleanName) + "'";

            String url = baseUrl
                    + "/api/2.0/mlflow/model-versions/search?filter="
                    + URLEncoder.encode(filter, StandardCharsets.UTF_8);

            System.out.println("===== SEARCH MLFLOW PRODUCTION VERSION =====");
            System.out.println("filter = " + filter);
            System.out.println("URL = " + url);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    String.class
            );

            String responseBody = response.getBody();

            System.out.println("MLflow search versions status = " + response.getStatusCode());
            System.out.println("MLflow search versions body = " + responseBody);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }

            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode versions = root.path("model_versions");

            if (!versions.isArray()) {
                return null;
            }

            for (JsonNode versionNode : versions) {
                if (hasProductionAlias(versionNode, cleanAlias)) {
                    return mapModelVersionNode(versionNode, cleanAlias);
                }
            }

            return null;

        } catch (Exception e) {
            System.out.println("Error getProductionVersionBySearch: " + e.getMessage());
            return null;
        }
    }

    /**
     * Rôle :
     * Vérifier si une version possède l'alias production.
     */
    private boolean hasProductionAlias(JsonNode versionNode, String alias) {
        if (versionNode == null || versionNode.isNull() || versionNode.isMissingNode()) {
            return false;
        }

        JsonNode aliases = versionNode.path("aliases");

        if (aliases.isArray()) {
            for (JsonNode item : aliases) {
                if (alias.equalsIgnoreCase(item.asText())) {
                    return true;
                }
            }
        }

        String currentStage = getText(versionNode, "current_stage");

        return currentStage != null && currentStage.equalsIgnoreCase("Production");
    }

    /**
     * Rôle :
     * Convertir un nœud JSON MLflow model_version vers ProductionModelInfo.
     */
    private ProductionModelInfo mapModelVersionNode(JsonNode modelVersion, String alias) {
        String name = getText(modelVersion, "name");
        String version = getText(modelVersion, "version");
        String status = getText(modelVersion, "status");
        String runId = getText(modelVersion, "run_id");
        String source = getText(modelVersion, "source");
        String currentStage = getText(modelVersion, "current_stage");

        String creationTimestamp = convertTimestamp(getText(modelVersion, "creation_timestamp"));
        String lastUpdatedTimestamp = convertTimestamp(getText(modelVersion, "last_updated_timestamp"));

        return new ProductionModelInfo(
                name,
                version,
                alias,
                status,
                currentStage,
                runId,
                source,
                creationTimestamp,
                lastUpdatedTimestamp
        );
    }

    /**
     * Rôle :
     * Nettoyer l'URL de base MLflow.
     */
    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:5000";
        }

        String cleanUrl = url.trim();

        if (cleanUrl.endsWith("/")) {
            return cleanUrl.substring(0, cleanUrl.length() - 1);
        }

        return cleanUrl;
    }

    /**
     * Rôle :
     * Normaliser l'alias MLflow.
     */
    private String normalizeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return "production";
        }

        return alias.trim();
    }

    /**
     * Rôle :
     * Récupérer un champ texte depuis un JsonNode.
     */
    private String getText(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        JsonNode valueNode = node.path(fieldName);

        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }

        String value = valueNode.asText();

        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }

    /**
     * Rôle :
     * Convertir un timestamp MLflow en LocalDateTime texte.
     */
    private String convertTimestamp(String timestampValue) {
        if (timestampValue == null || timestampValue.isBlank()) {
            return null;
        }

        try {
            long timestamp = Long.parseLong(timestampValue);

            return LocalDateTime
                    .ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                    .toString();

        } catch (Exception e) {
            return timestampValue;
        }
    }

    /**
     * Rôle :
     * Échapper les apostrophes dans un filtre MLflow.
     */
    private String escapeMlflowFilterValue(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("'", "\\'");
    }

    public static class ProductionModelInfo {

        private final String registeredModelName;
        private final String version;
        private final String alias;
        private final String mlflowStatus;
        private final String currentStage;
        private final String runId;
        private final String source;
        private final String createdAt;
        private final String updatedAt;

        public ProductionModelInfo(
                String registeredModelName,
                String version,
                String alias,
                String mlflowStatus,
                String currentStage,
                String runId,
                String source,
                String createdAt,
                String updatedAt
        ) {
            this.registeredModelName = registeredModelName;
            this.version = version;
            this.alias = alias;
            this.mlflowStatus = mlflowStatus;
            this.currentStage = currentStage;
            this.runId = runId;
            this.source = source;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public String getRegisteredModelName() {
            return registeredModelName;
        }

        public String getVersion() {
            return version;
        }

        public String getAlias() {
            return alias;
        }

        public String getMlflowStatus() {
            return mlflowStatus;
        }

        public String getCurrentStage() {
            return currentStage;
        }

        public String getRunId() {
            return runId;
        }

        public String getSource() {
            return source;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }
}