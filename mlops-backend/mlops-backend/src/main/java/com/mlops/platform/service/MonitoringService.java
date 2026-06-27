package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.MonitoringSummaryResponse;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MonitoringService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MlExperimentRepository mlExperimentRepository;

    @Value("${monitoring.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    public MonitoringService(
            ObjectMapper objectMapper,
            MlExperimentRepository mlExperimentRepository
    ) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.mlExperimentRepository = mlExperimentRepository;
    }

    public MonitoringSummaryResponse getSummary() {
        MonitoringSummaryResponse response = new MonitoringSummaryResponse();

        /*
         * Metrics from Prometheus
         */
        double totalDeployments = queryPrometheus("mlops_deployments");
        double activeProductionModels = queryPrometheus("mlops_active_production_models");

        double completedRuns = queryPrometheus("mlops_experiments_completed");
        double failedRuns = queryPrometheus("mlops_experiments_failed");
        double runningRuns = queryPrometheus("mlops_experiments_running");

        double successfulDeployments = queryPrometheus("mlops_deployments_deployed");
        double failedDeployments = queryPrometheus("mlops_deployments_failed");
        double deploymentsInProgress = queryPrometheus("mlops_deployments_deploying");


        double averageStandardRunDurationSeconds = safeRepositoryCallAverageStandardDuration();
double lastStandardRunDurationSeconds = safeRepositoryCallLastStandardDuration();

double averageDockerRunDurationSeconds = safeRepositoryCallAverageDockerDuration();
double lastDockerRunDurationSeconds = safeRepositoryCallLastDockerDuration();

long standardRunsCount = safeRepositoryCallStandardRunsCount();
long dockerRunsCount = safeRepositoryCallDockerRunsCount();

        double unavailableModelDeployments = queryPrometheus(
                "sum(kube_deployment_status_replicas_available{namespace=\"mlops\"} == 0)"
        );

        /*
         * Backend API latency from Prometheus
         */
        double averageBackendLatencySeconds = queryPrometheus(
                "sum(rate(http_server_requests_seconds_sum{application=\"mlops-backend\"}[5m])) " +
                        "/ " +
                        "sum(rate(http_server_requests_seconds_count{application=\"mlops-backend\"}[5m]))"
        );

        double averageBackendLatencyMs = averageBackendLatencySeconds * 1000.0;

        /*
         * Estimated run durations from database
         * Note: duration is estimated using updated_at - created_at.
         */
        double averageRunDurationSeconds = safeRepositoryCallAverageDuration();
        double maxRunDurationSeconds = safeRepositoryCallMaxDuration();
        double lastRunDurationSeconds = safeRepositoryCallLastDuration();

        /*
         * Rates
         */
        double runSuccessRate = calculateRate(
                completedRuns,
                completedRuns + failedRuns + runningRuns
        );

        double deploymentSuccessRate = calculateRate(
                successfulDeployments,
                totalDeployments
        );

        /*
         * Response mapping
         */
        response.setTotalDeployments(totalDeployments);
        response.setActiveProductionModels(activeProductionModels);

        response.setCompletedRuns(completedRuns);
        response.setFailedRuns(failedRuns);
        response.setRunningRuns(runningRuns);

        response.setSuccessfulDeployments(successfulDeployments);
        response.setFailedDeployments(failedDeployments);
        response.setDeploymentsInProgress(deploymentsInProgress);

        response.setRunSuccessRate(runSuccessRate);
        response.setDeploymentSuccessRate(deploymentSuccessRate);

        response.setUnavailableModelDeployments(unavailableModelDeployments);

        response.setAverageBackendLatencySeconds(roundOneDecimal(averageBackendLatencySeconds));
        response.setAverageBackendLatencyMs(roundOneDecimal(averageBackendLatencyMs));

        response.setAverageRunDurationSeconds(roundOneDecimal(averageRunDurationSeconds));
        response.setMaxRunDurationSeconds(roundOneDecimal(maxRunDurationSeconds));
        response.setLastRunDurationSeconds(roundOneDecimal(lastRunDurationSeconds));

        response.setAverageStandardRunDurationSeconds(roundOneDecimal(averageStandardRunDurationSeconds));
response.setLastStandardRunDurationSeconds(roundOneDecimal(lastStandardRunDurationSeconds));

response.setAverageDockerRunDurationSeconds(roundOneDecimal(averageDockerRunDurationSeconds));
response.setLastDockerRunDurationSeconds(roundOneDecimal(lastDockerRunDurationSeconds));

response.setStandardRunsCount(standardRunsCount);
response.setDockerRunsCount(dockerRunsCount);

        return response;
    }

    private double calculateRate(double success, double total) {
        if (total <= 0) {
            return 0.0;
        }

        return Math.round((success / total) * 1000.0) / 10.0;
    }

    private double roundOneDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }

        return Math.round(value * 10.0) / 10.0;
    }

    private double queryPrometheus(String promql) {
    try {
        java.net.URI uri = UriComponentsBuilder
                .fromHttpUrl(prometheusUrl + "/api/v1/query")
                .queryParam("query", promql)
                .build()
                .encode()
                .toUri();

        String json = restTemplate.getForObject(uri, String.class);

        if (json == null || json.isBlank()) {
            return 0.0;
        }

        JsonNode root = objectMapper.readTree(json);

        String status = root.path("status").asText();
        if (!"success".equalsIgnoreCase(status)) {
            System.err.println("[MONITORING] Prometheus returned non-success status for query: " + promql);
            System.err.println("[MONITORING] Response: " + json);
            return 0.0;
        }

        JsonNode result = root.path("data").path("result");

        if (!result.isArray() || result.isEmpty()) {
            return 0.0;
        }

        JsonNode valueNode = result.get(0).path("value");

        if (!valueNode.isArray() || valueNode.size() < 2) {
            return 0.0;
        }

        return valueNode.get(1).asDouble();

    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to query Prometheus: " + promql);
        System.err.println("[MONITORING] Error: " + e.getMessage());
        return 0.0;
    }
}
    private double safeRepositoryCallAverageStandardDuration() {
    try {
        return mlExperimentRepository.getAverageStandardRunDurationSeconds();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to get average standard run duration: " + e.getMessage());
        return 0.0;
    }
}

private double safeRepositoryCallLastStandardDuration() {
    try {
        return mlExperimentRepository.getLastStandardRunDurationSeconds();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to get last standard run duration: " + e.getMessage());
        return 0.0;
    }
}

private double safeRepositoryCallAverageDockerDuration() {
    try {
        return mlExperimentRepository.getAverageDockerRunDurationSeconds();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to get average docker run duration: " + e.getMessage());
        return 0.0;
    }
}

private double safeRepositoryCallLastDockerDuration() {
    try {
        return mlExperimentRepository.getLastDockerRunDurationSeconds();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to get last docker run duration: " + e.getMessage());
        return 0.0;
    }
}

private long safeRepositoryCallStandardRunsCount() {
    try {
        return mlExperimentRepository.countStandardRuns();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to count standard runs: " + e.getMessage());
        return 0L;
    }
}

private long safeRepositoryCallDockerRunsCount() {
    try {
        return mlExperimentRepository.countDockerRuns();
    } catch (Exception e) {
        System.err.println("[MONITORING] Failed to count docker runs: " + e.getMessage());
        return 0L;
    }
}

    private double safeRepositoryCallAverageDuration() {
        try {
            return mlExperimentRepository.getAverageRunDurationSeconds();
        } catch (Exception e) {
            System.err.println("[MONITORING] Failed to get average run duration: " + e.getMessage());
            return 0.0;
        }
    }

    private double safeRepositoryCallMaxDuration() {
        try {
            return mlExperimentRepository.getMaxRunDurationSeconds();
        } catch (Exception e) {
            System.err.println("[MONITORING] Failed to get max run duration: " + e.getMessage());
            return 0.0;
        }
    }

    private double safeRepositoryCallLastDuration() {
        try {
            return mlExperimentRepository.getLastRunDurationSeconds();
        } catch (Exception e) {
            System.err.println("[MONITORING] Failed to get last run duration: " + e.getMessage());
            return 0.0;
        }
    }
}