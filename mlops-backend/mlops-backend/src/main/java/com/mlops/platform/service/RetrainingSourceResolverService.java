package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Résout l'expérience source du réentraînement.
 *
 * Règles :
 * - la source est toujours l'expérience Production valide du projet ;
 * - sourceExperimentId reste un cache auto-réparé ;
 * - aucune écriture SQL directe n'est faite ici ;
 * - l'écriture est déléguée à RetrainingSourcePersistenceService,
 *   qui utilise une transaction courte REQUIRES_NEW avec retry.
 */
@Service
@RequiredArgsConstructor
public class RetrainingSourceResolverService {

    private final MlExperimentRepository experimentRepository;
    private final RetrainingSourcePersistenceService
            retrainingSourcePersistenceService;

    /**
     * Recherche la production actuelle puis synchronise le cache
     * sourceExperimentId dans une transaction indépendante.
     */
    public MlExperiment resolveAndSynchronize(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        MlExperiment production =
                experimentRepository
                        .findCurrentProductionExperimentForProject(projectId)
                        .orElseThrow(() -> new IllegalStateException(
                                "No valid Production experiment is available "
                                        + "for projectId=" + projectId
                        ));

        if (production.getId() == null) {
            throw new IllegalStateException(
                    "Production experiment has no id for projectId="
                            + projectId
            );
        }

        boolean synchronizedSuccessfully =
                retrainingSourcePersistenceService
                        .synchronizeSourceExperiment(
                                projectId,
                                production.getId()
                        );

        if (!synchronizedSuccessfully) {
            System.err.println(
                    "[RETRAINING_SOURCE] Source synchronization postponed. "
                            + "projectId=" + projectId
                            + ", sourceExperimentId=" + production.getId()
            );
        } else {
            System.out.println(
                    "[RETRAINING_SOURCE] Production source synchronized. "
                            + "projectId=" + projectId
                            + ", sourceExperimentId=" + production.getId()
                            + ", modelVersion="
                            + production.getModelVersion()
            );
        }

        return production;
    }

    public Long resolveSourceExperimentId(Long projectId) {
        MlExperiment production = resolveAndSynchronize(projectId);

        if (production.getId() == null) {
            throw new IllegalStateException(
                    "Resolved production experiment has no id. projectId="
                            + projectId
            );
        }

        return production.getId();
    }

    /**
     * Répare le cache d'une configuration active.
     *
     * Une absence temporaire de production n'est pas considérée comme
     * une erreur fatale : le prochain cycle du scheduler réessaiera.
     */
    public void repairEnabledConfig(RetrainingConfig config) {
        if (config == null
                || !config.isEnabled()
                || config.getProjectId() == null) {
            return;
        }

        try {
            resolveAndSynchronize(config.getProjectId());

        } catch (IllegalStateException noProductionYet) {
            System.out.println(
                    "[RETRAINING_SOURCE] Repair postponed. "
                            + "projectId=" + config.getProjectId()
                            + ", reason=" + noProductionYet.getMessage()
            );

        } catch (RuntimeException unexpectedError) {
            System.err.println(
                    "[RETRAINING_SOURCE] Repair failed. "
                            + "projectId=" + config.getProjectId()
                            + ", reason=" + unexpectedError.getMessage()
            );
        }
    }
}
