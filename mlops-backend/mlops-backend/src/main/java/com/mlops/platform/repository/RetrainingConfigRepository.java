package com.mlops.platform.repository;

import com.mlops.platform.entity.RetrainingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RetrainingConfigRepository extends JpaRepository<RetrainingConfig, Long> {

    Optional<RetrainingConfig> findByProjectId(Long projectId);

    @Query("""
            SELECT c
            FROM RetrainingConfig c
            WHERE c.enabled = true
            ORDER BY c.id ASC
            """)
    List<RetrainingConfig> findEnabledConfigs();

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.sourceExperimentId = :sourceExperimentId,
                c.updatedAt = :updatedAt
            WHERE c.projectId = :projectId
              AND (
                    c.sourceExperimentId IS NULL
                    OR c.sourceExperimentId <> :sourceExperimentId
              )
            """)
    int synchronizeSourceExperiment(
            @Param("projectId") Long projectId,
            @Param("sourceExperimentId") Long sourceExperimentId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    List<RetrainingConfig> findByEnabledTrueAndRunningFalseAndNextRetrainingAtIsNotNullAndNextRetrainingAtLessThanEqual(
            LocalDateTime now
    );

    @Query("""
            SELECT c
            FROM RetrainingConfig c
            WHERE c.enabled = true
              AND c.running = false
              AND (
                    c.manualTriggerRequested = true
                    OR (
                        c.nextRetrainingAt IS NOT NULL
                        AND c.nextRetrainingAt <= :now
                    )
              )
            ORDER BY
                CASE WHEN c.manualTriggerRequested = true THEN 0 ELSE 1 END,
                c.nextRetrainingAt ASC,
                c.id ASC
            """)
    List<RetrainingConfig> findDueConfigs(@Param("now") LocalDateTime now);

    @Query("""
            SELECT c
            FROM RetrainingConfig c
            WHERE c.enabled = true
              AND c.running = false
            ORDER BY c.updatedAt ASC, c.id ASC
            """)
    List<RetrainingConfig> findEnabledNotRunningConfigs();

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = true,
                c.updatedAt = :now
            WHERE c.id = :id
              AND c.enabled = true
              AND c.running = false
              AND (
                    c.manualTriggerRequested = true
                    OR (
                        c.nextRetrainingAt IS NOT NULL
                        AND c.nextRetrainingAt <= :now
                    )
              )
            """)
    int claimDueConfig(
            @Param("id") Long id,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.lastRetrainingAt = :lastRetrainingAt,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
              AND c.running = true
            """)
    int releaseAfterSuccess(
            @Param("id") Long id,
            @Param("lastRetrainingAt") LocalDateTime lastRetrainingAt,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
              AND c.running = true
            """)
    int releaseAfterFailure(
            @Param("id") Long id,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastDecisionReason = :decisionReason,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
              AND c.running = true
            """)
    int releaseAfterSkipped(
            @Param("id") Long id,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("decisionReason") String decisionReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    /*
     * Demande intelligente atomique.
     *
     * manualTriggerRequested=true est le signal durable principal.
     * nextRetrainingAt placé dans le passé reste un signal complémentaire.
     * Ainsi, une légère différence d'horloge ou un cycle concurrent ne peut
     * pas faire disparaître silencieusement la demande.
     */
    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.manualTriggerRequested = true,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastTriggerReason = :triggerReason,
                c.lastDecisionReason = :decisionReason,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
              AND c.enabled = true
              AND c.running = false
            """)
    int requestSmartRetraining(
            @Param("id") Long id,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("triggerReason") String triggerReason,
            @Param("decisionReason") String decisionReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.updatedAt = :now
            WHERE c.running = true
              AND c.updatedAt IS NOT NULL
              AND c.updatedAt < :cutoff
            """)
    int resetStuckRunningConfigs(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now
    );
}