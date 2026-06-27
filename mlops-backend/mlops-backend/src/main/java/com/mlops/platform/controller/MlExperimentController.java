package com.mlops.platform.controller;

import com.mlops.platform.dto.MlExperimentResponse;
import com.mlops.platform.service.MlExperimentService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/experiments")
@CrossOrigin(origins = "http://localhost:4200")
@SecurityRequirement(name = "bearerAuth")
public class MlExperimentController {

    private final MlExperimentService mlExperimentService;

    public MlExperimentController(MlExperimentService mlExperimentService) {
        this.mlExperimentService = mlExperimentService;
    }

    /**
     * Lance une expérience ML.
     *
     * Mode STANDARD_RUNTIME :
     * - model_file obligatoire
     * - dataset_file optionnel
     * - algorithm_replacements optionnel
     * - dockerfile et requirements_file ignorés
     *
     * Mode CUSTOM_DOCKERFILE :
     * - model_file obligatoire
     * - dataset_file optionnel
     * - dockerfile obligatoire côté service
     * - requirements_file optionnel
     * - algorithm_replacements désactivé côté service
     */
    @PostMapping(
            value = "/upload-and-launch",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public MlExperimentResponse uploadAndLaunchExperiment(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,

            @RequestParam("project_id") Long projectId,

            @RequestParam("model_name") String modelName,

            @RequestParam("run_name") String runName,

            @RequestParam(value = "primary_metric", defaultValue = "f1_score") String primaryMetric,

            @RequestParam(value = "target_column", required = false) String targetColumn,


            @RequestParam("model_file") MultipartFile modelFile,

            @RequestParam(value = "dataset_file", required = false) MultipartFile datasetFile,

            /**
             * Nouveau champ envoyé par Angular.
             * Valeurs possibles :
             * - STANDARD_RUNTIME
             * - CUSTOM_DOCKERFILE
             *
             * Si Angular ne l'envoie pas, le service utilisera le mode du projet.
             */
            @RequestParam(value = "runtime_mode", required = false) String runtimeMode,

            /**
             * Image Docker déjà disponible, optionnelle.
             * Exemple :
             * docker.io/user/my-training-image:latest
             */
            @RequestParam(value = "custom_image_uri", required = false) String customImageUri,

            /**
             * Dockerfile envoyé par l'utilisateur pour le mode CUSTOM_DOCKERFILE.
             */
            @RequestParam(value = "dockerfile", required = false) MultipartFile dockerfileFile,

            /**
             * requirements.txt optionnel pour le mode CUSTOM_DOCKERFILE.
             */
            @RequestParam(value = "requirements_file", required = false) MultipartFile requirementsFile
    ) {
        return mlExperimentService.uploadAndLaunchExperiment(
                authHeader,
                projectId,
                modelName,
                runName,
                primaryMetric,
                targetColumn,
                modelFile,
                datasetFile,
                runtimeMode,
                customImageUri,
                dockerfileFile,
                requirementsFile
        );
    }

    @GetMapping("/project/{projectId}")
    public List<MlExperimentResponse> getExperimentsByProject(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long projectId
    ) {
        return mlExperimentService.getExperimentsByProject(authHeader, projectId);
    }

    @GetMapping("/project/{projectId}/{experimentId}")
    public MlExperimentResponse getExperimentById(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long projectId,
            @PathVariable Long experimentId
    ) {
        return mlExperimentService.getExperimentById(authHeader, projectId, experimentId);
    }

    @PostMapping("/project/{projectId}/sync-results")
    public List<MlExperimentResponse> syncProjectResults(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long projectId
    ) {
        return mlExperimentService.syncProjectResults(authHeader, projectId);
    }

    @PostMapping("/project/{projectId}/recalculate-production")
    public MlExperimentResponse recalculateProduction(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long projectId
    ) {
        return mlExperimentService.recalculateProduction(authHeader, projectId);
    }

    @PostMapping("/sync-registered-model/{registeredModelName}")
    public List<MlExperimentResponse> syncRegisteredModel(
            @PathVariable String registeredModelName
    ) {
        return mlExperimentService.syncRegisteredModelExperimentsToDatabase(registeredModelName);
    }
}