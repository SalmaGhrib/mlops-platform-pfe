package com.mlops.platform.repository;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlExperimentRepository extends JpaRepository<MlExperiment, Long> {

    /*
     * Projection utilisée par le monitoring.
     * Elle permet de récupérer :
     * - projectId
     * - nombre de runs échoués récents pour ce projet
     */
    interface FailedRunsByProjectProjection {
        Long getProjectId();
        Long getFailedRuns();
    }

    /*
     * Récupérer toutes les expériences d’un projet.
     */
    List<MlExperiment> findByProject(MlProject project);

    /*
     * Récupérer toutes les expériences d’un projet triées par date décroissante.
     */
    List<MlExperiment> findByProjectOrderByCreatedAtDesc(MlProject project);

    /*
     * Récupérer une expérience précise dans un projet précis.
     */
    Optional<MlExperiment> findByIdAndProject(Long id, MlProject project);

    /*
     * Récupérer les expériences d’un projet pour un registered model précis.
     */
    List<MlExperiment> findByProjectAndRegisteredModelNameOrderByCreatedAtDesc(
            MlProject project,
            String registeredModelName
    );

    /*
     * Récupérer toutes les expériences liées à un registered model.
     */
    List<MlExperiment> findByRegisteredModelNameOrderByCreatedAtDesc(
            String registeredModelName
    );

    /*
     * Récupérer les expériences de tous les projets appartenant à un utilisateur.
     */
    List<MlExperiment> findByProjectOwnerOrderByCreatedAtDesc(User owner);

    /*
     * Récupérer les expériences par runtime mode.
     */
    @EntityGraph(attributePaths = {"project", "modelFile", "datasetFile"})
    List<MlExperiment> findByRuntimeModeOrderByCreatedAtAsc(String runtimeMode);

    /**
     * Runs Docker personnalisés qui nécessitent encore une action du watcher.
     *
     * Important : ne pas recharger les runs COMPLETED/FAILED/CANCELLED. Le watcher
     * ne doit gérer que la construction Kaniko et le job d'entraînement en cours.
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
            @Param("runtimeMode") String runtimeMode,
            @Param("statuses") List<ExperimentStatus> statuses
    );

    /**
     * Recharge une expérience avec toutes les relations nécessaires au watcher.
     *
     * Cette méthode évite les LazyInitializationException lorsque le scheduler
     * accède au projet, au script Python ou au dataset après la fermeture de la
     * session Hibernate.
     */
    @Query("""
            SELECT DISTINCT e
            FROM MlExperiment e
            JOIN FETCH e.project p
            LEFT JOIN FETCH e.modelFile mf
            LEFT JOIN FETCH e.datasetFile df
            WHERE e.id = :id
            """)
    Optional<MlExperiment> findByIdWithFiles(@Param("id") Long id);

    /**
     * Production retraining sources loaded with all file relations.
     *
     * The first valid row is the most recent Production row. Returning a list
     * avoids non-portable JPQL LIMIT syntax while keeping JOIN FETCH support.
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
              AND e.status = com.mlops.platform.entity.ExperimentStatus.COMPLETED
              AND e.mlflowRunId IS NOT NULL
              AND e.mlflowRunId <> ''
              AND e.modelVersion IS NOT NULL
              AND e.modelVersion <> ''
              AND e.productionVersion IS NOT NULL
              AND e.productionVersion <> ''
              AND e.modelVersion = e.productionVersion
              AND e.primaryMetricValue IS NOT NULL
              AND e.globalScore IS NOT NULL
            ORDER BY COALESCE(e.finishedAt, e.startedAt, e.createdAt, e.updatedAt) DESC,
                     e.id DESC
            """)
    List<MlExperiment> findProductionRetrainingSourcesWithFiles(
            @Param("projectId") Long projectId,
            @Param("registeredModelName") String registeredModelName
    );


    /**
     * Charge les expériences nécessaires au reconciler avec le projet déjà
     * initialisé. Cela évite les LazyInitializationException dans les tâches
     * planifiées et permet de comparer toutes les versions d'un même modèle.
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

    /*
     * Recherche par MLflow Run ID.
     */
    Optional<MlExperiment> findByMlflowRunId(String mlflowRunId);

    /*
     * Important : détecter si un MLflow Run ID est déjà lié à une ou plusieurs lignes.
     */
    List<MlExperiment> findAllByMlflowRunId(String mlflowRunId);

    /*
     * Recherche par Kubeflow Run ID.
     */
    Optional<MlExperiment> findByKubeflowRunId(String kubeflowRunId);

    /*
     * Recherche par nom d’expérience + registered model.
     */
    Optional<MlExperiment> findFirstByExperimentNameAndRegisteredModelNameOrderByCreatedAtDesc(
            String experimentName,
            String registeredModelName
    );

    /*
     * Recherche par nom Kubeflow + registered model.
     */
    Optional<MlExperiment> findFirstByKubeflowRunNameAndRegisteredModelNameOrderByCreatedAtDesc(
            String kubeflowRunName,
            String registeredModelName
    );

    /*
     * Recherche stricte dans un projet par experimentName + registeredModelName.
     * On garde updatedAt ici car cette méthode sert à retrouver la ligne la plus récemment synchronisée.
     * Ce n’est pas utilisé pour calculer la durée du run.
     */
    Optional<MlExperiment> findTopByProjectIdAndExperimentNameAndRegisteredModelNameOrderByUpdatedAtDesc(
            Long projectId,
            String experimentName,
            String registeredModelName
    );

    /*
     * Dernière ligne d’un modèle qui n’a pas encore mlflowRunId.
     */
    Optional<MlExperiment> findFirstByRegisteredModelNameAndMlflowRunIdIsNullOrderByCreatedAtDesc(
            String registeredModelName
    );

    /*
     * Recherche dans un projet précis avec runName + registeredModelName.
     */
    Optional<MlExperiment> findFirstByProjectAndExperimentNameAndRegisteredModelNameOrderByCreatedAtDesc(
            MlProject project,
            String experimentName,
            String registeredModelName
    );

    /*
     * Récupérer toutes les lignes d’un registered model + version.
     */
    List<MlExperiment> findByRegisteredModelNameAndModelVersionOrderByCreatedAtDesc(
            String registeredModelName,
            String modelVersion
    );

    /*
     * Compteurs globaux utilisés par les métriques Prometheus.
     */
    long countByStatus(ExperimentStatus status);

    long countByRuntimeMode(String runtimeMode);

    long countByRegisteredModelNameIsNotNull();

    /*
     * Nombre de runs STANDARD réellement mesurables.
     *
     * Correction :
     * - seulement COMPLETED
     * - started_at et finished_at obligatoires
     * - finished_at > started_at
     * - durée max standard = 4h
     *
     * Pourquoi 4h ?
     * Parce que tes anciens runs corrompus ont des durées de plusieurs jours.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM ml_experiments
            WHERE runtime_mode = 'STANDARD_RUNTIME'
              AND status = 'COMPLETED'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 14400
            """, nativeQuery = true)
    long countStandardRuns();

    /*
     * Nombre de runs CUSTOM_DOCKERFILE réellement mesurables.
     *
     * Durée max dockerisée = 6h.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM ml_experiments
            WHERE runtime_mode = 'CUSTOM_DOCKERFILE'
              AND status = 'COMPLETED'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
            """, nativeQuery = true)
    long countDockerRuns();

    /*
     * Durées globales réelles.
     *
     * Correction importante :
     * - durée réelle = finished_at - started_at
     * - ne jamais utiliser updated_at
     * - updated_at change à chaque synchronisation
     * - on garde seulement les runs COMPLETED
     * - on ignore les durées impossibles de plus de 6h
     */
    @Query(value = """
            SELECT COALESCE(ROUND(AVG(TIMESTAMPDIFF(SECOND, started_at, finished_at)), 1), 0)
            FROM ml_experiments
            WHERE status = 'COMPLETED'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
            """, nativeQuery = true)
    double getAverageRunDurationSeconds();

    @Query(value = """
            SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, started_at, finished_at)), 0)
            FROM ml_experiments
            WHERE status = 'COMPLETED'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
            """, nativeQuery = true)
    double getMaxRunDurationSeconds();

    @Query(value = """
            SELECT COALESCE((
                SELECT TIMESTAMPDIFF(SECOND, started_at, finished_at)
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
                ORDER BY finished_at DESC
                LIMIT 1
            ), 0)
            """, nativeQuery = true)
    double getLastRunDurationSeconds();

    /*
     * Durées réelles du mode STANDARD_RUNTIME uniquement.
     *
     * Correction :
     * on ignore les durées standard impossibles de plus de 4h.
     */
    @Query(value = """
            SELECT COALESCE(ROUND(AVG(TIMESTAMPDIFF(SECOND, started_at, finished_at)), 1), 0)
            FROM ml_experiments
            WHERE status = 'COMPLETED'
              AND runtime_mode = 'STANDARD_RUNTIME'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 14400
            """, nativeQuery = true)
    double getAverageStandardRunDurationSeconds();

    @Query(value = """
            SELECT COALESCE((
                SELECT TIMESTAMPDIFF(SECOND, started_at, finished_at)
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND runtime_mode = 'STANDARD_RUNTIME'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 14400
                ORDER BY finished_at DESC
                LIMIT 1
            ), 0)
            """, nativeQuery = true)
    double getLastStandardRunDurationSeconds();

    /*
     * Durées réelles du mode CUSTOM_DOCKERFILE uniquement.
     *
     * Correction :
     * on ignore les durées dockerisées impossibles de plus de 6h.
     */
    @Query(value = """
            SELECT COALESCE(ROUND(AVG(TIMESTAMPDIFF(SECOND, started_at, finished_at)), 1), 0)
            FROM ml_experiments
            WHERE status = 'COMPLETED'
              AND runtime_mode = 'CUSTOM_DOCKERFILE'
              AND started_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND finished_at > started_at
              AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
            """, nativeQuery = true)
    double getAverageDockerRunDurationSeconds();

    @Query(value = """
            SELECT COALESCE((
                SELECT TIMESTAMPDIFF(SECOND, started_at, finished_at)
                FROM ml_experiments
                WHERE status = 'COMPLETED'
                  AND runtime_mode = 'CUSTOM_DOCKERFILE'
                  AND started_at IS NOT NULL
                  AND finished_at IS NOT NULL
                  AND finished_at > started_at
                  AND TIMESTAMPDIFF(SECOND, started_at, finished_at) BETWEEN 1 AND 21600
                ORDER BY finished_at DESC
                LIMIT 1
            ), 0)
            """, nativeQuery = true)
    double getLastDockerRunDurationSeconds();

    /*
     * Monitoring par projet :
     * trouver les projets qui ont des runs échoués récemment.
     *
     * Ici on garde created_at car on cherche les échecs créés récemment,
     * pas la durée des runs.
     */
    @Query(value = """
            SELECT project_id AS projectId, COUNT(*) AS failedRuns
            FROM ml_experiments
            WHERE status = 'FAILED'
              AND project_id IS NOT NULL
              AND created_at >= DATE_SUB(NOW(), INTERVAL :hours HOUR)
            GROUP BY project_id
            HAVING COUNT(*) >= :threshold
            """, nativeQuery = true)
    List<FailedRunsByProjectProjection> findProjectsWithRecentFailedRuns(
            @Param("hours") int hours,
            @Param("threshold") long threshold
    );

    /*
     * Vérifie s’il existe déjà un réentraînement automatique actif pour un projet.
     *
     * Important : avec MySQL + nativeQuery, SELECT COUNT(*) > 0 peut revenir comme Long/BigInteger.
     * Si Spring essaie de caster directement vers boolean, on obtient :
     * class java.lang.Long cannot be cast to class java.lang.Boolean.
     *
     * On retourne donc un compteur Long, puis le service fait count > 0.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM ml_experiments
            WHERE project_id = :projectId
              AND experiment_name LIKE 'scheduled_retraining_%'
              AND status IN ('CREATED', 'RUNNING')
            """, nativeQuery = true)
    Long countActiveScheduledRetrainingForProject(
            @Param("projectId") Long projectId
    );


    @Query(value = """
            SELECT COUNT(*)
            FROM ml_experiments
            WHERE project_id = :projectId
              AND status = 'FAILED'
              AND created_at >= DATE_SUB(NOW(), INTERVAL :hours HOUR)
            """, nativeQuery = true)
    Long countFailedRunsForProjectSince(
            @Param("projectId") Long projectId,
            @Param("hours") int hours
    );

    @Query(value = """
            SELECT e.*
            FROM ml_experiments e
            WHERE e.project_id = :projectId
              AND e.is_production = b'1'
              AND e.status = 'COMPLETED'
              AND e.model_version IS NOT NULL
              AND e.primary_metric_value IS NOT NULL
              AND e.global_score IS NOT NULL
            ORDER BY COALESCE(e.finished_at, e.started_at, e.created_at) DESC, e.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<MlExperiment> findCurrentProductionExperimentForProject(
            @Param("projectId") Long projectId
    );

    /*
     * Versioning/Production consistency guards.
     * A run without metrics and a global score is not a valid platform model version.
     * It must never be Production and must never be deployed.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE ml_experiments
            SET is_production = b'0',
                model_version = NULL,
                latest_version = NULL,
                production_version = NULL
            WHERE registered_model_name IS NOT NULL
              AND (
                    status <> 'COMPLETED'
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
            @Param("registeredModelName") String registeredModelName
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
                     COALESCE(finished_at, started_at, created_at, updated_at) DESC,
                     id DESC
            """, nativeQuery = true)
    List<MlExperiment> findEligibleProductionCandidates(
            @Param("registeredModelName") String registeredModelName
    );

}
