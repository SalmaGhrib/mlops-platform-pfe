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
     * Monitoring est toujours actif : toute configuration enabled + not running
     * est éligible. Les états EVALUATING / TRIGGERED / RUNNING sont exclus,
     * et une configuration en COOLDOWN doit avoir dépassé sa période d'attente.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
          and c.running = false
          and (
                c.manualTriggerRequested = true
                or c.nextRetrainingAt is null
                or c.nextRetrainingAt <= :now
              )
          and c.state in (
                com.mlops.platform.entity.RetrainingState.IDLE,
                com.mlops.platform.entity.RetrainingState.COMPLETED,
                com.mlops.platform.entity.RetrainingState.FAILED,
                com.mlops.platform.entity.RetrainingState.COOLDOWN
          )
        order by
            case
                when c.manualTriggerRequested = true then 0
                else 1
            end,
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
     *
     * Le claim passe l'état à EVALUATING et mémorise la fenêtre de
     * déclenchement (clé d'idempotence).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = true,
               c.state = com.mlops.platform.entity.RetrainingState.EVALUATING,
               c.lastTriggerWindow = :now,
               c.updatedAt = :now
         where c.id = :id
           and c.enabled = true
           and c.running = false
           and c.state in (
                com.mlops.platform.entity.RetrainingState.IDLE,
                com.mlops.platform.entity.RetrainingState.COMPLETED,
                com.mlops.platform.entity.RetrainingState.FAILED,
                com.mlops.platform.entity.RetrainingState.COOLDOWN
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
               c.state = com.mlops.platform.entity.RetrainingState.TRIGGERED,
               c.lastTriggerWindow = :now,
               c.lastTriggerReason = 'MANUAL',
               c.updatedAt = :now
         where c.id = :id
           and c.enabled = true
           and c.running = false
           and c.state in (
                com.mlops.platform.entity.RetrainingState.IDLE,
                com.mlops.platform.entity.RetrainingState.COMPLETED,
                com.mlops.platform.entity.RetrainingState.FAILED,
                com.mlops.platform.entity.RetrainingState.COOLDOWN
           )
        """)
    int claimManualRetraining(
            @Param("id")
            Long id,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Trace la dernière cause de déclenchement (ex. GITHUB_PUSH <commit>)
     * après un claim réussi. N'affecte ni running ni l'état.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.lastTriggerReason = :reason,
               c.updatedAt = :now
         where c.id = :id
        """)
    int updateLastTriggerReason(
            @Param("id")
            Long id,

            @Param("reason")
            String reason,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Libération après création réussie d'un nouveau run.
     *
     * L'état passe à RUNNING (une expérience est active). Les champs
     * d'idempotence (lastTriggerWindow, lastSourceModelVersion,
     * lastExperimentId) sont enregistrés ici.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.state = com.mlops.platform.entity.RetrainingState.RUNNING,
               c.lastRetrainingAt = :lastRetrainingAt,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'SUCCESS',
               c.lastSourceModelVersion = :sourceModelVersion,
               c.lastExperimentId = :experimentId,
               c.consecutiveFailures = 0,
               c.cooldownUntil = null,
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

            @Param("sourceModelVersion")
            String sourceModelVersion,

            @Param("experimentId")
            Long experimentId,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Libération après échec du lancement.
     *
     * L'état passe à FAILED et le compteur d'échecs consécutifs est
     * incrémenté. La gestion du cooldown est effectuée par le service
     * appelant (transition COOLDOWN si le seuil est atteint).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.state = com.mlops.platform.entity.RetrainingState.FAILED,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'FAILED',
               c.consecutiveFailures = c.consecutiveFailures + 1,
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
     * Libération après un passage direct en cooldown (échecs consécutifs
     * au-delà du seuil). L'état passe à COOLDOWN jusqu'à cooldownUntil.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.state = com.mlops.platform.entity.RetrainingState.COOLDOWN,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.cooldownUntil = :cooldownUntil,
               c.lastMonitoringCheckAt = :updatedAt,
               c.lastTriggerReason = 'COOLDOWN',
               c.consecutiveFailures = c.consecutiveFailures + 1,
               c.updatedAt = :updatedAt
         where c.id = :id
           and c.running = true
        """)
    int releaseAfterCooldown(
            @Param("id")
            Long id,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("cooldownUntil")
            LocalDateTime cooldownUntil,

            @Param("updatedAt")
            LocalDateTime updatedAt
    );

    /**
     * Libération lorsqu'aucun signal ne justifie un réentraînement.
     *
     * L'état revient à IDLE : la configuration reste éligible pour la
     * prochaine échéance.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.state = com.mlops.platform.entity.RetrainingState.IDLE,
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
     * Mise à jour de l'état d'une configuration non réclamée.
     *
     * Utilisée par le diagnostic DRY-RUN et par les réparations
     * ponctuelles. Ne modifie pas running (la configuration n'est pas
     * réclamée).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.state = :state,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastDecisionReason = :decisionReason,
               c.updatedAt = :now
         where c.id = :id
           and c.running = false
        """)
    int updateStateForUnclaimedConfig(
            @Param("id")
            Long id,

            @Param("state")
            com.mlops.platform.entity.RetrainingState state,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("decisionReason")
            String decisionReason,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Demande manuelle ou intelligente atomique.
     *
     * La condition running=false empêche de demander un second lancement
     * pendant qu'un premier traitement est encore actif.
     *
     * L'état est repassé en IDLE (le scheduler le réclamera à nouveau).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.manualTriggerRequested = true,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.state = com.mlops.platform.entity.RetrainingState.IDLE,
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
     * On ne modifie que les lignes réellement anciennes. L'état redevient
     * IDLE (la config redeviendra due selon nextRetrainingAt).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.running = false,
               c.manualTriggerRequested = false,
               c.state = com.mlops.platform.entity.RetrainingState.IDLE,
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

    /**
     * Configurations actives en état RUNNING / TRIGGERED pour la
     * réconciliation des expériences terminées.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
          and c.state in (
                com.mlops.platform.entity.RetrainingState.RUNNING,
                com.mlops.platform.entity.RetrainingState.TRIGGERED
          )
        order by c.id asc
        """)
    List<RetrainingConfig> findRunningConfigs();

    /**
     * Configurations dont l'expérience de réentraînement s'est terminée
     * mais dont l'état n'est jamais repassé actif/IDLE.
     *
     * running=false signifie que la garde du scheduler est déjà relâchée ;
     * l'état RUNNING / TRIGGERED ne correspond plus à un traitement réel.
     * Ces configurations sont bloquées pour tout nouveau claim (manuel ou
     * planifié) tant qu'elles ne sont pas réinitialisées à IDLE.
     */
    @Query("""
        select c
        from RetrainingConfig c
        where c.enabled = true
          and c.running = false
          and c.state in (
                com.mlops.platform.entity.RetrainingState.RUNNING,
                com.mlops.platform.entity.RetrainingState.TRIGGERED
          )
        order by c.id asc
        """)
    List<RetrainingConfig> findFinishedRunningConfigs();

    /**
     * Réinitialise à IDLE une configuration dont l'expérience de
     * réentraînement est terminée (aucune expérience active en base).
     *
     * La clause d'état rend la requête idempotente : un second pod ne
     * remet pas doublement à jour la même ligne.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.state = com.mlops.platform.entity.RetrainingState.IDLE,
               c.nextRetrainingAt = :nextRetrainingAt,
               c.lastTriggerReason = 'RECONCILED',
               c.lastDecisionReason =
                   'Expérience de réentraînement terminée : état réinitialisé.',
               c.updatedAt = :now
         where c.id = :id
           and c.running = false
           and c.state in (
                com.mlops.platform.entity.RetrainingState.RUNNING,
                com.mlops.platform.entity.RetrainingState.TRIGGERED
           )
        """)
    int resetFinishedConfigToIdle(
            @Param("id")
            Long id,

            @Param("nextRetrainingAt")
            LocalDateTime nextRetrainingAt,

            @Param("now")
            LocalDateTime now
    );

    /**
     * Configurations actives dont le cooldown est expiré : elles redeviennent
     * éligibles au cycle suivant (état IDLE).
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update RetrainingConfig c
           set c.state = com.mlops.platform.entity.RetrainingState.IDLE,
               c.cooldownUntil = null,
               c.lastTriggerReason = 'COOLDOWN_EXPIRED',
               c.updatedAt = :now
         where c.enabled = true
           and c.running = false
           and c.state = com.mlops.platform.entity.RetrainingState.COOLDOWN
           and c.cooldownUntil is not null
           and c.cooldownUntil <= :now
        """)
    int releaseExpiredCooldowns(
            @Param("now")
            LocalDateTime now
    );
}