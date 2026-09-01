package com.mlops.platform.service;

import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.ModelDeploymentShareRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service centralise de controle d acces aux modeles deployes.
 *
 * Regles :
 * - PRIVATE   => proprietaire uniquement
 * - SHARED    => proprietaire + utilisateurs dans la liste de partage
 * - PUBLIC_API => tous les utilisateurs authentifies
 *
 * Toute verification d autorisation sur un modele passe par ici.
 */
@Service
public class ModelAccessControlService {

    private final ModelDeploymentRepository modelDeploymentRepository;
    private final MlProjectRepository mlProjectRepository;
    private final ModelDeploymentShareRepository modelDeploymentShareRepository;

    public ModelAccessControlService(
            ModelDeploymentRepository modelDeploymentRepository,
            MlProjectRepository mlProjectRepository,
            ModelDeploymentShareRepository modelDeploymentShareRepository
    ) {
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.mlProjectRepository = mlProjectRepository;
        this.modelDeploymentShareRepository = modelDeploymentShareRepository;
    }

    /**
     * Verifie si un utilisateur peut ACCEDER (voir / utiliser) un modele deploye.
     *
     * @return true si l utilisateur est autorise
     */
    public boolean canAccessDeployment(User user, ModelDeployment deployment) {
        if (user == null || user.getId() == null) {
            return false;
        }
        if (deployment == null) {
            return false;
        }

        if (isOwner(user, deployment)) {
            return true;
        }

        DeploymentVisibility visibility = deployment.getVisibility();
        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        return switch (visibility) {
            case PRIVATE -> false;
            case SHARED -> isSharedWithUser(user, deployment);
            case PUBLIC_API -> true;
        };
    }

    /**
     * Verifie si un utilisateur peut GERER (modifier / supprimer) un modele.
     * Reserve au proprietaire.
     */
    public boolean canManageDeployment(User user, ModelDeployment deployment) {
        return isOwner(user, deployment);
    }

    /**
     * Verifie si un utilisateur peut UTILISER une cle API pour un modele donne.
     * Pour SHARED : l utilisateur doit etre dans la liste de partage.
     */
    public boolean canUseApiKey(User keyOwner, ModelDeployment deployment) {
        if (keyOwner == null || keyOwner.getId() == null) {
            return false;
        }
        if (deployment == null) {
            return false;
        }

        if (isOwner(keyOwner, deployment)) {
            return true;
        }

        DeploymentVisibility visibility = deployment.getVisibility();
        if (visibility == null) {
            visibility = DeploymentVisibility.PRIVATE;
        }

        return switch (visibility) {
            case PRIVATE -> false;
            case SHARED -> isSharedWithUser(keyOwner, deployment);
            case PUBLIC_API -> true;
        };
    }

    /**
     * Verifie si un utilisateur est le proprietaire d un deploiement.
     */
    public boolean isOwner(User user, ModelDeployment deployment) {
        if (user == null || user.getId() == null) {
            return false;
        }
        if (deployment == null || deployment.getProjectId() == null) {
            return false;
        }

        Optional<MlProject> project = mlProjectRepository.findById(deployment.getProjectId());
        return project.isPresent()
                && project.get().getOwner() != null
                && project.get().getOwner().getId() != null
                && project.get().getOwner().getId().equals(user.getId());
    }

    /**
     * Verifie si un utilisateur est dans la liste de partage d un modele.
     * Cherche dans tous les deploiements du meme registeredModelName.
     */
    public boolean isSharedWithUser(User user, ModelDeployment deployment) {
        if (user == null || user.getId() == null) {
            return false;
        }
        if (deployment == null) {
            return false;
        }

        if (deployment.getId() != null
                && modelDeploymentShareRepository
                        .existsByDeploymentIdAndSharedWithUserId(
                                deployment.getId(), user.getId()
                        )) {
            return true;
        }

        if (deployment.getRegisteredModelName() == null
                || deployment.getRegisteredModelName().isBlank()) {
            return false;
        }

        List<ModelDeployment> sameModelDeployments =
                modelDeploymentRepository.findByRegisteredModelNameOrderByCreatedAtDesc(
                        deployment.getRegisteredModelName().trim()
                );

        if (sameModelDeployments == null) {
            return false;
        }

        for (ModelDeployment d : sameModelDeployments) {
            if (d != null && d.getId() != null
                    && modelDeploymentShareRepository
                            .existsByDeploymentIdAndSharedWithUserId(
                                    d.getId(), user.getId()
                            )) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recupere le proprietaire d un deploiement.
     */
    public User getOwner(ModelDeployment deployment) {
        if (deployment == null || deployment.getProjectId() == null) {
            return null;
        }

        return mlProjectRepository.findById(deployment.getProjectId())
                .map(MlProject::getOwner)
                .orElse(null);
    }
}
