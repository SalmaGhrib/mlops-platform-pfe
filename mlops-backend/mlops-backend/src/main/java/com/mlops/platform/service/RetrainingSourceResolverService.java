package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.MlExperimentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Résout l'expérience Production utilisée comme source du réentraînement.
 *
 * Règles :
 * - sourceExperimentId est utilisé en priorité s'il est encore valide ;
 * - sinon, la Production courante du projet est recherchée ;
 * - la source est rechargée avec son projet et ses fichiers ;
 * - sourceExperimentId reste un cache auto-réparé ;
 * - l'écriture est déléguée à RetrainingSourcePersistenceService.
 */
@Service
@RequiredArgsConstructor
public class RetrainingSourceResolverService {

    private final MlExperimentRepository experimentRepository;

    private final RetrainingSourcePersistenceService
            retrainingSourcePersistenceService;

    /**
     * Résout la source Production.
     *
     * Ordre :
     * 1. sourceExperimentId enregistré dans la configuration ;
     * 2. Production courante trouvée dans le projet.
     */
    public MlExperiment resolveAndSynchronize(
            Long projectId,
            Long preferredSourceExperimentId
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "projectId is required"
            );
        }

        MlExperiment production = null;

        /*
         * Première tentative :
         * utiliser la source déjà enregistrée dans retraining_config.
         */
        if (preferredSourceExperimentId != null) {

            production = experimentRepository
                    .findByIdWithFiles(
                            preferredSourceExperimentId
                    )
                    .filter(experiment ->
                            isValidProductionExperiment(
                                    experiment,
                                    projectId
                            )
                    )
                    .orElse(null);

            if (production != null) {
                System.out.println(
                        "[RETRAINING_SOURCE] "
                                + "Valid cached Production source found. "
                                + "projectId="
                                + projectId
                                + ", sourceExperimentId="
                                + production.getId()
                                + ", modelVersion="
                                + production.getModelVersion()
                                + ", productionVersion="
                                + production.getProductionVersion()
                                + ", runtimeMode="
                                + production.getRuntimeMode()
                );

            } else {
                System.out.println(
                        "[RETRAINING_SOURCE] "
                                + "Cached source is missing or invalid. "
                                + "projectId="
                                + projectId
                                + ", sourceExperimentId="
                                + preferredSourceExperimentId
                );
            }
        }

        /*
         * Deuxième tentative :
         * rechercher la Production courante du projet.
         */
        if (production == null) {

            MlExperiment productionCandidate =
                    experimentRepository
                            .findCurrentProductionExperimentForProject(
                                    projectId
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "No valid Production experiment "
                                                    + "is available for projectId="
                                                    + projectId
                                    )
                            );

            /*
             * Recharge la source avec project, modelFile et datasetFile.
             */
            production = experimentRepository
                    .findByIdWithFiles(
                            productionCandidate.getId()
                    )
                    .filter(experiment ->
                            isValidProductionExperiment(
                                    experiment,
                                    projectId
                            )
                    )
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Production experiment could not be "
                                            + "reloaded with its files. "
                                            + "experimentId="
                                            + productionCandidate.getId()
                            )
                    );

            System.out.println(
                    "[RETRAINING_SOURCE] "
                            + "Current Production source resolved. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + production.getId()
                            + ", modelVersion="
                            + production.getModelVersion()
                            + ", productionVersion="
                            + production.getProductionVersion()
                            + ", runtimeMode="
                            + production.getRuntimeMode()
            );
        }

        synchronizeCache(
                projectId,
                production
        );

        return production;
    }

    /**
     * Compatibilité avec les anciens appels utilisant uniquement projectId.
     */
    public MlExperiment resolveAndSynchronize(
            Long projectId
    ) {
        return resolveAndSynchronize(
                projectId,
                null
        );
    }

    /**
     * Version utilisée par AutoRetrainingService.
     */
    public Long resolveSourceExperimentId(
            Long projectId,
            Long preferredSourceExperimentId
    ) {
        MlExperiment production =
                resolveAndSynchronize(
                        projectId,
                        preferredSourceExperimentId
                );

        if (production.getId() == null) {
            throw new IllegalStateException(
                    "Resolved Production experiment has no id. "
                            + "projectId="
                            + projectId
            );
        }

        return production.getId();
    }

    /**
     * Compatibilité avec les anciens appels.
     */
    public Long resolveSourceExperimentId(
            Long projectId
    ) {
        return resolveSourceExperimentId(
                projectId,
                null
        );
    }

    /**
     * Version non bloquante de resolveSourceExperimentId.
     *
     * Retourne null (sans exception) lorsqu'aucune Production valide n'existe
     * pour le projet. Utilisée par la validation du claim : une configuration
     * sans source ne doit pas être réclamée, mais son échéance doit pouvoir
     * être avancée proprement.
     */
    public Long resolveSourceExperimentIdOrNull(
            Long projectId,
            Long preferredSourceExperimentId
    ) {
        try {
            return resolveSourceExperimentId(
                    projectId,
                    preferredSourceExperimentId
            );
        } catch (IllegalStateException noProduction) {
            System.out.println(
                    "[RETRAINING_SOURCE] No Production source available. "
                            + "projectId="
                            + projectId
                            + ", preferredSourceExperimentId="
                            + preferredSourceExperimentId
                            + ", reason="
                            + safeMessage(
                                    noProduction.getMessage()
                            )
            );

            return null;
        } catch (RuntimeException unexpected) {
            System.err.println(
                    "[RETRAINING_SOURCE] Source resolution failed. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    unexpected.getMessage()
                            )
            );

            return null;
        }
    }

    /**
     * Répare le cache sourceExperimentId d'une configuration active.
     */
    public void repairEnabledConfig(
            RetrainingConfig config
    ) {
        if (config == null
                || !config.isEnabled()
                || config.getProjectId() == null) {
            return;
        }

        try {
            resolveAndSynchronize(
                    config.getProjectId(),
                    config.getSourceExperimentId()
            );

        } catch (IllegalStateException noProductionYet) {

            System.out.println(
                    "[RETRAINING_SOURCE] Repair postponed. "
                            + "projectId="
                            + config.getProjectId()
                            + ", sourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", reason="
                            + safeMessage(
                                    noProductionYet.getMessage()
                            )
            );

        } catch (RuntimeException unexpectedError) {

            System.err.println(
                    "[RETRAINING_SOURCE] Repair failed. "
                            + "projectId="
                            + config.getProjectId()
                            + ", sourceExperimentId="
                            + config.getSourceExperimentId()
                            + ", reason="
                            + safeMessage(
                                    unexpectedError.getMessage()
                            )
            );
        }
    }

    /**
     * Vérifie que l'expérience est bien la Production valide du projet.
     *
     * Compatible avec :
     * - STANDARD_RUNTIME ;
     * - CUSTOM_DOCKERFILE.
     */
    private boolean isValidProductionExperiment(
            MlExperiment experiment,
            Long projectId
    ) {
        if (experiment == null
                || experiment.getId() == null) {
            return false;
        }

        if (experiment.getProject() == null
                || experiment.getProject().getId() == null
                || !projectId.equals(
                        experiment.getProject().getId()
                )) {
            return false;
        }

        if (experiment.getStatus()
                != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (isBlank(
                experiment.getMlflowRunId()
        )) {
            return false;
        }

        if (isBlank(
                experiment.getRegisteredModelName()
        )) {
            return false;
        }

        String modelVersion =
                normalizeVersion(
                        experiment.getModelVersion()
                );

        String productionVersion =
                normalizeVersion(
                        experiment.getProductionVersion()
                );

        if (modelVersion == null
                || productionVersion == null) {
            return false;
        }

        return modelVersion.equals(
                productionVersion
        );
    }

    /**
     * Synchronise retraining_config.sourceExperimentId.
     */
    private void synchronizeCache(
            Long projectId,
            MlExperiment production
    ) {
        if (production == null
                || production.getId() == null) {
            throw new IllegalStateException(
                    "Cannot synchronize an empty Production source. "
                            + "projectId="
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
                    "[RETRAINING_SOURCE] "
                            + "Source synchronization postponed. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + production.getId()
            );

            return;
        }

        System.out.println(
                "[RETRAINING_SOURCE] "
                        + "Production source synchronized. "
                        + "projectId="
                        + projectId
                        + ", sourceExperimentId="
                        + production.getId()
                        + ", modelVersion="
                        + production.getModelVersion()
                        + ", productionVersion="
                        + production.getProductionVersion()
                        + ", runtimeMode="
                        + production.getRuntimeMode()
        );
    }

    private String normalizeVersion(
            Object value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                String.valueOf(value).trim();

        return normalized.isEmpty()
                ? null
                : normalized;
    }

    private boolean isBlank(
            String value
    ) {
        return value == null
                || value.isBlank();
    }

    private String safeMessage(
            String message
    ) {
        if (message == null
                || message.isBlank()) {
            return "Erreur inconnue";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }
}