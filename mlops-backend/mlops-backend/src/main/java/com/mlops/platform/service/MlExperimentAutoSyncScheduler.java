package com.mlops.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler principal du cycle MLOps automatique.
 *
 * Correction :
 * - fréquence configurable par app.mlops.auto-sync.delay-ms
 * - initialDelay configurable
 * - protection anti chevauchement avec AtomicBoolean
 * - ne fait pas de recalcul si auto-sync désactivé
 */
@Service
public class MlExperimentAutoSyncScheduler {

    private final MlExperimentService mlExperimentService;
    private final ModelDeploymentService modelDeploymentService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.mlops.auto-sync.enabled:true}")
    private boolean autoSyncEnabled;

    public MlExperimentAutoSyncScheduler(
            MlExperimentService mlExperimentService,
            ModelDeploymentService modelDeploymentService
    ) {
        this.mlExperimentService = mlExperimentService;
        this.modelDeploymentService = modelDeploymentService;
    }

    @Scheduled(
            initialDelayString = "${app.mlops.auto-sync.initial-delay-ms:30000}",
            fixedDelayString = "${app.mlops.auto-sync.delay-ms:300000}"
    )
    public void autoSyncAllProjects() {
        if (!autoSyncEnabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            System.out.println("[AUTO_SYNC] Previous synchronization still running. Skip this cycle.");
            return;
        }

        try {
            System.out.println("[AUTO_SYNC] =====================================");
            System.out.println("[AUTO_SYNC] Starting full automatic MLOps lifecycle...");
            System.out.println("[AUTO_SYNC] =====================================");

            mlExperimentService.syncAllProjectsAutomatically();

            modelDeploymentService.reconcileDeploymentsWithKubernetes();

            System.out.println("[AUTO_SYNC] =====================================");
            System.out.println("[AUTO_SYNC] Full automatic MLOps lifecycle finished.");
            System.out.println("[AUTO_SYNC] =====================================");

        } catch (Exception e) {
            System.out.println("[AUTO_SYNC] Global synchronization failed.");
            System.out.println("[AUTO_SYNC] Reason: " + e.getMessage());
            e.printStackTrace();

        } finally {
            running.set(false);
        }
    }
}
