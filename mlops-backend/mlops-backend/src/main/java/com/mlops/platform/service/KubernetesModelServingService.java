package com.mlops.platform.service;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class KubernetesModelServingService {

    private final KubernetesClient kubernetesClient;

    @Value("${app.kubernetes.namespace:mlops}")
    private String namespace;

    @Value("${app.mlflow.tracking-uri:http://mlflow-service.mlops.svc.cluster.local:5000}")
    private String mlflowTrackingUri;

    @Value("${app.kubernetes.service-health-path:/health}")
    private String serviceHealthPath;

    @Value("${app.kubernetes.service-health-timeout-ms:2000}")
    private int serviceHealthTimeoutMs;

    public KubernetesModelServingService(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public String deployModelServer(
            String deploymentName,
            String serviceName,
            String dockerImage,
            String registeredModelName,
            String modelVersion,
            String dataType
    ) {
        return deployModelServer(
                deploymentName,
                serviceName,
                null,
                dockerImage,
                registeredModelName,
                modelVersion,
                dataType
        );
    }

    /*
     * Variante blue/green : le paramètre servingTrack ajoute le label
     * serving-track=blue|green sur les pods. Le Service stable du modèle
     * (ensureStableService) route le trafic vers le track actif.
     */
    public String deployModelServer(
            String deploymentName,
            String serviceName,
            String servingTrack,
            String dockerImage,
            String registeredModelName,
            String modelVersion,
            String dataType
    ) {
        if (deploymentName == null || deploymentName.isBlank()) {
            throw new IllegalArgumentException("deploymentName est obligatoire.");
        }

        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName est obligatoire.");
        }

        if (dockerImage == null || dockerImage.isBlank()) {
            throw new IllegalArgumentException("dockerImage est obligatoire.");
        }

        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new IllegalArgumentException("registeredModelName est obligatoire.");
        }

        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion est obligatoire.");
        }

        String cleanDeploymentName = deploymentName.trim();
        String cleanServiceName = serviceName.trim();
        String cleanDockerImage = dockerImage.trim();
        String cleanRegisteredModelName = registeredModelName.trim();
        String cleanModelVersion = modelVersion.trim();
        String cleanDataType = normalizeDataType(dataType);
        String cleanServingTrack = normalizeTrack(servingTrack);

        /*
         * Important pour le blue/green :
         *
         * Chaque deployment Kubernetes doit charger une version exacte :
         * models:/modelName/version
         *
         * Il ne faut pas utiliser :
         * models:/modelName@production
         *
         * Pourquoi ?
         * Si v2 est encore active et que l'alias production passe à v3,
         * un redémarrage du pod v2 pourrait charger v3 par erreur.
         *
         * Avec une version exacte :
         * - deployment v2 charge toujours v2
         * - deployment v3 charge toujours v3
         * - le switch blue/green se fait côté DB/API après readiness Kubernetes.
         */
        String modelUri = "models:/" + cleanRegisteredModelName + "/" + cleanModelVersion;

        Map<String, String> labels = new HashMap<>();
        labels.put("app", cleanDeploymentName);
        labels.put("component", "mlops-model-server");
        labels.put("model", sanitizeLabelValue(cleanRegisteredModelName));
        labels.put("model-version", sanitizeLabelValue("v" + cleanModelVersion));
        labels.put("serving-track", cleanServingTrack);

        var container = new ContainerBuilder()
                .withName("model-server")
                .withImage(cleanDockerImage)
                .withImagePullPolicy("Always")
                .withPorts(new ContainerPortBuilder()
                        .withContainerPort(8000)
                        .build())
                .withEnv(
                        new EnvVarBuilder()
                                .withName("MODEL_URI")
                                .withValue(modelUri)
                                .build(),
                        new EnvVarBuilder()
                                .withName("MODEL_DATA_TYPE")
                                .withValue(cleanDataType)
                                .build(),
                        new EnvVarBuilder()
                                .withName("MLFLOW_TRACKING_URI")
                                .withValue(mlflowTrackingUri)
                                .build()
                )
                .build();

        var deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(cleanDeploymentName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector()
                        .withMatchLabels(Map.of("app", cleanDeploymentName))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(container)
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        /*
         * createOrReplace :
         * - crée si le Deployment n'existe pas
         * - remplace/met à jour s'il existe déjà
         *
         * Cela évite l'erreur Kubernetes AlreadyExists.
         * Et surtout, on ne touche pas aux anciennes versions :
         * v2 peut rester active pendant qu'on crée/remplace v3.
         */
        kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .resource(deployment)
                .createOrReplace();

        var service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(cleanServiceName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of("app", cleanDeploymentName))
                    .withPorts(new ServicePortBuilder()
                            .withName("http")
                            .withPort(8000)
                            .withTargetPort(new IntOrString(8000))
                            .build())
                .endSpec()
                .build();

        kubernetesClient.services()
                .inNamespace(namespace)
                .resource(service)
                .createOrReplace();

        System.out.println("[K8S_SERVING] createOrReplace completed. "
                + "deployment=" + cleanDeploymentName
                + ", service=" + cleanServiceName
                + ", image=" + cleanDockerImage
                + ", modelUri=" + modelUri
                + ", dataType=" + cleanDataType);

        return "http://" + cleanServiceName + "." + namespace + ".svc.cluster.local:8000";
    }

    /*
     * Blue/green : crée le Service Kubernetes stable du modèle
     * (ex. detection-spam-service) si nécessaire, OU le laisse inchangé
     * s'il existe déjà (aucune bascule de trafic ici : la bascule est
     * volontaire et se fait via switchStableServiceTrack après readiness).
     *
     * Le Service stable sélectionne les pods par model + serving-track.
     */
    public void ensureStableService(
            String stableServiceName,
            String registeredModelName,
            String targetTrack
    ) {
        if (stableServiceName == null || stableServiceName.isBlank()
                || registeredModelName == null || registeredModelName.isBlank()) {
            return;
        }

        String cleanServiceName = stableServiceName.trim();
        String modelLabel = sanitizeLabelValue(registeredModelName.trim());
        String track = normalizeTrack(targetTrack);

        Map<String, String> stableLabels = new HashMap<>();
        stableLabels.put("component", "mlops-model-stable-service");
        stableLabels.put("model", modelLabel);

        io.fabric8.kubernetes.api.model.Service existing = kubernetesClient.services()
                .inNamespace(namespace)
                .withName(cleanServiceName)
                .get();

        if (existing != null) {
            System.out.println("[BLUE_GREEN_START] Stable service already exists. "
                    + "Traffic stays on current track. "
                    + "service=" + cleanServiceName
                    + ", model=" + registeredModelName.trim()
                    + ", targetTrack=" + track);
            return;
        }

        var stableService = new ServiceBuilder()
                .withNewMetadata()
                    .withName(cleanServiceName)
                    .withNamespace(namespace)
                    .withLabels(stableLabels)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSelector(Map.of(
                            "model", modelLabel,
                            "serving-track", track
                    ))
                    .withPorts(new ServicePortBuilder()
                            .withName("http")
                            .withPort(8000)
                            .withTargetPort(new IntOrString(8000))
                            .build())
                .endSpec()
                .build();

        kubernetesClient.services()
                .inNamespace(namespace)
                .resource(stableService)
                .createOrReplace();

        System.out.println("[BLUE_GREEN_START] Stable service created. "
                + "service=" + cleanServiceName
                + ", model=" + registeredModelName.trim()
                + ", selectorTrack=" + track);
    }

    /*
     * Blue/green : bascule atomique du trafic du modèle vers le track cible.
     * Le selector du Service stable passe de serving-track=X à serving-track=Y.
     * Aucun redémarrage de pod : les pods des deux versions restent en place.
     */
    public void switchStableServiceTrack(
            String stableServiceName,
            String registeredModelName,
            String targetTrack
    ) {
        if (stableServiceName == null || stableServiceName.isBlank()
                || registeredModelName == null || registeredModelName.isBlank()) {
            System.out.println("[TRAFFIC_SWITCH] Skipped: missing stableServiceName/registeredModelName.");
            return;
        }

        String cleanServiceName = stableServiceName.trim();
        String modelLabel = sanitizeLabelValue(registeredModelName.trim());
        String track = normalizeTrack(targetTrack);

        io.fabric8.kubernetes.api.model.Service existing = kubernetesClient.services()
                .inNamespace(namespace)
                .withName(cleanServiceName)
                .get();

        if (existing == null) {
            System.out.println("[TRAFFIC_SWITCH] Stable service not found. "
                    + "Creating it on track " + track + ". "
                    + "service=" + cleanServiceName);
            ensureStableService(cleanServiceName, registeredModelName.trim(), track);
            return;
        }

        io.fabric8.kubernetes.api.model.Service updated = new ServiceBuilder(existing)
                .editSpec()
                    .withSelector(Map.of(
                            "model", modelLabel,
                            "serving-track", track
                    ))
                .endSpec()
                .build();

        kubernetesClient.services()
                .inNamespace(namespace)
                .resource(updated)
                .createOrReplace();

        System.out.println("[TRAFFIC_SWITCH] Stable service selector switched to track="
                + track
                + ". service=" + cleanServiceName
                + ", model=" + registeredModelName.trim());
    }

    /*
     * Blue/green : smoke test de prédiction avant bascule du trafic.
     *
     * Règle : toute réponse HTTP du serveur (2xx, 3xx, 4xx, 5xx) prouve que
     * le routage fonctionne et que le serveur répond. Seule une erreur de
     * connexion/timeout est considérée comme un échec (serveur pas prêt).
     */
    public boolean probePredictionEndpoint(String predictionEndpoint) {
        if (predictionEndpoint == null || predictionEndpoint.isBlank()) {
            return false;
        }

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(serviceHealthTimeoutMs);
            requestFactory.setReadTimeout(serviceHealthTimeoutMs);

            RestTemplate probeTemplate = new RestTemplate(requestFactory);
            String url = normalizeBaseUrl(predictionEndpoint);

            probeTemplate.postForEntity(
                    url,
                    new HashMap<String, Object>(),
                    String.class
            );

            System.out.println("[BLUE_GREEN_SMOKE] Prediction endpoint responded. url=" + url);
            return true;

        } catch (HttpStatusCodeException e) {
            /*
             * 4xx/5xx = le serveur répond (routing OK), le body n'est pas
             * celui attendu. On considère la vérification comme réussie.
             */
            System.out.println("[BLUE_GREEN_SMOKE] Prediction endpoint responded with HTTP "
                    + e.getStatusCode().value() + ". url="
                    + normalizeBaseUrl(predictionEndpoint));
            return true;

        } catch (ResourceAccessException e) {
            System.out.println("[BLUE_GREEN_SMOKE] Prediction endpoint unreachable. url="
                    + normalizeBaseUrl(predictionEndpoint)
                    + ", reason=" + e.getMessage());
            return false;

        } catch (Exception e) {
            System.out.println("[BLUE_GREEN_SMOKE] Prediction endpoint probe failed. url="
                    + normalizeBaseUrl(predictionEndpoint)
                    + ", reason=" + e.getMessage());
            return false;
        }
    }

    public void deleteModelServer(String deploymentName, String serviceName) {
        if (deploymentName != null && !deploymentName.isBlank()) {
            try {
                kubernetesClient.apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(deploymentName.trim())
                        .delete();

                System.out.println("[K8S_SERVING] Deleted deployment: " + deploymentName.trim());

            } catch (Exception e) {
                System.out.println("[K8S_SERVING] Failed to delete deployment "
                        + deploymentName
                        + " : "
                        + e.getMessage());
            }
        }

        if (serviceName != null && !serviceName.isBlank()) {
            try {
                kubernetesClient.services()
                        .inNamespace(namespace)
                        .withName(serviceName.trim())
                        .delete();

                System.out.println("[K8S_SERVING] Deleted service: " + serviceName.trim());

            } catch (Exception e) {
                System.out.println("[K8S_SERVING] Failed to delete service "
                        + serviceName
                        + " : "
                        + e.getMessage());
            }
        }
    }

    public boolean isModelServerReady(String deploymentName, String serviceName) {
        if (deploymentName == null || deploymentName.isBlank()
                || serviceName == null || serviceName.isBlank()) {
            return false;
        }

        String cleanDeploymentName = deploymentName.trim();
        String cleanServiceName = serviceName.trim();

        try {
            var deployment = kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(cleanDeploymentName)
                    .get();

            if (deployment == null) {
                System.out.println("[K8S_SERVING] Deployment not found: " + cleanDeploymentName);
                return false;
            }

            Integer replicas = deployment.getSpec() != null
                    ? deployment.getSpec().getReplicas()
                    : null;

            Integer readyReplicas = deployment.getStatus() != null
                    ? deployment.getStatus().getReadyReplicas()
                    : null;

            Integer availableReplicas = deployment.getStatus() != null
                    ? deployment.getStatus().getAvailableReplicas()
                    : null;

            if (replicas == null || replicas < 1) {
                replicas = 1;
            }

            boolean deploymentReady =
                    readyReplicas != null
                            && availableReplicas != null
                            && readyReplicas >= replicas
                            && availableReplicas >= replicas;

            var service = kubernetesClient.services()
                    .inNamespace(namespace)
                    .withName(cleanServiceName)
                    .get();

            boolean serviceExists = service != null;
            String serviceBaseUrl = buildServiceBaseUrl(cleanServiceName);
            boolean healthyEndpoint = serviceExists && isServiceHealthy(serviceBaseUrl);
            boolean serviceEndpointsReady = serviceExists && isServiceEndpointReady(cleanServiceName);

            System.out.println("[K8S_SERVING] Readiness check: deployment="
                    + cleanDeploymentName
                    + ", readyReplicas="
                    + readyReplicas
                    + ", availableReplicas="
                    + availableReplicas
                    + ", replicas="
                    + replicas
                    + ", serviceExists="
                    + serviceExists
                    + ", serviceEndpointsReady="
                    + serviceEndpointsReady
                    + ", healthyEndpoint="
                    + healthyEndpoint);

            return (deploymentReady && serviceExists && serviceEndpointsReady) || healthyEndpoint;

        } catch (Exception e) {
            System.out.println("[K8S_SERVING] Readiness check failed for deployment="
                    + cleanDeploymentName
                    + ", service="
                    + cleanServiceName
                    + ", reason="
                    + e.getMessage());

            return false;
        }
    }

    private String normalizeDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "TABULAR";
        }

        String clean = dataType.trim().toUpperCase();

        if (clean.equals("TEXT") || clean.equals("NLP")) {
            return "TEXT";
        }

        if (clean.equals("IMAGE")) {
            return "IMAGE";
        }

        return "TABULAR";
    }

    private String normalizeTrack(String track) {
        if (track != null && "green".equalsIgnoreCase(track.trim())) {
            return "green";
        }
        return "blue";
    }

    private String sanitizeLabelValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        String clean = value.trim()
                .toLowerCase()
                .replace("_", "-")
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-", "")
                .replaceAll("-$", "");

        if (clean.isBlank()) {
            return "unknown";
        }

        if (clean.length() > 63) {
            clean = clean.substring(0, 63);
            clean = clean.replaceAll("-$", "");
        }

        return clean.isBlank() ? "unknown" : clean;
    }

    public boolean modelServerResourcesExist(String deploymentName, String serviceName) {
    if (deploymentName == null || deploymentName.isBlank()
            || serviceName == null || serviceName.isBlank()) {
        return false;
    }

    String cleanDeploymentName = deploymentName.trim();
    String cleanServiceName = serviceName.trim();

    try {
        var deployment = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(cleanDeploymentName)
                .get();

        var service = kubernetesClient.services()
                .inNamespace(namespace)
                .withName(cleanServiceName)
                .get();

        var endpoints = kubernetesClient.endpoints()
                .inNamespace(namespace)
                .withName(cleanServiceName)
                .get();

        boolean deploymentExists = deployment != null;
        boolean serviceExists = service != null;
        boolean endpointsExist = endpoints != null
                && endpoints.getSubsets() != null
                && !endpoints.getSubsets().isEmpty();

        System.out.println("[K8S_SERVING] Resources existence check: deployment="
                + cleanDeploymentName
                + ", deploymentExists="
                + deploymentExists
                + ", service="
                + cleanServiceName
                + ", serviceExists="
                + serviceExists
                + ", endpointsExist="
                + endpointsExist);

        return deploymentExists && serviceExists && endpointsExist;

    } catch (Exception e) {
        System.out.println("[K8S_SERVING] Resources existence check failed: deployment="
                + cleanDeploymentName
                + ", service="
                + cleanServiceName
                + ", reason="
                + e.getMessage());

        return false;
    }
}

    private String buildServiceBaseUrl(String cleanServiceName) {
        return "http://" + cleanServiceName + "." + namespace + ".svc.cluster.local:8000";
    }

    private boolean isServiceEndpointReady(String cleanServiceName) {
        if (cleanServiceName == null || cleanServiceName.isBlank()) {
            return false;
        }

        try {
            Endpoints endpoints = kubernetesClient.endpoints()
                    .inNamespace(namespace)
                    .withName(cleanServiceName.trim())
                    .get();

            boolean ready = endpoints != null
                    && endpoints.getSubsets() != null
                    && !endpoints.getSubsets().isEmpty();

            System.out.println("[K8S_SERVING] Endpoint readiness check: service="
                    + cleanServiceName
                    + ", ready="
                    + ready);

            return ready;
        } catch (Exception e) {
            System.out.println("[K8S_SERVING] Endpoint readiness check failed: service="
                    + cleanServiceName
                    + ", reason="
                    + e.getMessage());
            return false;
        }
    }

    private boolean isServiceHealthy(String serviceBaseUrl) {
        if (serviceBaseUrl == null || serviceBaseUrl.isBlank()) {
            return false;
        }

        String healthUrl = normalizeBaseUrl(serviceBaseUrl) + normalizeHealthPath(serviceHealthPath);

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(serviceHealthTimeoutMs);
            requestFactory.setReadTimeout(serviceHealthTimeoutMs);

            RestTemplate healthTemplate = new RestTemplate(requestFactory);
            ResponseEntity<String> response = healthTemplate.getForEntity(healthUrl, String.class);

            boolean healthy = response != null
                    && response.getStatusCode().is2xxSuccessful();

            System.out.println("[K8S_SERVING] Health endpoint check: url="
                    + healthUrl
                    + ", status="
                    + (response != null ? response.getStatusCodeValue() : "null")
                    + ", healthy="
                    + healthy);

            return healthy;
        } catch (Exception e) {
            System.out.println("[K8S_SERVING] Health endpoint check failed: url="
                    + healthUrl
                    + ", reason="
                    + e.getMessage());
            return false;
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String clean = url.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private String normalizeHealthPath(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/health";
        }
        String clean = healthPath.trim();
        if (!clean.startsWith("/")) {
            clean = "/" + clean;
        }
        return clean;
    }
}