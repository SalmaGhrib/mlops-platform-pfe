package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.RetrainingConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrainingSourceResolverService {

    private final MlExperimentRepository experimentRepository;
    private final RetrainingConfigRepository configRepository;

    public RetrainingSourceResolverService(
            MlExperimentRepository experimentRepository,
            RetrainingConfigRepository configRepository
    ) {
        this.experimentRepository = experimentRepository;
        this.configRepository = configRepository;
    }

    /**
     * Source de vérité du réentraînement : la version Production valide du projet.
     * sourceExperimentId devient un cache auto-réparé, jamais une valeur à saisir manuellement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MlExperiment resolveAndSynchronize(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        MlExperiment production = experimentRepository
                .findCurrentProductionExperimentForProject(projectId)
                .orElseThrow(() -> new IllegalStateException(
                        "No valid Production experiment is available for projectId=" + projectId
                ));

        if (production.getId() == null) {
            throw new IllegalStateException(
                    "Production experiment has no id for projectId=" + projectId
            );
        }

        configRepository.synchronizeSourceExperiment(
                projectId,
                production.getId()
        );

        System.out.println("[RETRAINING_SOURCE] Production source synchronized. projectId="
                + projectId
                + ", sourceExperimentId=" + production.getId()
                + ", modelVersion=" + production.getModelVersion());

        return production;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long resolveSourceExperimentId(Long projectId) {
        return resolveAndSynchronize(projectId).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repairEnabledConfig(RetrainingConfig config) {
        if (config == null || !config.isEnabled() || config.getProjectId() == null) {
            return;
        }

        try {
            resolveAndSynchronize(config.getProjectId());
        } catch (IllegalStateException noProductionYet) {
            System.out.println("[RETRAINING_SOURCE] Repair postponed. projectId="
                    + config.getProjectId()
                    + ", reason=" + noProductionYet.getMessage());
        }
    }
}
