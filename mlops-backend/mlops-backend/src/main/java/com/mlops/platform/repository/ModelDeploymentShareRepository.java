package com.mlops.platform.repository;

import com.mlops.platform.entity.ModelDeploymentShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelDeploymentShareRepository extends JpaRepository<ModelDeploymentShare, Long> {

    List<ModelDeploymentShare> findByDeploymentId(Long deploymentId);

    List<ModelDeploymentShare> findBySharedWithUserId(Long sharedWithUserId);

    Optional<ModelDeploymentShare> findByDeploymentIdAndSharedWithUserId(
            Long deploymentId,
            Long sharedWithUserId
    );

    boolean existsByDeploymentIdAndSharedWithUserId(
            Long deploymentId,
            Long sharedWithUserId
    );

    void deleteByDeploymentId(Long deploymentId);

    void deleteByDeploymentIdAndSharedWithUserId(Long deploymentId, Long sharedWithUserId);
}