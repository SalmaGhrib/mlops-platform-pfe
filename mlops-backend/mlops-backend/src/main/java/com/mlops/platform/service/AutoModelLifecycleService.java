package com.mlops.platform.service;

import com.mlops.platform.dto.DeployModelRequestDto;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.ModelDeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AutoModelLifecycleService {

    private final ModelPromotionService modelPromotionService;
    private final ModelDeploymentService modelDeploymentService;
    private final ModelDeploymentRepository modelDeploymentRepository;

    public AutoModelLifecycleService(
            ModelPromotionService modelPromotionService,
            ModelDeploymentService modelDeploymentService,
            ModelDeploymentRepository modelDeploymentRepository
    ) {
        this.modelPromotionService = modelPromotionService;
        this.modelDeploymentService = modelDeploymentService;
        this.modelDeploymentRepository = modelDeploymentRepository;
    }

    /*
     * Cycle automatique complet :
     *
     * 1. Vérifie les déploiements Kubernetes existants.
     * 2. Recalcule le meilleur modèle du projet.
     * 3. Met à jour la version production côté MLflow/MySQL.
     * 4. Si la version production n'est pas encore déployée, lance le déploiement.
     * 5. En mode blue/green :
     *    - l'ancien deployment reste actif tant que le nouveau n'est pas READY.
     *    - quand le nouveau est READY, ModelDeploymentService désactive l'ancien.
     * 6. La clé API est conservée.
     */
    @Transactional
    public MlExperiment evaluateAndAutoPromoteAndDeploy(
            MlProject project,
            String registeredModelName
    ) {
        if (project == null || project.getId() == null) {
            System.out.println("[AUTO_LIFECYCLE] Project is null. Skip.");
            return null;
        }

        if (registeredModelName == null || registeredModelName.isBlank()) {
            System.out.println("[AUTO_LIFECYCLE] registeredModelName is blank. Skip.");
            return null;
        }

        if (!isAutoPromoteEnabled(project)) {
            System.out.println("[AUTO_LIFECYCLE] Auto promote disabled for project=" + project.getId());
            return null;
        }

        String cleanRegisteredModelName = registeredModelName.trim();

        /*
         * Avant de décider, on synchronise les déploiements en attente.
         * Exemple :
         * - un deployment était DEPLOYING
         * - Kubernetes devient READY après quelques secondes/minutes
         * - on le marque DEPLOYED et active automatiquement
         */
        try {
            modelDeploymentService.reconcileDeploymentsWithKubernetes();
        } catch (Exception e) {
            System.out.println("[AUTO_LIFECYCLE] Deployment reconciliation failed but lifecycle continues.");
            System.out.println("[AUTO_LIFECYCLE] Reason: " + e.getMessage());
        }

        MlExperiment promotedExperiment;

        try {
            promotedExperiment = modelPromotionService.recalculateProductionForProject(
                    project,
                    cleanRegisteredModelName
            );
        } catch (Exception e) {
            System.out.println("[AUTO_LIFECYCLE] Auto-promotion failed for model=" + cleanRegisteredModelName);
            System.out.println("[AUTO_LIFECYCLE] Reason: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        if (!isDeployableProductionExperiment(promotedExperiment)) {
            System.out.println("[AUTO_LIFECYCLE] No deployable production experiment found for model="
                    + cleanRegisteredModelName);
            return promotedExperiment;
        }

        String productionVersion = firstNonBlank(
                promotedExperiment.getModelVersion(),
                promotedExperiment.getProductionVersion()
        );

        if (productionVersion == null || productionVersion.isBlank()) {
            System.out.println("[AUTO_LIFECYCLE] Production version is blank. Skip deployment.");
            return promotedExperiment;
        }

        /*
         * Cas 1 :
         * Même version déjà DEPLOYED + active + vraiment prête dans Kubernetes.
         * => On ne fait rien.
         */
        Optional<ModelDeployment> existingActiveDeployment =
                modelDeploymentRepository.findByRegisteredModelNameAndModelVersionAndActiveTrue(
                        cleanRegisteredModelName,
                        productionVersion
                );

        if (existingActiveDeployment.isPresent()) {
            ModelDeployment existing = existingActiveDeployment.get();

            boolean alreadyDeployed =
                    DeploymentStatus.DEPLOYED.equals(existing.getDeploymentStatus());

            boolean reallyReadyInKubernetes =
                    modelDeploymentService.isDeploymentReallyReadyInKubernetes(existing);

            if (alreadyDeployed && reallyReadyInKubernetes) {
                System.out.println("[AUTO_LIFECYCLE] Production version already deployed and ready. Skip."
                        + " model=" + cleanRegisteredModelName
                        + ", version=" + productionVersion
                        + ", deploymentId=" + existing.getId());

                return promotedExperiment;
            }

            /*
             * Cas particulier :
             * MySQL dit DEPLOYED mais Kubernetes n'est pas prêt.
             * On ne garde pas cette ligne comme active.
             * ModelDeploymentService va recréer ou remettre en état.
             */
            if (alreadyDeployed && !reallyReadyInKubernetes) {
                System.out.println("[AUTO_LIFECYCLE] MySQL says DEPLOYED but Kubernetes is not ready. Marking OUT_OF_SYNC."
                        + " model=" + cleanRegisteredModelName
                        + ", version=" + productionVersion
                        + ", deploymentId=" + existing.getId());

                existing.setDeploymentStatus(DeploymentStatus.OUT_OF_SYNC);
                existing.setActive(false);
                existing.setErrorMessage("Deployment marked DEPLOYED in MySQL but Kubernetes resources are not ready.");
                modelDeploymentRepository.saveAndFlush(existing);
            }

            /*
             * Si le deployment est déjà en cours, on évite de créer des doublons.
             */
            if (DeploymentStatus.DEPLOYING.equals(existing.getDeploymentStatus())) {
                System.out.println("[AUTO_LIFECYCLE] Production deployment already in progress. Skip duplicate deployment."
                        + " model=" + cleanRegisteredModelName
                        + ", version=" + productionVersion
                        + ", deploymentId=" + existing.getId());

                return promotedExperiment;
            }
        }

        /*
         * Cas 2 :
         * Première version ou nouvelle meilleure version.
         * Le service de déploiement s'occupe du blue/green :
         * - nouveau deployment créé en active=false
         * - si READY => active=true
         * - ancien deployment désactivé seulement après succès
         */
        try {
            DeployModelRequestDto request = new DeployModelRequestDto();
            request.setProjectId(promotedExperiment.getProject().getId());
            request.setExperimentId(promotedExperiment.getId());
            request.setRegisteredModelName(cleanRegisteredModelName);
            request.setModelVersion(productionVersion);

            System.out.println("[AUTO_LIFECYCLE] Auto-deployment starting..."
                    + " projectId=" + request.getProjectId()
                    + ", experimentId=" + request.getExperimentId()
                    + ", model=" + request.getRegisteredModelName()
                    + ", version=" + request.getModelVersion());

            ModelDeploymentResponse deploymentResponse =
                    modelDeploymentService.deployProductionModel(request);

            if (deploymentResponse == null) {
                System.out.println("[AUTO_LIFECYCLE] Auto-deployment returned null response.");
                return promotedExperiment;
            }

            System.out.println("[AUTO_LIFECYCLE] Auto-deployment finished: "
                    + "model=" + deploymentResponse.getRegisteredModelName()
                    + ", version=" + deploymentResponse.getModelVersion()
                    + ", status=" + deploymentResponse.getDeploymentStatus()
                    + ", active=" + deploymentResponse.getActive()
                    + ", endpoint=" + deploymentResponse.getPredictionEndpoint());

        } catch (Exception e) {
            /*
             * Très important :
             * Si Kubernetes échoue, on ne casse pas la synchronisation.
             * L'ancien modèle reste actif car ModelDeploymentService ne le désactive
             * qu'après readiness du nouveau deployment.
             */
            System.out.println("[AUTO_LIFECYCLE] Auto-deployment failed for model="
                    + cleanRegisteredModelName
                    + ", version=" + productionVersion);
            System.out.println("[AUTO_LIFECYCLE] Reason: " + e.getMessage());
            e.printStackTrace();
        }

        return promotedExperiment;
    }

    private boolean isDeployableProductionExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return false;
        }

        if (experiment.getId() == null) {
            return false;
        }

        if (experiment.getProject() == null || experiment.getProject().getId() == null) {
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (experiment.getRegisteredModelName() == null || experiment.getRegisteredModelName().isBlank()) {
            return false;
        }

        if (experiment.getModelVersion() == null || experiment.getModelVersion().isBlank()) {
            return false;
        }

        boolean isProduction =
                Boolean.TRUE.equals(experiment.getProduction())
                        || (
                        experiment.getProductionVersion() != null
                                && experiment.getProductionVersion().equals(experiment.getModelVersion())
                );

        return isProduction;
    }

    private boolean isAutoPromoteEnabled(MlProject project) {
        return project == null
                || project.getAutoPromoteBestModel() == null
                || Boolean.TRUE.equals(project.getAutoPromoteBestModel());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return null;
    }
}