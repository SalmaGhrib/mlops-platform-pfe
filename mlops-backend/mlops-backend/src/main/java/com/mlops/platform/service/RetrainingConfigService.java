package com.mlops.platform.service;

import com.mlops.platform.dto.RetrainingConfigRequest;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.entity.RetrainingFrequencyType;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RetrainingConfigService {

    private final RetrainingConfigRepository retrainingConfigRepository;

    public RetrainingConfigService(RetrainingConfigRepository retrainingConfigRepository) {
        this.retrainingConfigRepository = retrainingConfigRepository;
    }

    @Transactional
    public RetrainingConfig saveOrUpdateProjectConfig(
            Long projectId,
            RetrainingConfigRequest request
    ) {
        return saveOrUpdateInternal(projectId, null, request, false);
    }

    @Transactional
    public RetrainingConfig saveOrUpdateConfig(
            Long projectId,
            Long sourceExperimentId,
            RetrainingConfigRequest request
    ) {
        return saveOrUpdateInternal(projectId, sourceExperimentId, request, true);
    }

    private RetrainingConfig saveOrUpdateInternal(
            Long projectId,
            Long sourceExperimentId,
            RetrainingConfigRequest request,
            boolean requireSourceExperiment
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (requireSourceExperiment && sourceExperimentId == null) {
            throw new IllegalArgumentException("sourceExperimentId is required");
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseGet(RetrainingConfig::new);

        boolean enabled = request != null && request.isEnabled();

        RetrainingFrequencyType frequencyType = resolveFrequencyType(request);
        Integer frequencyValue = resolveFrequencyValue(request);

        config.setProjectId(projectId);

        if (sourceExperimentId != null) {
            config.setSourceExperimentId(sourceExperimentId);
        }

        config.setEnabled(enabled);
        config.setFrequencyType(frequencyType);
        config.setFrequencyValue(frequencyValue);
        config.setRunning(false);

        if (enabled) {
            config.setNextRetrainingAt(
                    config.calculateNextRetrainingDate(LocalDateTime.now())
            );
        } else {
            config.setNextRetrainingAt(null);
        }

        return retrainingConfigRepository.saveAndFlush(config);
    }

    public RetrainingConfig getConfig(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        return retrainingConfigRepository
                .findByProjectId(projectId)
                .orElse(null);
    }

    @Transactional
    public RetrainingConfig enableDefaultWeeklyRetraining(
            Long projectId,
            Long sourceExperimentId
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (sourceExperimentId == null) {
            throw new IllegalArgumentException("sourceExperimentId is required");
        }

        RetrainingConfigRequest request = new RetrainingConfigRequest();
        request.setEnabled(true);
        request.setFrequencyType(RetrainingFrequencyType.WEEK);
        request.setFrequencyValue(1);

        return saveOrUpdateConfig(projectId, sourceExperimentId, request);
    }

    @Transactional
    public RetrainingConfig disableRetraining(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No retraining config found for projectId=" + projectId
                ));

        config.setEnabled(false);
        config.setRunning(false);
        config.setNextRetrainingAt(null);

        return retrainingConfigRepository.saveAndFlush(config);
    }

    @Transactional
    public RetrainingConfig forceRetrainingNow(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        RetrainingConfig config = retrainingConfigRepository
                .findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No retraining config found for projectId=" + projectId
                ));

        if (!config.isEnabled()) {
            throw new IllegalStateException(
                    "Retraining is disabled for projectId=" + projectId
            );
        }

        if (config.isRunning()) {
            throw new IllegalStateException(
                    "Retraining is already running for projectId=" + projectId
            );
        }

        config.setRunning(false);
        config.setNextRetrainingAt(LocalDateTime.of(2000, 1, 1, 0, 0));

        return retrainingConfigRepository.saveAndFlush(config);
    }

    private RetrainingFrequencyType resolveFrequencyType(RetrainingConfigRequest request) {
        if (request == null || request.getFrequencyType() == null) {
            return RetrainingFrequencyType.WEEK;
        }

        return request.getFrequencyType();
    }

    private Integer resolveFrequencyValue(RetrainingConfigRequest request) {
        if (
                request == null ||
                request.getFrequencyValue() == null ||
                request.getFrequencyValue() <= 0
        ) {
            return 1;
        }

        return request.getFrequencyValue();
    }
}