package com.mlops.platform.repository;

import com.mlops.platform.entity.PredictionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PredictionLogRepository
        extends JpaRepository<PredictionLog, Long> {

    /**
     * Prédictions d'un projet dans une fenêtre temporelle.
     *
     * Conservé car utilisé par le dashboard de monitoring
     * et d'autres fonctionnalités historiques.
     */
    List<PredictionLog> findByProjectIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long projectId,
            LocalDateTime start,
            LocalDateTime end
    );

    /**
     * Nombre de prédictions récentes d'un projet.
     */
    Long countByProjectIdAndCreatedAtAfter(
            Long projectId,
            LocalDateTime after
    );

    /**
     * Nombre de prédictions échouées récentes.
     */
    Long countByProjectIdAndSuccessFalseAndCreatedAtAfter(
            Long projectId,
            LocalDateTime after
    );

    /**
     * Retourne uniquement les prédictions réussies correspondant
     * exactement à l'expérience actuellement en Production.
     *
     * Cette méthode sert à construire les fenêtres de référence
     * utilisées pour le calcul du data drift.
     *
     * Cela évite de mélanger :
     * - plusieurs versions du même modèle ;
     * - d'anciennes expériences ;
     * - les prédictions en échec.
     */
    @Query("""
            SELECT p
            FROM PredictionLog p
            WHERE p.projectId = :projectId
              AND p.experimentId = :experimentId
              AND p.success = true
            ORDER BY p.createdAt ASC
            """)
    List<PredictionLog> findSuccessfulPredictionsForProduction(
            @Param("projectId") Long projectId,
            @Param("experimentId") Long experimentId
    );
}