package com.mlops.platform.service;

import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.repository.MlExperimentRepository;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

@Service
public class CustomDockerPersistenceService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2_000L;

    private final MlExperimentRepository experimentRepository;
    private final PlatformTransactionManager transactionManager;

    public CustomDockerPersistenceService(
            MlExperimentRepository experimentRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.experimentRepository = experimentRepository;
        this.transactionManager = transactionManager;
    }

    public MlExperiment updateExperiment(
            Long experimentId,
            Consumer<MlExperiment> updater
    ) {
        if (experimentId == null) {
            throw new IllegalArgumentException("experimentId cannot be null");
        }

        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                TransactionTemplate template =
                        new TransactionTemplate(transactionManager);

                template.setPropagationBehavior(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW
                );
                template.setIsolationLevel(
                        TransactionDefinition.ISOLATION_READ_COMMITTED
                );
                template.setTimeout(20);

                MlExperiment result = template.execute(status -> {
                    MlExperiment managedExperiment =
                            experimentRepository
                                    .findByIdWithFiles(experimentId)
                                    .orElseThrow(() ->
                                            new IllegalStateException(
                                                    "Experiment not found: "
                                                            + experimentId
                                            )
                                    );

                    updater.accept(managedExperiment);

                    return experimentRepository.saveAndFlush(
                            managedExperiment
                    );
                });

                if (result == null) {
                    throw new IllegalStateException(
                            "Persistence transaction returned null for experimentId="
                                    + experimentId
                    );
                }

                return result;

            } catch (RuntimeException exception) {
                lastError = exception;

                if (!isTransientPersistenceFailure(exception)
                        || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }

                System.err.println(
                        "[CUSTOM-DOCKER-PERSISTENCE] Temporary database failure. "
                                + "experimentId="
                                + experimentId
                                + ", attempt="
                                + attempt
                                + "/"
                                + MAX_ATTEMPTS
                                + ", reason="
                                + safeMessage(exception.getMessage())
                );

                sleepBeforeRetry();
            }
        }

        throw lastError != null
                ? lastError
                : new IllegalStateException("Unknown persistence failure");
    }

    public MlExperiment markBuildCompleted(
            Long experimentId,
            String logs
    ) {
        return updateExperiment(experimentId, experiment -> {
            if (isTerminal(experiment.getStatus())) {
                return;
            }

            experiment.setBuildLogs(logs);
            experiment.setBuildStatus("IMAGE_BUILD_COMPLETED");
            experiment.setStatus(ExperimentStatus.RUNNING);
            experiment.setMessage(
                    "Custom Docker image built successfully. "
                            + "Training job will be launched."
            );
            experiment.setErrorMessage(null);
        });
    }

    public MlExperiment markTrainingStarted(
            Long experimentId,
            String jobName
    ) {
        return updateExperiment(experimentId, experiment -> {
            if (isTerminal(experiment.getStatus())) {
                return;
            }

            experiment.setTrainingJobName(jobName);
            experiment.setKubeflowRunName(jobName);
            experiment.setKubeflowStatus("RUNNING");
            experiment.setBuildStatus("CUSTOM_IMAGE_JOB_RUNNING");
            experiment.setStatus(ExperimentStatus.RUNNING);

            if (experiment.getStartedAt() == null) {
                experiment.setStartedAt(
                        experiment.getCreatedAt() != null
                                ? experiment.getCreatedAt()
                                : LocalDateTime.now(ZoneOffset.UTC)
                );
            }

            experiment.setFinishedAt(null);
            experiment.setMessage(
                    "Custom Docker training job launched: " + jobName
            );
            experiment.setErrorMessage(null);
        });
    }

    private boolean isTransientPersistenceFailure(Throwable error) {
        Throwable current = error;

        while (current != null) {
            String className = current.getClass().getName();
            String message = safeMessage(
                    current.getMessage()
            ).toLowerCase();

            if (current instanceof RecoverableDataAccessException
                    || current instanceof QueryTimeoutException
                    || current instanceof CannotAcquireLockException
                    || current instanceof ObjectOptimisticLockingFailureException
                    || current instanceof DataAccessException
                    || className.contains("CommunicationsException")
                    || className.contains("CJCommunicationsException")
                    || className.contains("SocketTimeoutException")
                    || className.contains("SQLTransient")
                    || className.contains("PessimisticLock")
                    || className.contains("OptimisticLock")
                    || className.contains("StaleObjectState")
                    || message.contains("communications link failure")
                    || message.contains("unable to commit against jdbc connection")
                    || message.contains("socket timeout")
                    || message.contains("read timed out")
                    || message.contains("connection is closed")
                    || message.contains("connection marked as broken")
                    || message.contains("lock wait timeout")
                    || message.contains("deadlock")
                    || message.contains("could not execute statement")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private boolean isTerminal(ExperimentStatus status) {
        return ExperimentStatus.COMPLETED.equals(status)
                || ExperimentStatus.FAILED.equals(status)
                || ExperimentStatus.CANCELLED.equals(status);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Persistence retry interrupted",
                    interruptedException
            );
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }

        return message
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\u0000", "")
                .trim();
    }
}
