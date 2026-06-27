package com.mlops.platform.controller;

import com.mlops.platform.dto.MetricPriorityDto;
import com.mlops.platform.dto.MlflowModelVersionDto;
import com.mlops.platform.dto.ModelRegistryResponse;
import com.mlops.platform.service.MlExperimentService;
import com.mlops.platform.service.MlflowTrackingClientService;
import com.mlops.platform.service.ModelRegistryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller pour consulter et synchroniser MLflow Model Registry.
 */
@RestController
@RequestMapping("/api/model-registry")
@CrossOrigin(origins = "*")
public class ModelRegistryController {

    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final MlExperimentService mlExperimentService;
    private final ModelRegistryService modelRegistryService;

    public ModelRegistryController(
            MlflowTrackingClientService mlflowTrackingClientService,
            MlExperimentService mlExperimentService,
            ModelRegistryService modelRegistryService
    ) {
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.mlExperimentService = mlExperimentService;
        this.modelRegistryService = modelRegistryService;
    }

    /**
     * Nouvelle page Angular Model Registry :
     * récupère les modèles du user connecté depuis MySQL.
     */
    @GetMapping("/my-models")
    public List<ModelRegistryResponse> getMyRegisteredModels(
            @RequestHeader("Authorization") String authHeader
    ) {
        return modelRegistryService.getMyRegisteredModels(authHeader);
    }

    /**
     * Nouvelle page Angular Model Registry :
     * synchronise MLflow Registry vers MySQL puis retourne les modèles du user connecté.
     */
    @PostMapping("/my-models/sync")
    public List<ModelRegistryResponse> syncMyRegisteredModels(
            @RequestHeader("Authorization") String authHeader
    ) {
        return modelRegistryService.syncAndGetMyRegisteredModels(authHeader);
    }

    /**
     * Récupère toutes les versions MLflow d'un registered model avec leurs métriques.
     */
    @GetMapping("/{registeredModelName}/versions")
    public List<MlflowModelVersionDto> getAllVersions(@PathVariable String registeredModelName) {
        return mlflowTrackingClientService.getAllModelVersionsWithMetrics(registeredModelName);
    }

    /**
     * Récupère la version actuellement attachée à l'alias production.
     */
    @GetMapping("/{registeredModelName}/production")
    public String getProductionVersion(@PathVariable String registeredModelName) {
        return mlflowTrackingClientService.getProductionVersion(registeredModelName);
    }

    /**
     * Choisit la meilleure version selon une liste de priorités de métriques.
     */
    @PostMapping("/{registeredModelName}/best-version")
    public MlflowModelVersionDto chooseBestVersion(
            @PathVariable String registeredModelName,
            @RequestBody List<MetricPriorityDto> priorities
    ) {
        return mlflowTrackingClientService.chooseBestVersion(registeredModelName, priorities);
    }

    /**
     * Choisit la meilleure version et lui affecte l'alias production.
     */
    @PostMapping("/{registeredModelName}/promote-best")
    public MlflowModelVersionDto promoteBestVersion(
            @PathVariable String registeredModelName,
            @RequestBody List<MetricPriorityDto> priorities
    ) {
        return mlflowTrackingClientService.promoteBestVersionToProduction(registeredModelName, priorities);
    }

    /**
     * Synchronise MLflow Registry vers MySQL. Version POST.
     */
    @PostMapping("/{registeredModelName}/sync-db")
    public List<MlflowModelVersionDto> syncModelRegistryToDatabasePost(
            @PathVariable String registeredModelName
    ) {
        return mlflowTrackingClientService.syncModelVersionsToDatabase(registeredModelName);
    }

    /**
     * Synchronise MLflow Registry vers MySQL. Version GET pratique pour tester dans le navigateur.
     */
    @GetMapping("/{registeredModelName}/sync-db")
    public List<MlflowModelVersionDto> syncModelRegistryToDatabaseGet(
            @PathVariable String registeredModelName
    ) {
        return mlflowTrackingClientService.syncModelVersionsToDatabase(registeredModelName);
    }

    /**
     * Promeut une version précise en production.
     */
    @PostMapping("/{registeredModelName}/versions/{version}/promote")
    public MlflowModelVersionDto promoteSpecificVersion(
            @PathVariable String registeredModelName,
            @PathVariable String version
    ) {
        mlflowTrackingClientService.setProductionAlias(registeredModelName, version);

        return mlflowTrackingClientService.getAllModelVersionsWithMetrics(registeredModelName)
                .stream()
                .filter(v -> version.equals(v.getVersion()))
                .findFirst()
                .orElse(null);
    }
}