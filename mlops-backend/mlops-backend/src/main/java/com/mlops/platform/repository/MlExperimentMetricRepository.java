package com.mlops.platform.repository;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlExperimentMetricRepository
        extends JpaRepository<MlExperimentMetric, Long> {

    List<MlExperimentMetric> findByExperiment(MlExperiment experiment);

    List<MlExperimentMetric> findByExperimentId(Long experimentId);

    Optional<MlExperimentMetric> findByExperimentAndMetricName(
            MlExperiment experiment,
            String metricName
    );

    Optional<MlExperimentMetric> findByExperimentIdAndMetricName(
            Long experimentId,
            String metricName
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MlExperimentMetric m where m.experiment.id = :experimentId")
    int deleteAllByExperimentIdBulk(
            @Param("experimentId") Long experimentId
    );

    default void deleteByExperimentId(Long experimentId) {
        if (experimentId != null) {
            deleteAllByExperimentIdBulk(experimentId);
        }
    }

    default void deleteByExperiment(MlExperiment experiment) {
        if (experiment != null && experiment.getId() != null) {
            deleteAllByExperimentIdBulk(experiment.getId());
        }
    }

    /*
     * MySQL/MariaDB UPSERT atomique.
     *
     * La contrainte unique (experiment_id, metric_name) garantit qu'une seule
     * ligne existe pour une métrique donnée. Cette requête ne charge aucune
     * entité MlExperimentMetric dans le contexte Hibernate et évite donc les
     * ObjectOptimisticLockingFailureException pendant les synchronisations.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO ml_experiment_metrics (
                        experiment_id,
                        metric_name,
                        metric_value,
                        direction,
                        production_version,
                        is_production,
                        global_score
                    )
                    VALUES (
                        :experimentId,
                        :metricName,
                        :metricValue,
                        :direction,
                        :productionVersion,
                        :production,
                        :globalScore
                    )
                    ON DUPLICATE KEY UPDATE
                        metric_value = VALUES(metric_value),
                        direction = VALUES(direction),
                        production_version = VALUES(production_version),
                        is_production = VALUES(is_production),
                        global_score = VALUES(global_score)
                    """,
            nativeQuery = true
    )
    int upsertMetric(
            @Param("experimentId") Long experimentId,
            @Param("metricName") String metricName,
            @Param("metricValue") Double metricValue,
            @Param("direction") String direction,
            @Param("productionVersion") String productionVersion,
            @Param("production") boolean production,
            @Param("globalScore") Double globalScore
    );

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update MlExperimentMetric m
               set m.globalScore = :globalScore
             where m.experiment.id = :experimentId
            """)
    int updateGlobalScoreByExperimentId(
            @Param("experimentId") Long experimentId,
            @Param("globalScore") Double globalScore
    );

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update MlExperimentMetric m
               set m.production = :production,
                   m.productionVersion = :productionVersion
             where m.experiment.id = :experimentId
            """)
    int updateProductionStateByExperimentId(
            @Param("experimentId") Long experimentId,
            @Param("production") boolean production,
            @Param("productionVersion") String productionVersion
    );
}