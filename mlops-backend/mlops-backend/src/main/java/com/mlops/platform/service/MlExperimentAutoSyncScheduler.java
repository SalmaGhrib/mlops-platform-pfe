package com.mlops.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler principal du cycle MLOps automatique.
 *
 * Objectif :
 * - ne plus cliquer manuellement sur "Synchroniser MLflow"
 * - ne plus utiliser Swagger pour déployer
 * - synchroniser MySQL, MLflow et Kubernetes de façon cohérente
 * - garder l'ancienne version déployée active tant que la nouvelle version n'est pas prête
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

    /**
     * Toutes les 30 secondes par défaut :
     *
     * 1. synchronise les projets
     * 2. récupère les résultats Kubeflow/MLflow
     * 3. importe les runs MLflow manquants
     * 4. fusionne les doublons
     * 5. recalcule les scores globaux
     * 6. choisit le meilleur modèle
     * 7. met à jour l'alias MLflow production
     * 8. lance le déploiement Kubernetes de la nouvelle version si nécessaire
     * 9. réconcilie les déploiements Kubernetes en attente
     *
     * Règle importante :
     * - MLflow peut passer v7 en production.
     * - Mais Kubernetes peut encore servir v6.
     * - Donc on ne rend v7 ACTIVE/DEPLOYED en MySQL que lorsque le pod + service v7 sont réellement prêts.
     */
    @Scheduled(fixedDelayString = "${app.mlops.auto-sync.delay-ms:10000}")
    public void autoSyncAllProjects() {
        if (!autoSyncEnabled) {
            return;
        }

        /*
         * Protection :
         * évite que deux synchronisations se lancent en même temps
         * si une sync prend plus de 30 secondes.
         */
        if (!running.compareAndSet(false, true)) {
            System.out.println("[AUTO_SYNC] Previous synchronization still running. Skip this cycle.");
            return;
        }

        try {
            System.out.println("[AUTO_SYNC] =====================================");
            System.out.println("[AUTO_SYNC] Starting full automatic MLOps lifecycle...");
            System.out.println("[AUTO_SYNC] =====================================");

            /*
             * 1) Synchronisation globale :
             * - MySQL <-> MLflow
             * - import des runs
             * - merge doublons
             * - score global
             * - promotion MLflow
             * - lancement éventuel d'un nouveau déploiement Kubernetes
             */
            mlExperimentService.syncAllProjectsAutomatically();

            /*
             * 2) Réconciliation Kubernetes :
             * Si un déploiement est en DEPLOYING, on vérifie si son Deployment + Service
             * sont maintenant prêts dans Kubernetes.
             *
             * Si oui :
             * - il devient DEPLOYED
             * - il devient ACTIVE
             * - l'ancienne version est désactivée/supprimée
             *
             * Si non :
             * - l'ancienne version reste active
             */
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