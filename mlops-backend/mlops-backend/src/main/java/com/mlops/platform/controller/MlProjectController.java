package com.mlops.platform.controller;

import com.mlops.platform.dto.DeleteProjectResponse;
import com.mlops.platform.dto.MlProjectMetricsRequest;
import com.mlops.platform.dto.MlProjectRequest;
import com.mlops.platform.dto.MlProjectResponse;
import com.mlops.platform.dto.SearchPageResponse;
import com.mlops.platform.service.MlProjectService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller des projets ML.
 *
 * Rôle :
 * - créer un projet ML
 * - afficher les projets de l'utilisateur connecté
 * - modifier les informations générales d'un projet
 * - modifier l'ordre des 3 métriques prioritaires
 * - retourner les métriques disponibles selon le type du projet
 * - supprimer un projet avec toutes ses expériences liées
 */
@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "http://localhost:4200")
@SecurityRequirement(name = "bearerAuth")
public class MlProjectController {

    private final MlProjectService mlProjectService;

    public MlProjectController(MlProjectService mlProjectService) {
        this.mlProjectService = mlProjectService;
    }

    /**
     * Créer un projet ML.
     */
    @PostMapping
    public MlProjectResponse createProject(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @Valid @RequestBody MlProjectRequest request
    ) {
        return mlProjectService.createProject(authHeader, request);
    }

    /**
     * Recherche dynamique et intelligente des projets avec filtres.
     *
     * @param q           texte libre (nom, description, cible, métriques)
     * @param projectType classification | regression
     * @param dataType    TABULAR | IMAGE | TEXT
     * @param page        page 0-based
     * @param size        taille de page (1..50)
     */
    @GetMapping("/search")
    public SearchPageResponse<MlProjectResponse> searchProjects(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String projectType,
            @RequestParam(required = false) String dataType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return mlProjectService.searchProjects(
                authHeader,
                q,
                projectType,
                dataType,
                page,
                size
        );
    }

    /**
     * Récupérer tous les projets de l'utilisateur connecté.
     */
    @GetMapping
    public List<MlProjectResponse> getMyProjects(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        return mlProjectService.getMyProjects(authHeader);
    }

    /**
     * Récupérer un projet par son id.
     */
    @GetMapping("/{id}")
    public MlProjectResponse getProjectById(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id
    ) {
        return mlProjectService.getProjectById(authHeader, id);
    }

    /**
     * Modifier les informations générales du projet.
     */
    @PutMapping("/{id}")
    public MlProjectResponse updateProject(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @Valid @RequestBody MlProjectRequest request
    ) {
        return mlProjectService.updateProject(authHeader, id, request);
    }

    /**
     * Modifier l'ordre des 3 métriques prioritaires du projet.
     */
    @PatchMapping("/{id}/metrics-priority")
    public MlProjectResponse updateProjectMetricsPriority(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody MlProjectMetricsRequest request
    ) {
        return mlProjectService.updateProjectMetrics(authHeader, id, request);
    }

    /**
     * Ancien endpoint gardé pour compatibilité.
     *
     * Il fait la même chose que :
     * PATCH /api/projects/{id}/metrics-priority
     */
    @PatchMapping("/{id}/metrics")
    public MlProjectResponse updateProjectMetrics(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody MlProjectMetricsRequest request
    ) {
        return mlProjectService.updateProjectMetrics(authHeader, id, request);
    }

    /**
     * Mettre à jour l'ordre des métriques du projet.
     */
    @PutMapping("/{id}/metrics")
    public MlProjectResponse replaceProjectMetrics(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody MlProjectMetricsRequest request
    ) {
        return mlProjectService.updateProjectMetrics(authHeader, id, request);
    }

    /**
     * Retourner les métriques disponibles selon le type du projet.
     */
    @GetMapping("/metric-options/{projectType}")
    public List<String> getMetricOptions(
            @PathVariable String projectType
    ) {
        return mlProjectService.getMetricOptionsByProjectType(projectType);
    }

    /**
     * Retourner toutes les métriques disponibles par type.
     */
    @GetMapping("/metric-options")
    public Map<String, List<String>> getAllMetricOptions() {
        return Map.of(
                "regression", mlProjectService.getMetricOptionsByProjectType("regression"),
                "classification", mlProjectService.getMetricOptionsByProjectType("classification")
        );
    }

    /**
     * Supprimer un projet avec toutes ses expériences, métriques et éléments liés.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteProjectResponse> deleteProject(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id
    ) {
        DeleteProjectResponse response = mlProjectService.deleteProject(
                authHeader,
                id
        );

        return ResponseEntity.ok(response);
    }
}