package com.mlops.platform.repository;

import com.mlops.platform.entity.ModelApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ModelApiUsageRepository extends JpaRepository<ModelApiUsage, Long> {

    List<ModelApiUsage> findByApiKeyIdAndRequestedAtAfterOrderByRequestedAtAsc(
            Long apiKeyId,
            LocalDateTime after
    );

    long countByApiKeyIdAndRequestedAtAfter(Long apiKeyId, LocalDateTime after);

    long countByApiKeyIdAndRequestedAtAfterAndSuccessTrue(Long apiKeyId, LocalDateTime after);

    long countByApiKeyIdAndRequestedAtAfterAndSuccessFalse(Long apiKeyId, LocalDateTime after);

    @Query("SELECT u FROM ModelApiUsage u "
            + "WHERE u.apiKeyId IN (SELECT k.id FROM ModelApiKey k WHERE k.deploymentId = :deploymentId) "
            + "AND u.requestedAt > :after ORDER BY u.requestedAt ASC")
    List<ModelApiUsage> findByDeploymentIdAndRequestedAtAfterOrderByRequestedAtAsc(
            @Param("deploymentId") Long deploymentId,
            @Param("after") LocalDateTime after
    );

    @Query("SELECT COUNT(u) FROM ModelApiUsage u "
            + "WHERE u.apiKeyId IN (SELECT k.id FROM ModelApiKey k WHERE k.deploymentId = :deploymentId) "
            + "AND u.requestedAt > :after")
    long countByDeploymentIdAndRequestedAtAfter(
            @Param("deploymentId") Long deploymentId,
            @Param("after") LocalDateTime after
    );

    @Query("SELECT COUNT(u) FROM ModelApiUsage u "
            + "WHERE u.apiKeyId IN (SELECT k.id FROM ModelApiKey k WHERE k.deploymentId = :deploymentId) "
            + "AND u.requestedAt > :after AND u.success = true")
    long countByDeploymentIdAndRequestedAtAfterAndSuccessTrue(
            @Param("deploymentId") Long deploymentId,
            @Param("after") LocalDateTime after
    );

    @Query("SELECT NEW map("
            + "COUNT(u) as total, "
            + "SUM(CASE WHEN u.success = true THEN 1 ELSE 0 END) as successCount, "
            + "COALESCE(AVG(CAST(u.latencyMs AS double)), 0.0) as avgLatency, "
            + "MAX(u.requestedAt) as lastUsedAt) "
            + "FROM ModelApiUsage u "
            + "WHERE u.apiKeyId IN (SELECT k.id FROM ModelApiKey k WHERE k.deploymentId = :deploymentId) "
            + "AND u.requestedAt > :after")
    java.util.Map<String, Object> getAggregatedStats(
            @Param("deploymentId") Long deploymentId,
            @Param("after") LocalDateTime after
    );

    @Query("SELECT NEW map("
            + "COUNT(u) as total, "
            + "SUM(CASE WHEN u.success = true THEN 1 ELSE 0 END) as successCount, "
            + "COALESCE(AVG(CAST(u.latencyMs AS double)), 0.0) as avgLatency, "
            + "MAX(u.requestedAt) as lastUsedAt) "
            + "FROM ModelApiUsage u "
            + "WHERE u.apiKeyId = :keyId "
            + "AND u.requestedAt > :after")
    java.util.Map<String, Object> getAggregatedStatsForKey(
            @Param("keyId") Long keyId,
            @Param("after") LocalDateTime after
    );
}
