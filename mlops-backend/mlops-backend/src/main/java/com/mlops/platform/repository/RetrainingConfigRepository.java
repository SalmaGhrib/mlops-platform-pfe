package com.mlops.platform.repository;

import com.mlops.platform.entity.RetrainingConfig;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.sourceExperimentId = :sourceExperimentId,
                c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.projectId = :projectId
              AND (c.sourceExperimentId IS NULL OR c.sourceExperimentId <> :sourceExperimentId)
            """)
    int synchronizeSourceExperiment(
            @Param("projectId") Long projectId,
            @Param("sourceExperimentId") Long sourceExperimentId
    );

    /*
     * Scheduler query.
     * We keep the derived query for compatibility, but the scheduler now uses
     * findDueConfigs(...) + claimDueConfig(...) to avoid double launches on AKS.
     */
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
                    OR (c.nextRetrainingAt IS NOT NULL AND c.nextRetrainingAt <= :now)
              )
            ORDER BY c.nextRetrainingAt ASC, c.id ASC
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

    /*
     * Atomic claim: only one backend pod/scheduler cycle can claim the same config.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = true,
                c.updatedAt = :now
            WHERE c.id = :id
              AND c.enabled = true
              AND c.running = false
              AND (
                    c.manualTriggerRequested = true
                    OR (c.nextRetrainingAt IS NOT NULL AND c.nextRetrainingAt <= :now)
              )
            """)
    int claimDueConfig(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.lastRetrainingAt = :lastRetrainingAt,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
            """)
    int releaseAfterSuccess(
            @Param("id") Long id,
            @Param("lastRetrainingAt") LocalDateTime lastRetrainingAt,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
            """)
    int releaseAfterFailure(
            @Param("id") Long id,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );


    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.running = false,
                c.manualTriggerRequested = false,
                c.nextRetrainingAt = :nextRetrainingAt,
                c.lastDecisionReason = :decisionReason,
                c.lastMonitoringCheckAt = :updatedAt,
                c.updatedAt = :updatedAt
            WHERE c.id = :id
            """)
    int releaseAfterSkipped(
            @Param("id") Long id,
            @Param("nextRetrainingAt") LocalDateTime nextRetrainingAt,
            @Param("decisionReason") String decisionReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying
    @Transactional
    @Query("""
            UPDATE RetrainingConfig c
            SET c.nextRetrainingAt = :nextRetrainingAt,
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
    /*
     * If the backend pod is killed while a retraining launch is running, running=true
     * can stay blocked forever. This reset makes the scheduler self-healing in AKS.
     */
    @Modifying
    @Transactional
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
