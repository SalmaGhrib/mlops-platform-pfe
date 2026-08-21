package com.mlops.platform.controller;

import com.mlops.platform.dto.DeployModelRequestDto;
import com.mlops.platform.dto.ModelDeploymentResponse;
import com.mlops.platform.dto.ModelDeploymentShareResponse;
import com.mlops.platform.dto.SearchPageResponse;
import com.mlops.platform.dto.ShareDeploymentRequest;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import com.mlops.platform.service.ModelDeploymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-deployments")
@CrossOrigin(origins = "*")
public class ModelDeploymentController {

    private final ModelDeploymentService modelDeploymentService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public ModelDeploymentController(
            ModelDeploymentService modelDeploymentService,
            JwtService jwtService,
            UserRepository userRepository
    ) {
        this.modelDeploymentService = modelDeploymentService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /*
     * Deployed Models :
     * retourne uniquement les modèles appartenant au user connecté.
     */
    @GetMapping("/owned")
    public ResponseEntity<List<ModelDeploymentResponse>> getOwnedDeploymentsForCurrentUser(
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);

        List<ModelDeploymentResponse> response =
                modelDeploymentService.getOwnedDeploymentsForUser(currentUser);

        return ResponseEntity.ok(response);
    }

    /*
     * Prediction Test :
     * retourne tous les modèles utilisables par le user :
     * - ses modèles
     * - les modèles PUBLIC_API
     * - les modèles SHARED avec lui
     */
    @GetMapping("/visible")
    public ResponseEntity<List<ModelDeploymentResponse>> getVisibleDeploymentsForCurrentUser(
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);

        List<ModelDeploymentResponse> response =
                modelDeploymentService.getVisibleDeploymentsForUser(currentUser);

        return ResponseEntity.ok(response);
    }

    /*
     * Accessible Models :
     * retourne uniquement les modèles DES AUTRES utilisateurs :
     * - PUBLIC_API
     * - SHARED avec le user connecté
     * Exclut les modèles du propriétaire.
     */
    @GetMapping("/accessible")
    public ResponseEntity<List<ModelDeploymentResponse>> getAccessibleDeploymentsForCurrentUser(
            @RequestHeader("Authorization") String authHeader
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);

        List<ModelDeploymentResponse> response =
                modelDeploymentService.getAccessibleDeploymentsForUser(currentUser);

        return ResponseEntity.ok(response);
    }

    /*
     * Recherche dynamique des déploiements (owned ou accessible)
     * avec filtres projectType / dataType et pagination.
     */
    @GetMapping("/search")
    public ResponseEntity<SearchPageResponse<ModelDeploymentResponse>> searchDeploymentsForCurrentUser(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String projectType,
            @RequestParam(required = false) String dataType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        User currentUser = getUserFromAuthorizationHeader(authHeader);

        SearchPageResponse<ModelDeploymentResponse> response =
                modelDeploymentService.searchDeploymentsForUser(
                        currentUser, scope, q, projectType, dataType, page, size
                );

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{deploymentId}/visibility")
    public ResponseEntity<ModelDeploymentResponse> updateDeploymentVisibility(
            @PathVariable Long deploymentId,
            @RequestBody Map<String, String> request
    ) {
        String visibility = request.get("visibility");

        ModelDeploymentResponse response =
                modelDeploymentService.updateDeploymentVisibility(deploymentId, visibility);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{deploymentId}/shares")
    public ResponseEntity<List<ModelDeploymentShareResponse>> getDeploymentShares(
            @PathVariable Long deploymentId
    ) {
        return ResponseEntity.ok(
                modelDeploymentService.getDeploymentShares(deploymentId)
        );
    }

    @PostMapping("/{deploymentId}/shares")
    public ResponseEntity<List<ModelDeploymentShareResponse>> shareDeploymentWithUsers(
            @PathVariable Long deploymentId,
            @RequestBody ShareDeploymentRequest request
    ) {
        return ResponseEntity.ok(
                modelDeploymentService.shareDeploymentWithUsers(deploymentId, request)
        );
    }

    @DeleteMapping("/{deploymentId}/shares/{userId}")
    public ResponseEntity<Void> removeDeploymentShare(
            @PathVariable Long deploymentId,
            @PathVariable Long userId
    ) {
        modelDeploymentService.removeDeploymentShare(deploymentId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/production")
    public ResponseEntity<ModelDeploymentResponse> deployProductionModel(
            @RequestBody DeployModelRequestDto request
    ) {
        ModelDeploymentResponse response = modelDeploymentService.deployProductionModel(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/create")
    public ResponseEntity<ModelDeploymentResponse> createDeploymentRecord(
            @RequestParam Long projectId,
            @RequestParam Long experimentId,
            @RequestParam String registeredModelName,
            @RequestParam String modelVersion,
            @RequestParam(required = false) String mlflowRunId,
            @RequestParam(required = false) String kubeflowRunId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.createDeploymentRecord(
                projectId,
                experimentId,
                registeredModelName,
                modelVersion,
                mlflowRunId,
                kubeflowRunId
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/project/{projectId}/active")
    public ResponseEntity<ModelDeploymentResponse> getActiveDeploymentByProject(
            @PathVariable Long projectId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.getActiveDeploymentByProject(projectId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<ModelDeploymentResponse>> getDeploymentsByProject(
            @PathVariable Long projectId
    ) {
        List<ModelDeploymentResponse> response = modelDeploymentService.getDeploymentsByProject(projectId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/experiment/{experimentId}")
    public ResponseEntity<List<ModelDeploymentResponse>> getDeploymentsByExperiment(
            @PathVariable Long experimentId
    ) {
        List<ModelDeploymentResponse> response = modelDeploymentService.getDeploymentsByExperiment(experimentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{deploymentId}/deploying")
    public ResponseEntity<ModelDeploymentResponse> markAsDeploying(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.markAsDeploying(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{deploymentId}/deployed")
    public ResponseEntity<ModelDeploymentResponse> markAsDeployed(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.markAsDeployed(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{deploymentId}/failed")
    public ResponseEntity<ModelDeploymentResponse> markAsFailed(
            @PathVariable Long deploymentId,
            @RequestParam String errorMessage
    ) {
        ModelDeploymentResponse response = modelDeploymentService.markAsFailed(deploymentId, errorMessage);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{deploymentId}/api/enable")
    public ResponseEntity<ModelDeploymentResponse> enableApiAccess(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.enableApiAccess(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{deploymentId}/api/disable")
    public ResponseEntity<ModelDeploymentResponse> disableApiAccess(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.disableApiAccess(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{deploymentId}/api/regenerate-key")
    public ResponseEntity<ModelDeploymentResponse> regenerateApiKey(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.regenerateApiKey(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{deploymentId}/stop")
    public ResponseEntity<ModelDeploymentResponse> stopDeployment(
            @PathVariable Long deploymentId
    ) {
        ModelDeploymentResponse response = modelDeploymentService.stopDeployment(deploymentId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sync-kubernetes")
    public ResponseEntity<Map<String, Object>> syncKubernetesDeployments() {
        modelDeploymentService.reconcileDeploymentsWithKubernetes();

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Kubernetes deployments synchronized with MySQL successfully."
        ));
    }

    private User getUserFromAuthorizationHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            throw new RuntimeException("Invalid or expired token");
        }

        String email = jwtService.extractEmail(token);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}