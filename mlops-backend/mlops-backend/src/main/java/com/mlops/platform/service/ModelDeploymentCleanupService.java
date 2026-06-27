package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ModelDeploymentCleanupService {

    private final ModelDeploymentRepository modelDeploymentRepository;
    private final ModelDeploymentShareRepository modelDeploymentShareRepository;
    private final KubernetesModelServingService kubernetesModelServingService;

    @Value("${app.mlops.deployment-cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${app.mlops.deployment-cleanup.keep-history:true}")
    private boolean keepHistory;

    @Value("${app.mlops.deployment-cleanup.delete-failed-after-hours:1}")
    private long deleteFailedAfterHours;

    public ModelDeploymentCleanupService(
            ModelDeploymentRepository modelDeploymentRepository,
            ModelDeploymentShareRepository modelDeploymentShareRepository,
            KubernetesModelServingService kubernetesModelServingService
    ) {
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.modelDeploymentShareRepository = modelDeploymentShareRepository;
        this.kubernetesModelServingService = kubernetesModelServingService;
    }

    @Scheduled(
            fixedDelayString = "${app.mlops.deployment-cleanup.delay-ms:120000}",
            initialDelayString = "${app.mlops.deployment-cleanup.initial-delay-ms:60000}"
    )
    @Transactional
    public void cleanupUselessDeploymentRows() {
        if (!cleanupEnabled) {
            return;
        }

        List<ModelDeployment> deployments = modelDeploymentRepository.findAll(
                Sort.by(Sort.Direction.DESC, "id")
        );

        if (deployments == null || deployments.isEmpty()) {
            return;
        }

        System.out.println("[DEPLOYMENT_CLEANUP] Starting cleanup. rows=" + deployments.size());

        Set<String> seenModelVersion = new HashSet<>();

        int deletedCount = 0;
        int skippedActiveCount = 0;

        for (ModelDeployment deployment : deployments) {
            if (deployment == null || deployment.getId() == null) {
                continue;
            }

            if (Boolean.TRUE.equals(deployment.getActive())
                    && DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
                skippedActiveCount++;
                continue;
            }

            String key = buildModelVersionKey(deployment);

            if (keepHistory && !seenModelVersion.contains(key)) {
                seenModelVersion.add(key);

                if (shouldKeepAsUsefulHistory(deployment)) {
                    continue;
                }
            }

            if (shouldDeleteDeploymentRow(deployment)) {
                deleteDeploymentRowOnly(deployment);
                deletedCount++;
            }
        }

        modelDeploymentRepository.flush();

        System.out.println("[DEPLOYMENT_CLEANUP] Finished cleanup. deleted="
                + deletedCount
                + ", skippedActive="
                + skippedActiveCount);
    }

    private boolean shouldKeepAsUsefulHistory(ModelDeployment deployment) {
        if (deployment == null) {
            return false;
        }

        if (DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())) {
            return true;
        }

        if (DeploymentStatus.PENDING.equals(deployment.getDeploymentStatus())
                || DeploymentStatus.DEPLOYING.equals(deployment.getDeploymentStatus())) {
            return !isOlderThanHours(deployment, deleteFailedAfterHours);
        }

        return false;
    }

    private boolean shouldDeleteDeploymentRow(ModelDeployment deployment) {
        if (deployment == null || deployment.getId() == null) {
            return false;
        }

        if (Boolean.TRUE.equals(deployment.getActive())) {
            return false;
        }

        DeploymentStatus status = deployment.getDeploymentStatus();

        if (status == null) {
            return true;
        }

        if (DeploymentStatus.FAILED.equals(status)
                || DeploymentStatus.OUT_OF_SYNC.equals(status)) {
            return isOlderThanHours(deployment, deleteFailedAfterHours);
        }

        if (DeploymentStatus.PENDING.equals(status)) {
            return isOlderThanHours(deployment, deleteFailedAfterHours);
        }

        if (DeploymentStatus.DEPLOYING.equals(status)) {
            boolean resourcesExist = kubernetesModelServingService.modelServerResourcesExist(
                    deployment.getDeploymentName(),
                    deployment.getServiceName()
            );

            return !resourcesExist && isOlderThanHours(deployment, deleteFailedAfterHours);
        }

        if (DeploymentStatus.DEPLOYED.equals(status)) {
            return !keepHistory;
        }

        return false;
    }

    private boolean isOlderThanHours(ModelDeployment deployment, long hours) {
        if (deployment == null) {
            return false;
        }

        LocalDateTime referenceDate = deployment.getUpdatedAt();

        if (referenceDate == null) {
            referenceDate = deployment.getCreatedAt();
        }

        if (referenceDate == null) {
            return true;
        }

        return referenceDate.isBefore(LocalDateTime.now().minusHours(hours));
    }

    private void deleteDeploymentRowOnly(ModelDeployment deployment) {
        if (deployment == null || deployment.getId() == null) {
            return;
        }

        try {
            modelDeploymentShareRepository.deleteByDeploymentId(deployment.getId());
            modelDeploymentShareRepository.flush();

            modelDeploymentRepository.delete(deployment);

            System.out.println("[DEPLOYMENT_CLEANUP] Deleted useless DB row. id="
                    + deployment.getId()
                    + ", model="
                    + deployment.getRegisteredModelName()
                    + ", version="
                    + deployment.getModelVersion()
                    + ", status="
                    + deployment.getDeploymentStatus()
                    + ", active="
                    + deployment.getActive());

        } catch (Exception e) {
            System.out.println("[DEPLOYMENT_CLEANUP] Failed to delete row id="
                    + deployment.getId()
                    + ", reason="
                    + e.getMessage());
        }
    }

    private String buildModelVersionKey(ModelDeployment deployment) {
        if (deployment == null) {
            return "unknown::unknown";
        }

        String model = deployment.getRegisteredModelName() == null
                ? "unknown"
                : deployment.getRegisteredModelName().trim().toLowerCase();

        String version = deployment.getModelVersion() == null
                ? "unknown"
                : deployment.getModelVersion().trim().toLowerCase();

        return model + "::" + version;
    }
}