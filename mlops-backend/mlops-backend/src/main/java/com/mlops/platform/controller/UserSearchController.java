package com.mlops.platform.controller;

import com.mlops.platform.dto.UserSearchResponse;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserSearchController {

    private final UserRepository userRepository;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final MlProjectRepository mlProjectRepository;

    public UserSearchController(
            UserRepository userRepository,
            ModelDeploymentRepository modelDeploymentRepository,
            MlProjectRepository mlProjectRepository
    ) {
        this.userRepository = userRepository;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.mlProjectRepository = mlProjectRepository;
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserSearchResponse>> searchUsers(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "deploymentId", required = false) Long deploymentId,
            @RequestParam(name = "limit", required = false, defaultValue = "10") int limit
    ) {
        String cleanQuery = query == null ? "" : query.trim().toLowerCase();

        if (cleanQuery.length() < 2) {
            return ResponseEntity.ok(List.of());
        }

        Long ownerId = null;

        if (deploymentId != null) {
            ModelDeployment deployment = modelDeploymentRepository.findById(deploymentId)
                    .orElse(null);

            if (deployment != null && deployment.getProjectId() != null) {
                MlProject project = mlProjectRepository.findById(deployment.getProjectId())
                        .orElse(null);

                if (project != null && project.getOwner() != null) {
                    ownerId = project.getOwner().getId();
                }
            }
        }

        final Long finalOwnerId = ownerId;
        int safeLimit = Math.max(1, Math.min(limit, 20));

        List<UserSearchResponse> users = userRepository.searchActiveUsers(cleanQuery)
                .stream()
                .filter(user -> finalOwnerId == null || !finalOwnerId.equals(user.getId()))
                .limit(safeLimit)
                .map(UserSearchResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(users);
    }
}