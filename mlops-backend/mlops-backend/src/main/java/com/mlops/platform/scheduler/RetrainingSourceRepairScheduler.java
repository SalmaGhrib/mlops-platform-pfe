package com.mlops.platform.scheduler;

import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.repository.RetrainingConfigRepository;
import com.mlops.platform.service.RetrainingSourceResolverService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetrainingSourceRepairScheduler {

    private final RetrainingConfigRepository configRepository;
    private final RetrainingSourceResolverService sourceResolver;

    public RetrainingSourceRepairScheduler(
            RetrainingConfigRepository configRepository,
            RetrainingSourceResolverService sourceResolver
    ) {
        this.configRepository = configRepository;
        this.sourceResolver = sourceResolver;
    }

    @Scheduled(
            fixedDelayString = "${app.retraining.source-repair.delay-ms:300000}",
            initialDelayString = "${app.retraining.source-repair.initial-delay-ms:45000}"
    )
    @SchedulerLock(
            name = "retrainingSourceRepairScheduler",
            lockAtMostFor = "4m",
            lockAtLeastFor = "5s"
    )
    public void repairSources() {
        List<RetrainingConfig> configs = configRepository.findEnabledConfigs();

        for (RetrainingConfig config : configs) {
            sourceResolver.repairEnabledConfig(config);
        }
    }
}
