package com.mlops.platform.service;

import com.mlops.platform.repository.RetrainingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RetrainingSourcePersistenceService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 250L;

    private final RetrainingConfigRepository retrainingConfigRepository;
    private final PlatformTransactionManager transactionManager;

    public boolean synchronizeSourceExperiment(Long projectId, Long sourceExperimentId) {
        if (projectId == null || sourceExperimentId == null) {
            return false;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int updatedRows = executeInNewTransaction(projectId, sourceExperimentId);

                System.out.println(
                        "[RETRAINING_SOURCE] Synchronization completed. "
                                + "projectId=" + projectId
                                + ", sourceExperimentId=" + sourceExperimentId
                                + ", updatedRows=" + updatedRows
                                + ", attempt=" + attempt
                );
                return true;

            } catch (PessimisticLockingFailureException exception) {
                System.err.println(
                        "[RETRAINING_SOURCE] Lock conflict. "
                                + "projectId=" + projectId
                                + ", sourceExperimentId=" + sourceExperimentId
                                + ", attempt=" + attempt
                                + ", reason=" + exception.getMessage()
                );

                if (attempt == MAX_ATTEMPTS) {
                    return false;
                }
                sleepBeforeRetry(attempt);

            } catch (RuntimeException exception) {
                System.err.println(
                        "[RETRAINING_SOURCE] Synchronization failed. "
                                + "projectId=" + projectId
                                + ", sourceExperimentId=" + sourceExperimentId
                                + ", reason=" + exception.getMessage()
                );
                return false;
            }
        }

        return false;
    }

    private int executeInNewTransaction(Long projectId, Long sourceExperimentId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(10);

        Integer result = template.execute(status ->
                retrainingConfigRepository.synchronizeSourceExperiment(
                        projectId,
                        sourceExperimentId,
                        LocalDateTime.now()
                )
        );

        return result == null ? 0 : result;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Retraining source synchronization was interrupted.",
                    exception
            );
        }
    }
}
