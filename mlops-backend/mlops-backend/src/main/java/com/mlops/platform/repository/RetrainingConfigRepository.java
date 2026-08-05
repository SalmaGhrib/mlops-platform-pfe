package com.mlops.platform.repository;

import com.mlops.platform.entity.RetrainingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RetrainingConfigRepository
        extends JpaRepository<RetrainingConfig, Long> {

    Optional<RetrainingConfig> findByProjectId(
            Long projectId
    );

    /**
     * Retourne uniquement la source actuellement enregistrée.
     *
     * Cette lecture permet d'éviter un UPDATE inutile lorsque le cache est
     * déjà synchronisé avec la Production courante.
     */
    @Query("""
        select c.sourceExperimentId
        from RetrainingConfig c
        where c.projectId = :projectId
        """)
    Optional<Long> findSourceExperimentIdByProjectId(
            @Param("projectId") Long projectId
    );

    /**
     * Configurations actives.
     *
     * Cette méthode est une lecture pure : elle ne doit provoquer aucune
     * modification de sourceExperimentId.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
        order by c.id asc
        """)
    List<RetrainingConfig> findEnabledConfigs();

    /**
     * Met à jour sourceExperimentId uniquement lorsque la valeur a
     * réellement changé.
     *
     * La transaction est gérée par RetrainingSourcePersistenceService.
     * Il ne faut donc pas placer REQUIRES_NEW ici.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.sourceExperimentId = :sourceExperimentId,
               c.updatedAt = :updatedAt
         where c.projectId = :projectId
           and (
                c.sourceExperimentId is null
                or c.sourceExperimentId <> :sourceExperimentId
           )
        """)
    int updateSourceExperimentIfChanged(
            @Param("projectId")
            Long projectId,

            @Param("sourceExperimentId")
            Long sourceExperimentId,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Ancienne méthode Spring Data conservée si elle est encore utilisée
     * ailleurs dans le projet.
     *
     * Pour le scheduler principal, utiliser findDueConfigs().
     */
    List<RetrainingConfig>
    findByEnabledTrueAndRunningFalseAndNextRetrainingAtIsNotNullAndNextRetrainingAtLessThanEqual(
            LocalDateTime now
    );

    /**
     * Configurations réclamables par le scheduler.
     *
     * Une configuration est due lorsque :
     * - une demande manuelle existe ;
     * - ou nextRetrainingAt est atteint.
     *
     * La stratégie seule ne rend jamais une configuration due.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
          and c.running = false
          and (
                c.manualTriggerRequested = true
                or (
                    c.nextRetrainingAt is not null
                    and c.nextRetrainingAt <= :now
                )
          )
        order by
            case
                when c.manualTriggerRequested = true then 0
                else 1
            end,
            c.nextRetrainingAt asc,
            c.id asc
        """)
    List<RetrainingConfig> findDueConfigs(
            @Param("now") LocalDateTime now
    );

    /**
     * Configurations actives qui ne sont pas actuellement réclamées.
     *
     * Cette méthode est utile pour la réparation périodique, mais le service
     * de réparation doit éviter toute écriture lorsque la source est déjà
     * correcte.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
          and c.running = false
        order by c.updatedAt asc, c.id asc
        """)
    List<RetrainingConfig> findEnabledNotRunningConfigs();

    /**
     * Claim atomique.
     *
     * Deux pods ou deux threads ne peuvent pas réclamer la même configuration.
     * La transaction courte est gérée par le scheduler/service appelant.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = true,
               c.updatedAt = :now
         where c.id = :id
           and c.enabled = true
           and c.running = false
           and (
                c.manualTriggerRequested = true
                or (
                    c.nextRetrainingAt is not null
                    and c.nextRetrainingAt <= :now
                )
           )
        """)
    int claimDueConfig(
            @Param("id")
            Long id,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Claim atomique pour un lancement manuel (force-now).
     *
     * Deux requêtes concurrentes ne peuvent pas réserver la même
     * configuration : la condition running=false rend le claim exclusif.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = true,
               c.manualTriggerRequested = false,
               c.lastTriggerReason = 'MANUAL',
               c.updatedAt = :now
         where c.id = :id
           and c.enabled = true
           and c.running = false
        """)
    int claimManualRetraining(
            @Param("id")
            Long id,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Libération après création réussie d'un nouveau run.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.lastRetrainingAt = :lastRetrainingAt,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'SUCCESS',
               c.updatedAt = :updatedAt
         where c.id = :id
           and c.running = true
        """)
    int releaseAfterSuccess(
            @Param("id")
            Long id,

            @Param("lastRetrainingAt")
            LocalDateTime lastRetrainingAt,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Libération après échec du lancement.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'FAILED',
               c.updatedAt = :updatedAt
         where c.id = :id
           and c.running = true
        """)
    int releaseAfterFailure(
            @Param("id")
            Long id,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Libération lorsqu'aucun signal ne justifie un réentraînement.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastDecisionReason = :decisionReason,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'SKIPPED',
               c.updatedAt = :updatedAt
         where c.id = :id
           and c.running = true
        """)
    int releaseAfterSkipped(
            @Param("id")
            Long id,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("decisionReason")
            String decisionReason,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Demande manuelle ou intelligente atomique.
     *
     * La condition running=false empêche de demander un second lancement
     * pendant qu'un premier traitement est encore actif.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.manualTriggerRequested = true,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastTriggerReason = :triggerReason,
               c.lastDecisionReason = :decisionReason,
               c.updatedAt = :updatedAt
         where c.id = :id
           and c.enabled = true
           and c.running = false
           and c.manualTriggerRequested = false
        """)
    int requestSmartRetraining(
            @Param("id")
            Long id,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("triggerReason")
            String triggerReason,

            @Param("decisionReason")
            String decisionReason,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Répare les configurations bloquées.
     *
     * On ne modifie que les lignes réellement anciennes.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.lastTriggerReason = 'STUCK_RESET',
               c.lastDecisionReason =
                   'Le verrou de réentraînement a été réinitialisé automatiquement.',
               c.updatedAt = :now
         where c.running = true
           and c.updatedAt is not null
           and c.updatedAt < :cutoff
        """)
    int resetStuckRunningConfigs(
            @Param("cutoff")
            LocalDateTime cutoff,

            @Param("now")
            LocalDateTime now
    );
}