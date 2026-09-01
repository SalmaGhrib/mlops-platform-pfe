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
 * Service centralisé de contrôle d'accès aux modèles déployés.
 *
 * Règles :
 * - PRIVATE   → propriétaire uniquement
 * - SHARED    → propriétaire + utilisateurs dans la liste de partage
 * - PUBLIC_API → tous les utilisateurs authentifiés
 *
 * Toute vérification d'autorisation sur un modèle passée par ici.
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
     * Vérifie si un utilisateur peut ACCÉDER (voir / utiliser) un modèle déployé.
     *
     * @return true si l'utilisateur est autorisé
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
     * Vérifie si un utilisateur peut GÉRER (modifier / supprimer) un modèle.
     * Réservé au propriétaire.
     */
    public boolean canManageDeployment(User user, ModelDeployment deployment) {
        return isOwner(user, deployment);
    }

    /**
     * Vérifie si un utilisateur peut GÉRER les clés API d'un modèle.
     * Réservé au propriétaire.
     */
    public boolean canManageApiKeys(User user, ModelDeployment deployment) {
        return isOwner(user, deployment);
    }

    /**
     * Vérifie si un utilisateur peut UTILISER une clé API pour un modèle donné.
     * Pour SHARED : l'utilisateur doit être dans la liste de partage.
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
     * Vérifie si un utilisateur est le propriétaire d'un déploiement.
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
     * Vérifie si un utilisateur est dans la liste de partage d'un modèle.
     * Cherche dans tous les déploiements du même registeredModelName.
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
     * Récupère le propriétaire d'un déploiement.
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
