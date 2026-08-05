package com.mlops.platform.repository;

import com.mlops.platform.entity.ModelApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelApiKeyRepository extends JpaRepository<ModelApiKey, Long> {

    Optional<ModelApiKey> findByKeyPrefix(String keyPrefix);

    List<ModelApiKey> findByDeploymentIdOrderByCreatedAtDesc(Long deploymentId);

    List<ModelApiKey> findByDeploymentIdAndEnabledTrue(Long deploymentId);

    Optional<ModelApiKey> findByIdAndDeploymentId(Long id, Long deploymentId);

    long countByDeploymentIdAndEnabledTrue(Long deploymentId);
}
