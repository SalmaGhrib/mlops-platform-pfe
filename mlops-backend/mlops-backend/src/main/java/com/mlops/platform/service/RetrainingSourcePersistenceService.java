package com.mlops.platform.service;

import com.mlops.platform.repository.RetrainingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class RetrainingSourcePersistenceService {

    private static final int MAX_ATTEMPTS = 3;

    private static final long BASE_BACKOFF_MS = 200L;

    private static final long MAX_LOCAL_LOCK_WAIT_SECONDS = 3L;

    private final RetrainingConfigRepository
            retrainingConfigRepository;

    private final PlatformTransactionManager
            transactionManager;

    /**
     * Sérialise les mises à jour d'un même projet dans le pod courant.
     *
     * ShedLock protège les schedulers distribués, mais il ne protège pas les
     * requêtes HTTP et les autres services du même pod. Ce verrou local évite
     * qu'AUTO_SYNC, le reconciler et une requête HTTP modifient simultanément
     * la même configuration.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> projectLocks =
            new ConcurrentHashMap<>();

    /**
     * Synchronise le cache sourceExperimentId.
     *
     * Comportement :
     * 1. lecture rapide ;
     * 2. aucun UPDATE si la valeur est déjà correcte ;
     * 3. verrou local par projectId ;
     * 4. transaction REQUIRES_NEW très courte ;
     * 5. retry uniquement pour les conflits transitoires.
     */
    public boolean synchronizeSourceExperiment(
            Long projectId,
            Long sourceExperimentId
    ) {
        if (projectId == null
                || sourceExperimentId == null) {
            return false;
        }

        /*
         * Vérification rapide avant de prendre le verrou.
         */
        if (isAlreadySynchronized(
                projectId,
                sourceExperimentId
        )) {
            logAlreadySynchronized(
                    projectId,
                    sourceExperimentId
            );

            return true;
        }

        ReentrantLock projectLock =
                projectLocks.computeIfAbsent(
                        projectId,
                        ignored -> new ReentrantLock()
                );

        boolean lockAcquired = false;

        try {
            lockAcquired = projectLock.tryLock(
                    MAX_LOCAL_LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!lockAcquired) {
                System.out.println(
                        "[RETRAINING_SOURCE] Local synchronization postponed. "
                                + "Another operation is already updating "
                                + "the same project. projectId="
                                + projectId
                                + ", sourceExperimentId="
                                + sourceExperimentId
                );

                /*
                 * Ce n'est pas une erreur fatale.
                 * Le prochain cycle pourra réparer le cache.
                 */
                return false;
            }

            /*
             * Une autre opération du pod a peut-être synchronisé la valeur
             * pendant que ce thread attendait le verrou.
             */
            if (isAlreadySynchronized(
                    projectId,
                    sourceExperimentId
            )) {
                logAlreadySynchronized(
                        projectId,
                        sourceExperimentId
                );

                return true;
            }

            for (int attempt = 1;
                 attempt <= MAX_ATTEMPTS;
                 attempt++) {

                try {
                    int updatedRows =
                            executeShortUpdateTransaction(
                                    projectId,
                                    sourceExperimentId
                            );

                    System.out.println(
                            "[RETRAINING_SOURCE] Synchronization completed. "
                                    + "projectId="
                                    + projectId
                                    + ", sourceExperimentId="
                                    + sourceExperimentId
                                    + ", updatedRows="
                                    + updatedRows
                                    + ", attempt="
                                    + attempt
                    );

                    return true;

                } catch (RuntimeException exception) {
                    if (!isTransientLockFailure(exception)) {
                        System.err.println(
                                "[RETRAINING_SOURCE] Synchronization failed. "
                                        + "projectId="
                                        + projectId
                                        + ", sourceExperimentId="
                                        + sourceExperimentId
                                        + ", reason="
                                        + safeMessage(
                                                exception.getMessage()
                                        )
                        );

                        return false;
                    }

                    System.err.println(
                            "[RETRAINING_SOURCE] Transient lock conflict. "
                                    + "projectId="
                                    + projectId
                                    + ", sourceExperimentId="
                                    + sourceExperimentId
                                    + ", attempt="
                                    + attempt
                                    + ", reason="
                                    + safeMessage(
                                            exception.getMessage()
                                    )
                    );

                    if (attempt >= MAX_ATTEMPTS) {
                        System.err.println(
                                "[RETRAINING_SOURCE] Source synchronization "
                                        + "postponed after retries. projectId="
                                        + projectId
                                        + ", sourceExperimentId="
                                        + sourceExperimentId
                        );

                        return false;
                    }

                    sleepBeforeRetry(attempt);

                    /*
                     * Après l'attente, vérifier si une autre transaction a déjà
                     * effectué la synchronisation.
                     */
                    if (isAlreadySynchronized(
                            projectId,
                            sourceExperimentId
                    )) {
                        logAlreadySynchronized(
                                projectId,
                                sourceExperimentId
                        );

                        return true;
                    }
                }
            }

            return false;

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            System.err.println(
                    "[RETRAINING_SOURCE] Synchronization interrupted. "
                            + "projectId="
                            + projectId
                            + ", sourceExperimentId="
                            + sourceExperimentId
            );

            return false;

        } finally {
            if (lockAcquired) {
                projectLock.unlock();
            }

            /*
             * Supprime le verrou de la map lorsqu'il n'est plus utilisé.
             */
            if (!projectLock.isLocked()
                    && !projectLock.hasQueuedThreads()) {
                projectLocks.remove(
                        projectId,
                        projectLock
                );
            }
        }
    }

    /**
     * Lecture effectuée hors transaction longue.
     *
     * Une lecture peut échouer momentanément pendant un deadlock externe.
     * Dans ce cas, on retourne false et on laisse la logique de retry agir.
     */
    private boolean isAlreadySynchronized(
            Long projectId,
            Long sourceExperimentId
    ) {
        try {
            Optional<Long> currentSource =
                    retrainingConfigRepository
                            .findSourceExperimentIdByProjectId(
                                    projectId
                            );

            return currentSource.isPresent()
                    && Objects.equals(
                            currentSource.get(),
                            sourceExperimentId
                    );

        } catch (RuntimeException readError) {
            System.out.println(
                    "[RETRAINING_SOURCE] Could not verify current cache. "
                            + "projectId="
                            + projectId
                            + ", reason="
                            + safeMessage(
                                    readError.getMessage()
                            )
            );

            return false;
        }
    }

    /**
     * Exécute uniquement l'UPDATE conditionnel dans une transaction courte.
     *
     * Aucun appel MLflow, Kubernetes, Kaniko, HTTP ou Thread.sleep ne doit être
     * exécuté dans cette transaction.
     */
    private int executeShortUpdateTransaction(
            Long projectId,
            Long sourceExperimentId
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        transactionTemplate.setPropagationBehavior(
                TransactionDefinition
                        .PROPAGATION_REQUIRES_NEW
        );

        transactionTemplate.setIsolationLevel(
                TransactionDefinition
                        .ISOLATION_READ_COMMITTED
        );

        /*
         * Le verrou ne doit jamais bloquer la requête HTTP pendant plusieurs
         * dizaines de secondes.
         */
        transactionTemplate.setTimeout(5);

        Integer updatedRows =
                transactionTemplate.execute(status ->
                        retrainingConfigRepository
                                .updateSourceExperimentIfChanged(
                                        projectId,
                                        sourceExperimentId,
                                        utcNow()
                                )
                );

        return updatedRows == null
                ? 0
                : updatedRows;
    }

    /**
     * Détecte les erreurs transitoires de verrouillage, y compris lorsqu'elles
     * sont encapsulées par Hibernate ou Spring.
     */
    private boolean isTransientLockFailure(
            Throwable error
    ) {
        Throwable current = error;

        while (current != null) {
            if (current
                    instanceof CannotAcquireLockException
                    || current
                    instanceof PessimisticLockingFailureException
                    || current
                    instanceof DeadlockLoserDataAccessException
                    || current
                    instanceof ConcurrencyFailureException) {
                return true;
            }

            String message =
                    current.getMessage();

            if (message != null) {
                String normalized =
                        message.toLowerCase();

                if (normalized.contains("deadlock")
                        || normalized.contains(
                                "lock wait timeout"
                        )
                        || normalized.contains(
                                "could not acquire lock"
                        )
                        || normalized.contains(
                                "pessimistic"
                        )) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Backoff court et aléatoire, toujours hors transaction.
     */
    private void sleepBeforeRetry(
            int attempt
    ) {
        long jitter =
                ThreadLocalRandom
                        .current()
                        .nextLong(
                                50L,
                                151L
                        );

        long delay =
                BASE_BACKOFF_MS
                        * attempt
                        + jitter;

        try {
            Thread.sleep(delay);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Retraining source synchronization "
                            + "was interrupted.",
                    interrupted
            );
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(
                Clock.systemUTC()
        );
    }

    private void logAlreadySynchronized(
            Long projectId,
            Long sourceExperimentId
    ) {
        System.out.println(
                "[RETRAINING_SOURCE] Cache already synchronized. "
                        + "projectId="
                        + projectId
                        + ", sourceExperimentId="
                        + sourceExperimentId
        );
    }

    private String safeMessage(
            String message
    ) {
        if (message == null
                || message.isBlank()) {
            return "Unknown persistence error";
        }

        String clean =
                message
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace("\u0000", "")
                        .trim();

        return clean.length() > 1000
                ? clean.substring(0, 997) + "..."
                : clean;
    }
}