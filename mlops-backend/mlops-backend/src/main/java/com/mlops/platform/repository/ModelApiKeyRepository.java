package com.mlops.platform.repository;

import com.mlops.platform.entity.ModelApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ModelApiKeyRepository extends JpaRepository<ModelApiKey, Long> {

    Optional<ModelApiKey> findByKeyPrefix(String keyPrefix);

    List<ModelApiKey> findByDeploymentIdOrderByCreatedAtDesc(Long deploymentId);

    List<ModelApiKey> findByDeploymentIdAndEnabledTrue(Long deploymentId);

    Optional<ModelApiKey> findByIdAndDeploymentId(Long id, Long deploymentId);

    long countByDeploymentIdAndEnabledTrue(Long deploymentId);

    Optional<ModelApiKey> findByDeploymentIdAndOwnerId(Long deploymentId, Long ownerId);

    List<ModelApiKey> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    @Modifying
    @Query("UPDATE ModelApiKey k SET k.totalRequests = COALESCE(k.totalRequests, 0) + 1, k.lastUsedAt = :now WHERE k.id = :id")
    int incrementUsageAtomic(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE ModelApiKey k SET k.totalRequests = COALESCE(k.totalRequests, 0) + 1, k.successfulRequests = COALESCE(k.successfulRequests, 0) + 1, k.lastUsedAt = :now WHERE k.id = :id")
    int incrementSuccessAtomic(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE ModelApiKey k SET k.totalRequests = COALESCE(k.totalRequests, 0) + 1, k.failedRequests = COALESCE(k.failedRequests, 0) + 1, k.lastUsedAt = :now WHERE k.id = :id")
    int incrementFailureAtomic(@Param("id") Long id, @Param("now") LocalDateTime now);

    void deleteByIdAndDeploymentId(Long id, Long deploymentId);

    List<ModelApiKey> findByDeploymentIdAndOwnerIdOrderByCreatedAtDesc(Long deploymentId, Long ownerId);
}
