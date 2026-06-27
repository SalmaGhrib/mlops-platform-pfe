package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class MlopsBusinessMetricsService {

    public MlopsBusinessMetricsService(
            MeterRegistry meterRegistry,
            MlExperimentRepository experimentRepository,
            ModelDeploymentRepository deploymentRepository
    ) {

        /*
         * ==============================
         * Metrics des runs / expériences
         * ==============================
         */

        Gauge.builder("mlops_experiments_total", experimentRepository, MlExperimentRepository::count)
                .description("Total number of ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_created_total", experimentRepository,
                        repo -> repo.countByStatus(ExperimentStatus.CREATED))
                .description("Total number of created ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_running_total", experimentRepository,
                        repo -> repo.countByStatus(ExperimentStatus.RUNNING))
                .description("Total number of running ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_completed_total", experimentRepository,
                        repo -> repo.countByStatus(ExperimentStatus.COMPLETED))
                .description("Total number of completed ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_failed_total", experimentRepository,
                        repo -> repo.countByStatus(ExperimentStatus.FAILED))
                .description("Total number of failed ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_cancelled_total", experimentRepository,
                        repo -> repo.countByStatus(ExperimentStatus.CANCELLED))
                .description("Total number of cancelled ML experiment runs")
                .register(meterRegistry);

        Gauge.builder("mlops_experiments_with_registered_model_total", experimentRepository,
                        MlExperimentRepository::countByRegisteredModelNameIsNotNull)
                .description("Total number of experiments linked to a registered model")
                .register(meterRegistry);


        /*
         * ==============================
         * Metrics des déploiements
         * ==============================
         */

        Gauge.builder("mlops_deployments_total", deploymentRepository, ModelDeploymentRepository::count)
                .description("Total number of model deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_deployments_pending_total", deploymentRepository,
                        repo -> repo.countByDeploymentStatus(DeploymentStatus.PENDING))
                .description("Total number of pending deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_deployments_deploying_total", deploymentRepository,
                        repo -> repo.countByDeploymentStatus(DeploymentStatus.DEPLOYING))
                .description("Total number of deployments currently deploying")
                .register(meterRegistry);

        Gauge.builder("mlops_deployments_deployed_total", deploymentRepository,
                        repo -> repo.countByDeploymentStatus(DeploymentStatus.DEPLOYED))
                .description("Total number of successfully deployed models")
                .register(meterRegistry);

        Gauge.builder("mlops_deployments_failed_total", deploymentRepository,
                        repo -> repo.countByDeploymentStatus(DeploymentStatus.FAILED))
                .description("Total number of failed deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_deployments_out_of_sync_total", deploymentRepository,
                        repo -> repo.countByDeploymentStatus(DeploymentStatus.OUT_OF_SYNC))
                .description("Total number of out of sync deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_active_deployments_total", deploymentRepository,
                        ModelDeploymentRepository::countByActiveTrue)
                .description("Total number of active deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_active_production_models_total", deploymentRepository,
                        repo -> repo.countByActiveTrueAndDeploymentStatus(DeploymentStatus.DEPLOYED))
                .description("Total number of active deployed production models")
                .register(meterRegistry);

        Gauge.builder("mlops_api_enabled_deployments_total", deploymentRepository,
                        ModelDeploymentRepository::countByApiEnabledTrue)
                .description("Total number of deployments with API access enabled")
                .register(meterRegistry);

        Gauge.builder("mlops_public_deployments_total", deploymentRepository,
                        repo -> repo.countByVisibility(DeploymentVisibility.PUBLIC_API))
                .description("Total number of public API deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_private_deployments_total", deploymentRepository,
                        repo -> repo.countByVisibility(DeploymentVisibility.PRIVATE))
                .description("Total number of private deployments")
                .register(meterRegistry);

        Gauge.builder("mlops_shared_deployments_total", deploymentRepository,
                        repo -> repo.countByVisibility(DeploymentVisibility.SHARED))
                .description("Total number of shared deployments")
                .register(meterRegistry);
    }
}