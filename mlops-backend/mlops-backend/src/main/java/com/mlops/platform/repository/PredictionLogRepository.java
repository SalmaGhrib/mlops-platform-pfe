package com.mlops.platform.repository;

import com.mlops.platform.entity.PredictionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PredictionLogRepository extends JpaRepository<PredictionLog, Long> {

    List<PredictionLog> findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long projectId,
            LocalDateTime start,
            LocalDateTime end
    );

    Long countByProjectIdAndCreatedAtAfter(
            Long projectId,
            LocalDateTime after
    );

    Long countByProjectIdAndSuccessFalseAndCreatedAtAfter(
            Long projectId,
            LocalDateTime after
    );
}
