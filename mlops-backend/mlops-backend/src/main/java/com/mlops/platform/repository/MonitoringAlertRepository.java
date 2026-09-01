package com.mlops.platform.repository;

import com.mlops.platform.entity.AlertSeverity;
import com.mlops.platform.entity.AlertType;
import com.mlops.platform.entity.MonitoringAlert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MonitoringAlertRepository
        extends JpaRepository<MonitoringAlert, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<MonitoringAlert> findByIdempotencyKey(String idempotencyKey);

    List<MonitoringAlert> findByProjectIdOrderByCreatedAtDesc(
            Long projectId,
            Pageable pageable
    );

    List<MonitoringAlert> findByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long projectId,
            LocalDateTime createdAt
    );

    List<MonitoringAlert> findByAlertTypeAndCreatedAtGreaterThanEqual(
            AlertType alertType,
            LocalDateTime createdAt
    );

    long countBySeverityAndCreatedAtGreaterThanEqual(
            AlertSeverity severity,
            LocalDateTime createdAt
    );

    long countByCreatedAtGreaterThanEqual(
            LocalDateTime createdAt
    );

    List<MonitoringAlert> findTop20ByOrderByCreatedAtDesc();

    List<MonitoringAlert> findTop50ByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            LocalDateTime createdAt
    );

    boolean existsByAlertTypeAndProjectIdAndModelVersion(
            AlertType alertType,
            Long projectId,
            String modelVersion
    );
}