package com.mlops.platform.controller;

import com.mlops.platform.dto.RetrainingConfigRequest;
import com.mlops.platform.entity.RetrainingConfig;
import com.mlops.platform.service.RetrainingConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/retraining")
@CrossOrigin(origins = "http://localhost:4200")
public class RetrainingConfigController {

    private final RetrainingConfigService retrainingConfigService;

    public RetrainingConfigController(RetrainingConfigService retrainingConfigService) {
        this.retrainingConfigService = retrainingConfigService;
    }

    /*
     * Endpoint utilisé par Angular dans la page Mes Projets.
     * Il permet de créer/modifier la configuration de réentraînement
     * directement au niveau du projet.
     */
    @PostMapping("/projects/{projectId}/config")
    public ResponseEntity<RetrainingConfig> saveProjectConfig(
            @PathVariable Long projectId,
            @RequestBody RetrainingConfigRequest request
    ) {
        RetrainingConfig config = retrainingConfigService.saveOrUpdateProjectConfig(
                projectId,
                request
        );

        return ResponseEntity.ok(config);
    }

    /*
     * Endpoint utilisé quand on veut associer une expérience source précise.
     */
    @PostMapping("/projects/{projectId}/experiments/{experimentId}/config")
    public ResponseEntity<RetrainingConfig> saveConfig(
            @PathVariable Long projectId,
            @PathVariable Long experimentId,
            @RequestBody RetrainingConfigRequest request
    ) {
        RetrainingConfig config = retrainingConfigService.saveOrUpdateConfig(
                projectId,
                experimentId,
                request
        );

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/experiments/{experimentId}/enable-weekly")
    public ResponseEntity<RetrainingConfig> enableDefaultWeeklyRetraining(
            @PathVariable Long projectId,
            @PathVariable Long experimentId
    ) {
        RetrainingConfig config = retrainingConfigService.enableDefaultWeeklyRetraining(
                projectId,
                experimentId
        );

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/disable")
    public ResponseEntity<RetrainingConfig> disableRetraining(
            @PathVariable Long projectId
    ) {
        RetrainingConfig config = retrainingConfigService.disableRetraining(projectId);

        return ResponseEntity.ok(config);
    }

    @PostMapping("/projects/{projectId}/force-now")
    public ResponseEntity<RetrainingConfig> forceRetrainingNow(
            @PathVariable Long projectId
    ) {
        RetrainingConfig config = retrainingConfigService.forceRetrainingNow(projectId);

        return ResponseEntity.ok(config);
    }

    @GetMapping("/projects/{projectId}/config")
    public ResponseEntity<RetrainingConfig> getConfig(
            @PathVariable Long projectId
    ) {
        RetrainingConfig config = retrainingConfigService.getConfig(projectId);

        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(config);
    }
}