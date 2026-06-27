package com.mlops.platform.repository;

import com.mlops.platform.entity.RetrainingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RetrainingConfigRepository extends JpaRepository<RetrainingConfig, Long> {

    Optional<RetrainingConfig> findByProjectId(Long projectId);

    /*
     * Le scheduler ne doit récupérer que :
     * - enabled = true
     * - running = false
     * - nextRetrainingAt non null
     * - nextRetrainingAt <= now
     */
    List<RetrainingConfig> findByEnabledTrueAndRunningFalseAndNextRetrainingAtIsNotNullAndNextRetrainingAtLessThanEqual(
            LocalDateTime now
    );
}