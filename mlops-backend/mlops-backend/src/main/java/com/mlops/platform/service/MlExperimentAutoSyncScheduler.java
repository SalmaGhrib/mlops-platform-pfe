package com.mlops.platform.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

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
            fixedDelayString = "${app.mlops.auto-sync.delay-ms:60000}",
            initialDelayString = "${app.mlops.auto-sync.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "mlopsLifecycleCoordinator",
            lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT5S"
    )
    public void autoSyncAllProjects() {
        if (!autoSyncEnabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            System.out.println(
                    "[AUTO_SYNC] Previous synchronization still running. Cycle skipped."
            );
            return;
        }

        try {
            System.out.println("[AUTO_SYNC] =====================================");
            System.out.println("[AUTO_SYNC] Starting automatic MLOps lifecycle");
            System.out.println("[AUTO_SYNC] =====================================");

            /*
             * MlExperimentService traite chaque projet dans une transaction
             * REQUIRES_NEW indépendante. Un échec d'un projet ne pollue donc
             * pas la session Hibernate du projet suivant.
             */
            mlExperimentService.syncAllProjectsAutomatically();

            /*
             * Le reconcile est lancé une seule fois, après toutes les
             * synchronisations et promotions.
             */
            modelDeploymentService.reconcileDeploymentsWithKubernetes();

            System.out.println("[AUTO_SYNC] =====================================");
            System.out.println("[AUTO_SYNC] Automatic MLOps lifecycle completed");
            System.out.println("[AUTO_SYNC] =====================================");

        } catch (Exception e) {
            System.err.println("[AUTO_SYNC] Global synchronization failed.");
            System.err.println("[AUTO_SYNC] Reason: " + e.getMessage());
            e.printStackTrace();

        } finally {
            running.set(false);
        }
    }
}