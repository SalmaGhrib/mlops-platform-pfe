package com.mlops.platform.service;

import com.mlops.platform.repository.RetrainingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RetrainingSchedulerPersistenceService {

    private final RetrainingConfigRepository retrainingConfigRepository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public int resetStuckRunningConfigs(
            LocalDateTime cutoff,
            LocalDateTime now
    ) {
        return retrainingConfigRepository.resetStuckRunningConfigs(
                cutoff,
                now
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public int claimDueConfig(
            Long configId,
            LocalDateTime now
    ) {
        return retrainingConfigRepository.claimDueConfig(
                configId,
                now
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public int releaseAfterFailure(
            Long configId,
            LocalDateTime nextRetrainingAt,
            LocalDateTime updatedAt
    ) {
        return retrainingConfigRepository.releaseAfterFailure(
                configId,
                nextRetrainingAt,
                updatedAt
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public int releaseAfterSuccess(
            Long configId,
            LocalDateTime lastRetrainingAt,
            LocalDateTime nextRetrainingAt,
            LocalDateTime updatedAt
    ) {
        return retrainingConfigRepository.releaseAfterSuccess(
                configId,
                lastRetrainingAt,
                nextRetrainingAt,
                updatedAt
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 10
    )
    public int releaseAfterSkipped(
            Long configId,
            LocalDateTime nextRetrainingAt,
            String decisionReason,
            LocalDateTime updatedAt
    ) {
        return retrainingConfigRepository.releaseAfterSkipped(
                configId,
                nextRetrainingAt,
                decisionReason,
                updatedAt
        );
    }
}