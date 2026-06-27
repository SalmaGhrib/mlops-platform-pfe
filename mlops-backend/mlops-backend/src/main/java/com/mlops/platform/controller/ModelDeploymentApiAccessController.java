package com.mlops.platform.controller;

import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.service.ModelDeploymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/model-deployments")
@CrossOrigin(origins = "*")
public class ModelDeploymentApiAccessController {

    private final ModelDeploymentService modelDeploymentService;

    public ModelDeploymentApiAccessController(ModelDeploymentService modelDeploymentService) {
        this.modelDeploymentService = modelDeploymentService;
    }

    /*
     * Active l'accès API externe pour un modèle déjà déployé.
     *
     * Résultat :
     * - visibility = PUBLIC_API
     * - apiEnabled = true
     * - apiKey générée
     */
    @PostMapping("/{deploymentId}/enable-api-access")
    public ResponseEntity<ModelDeploymentResponse> enableApiAccess(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.enableApiAccess(deploymentId);
        return ResponseEntity.ok(response);
    }

    /*
     * Désactive l'accès API externe.
     *
     * Le modèle reste déployé dans Kubernetes,
     * mais il ne peut plus être utilisé par une application externe.
     */
    @PostMapping("/{deploymentId}/disable-api-access")
    public ResponseEntity<ModelDeploymentResponse> disableApiAccess(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.disableApiAccess(deploymentId);
        return ResponseEntity.ok(response);
    }

    /*
     * Génère une nouvelle clé API.
     *
     * L'ancienne clé devient invalide immédiatement.
     */
    @PostMapping("/{deploymentId}/regenerate-api-key")
    public ResponseEntity<ModelDeploymentResponse> regenerateApiKey(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.regenerateApiKey(deploymentId);
        return ResponseEntity.ok(response);
    }
}