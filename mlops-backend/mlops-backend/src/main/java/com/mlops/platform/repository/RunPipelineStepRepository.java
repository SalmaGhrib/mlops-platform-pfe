package com.mlops.platform.repository;

import com.mlops.platform.entity.RunPipelineStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface RunPipelineStepRepository extends JpaRepository<RunPipelineStep, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RunPipelineStep s WHERE s.experimentId = :experimentId AND s.stepCode = :stepCode")
    Optional<RunPipelineStep> findByExperimentIdAndStepCodeForUpdate(
            @Param("experimentId") Long experimentId,
            @Param("stepCode") String stepCode
    );

    Optional<RunPipelineStep> findByExperimentIdAndStepCode(Long experimentId, String stepCode);

    List<RunPipelineStep> findByExperimentIdOrderByOrderIndexAsc(Long experimentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RunPipelineStep s WHERE s.experimentId = :experimentId ORDER BY s.orderIndex ASC")
    List<RunPipelineStep> findByExperimentIdForUpdate(@Param("experimentId") Long experimentId);

    void deleteByExperimentId(Long experimentId);
}
