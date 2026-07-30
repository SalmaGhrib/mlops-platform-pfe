package com.mlops.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active le moteur Spring @Scheduled uniquement lorsque :
 *
 * app.scheduling.enabled=true
 *
 * Si la propriété vaut false :
 * - aucune méthode @Scheduled n'est exécutée ;
 * - ShedLock n'essaie pas d'acquérir de verrou ;
 * - les schedulers ne consomment pas de connexion MySQL.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SchedulingConfig {
}