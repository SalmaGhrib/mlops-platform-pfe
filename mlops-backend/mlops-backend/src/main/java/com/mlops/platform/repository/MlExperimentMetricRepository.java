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
public interface MlExperimentMetricRepository extends JpaRepository<MlExperimentMetric, Long> {

    List<MlExperimentMetric> findByExperiment(MlExperiment experiment);

    List<MlExperimentMetric> findByExperimentId(Long experimentId);

    Optional<MlExperimentMetric> findByExperimentAndMetricName(
            MlExperiment experiment,
            String metricName
    );

    /*
     * Très important :
     * On utilise un DELETE JPQL direct.
     * Cela évite que Hibernate charge chaque MlExperimentMetric en mémoire
     * puis essaye de supprimer une ligne déjà supprimée dans la même sync.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MlExperimentMetric m where m.experiment.id = :experimentId")
    void deleteAllByExperimentIdBulk(Long experimentId);

    /*
     * Compatibilité avec ton ancien code.
     * Comme ça, si ton service appelle encore deleteByExperimentId(...),
     * il utilisera le bulk delete sécurisé.
     */
    @Transactional
    default void deleteByExperimentId(Long experimentId) {
        deleteAllByExperimentIdBulk(experimentId);
    }

    /*
     * Compatibilité avec ton ancien code deleteByExperiment(experiment).
     */
    @Transactional
    default void deleteByExperiment(MlExperiment experiment) {
        if (experiment != null && experiment.getId() != null) {
            deleteAllByExperimentIdBulk(experiment.getId());
        }
    }
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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