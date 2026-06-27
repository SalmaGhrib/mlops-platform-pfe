package com.mlops.platform.repository;

import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.entity.DeploymentVisibility;
import com.mlops.platform.entity.ModelDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelDeploymentRepository extends JpaRepository<ModelDeployment, Long> {

    /*
     * Anciennes méthodes conservées pour ne pas casser ton code existant.
     */
    Optional<ModelDeployment> findByProjectIdAndActiveTrue(Long projectId);

    Optional<ModelDeployment> findByExperimentIdAndActiveTrue(Long experimentId);

    List<ModelDeployment> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<ModelDeployment> findByExperimentIdOrderByCreatedAtDesc(Long experimentId);

    List<ModelDeployment> findByRegisteredModelNameOrderByCreatedAtDesc(String registeredModelName);

    List<ModelDeployment> findByDeploymentStatus(DeploymentStatus deploymentStatus);

    Optional<ModelDeployment> findByRegisteredModelNameAndModelVersionAndActiveTrue(
            String registeredModelName,
            String modelVersion
    );

    boolean existsByRegisteredModelNameAndModelVersionAndActiveTrue(
            String registeredModelName,
            String modelVersion
    );

    List<ModelDeployment> findByRegisteredModelNameAndActiveTrue(String registeredModelName);

    List<ModelDeployment> findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
            String registeredModelName,
            String modelVersion
    );

    /*
     * Nouvelle méthode importante :
     * permet de savoir si une version a déjà échoué.
     * Objectif : éviter que le scheduler recrée une ligne FAILED toutes les 30 secondes.
     */
    Optional<ModelDeployment> findTopByRegisteredModelNameAndModelVersionAndDeploymentStatusOrderByCreatedAtDesc(
            String registeredModelName,
            String modelVersion,
            DeploymentStatus deploymentStatus
    );

    boolean existsByRegisteredModelNameAndModelVersionAndDeploymentStatus(
            String registeredModelName,
            String modelVersion,
            DeploymentStatus deploymentStatus
    );

    /*
     * Méthodes pour l'accès API externe sécurisé.
     */
    Optional<ModelDeployment> findByApiKey(String apiKey);

    boolean existsByApiKey(String apiKey);

    List<ModelDeployment> findByRegisteredModelNameAndActiveTrueAndDeploymentStatusOrderByCreatedAtDesc(
            String registeredModelName,
            DeploymentStatus deploymentStatus
    );

    List<ModelDeployment> findByApiEnabledTrueAndVisibilityAndDeploymentStatusAndActiveTrue(
            DeploymentVisibility visibility,
            DeploymentStatus deploymentStatus
    );

    List<ModelDeployment> findByProjectIdAndApiEnabledTrueAndVisibilityAndDeploymentStatusAndActiveTrue(
            Long projectId,
            DeploymentVisibility visibility,
            DeploymentStatus deploymentStatus
    );

    /*
     * Méthodes pour que la clé API suive le modèle,
     * même si la version production change.
     */
    Optional<ModelDeployment> findTopByRegisteredModelNameAndActiveTrueAndDeploymentStatusOrderByCreatedAtDesc(
            String registeredModelName,
            DeploymentStatus deploymentStatus
    );

    List<ModelDeployment> findAllByOrderByCreatedAtDesc();

    Optional<ModelDeployment> findTopByRegisteredModelNameAndApiEnabledTrueAndApiKeyIsNotNullOrderByUpdatedAtDesc(
            String registeredModelName
    );

    Optional<ModelDeployment> findTopByRegisteredModelNameAndApiKeyAndApiEnabledTrueOrderByUpdatedAtDesc(
            String registeredModelName,
            String apiKey
    );

    long countByDeploymentStatus(DeploymentStatus deploymentStatus);

long countByActiveTrue();

long countByActiveTrueAndDeploymentStatus(DeploymentStatus deploymentStatus);

long countByApiEnabledTrue();

long countByVisibility(DeploymentVisibility visibility);
}