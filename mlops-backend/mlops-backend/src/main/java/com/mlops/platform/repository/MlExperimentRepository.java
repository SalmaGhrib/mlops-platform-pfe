package com.mlops.platform.repository;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlExperimentRepository
        extends JpaRepository<MlExperiment, Long> {

    interface FailedRunsByProjectProjection {
        Long getProjectId();

        Long getFailedRuns();
    }

    List<MlExperiment> findByProject(
            MlProject project
    );

    List<MlExperiment> findByProjectOrderByCreatedAtDesc(
            MlProject project
    );

    Optional<MlExperiment> findByIdAndProject(
            Long id,
            MlProject project
    );

    List<MlExperiment>
    findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
            MlProject project,
            String registeredModelName
    );

    List<MlExperiment>
    findByRegisteredModelNameOrderByCreatedAtDesc(
            String registeredModelName
    );

    List<MlExperiment>
    findByProjectOwnerOrderByCreatedAtDesc(
            User owner
    );

    @EntityGraph(
            attributePaths = {
                    "project",
                    "modelFile",
                    "datasetFile"
            }
    )
    List<MlExperiment> findByRuntimeModeOrderByCreatedAtAsc(
            String runtimeMode
    );

    /**
     * Runs Docker personnalisés qui nécessitent encore
     * une action du watcher.
     */
    @Query("""
        SELECT DISTINCT e
        FROM MlExperiment e
        JOIN FETCH e.project p
        LEFT JOIN FETCH e.modelFile mf
        LEFT JOIN FETCH e.datasetFile df
        WHERE e.runtimeMode = :runtimeMode
          AND e.status IN :statuses
        ORDER BY e.createdAt ASC
        """)
    List<MlExperiment> findActionableCustomDockerExperiments(
            @Param("runtimeMode")
            String runtimeMode,

            @Param("statuses")
            List<ExperimentStatus> statuses
    );

    /**
     * Recharge une expérience avec son projet et ses fichiers.
     */
    @Query("""
        SELECT DISTINCT e
        FROM MlExperiment e
        JOIN FETCH e.project p
        LEFT JOIN FETCH e.modelFile mf
        LEFT JOIN FETCH e.datasetFile df
        WHERE e.id = :id
        """)
    Optional<MlExperiment> findByIdWithFiles(
            @Param("id")
            Long id
    );

    /**
     * Sources Production avec les fichiers nécessaires au
     * réentraînement.
     */
    @Query("""
        SELECT DISTINCT e
        FROM MlExperiment e
        JOIN FETCH e.project p
        LEFT JOIN FETCH e.modelFile mf
        LEFT JOIN FETCH e.datasetFile df
        WHERE p.id = :projectId
          AND e.registeredModelName = :registeredModelName
          AND e.production = true
          AND e.status =
              com.mlops.platform.entity.ExperimentStatus.COMPLETED
          AND e.mlflowRunId IS NOT NULL
          AND e.mlflowRunId <> ''
          AND e.modelVersion IS NOT NULL
          AND e.modelVersion <> ''
          AND e.productionVersion IS NOT NULL
          AND e.productionVersion <> ''
          AND e.modelVersion = e.productionVersion
          AND e.primaryMetricValue IS NOT NULL
          AND e.globalScore IS NOT NULL
        ORDER BY COALESCE(
            e.finishedAt,
            e.startedAt,
            e.createdAt,
            e.updatedAt
        ) DESC,
        e.id DESC
        """)
    List<MlExperiment> findProductionRetrainingSourcesWithFiles(
            @Param("projectId")
            Long projectId,

            @Param("registeredModelName")
            String registeredModelName
    );

    /**
     * Charge les expériences utilisées par le reconciler Production.
     */
    @Query("""
        SELECT DISTINCT e
        FROM MlExperiment e
        JOIN FETCH e.project p
        LEFT JOIN FETCH e.modelFile mf
        LEFT JOIN FETCH e.datasetFile df
        ORDER BY e.createdAt DESC
        """)
    List<MlExperiment> findAllForProductionReconciliation();

    Optional<MlExperiment> findByMlflowRunId(
            String mlflowRunId
    );

    List<MlExperiment> findAllByMlflowRunId(
            String mlflowRunId
    );

    /**
     * Vérifie si un run MLflow est déjà lié à une AUTRE expérience.
     *
     * Remplace findAllByMlflowRunId (chargement complet de la liste)
     * dans les chemins de synchronisation : une simple requête EXISTS
     * suffit et évite de matérialiser des entités.
     */
    boolean existsByMlflowRunIdAndIdNot(
            String mlflowRunId,
            Long id
    );

    boolean existsByMlflowRunId(
            String mlflowRunId
    );

    /**
     * Registered model names d'un projet (Phase A du lifecycle auto),
     * sans charger les entités complètes.
     */
    @Query("""
        SELECT DISTINCT e.registeredModelName
        FROM MlExperiment e
        WHERE e.project.id = :projectId
          AND e.registeredModelName IS NOT NULL
          AND e.registeredModelName <> ''
        """)
    List<String> findDistinctRegisteredModelNamesByProjectId(
            @Param("projectId")
            Long projectId
    );

    /**
     * Sélectionne uniquement les IDs des expériences candidates à la
     * synchronisation d'un projet (Phase A de l'auto-sync).
     *
     * Une expérience est candidate si :
     * - elle est encore active (CREATED / RUNNING / CANCELLED) ;
     * - ou elle a été mise à jour récemment (fenêtre glissante) ;
     * - ou elle possède un run MLflow sans version encore (à réimporter).
     *
     * Le tri par updatedAt DESC garantit que les expériences récemment
     * modifiées sont traitées en priorité dans le lot.
     */
    @Query("""
        SELECT e.id
        FROM MlExperiment e
        WHERE e.project.id = :projectId
          AND (
                e.status IN :activeStatuses
                OR e.updatedAt >= :windowStart
                OR (e.mlflowRunId IS NOT NULL AND e.mlflowRunId <> '' AND e.modelVersion IS NULL)
          )
        ORDER BY e.updatedAt DESC
        """)
    List<Long> findCandidateExperimentIds(
            @Param("projectId")
            Long projectId,

            @Param("activeStatuses")
            List<com.mlops.platform.entity.ExperimentStatus> activeStatuses,

            @Param("windowStart")
            java.time.LocalDateTime windowStart,

            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Écriture courte et ciblée de l'état Production d'une expérience
     * (utilisée par les Phase C courtes). N'écrit que les colonnes utiles.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE ml_experiments
        SET is_production = :production,
            production_version = :productionVersion,
            updated_at = NOW()
        WHERE id = :id
        """, nativeQuery = true)
    int updateProductionStateById(
            @Param("id")
            Long id,

            @Param("production")
            boolean production,

            @Param("productionVersion")
            String productionVersion
    );

    Optional<MlExperiment> findByKubeflowRunId(
            String kubeflowRunId
    );

    Optional<MlExperiment>
    findFirstByExperimentNameAndRegisteredModelNameOrderByCreatedAtDesc(
            String experimentName,
            String registeredModelName
    );

    Optional<MlExperiment>
    findFirstByKubeflowRunNameAndRegisteredModelNameOrderByCreatedAtDesc(
            String kubeflowRunName,
            String registeredModelName
    );

    Optional<MlExperiment>
    findTopByProjectIdAndExperimentNameAndRegisteredModelNameOrderByUpdatedAtDesc(
            Long projectId,
            String experimentName,
            String registeredModelName
    );

    Optional<MlExperiment>
    findFirstByRegisteredModelNameAndMlflowRunIdIsNullOrderByCreatedAtDesc(
            String registeredModelName
    );

    Optional<MlExperiment>
    findFirstByProjectAndExperimentNameAndRegisteredModelNameOrderByCreatedAtDesc(
            MlProject project,
            String experimentName,
            String registeredModelName
    );

    List<MlExperiment>
    findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
            String registeredModelName,
            String modelVersion
    );

    long countByStatus(
            ExperimentStatus status
    );

    long countByRuntimeMode(
            String runtimeMode
    );

    long countByRegisteredModelNameIsNotNull();

    @Query(value = """
        SELECT COUNT(*)
        FROM ml_experiments
        WHERE runtime_mode = 'STANDARD_RUNTIME'
          AND status = 'COMPLETED'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 14400
        """, nativeQuery = true)
    long countStandardRuns();

    @Query(value = """
        SELECT COUNT(*)
        FROM ml_experiments
        WHERE runtime_mode = 'CUSTOM_DOCKERFILE'
          AND status = 'COMPLETED'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 21600
        """, nativeQuery = true)
    long countDockerRuns();

    @Query(value = """
        SELECT COALESCE(
            ROUND(
                AVG(
                    TIMESTAMPDIFF(
                        SECOND,
                        started_at,
                        finished_at
                    )
                ),
                1
            ),
            0
        )
        FROM ml_experiments
        WHERE status = 'COMPLETED'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 21600
        """, nativeQuery = true)
    double getAverageRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            MAX(
                TIMESTAMPDIFF(
                    SECOND,
                    started_at,
                    finished_at
                )
            ),
            0
        )
        FROM ml_experiments
        WHERE status = 'COMPLETED'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 21600
        """, nativeQuery = true)
    double getMaxRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            (
                SELECT TIMESTAMPDIFF(
                    SECOND,
                    started_at,
                    finished_at
                )
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(
                      SECOND,
                      started_at,
                      finished_at
                  ) BETWEEN 1 AND 21600
                ORDER BY finished_at DESC
                LIMIT 1
            ),
            0
        )
        """, nativeQuery = true)
    double getLastRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            ROUND(
                AVG(
                    TIMESTAMPDIFF(
                        SECOND,
                        started_at,
                        finished_at
                    )
                ),
                1
            ),
            0
        )
        FROM ml_experiments
        WHERE status = 'COMPLETED'
          AND runtime_mode = 'STANDARD_RUNTIME'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 14400
        """, nativeQuery = true)
    double getAverageStandardRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            (
                SELECT TIMESTAMPDIFF(
                    SECOND,
                    started_at,
                    finished_at
                )
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND runtime_mode = 'STANDARD_RUNTIME'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(
                      SECOND,
                      started_at,
                      finished_at
                  ) BETWEEN 1 AND 14400
                ORDER BY finished_at DESC
                LIMIT 1
            ),
            0
        )
        """, nativeQuery = true)
    double getLastStandardRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            ROUND(
                AVG(
                    TIMESTAMPDIFF(
                        SECOND,
                        started_at,
                        finished_at
                    )
                ),
                1
            ),
            0
        )
        FROM ml_experiments
        WHERE status = 'COMPLETED'
          AND runtime_mode = 'CUSTOM_DOCKERFILE'
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND finished_at > started_at
          AND TIMESTAMPDIFF(
              SECOND,
              started_at,
              finished_at
          ) BETWEEN 1 AND 21600
        """, nativeQuery = true)
    double getAverageDockerRunDurationSeconds();

    @Query(value = """
        SELECT COALESCE(
            (
                SELECT TIMESTAMPDIFF(
                    SECOND,
                    started_at,
                    finished_at
                )
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND runtime_mode = 'CUSTOM_DOCKERFILE'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(
                      SECOND,
                      started_at,
                      finished_at
                  ) BETWEEN 1 AND 21600
                ORDER BY finished_at DESC
                LIMIT 1
            ),
            0
        )
        """, nativeQuery = true)
    double getLastDockerRunDurationSeconds();

    @Query(value = """
        SELECT
            project_id AS projectId,
            COUNT(*) AS failedRuns
        FROM ml_experiments
        WHERE status = 'FAILED'
          AND project_id IS NOT NULL
          AND created_at >= DATE_SUB(
              NOW(),
              INTERVAL :hours HOUR
          )
        GROUP BY project_id
        HAVING COUNT(*) >= :threshold
        """, nativeQuery = true)
    List<FailedRunsByProjectProjection>
    findProjectsWithRecentFailedRuns(
            @Param("hours")
            int hours,

            @Param("threshold")
            long threshold
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM ml_experiments
        WHERE project_id = :projectId
          AND experiment_name LIKE 'scheduled_retraining_%'
          AND status IN ('CREATED', 'RUNNING')
        """, nativeQuery = true)
    Long countActiveScheduledRetrainingForProject(
            @Param("projectId")
            Long projectId
    );

    /**
     * Expériences de réentraînement actives d'un projet.
     *
     * Utilisé par la récupération automatique des états bloqués :
     * on vérifie ensuite l'âge du traitement et l'existence réelle du
     * Job Kubernetes avant de décider d'un éventuel passage en échec.
     */
    @Query(value = """
        SELECT *
        FROM ml_experiments
        WHERE project_id = :projectId
          AND experiment_name LIKE 'scheduled_retraining_%'
          AND status IN ('CREATED', 'RUNNING')
        ORDER BY created_at ASC
        """, nativeQuery = true)
    List<MlExperiment> findActiveRetrainingForProject(
            @Param("projectId")
            Long projectId
    );

    /**
     * Compte les expériences de réentraînement actives mais récentes
     * uniquement (avec limite d'âge).
     *
     * Une expérience CREATED/RUNNING trop ancienne n'est plus considérée
     * comme un réentraînement actif : elle est bloquée et récupérée.
     */
    @Query(value = """
        SELECT COUNT(*)
        FROM ml_experiments
        WHERE project_id = :projectId
          AND experiment_name LIKE 'scheduled_retraining_%'
          AND status IN ('CREATED', 'RUNNING')
          AND created_at >= DATE_SUB(NOW(), INTERVAL :hours HOUR)
        """, nativeQuery = true)
    Long countActiveRetrainingForProjectSince(
            @Param("projectId")
            Long projectId,

            @Param("hours")
            int hours
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM ml_experiments
        WHERE project_id = :projectId
          AND status = 'FAILED'
          AND created_at >= DATE_SUB(
              NOW(),
              INTERVAL :hours HOUR
          )
        """, nativeQuery = true)
    Long countFailedRunsForProjectSince(
            @Param("projectId")
            Long projectId,

            @Param("hours")
            int hours
    );

    /**
     * Recherche la Production courante d'un projet.
     *
     * Une source valide doit avoir :
     * - is_production = true ;
     * - status COMPLETED ;
     * - un run MLflow ;
     * - un registered model ;
     * - modelVersion = productionVersion ;
     * - une métrique principale ;
     * - un score global.
     */
    @Query(value = """
        SELECT e.*
        FROM ml_experiments e
        WHERE e.project_id = :projectId
          AND e.is_production = b'1'
          AND e.status = 'COMPLETED'
          AND e.mlflow_run_id IS NOT NULL
          AND e.mlflow_run_id <> ''
          AND e.registered_model_name IS NOT NULL
          AND e.registered_model_name <> ''
          AND e.model_version IS NOT NULL
          AND e.model_version <> ''
          AND e.production_version IS NOT NULL
          AND e.production_version <> ''
          AND e.model_version = e.production_version
          AND e.primary_metric_value IS NOT NULL
          AND e.global_score IS NOT NULL
        ORDER BY COALESCE(
            e.finished_at,
            e.started_at,
            e.created_at,
            e.updated_at
        ) DESC,
        e.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<MlExperiment>
    findCurrentProductionExperimentForProject(
            @Param("projectId")
            Long projectId
    );

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE ml_experiments
        SET is_production = b'0',
            model_version = CASE
                WHEN status IN ('FAILED', 'CANCELLED')
                    THEN NULL
                ELSE model_version
            END,
            latest_version = CASE
                WHEN status IN ('FAILED', 'CANCELLED')
                    THEN NULL
                ELSE latest_version
            END,
            production_version = NULL
        WHERE registered_model_name IS NOT NULL
          AND (
                status IN ('FAILED', 'CANCELLED')
                OR mlflow_run_id IS NULL
                OR mlflow_run_id = ''
                OR primary_metric_value IS NULL
                OR global_score IS NULL
          )
        """, nativeQuery = true)
    int invalidateAllNonEligibleVersions();

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE ml_experiments
        SET is_production = b'0'
        WHERE registered_model_name = :registeredModelName
        """, nativeQuery = true)
    int clearProductionByRegisteredModelName(
            @Param("registeredModelName")
            String registeredModelName
    );

    @Query(value = """
        SELECT DISTINCT registered_model_name
        FROM ml_experiments
        WHERE registered_model_name IS NOT NULL
          AND registered_model_name <> ''
        """, nativeQuery = true)
    List<String> findDistinctRegisteredModelNames();

    @Query(value = """
        SELECT *
        FROM ml_experiments
        WHERE registered_model_name = :registeredModelName
          AND status = 'COMPLETED'
          AND mlflow_run_id IS NOT NULL
          AND mlflow_run_id <> ''
          AND model_version IS NOT NULL
          AND model_version <> ''
          AND primary_metric_value IS NOT NULL
          AND global_score IS NOT NULL
        ORDER BY global_score DESC,
                 COALESCE(
                     finished_at,
                     started_at,
                     created_at,
                     updated_at
                 ) DESC,
                 id DESC
        """, nativeQuery = true)
    List<MlExperiment> findEligibleProductionCandidates(
            @Param("registeredModelName")
            String registeredModelName
    );

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE ml_experiments
        SET is_production = b'0',
            production_version = NULL
        WHERE registered_model_name = :registeredModelName
        """, nativeQuery = true)
    int clearProductionStateByRegisteredModelName(
            @Param("registeredModelName")
            String registeredModelName
    );

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE ml_experiments
        SET is_production = b'1',
            production_version = :productionVersion
        WHERE id = :experimentId
        """, nativeQuery = true)
    int markExperimentAsProduction(
            @Param("experimentId")
            Long experimentId,

            @Param("productionVersion")
            String productionVersion
    );
}