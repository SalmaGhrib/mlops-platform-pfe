package com.mlops.platform.service;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
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
                    + ", healthyEndpoint="
                    + healthyEndpoint);

            return (deploymentReady && serviceExists) || healthyEndpoint;

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

        boolean deploymentExists = deployment != null;
        boolean serviceExists = service != null;

        System.out.println("[K8S_SERVING] Resources existence check: deployment="
                + cleanDeploymentName
                + ", deploymentExists="
                + deploymentExists
                + ", service="
                + cleanServiceName
                + ", serviceExists="
                + serviceExists);

        return deploymentExists && serviceExists;

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