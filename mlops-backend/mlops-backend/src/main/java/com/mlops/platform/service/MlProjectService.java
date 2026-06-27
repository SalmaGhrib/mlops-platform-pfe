package com.mlops.platform.service;

import com.mlops.platform.dto.DeleteProjectResponse;
import com.mlops.platform.dto.MlProjectMetricsRequest;
import com.mlops.platform.dto.MlProjectRequest;
import com.mlops.platform.dto.MlProjectResponse;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.MlProjectRepository;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MlProjectService {

    private final MlProjectRepository mlProjectRepository;
    private final MlExperimentRepository mlExperimentRepository;
    private final UploadedProjectFileRepository uploadedProjectFileRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final ModelPromotionService modelPromotionService;
    private final MlExperimentService mlExperimentService;
    private final ApplicationContext applicationContext;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final AutoModelLifecycleService autoModelLifecycleService;
    private final ModelDeploymentService modelDeploymentService;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    public MlProjectService(
            MlProjectRepository mlProjectRepository,
            MlExperimentRepository mlExperimentRepository,
            UploadedProjectFileRepository uploadedProjectFileRepository,
            UserRepository userRepository,
            JwtService jwtService,
            ModelPromotionService modelPromotionService,
            MlExperimentService mlExperimentService,
            MlflowTrackingClientService mlflowTrackingClientService,
            AutoModelLifecycleService autoModelLifecycleService,
            ApplicationContext applicationContext,
            ModelDeploymentService modelDeploymentService
    ) {
        this.mlProjectRepository = mlProjectRepository;
        this.mlExperimentRepository = mlExperimentRepository;
        this.uploadedProjectFileRepository = uploadedProjectFileRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.modelPromotionService = modelPromotionService;
        this.mlExperimentService = mlExperimentService;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.autoModelLifecycleService = autoModelLifecycleService;
        this.applicationContext = applicationContext;
        this.modelDeploymentService = modelDeploymentService;
    }

    @Transactional
    public MlProjectResponse createProject(String authHeader, MlProjectRequest request) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        String dataType = normalizeDataType(request.getDataType());

       

        String projectType = request.getProjectType();

       

        projectType = normalizeProjectType(projectType);

        String metric1 = cleanMetric(
                request.getPrimaryMetric1(),
                defaultPrimaryMetric(projectType)
        );

        String metric2 = cleanMetric(
                request.getPrimaryMetric2(),
                null
        );

        String metric3 = cleanMetric(
                request.getPrimaryMetric3(),
                null
        );

        validateMetricForProjectType(projectType, metric1);
        validateMetricForProjectType(projectType, metric2);
        validateMetricForProjectType(projectType, metric3);
        validateNoDuplicateMetrics(metric1, metric2, metric3);

        MlProject project = new MlProject();

        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setOwner(owner);

        project.setProjectType(projectType);
        project.setDataType(dataType);

        project.setPrimaryMetric1(metric1);
        project.setPrimaryMetric2(metric2);
        project.setPrimaryMetric3(metric3);

        String targetColumn = cleanTargetColumn(request.getTargetColumn());
        project.setTargetColumn(targetColumn);

        String runtimeMode = normalizeRuntimeMode(request.getRuntimeMode());
        project.setRuntimeMode(runtimeMode);

        project.setAutoPromoteBestModel(
                request.getAutoPromoteBestModel() != null
                        ? request.getAutoPromoteBestModel()
                        : true
        );

        MlProject savedProject = mlProjectRepository.save(project);

        return mapToResponse(savedProject);
    }

    public List<MlProjectResponse> getMyProjects(String authHeader) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        return mlProjectRepository.findByOwnerOrderByCreatedAtDesc(owner)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public MlProjectResponse getProjectById(String authHeader, Long projectId) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        return mapToResponse(project);
    }

    @Transactional
    public MlProjectResponse updateProject(
            String authHeader,
            Long projectId,
            MlProjectRequest request
    ) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        /*
         * Le nom du projet est un identifiant technique.
         * Il ne doit pas changer après création, sinon MLflow/Kubeflow/Registry
         * peuvent avoir plusieurs noms pour un seul projet.
         */
        if (request.getName() != null
                && !request.getName().isBlank()
                && !request.getName().trim().equals(project.getName())) {
            throw new RuntimeException(
                    "Le nom du projet ne peut pas être modifié après création, car il est utilisé par MLflow, Kubeflow et le Model Registry."
            );
        }

        project.setDescription(request.getDescription());

        String targetColumn = cleanTargetColumn(request.getTargetColumn());
        project.setTargetColumn(targetColumn);

        if (request.getAutoPromoteBestModel() != null) {
            project.setAutoPromoteBestModel(request.getAutoPromoteBestModel());
        }

        MlProject updatedProject = mlProjectRepository.save(project);

        return mapToResponse(updatedProject);
    }

    public MlProjectResponse updateProjectMetrics(
        String authHeader,
        Long projectId,
        MlProjectMetricsRequest request
) {
    User owner = getUserFromAuthorizationHeader(authHeader);

    /*
     * 1. Sauvegarder d'abord le nouvel ordre des métriques.
     * On garde une transaction séparée pour être sûr que les nouvelles métriques
     * sont bien persistées avant le recalcul.
     */
    MlProject savedProject = applicationContext
            .getBean(MlProjectService.class)
            .saveProjectMetricsTransactional(owner, projectId, request);

    String registeredModelName = buildRegisteredModelName(savedProject);

    /*
     * 2. Recalculer les scores globaux selon le nouvel ordre :
     * primaryMetric1 -> primaryMetric2 -> primaryMetric3
     */
    try {
        mlExperimentService.recalculateGlobalScoresForProject(
                savedProject,
                registeredModelName
        );

        System.out.println("[PROJECT_METRICS] Global scores recalculated after metrics update. project="
                + savedProject.getId()
                + ", model="
                + registeredModelName);

    } catch (Exception e) {
        System.out.println("[PROJECT_METRICS] Recalculation failed after metrics update: "
                + e.getMessage());
        e.printStackTrace();
    }

    /*
     * 3. Si l'auto-promotion est activée :
     * - choisir la meilleure version après recalcul
     * - mettre à jour l'alias MLflow production
     * - déployer automatiquement dans Kubernetes si la version gagnante change
     */
    if (Boolean.TRUE.equals(savedProject.getAutoPromoteBestModel())) {
        try {
            autoModelLifecycleService.evaluateAndAutoPromoteAndDeploy(
                    savedProject,
                    registeredModelName
            );

            System.out.println("[PROJECT_METRICS] Auto lifecycle executed after metrics update. project="
                    + savedProject.getId()
                    + ", model="
                    + registeredModelName);

        } catch (Exception e) {
            System.out.println("[PROJECT_METRICS] Auto lifecycle after metrics update failed: "
                    + e.getMessage());
            e.printStackTrace();
        }

        /*
         * 4. Recalcul final pour rafraîchir les flags production,
         * globalScore et métriques après promotion/déploiement.
         */
        try {
            mlExperimentService.recalculateGlobalScoresForProject(
                    savedProject,
                    registeredModelName
            );

            System.out.println("[PROJECT_METRICS] Final recalculation finished after auto lifecycle. project="
                    + savedProject.getId());

        } catch (Exception e) {
            System.out.println("[PROJECT_METRICS] Final recalculation after promotion failed: "
                    + e.getMessage());
            e.printStackTrace();
        }
    }

    MlProject refreshedProject = mlProjectRepository.findById(savedProject.getId())
            .orElse(savedProject);

    return mapToResponse(refreshedProject);
}
    @Transactional
    public MlProject saveProjectMetricsTransactional(
            User owner,
            Long projectId,
            MlProjectMetricsRequest request
    ) {
        MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String projectType = normalizeProjectType(project.getProjectType());

        String metric1 = cleanMetric(
                request.getPrimaryMetric1(),
                project.getPrimaryMetric1()
        );

        String metric2 = cleanMetric(
                request.getPrimaryMetric2(),
                null
        );

        String metric3 = cleanMetric(
                request.getPrimaryMetric3(),
                null
        );

        validateMetricForProjectType(projectType, metric1);
        validateMetricForProjectType(projectType, metric2);
        validateMetricForProjectType(projectType, metric3);
        validateNoDuplicateMetrics(metric1, metric2, metric3);

        project.setPrimaryMetric1(metric1);
        project.setPrimaryMetric2(metric2);
        project.setPrimaryMetric3(metric3);

        if (request.getAutoPromoteBestModel() != null) {
            project.setAutoPromoteBestModel(request.getAutoPromoteBestModel());
        }

        MlProject savedProject = mlProjectRepository.save(project);
        mlProjectRepository.flush();

        return savedProject;
    }

    @Transactional
    public DeleteProjectResponse deleteProject(String authHeader, Long projectId) {
        return deleteProjectCascade(authHeader, projectId);
    }

   @Transactional
public DeleteProjectResponse deleteProjectCascade(String authHeader, Long projectId) {
    User owner = getUserFromAuthorizationHeader(authHeader);

    MlProject project = mlProjectRepository.findByIdAndOwner(projectId, owner)
            .orElseThrow(() -> new RuntimeException("Project not found"));

    String registeredModelName = buildRegisteredModelName(project);
    String mlflowExperimentName = buildMlflowExperimentName(project);

    List<MlExperiment> experiments =
            mlExperimentRepository.findByProjectOrderByCreatedAtDesc(project);

    List<UploadedProjectFile> uploadedFiles =
            uploadedProjectFileRepository.findByProject(project);

    int deletedExperimentsCount = 0;

    /*
     * 1. Supprimer d'abord les déploiements du projet :
     * - Kubernetes Deployment
     * - Kubernetes Service
     * - model_deployment_shares
     * - model_deployments
     *
     * Important :
     * On fait ça avant de supprimer les expériences/projet pour éviter
     * les ressources Kubernetes orphelines.
     */
    try {
        modelDeploymentService.deleteDeploymentsForProject(projectId);
        System.out.println("[DELETE PROJECT] Deployments deleted for projectId=" + projectId);
    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete deployments for projectId=" + projectId);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());

        throw new RuntimeException(
                "Impossible de supprimer le projet car ses déploiements n'ont pas pu être supprimés. Cause: "
                        + e.getMessage(),
                e
        );
    }

    /*
     * 2. Supprimer les expériences.
     *
     * Important :
     * Les expériences peuvent référencer datasetFile/modelFile.
     * Donc on supprime les expériences avant les fichiers uploadés.
     */
    if (experiments != null) {
        for (MlExperiment experiment : experiments) {
            if (experiment == null || experiment.getId() == null) {
                continue;
            }

            try {
                mlExperimentService.deleteExperimentCascade(experiment);
                deletedExperimentsCount++;

                System.out.println("[DELETE PROJECT] Experiment deleted id=" + experiment.getId());

            } catch (Exception e) {
                System.out.println("[DELETE PROJECT] Failed to delete experiment id=" + experiment.getId());
                System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());

                throw new RuntimeException(
                        "Impossible de supprimer le projet, car l'expérience id="
                                + experiment.getId()
                                + " n'a pas pu être supprimée. Cause: "
                                + e.getMessage(),
                        e
                );
            }
        }
    }

    mlExperimentRepository.flush();

    /*
     * 3. Supprimer MLflow registered model.
     * Si MLflow échoue, on continue la suppression locale.
     */
    try {
        boolean deletedRegisteredModel =
                mlflowTrackingClientService.deleteRegisteredModel(registeredModelName);

        System.out.println("[DELETE PROJECT] MLflow registered model deleted="
                + deletedRegisteredModel + " name=" + registeredModelName);

    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete MLflow registered model=" + registeredModelName);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());
    }

    /*
     * 4. Supprimer l'expérience MLflow par nom projet.
     */
    try {
        boolean deletedExperiment =
                mlflowTrackingClientService.deleteMlflowExperimentByName(mlflowExperimentName);

        System.out.println("[DELETE PROJECT] MLflow experiment deleted="
                + deletedExperiment + " name=" + mlflowExperimentName);

    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete MLflow experiment=" + mlflowExperimentName);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());
    }

    /*
     * 5. Supprimer aussi l'expérience MLflow si elle porte le nom du registered model.
     */
    try {
        boolean deletedExperiment =
                mlflowTrackingClientService.deleteMlflowExperimentByName(registeredModelName);

        System.out.println("[DELETE PROJECT] MLflow experiment deleted="
                + deletedExperiment + " name=" + registeredModelName);

    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete MLflow experiment=" + registeredModelName);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());
    }

    /*
     * 6. Supprimer les fichiers physiques du disque.
     */
    if (uploadedFiles != null) {
        for (UploadedProjectFile file : uploadedFiles) {
            if (file == null) {
                continue;
            }

            deletePhysicalFileQuietly(file);
        }
    }

    /*
     * 7. Supprimer les fichiers uploadés en base.
     */
    try {
        uploadedProjectFileRepository.deleteByProject(project);
        uploadedProjectFileRepository.flush();

        System.out.println("[DELETE PROJECT] Uploaded files deleted for projectId=" + projectId);

    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete uploaded files for projectId=" + projectId);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());

        throw new RuntimeException(
                "Impossible de supprimer les fichiers uploadés du projet. Cause: " + e.getMessage(),
                e
        );
    }

    /*
     * 8. Supprimer le dossier complet du projet.
     */
    deleteProjectUploadDirectoryQuietly(projectId);

    /*
     * 9. Supprimer enfin le projet.
     */
    try {
        mlProjectRepository.delete(project);
        mlProjectRepository.flush();

        System.out.println("[DELETE PROJECT] Project deleted id=" + projectId);

    } catch (Exception e) {
        System.out.println("[DELETE PROJECT] Failed to delete project id=" + projectId);
        System.out.println("[DELETE PROJECT] Reason=" + e.getMessage());

        throw new RuntimeException(
                "Impossible de supprimer le projet id=" + projectId + ". Cause: " + e.getMessage(),
                e
        );
    }

    return new DeleteProjectResponse(
            projectId,
            "Project, deployments, services, experiments, files, Kubeflow runs and MLflow registry deleted successfully.",
            deletedExperimentsCount,
            true
    );
}

    private void deletePhysicalFileQuietly(UploadedProjectFile file) {
        try {
            if (file == null || file.getFilePath() == null || file.getFilePath().isBlank()) {
                return;
            }

            Path path = Paths.get(file.getFilePath()).toAbsolutePath().normalize();

            Files.deleteIfExists(path);

            System.out.println("Deleted physical uploaded file: " + path);

        } catch (Exception e) {
            System.out.println("Cannot delete physical uploaded file: " + e.getMessage());
        }
    }

    private void deleteProjectUploadDirectoryQuietly(Long projectId) {
        try {
            if (projectId == null) {
                return;
            }

            Path projectUploadPath = Paths.get(uploadDir, "projects", String.valueOf(projectId))
                    .toAbsolutePath()
                    .normalize();

            if (!Files.exists(projectUploadPath)) {
                return;
            }

            try (var paths = Files.walk(projectUploadPath)) {
                paths.sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception e) {
                                System.out.println("Cannot delete path: " + path + " reason=" + e.getMessage());
                            }
                        });
            }

            System.out.println("Deleted project upload directory: " + projectUploadPath);

        } catch (Exception e) {
            System.out.println("Cannot delete project upload directory: " + e.getMessage());
        }
    }

    public List<String> getMetricOptionsByProjectType(String projectType) {
        String type = normalizeProjectType(projectType);

        if ("regression".equals(type)) {
            return List.of(
                    "rmse",
                    "mae",
                    "mse",
                    "r2",
                    "mape",
                    "explained_variance",
                    "loss",
                    "val_loss"
            );
        }

        return List.of(
                "accuracy",
                "f1_score",
                "precision",
                "recall",
                "roc_auc",
                "log_loss",
                "loss",
                "val_loss",
                "val_accuracy",
                "average_precision",
                "cohen_kappa"
        );
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

    private String normalizeProjectType(String projectType) {
        if (projectType == null || projectType.isBlank()) {
            return "classification";
        }

        String normalized = projectType.trim().toLowerCase();

        if (!normalized.equals("regression") && !normalized.equals("classification")) {
            throw new RuntimeException(
                    "Invalid project type. Allowed values: regression, classification"
            );
        }

        return normalized;
    }

    private String normalizeRuntimeMode(String runtimeMode) {
        String mode = runtimeMode;

        if (mode == null || mode.isBlank()) {
            mode = "STANDARD_RUNTIME";
        }

        mode = mode.trim().toUpperCase();

        if (!mode.equals("STANDARD_RUNTIME") && !mode.equals("CUSTOM_DOCKERFILE")) {
            throw new RuntimeException("Invalid runtime mode. Allowed values: STANDARD_RUNTIME, CUSTOM_DOCKERFILE");
        }

        return mode;
    }

    private String cleanTargetColumn(String targetColumn) {
        if (targetColumn == null || targetColumn.isBlank()) {
            return null;
        }

        return targetColumn.trim();
    }

    private String cleanMetric(String metric, String fallback) {
        String value = metric;

        if (value == null || value.isBlank()) {
            value = fallback;
        }

        if (value == null || value.isBlank()) {
            return null;
        }

        String cleaned = value.trim().toLowerCase();

        return normalizeMetricAlias(cleaned);
    }

    private String normalizeMetricAlias(String metric) {
        if (metric == null || metric.isBlank()) {
            return null;
        }

        String cleaned = metric.trim().toLowerCase();

        if ("precision_weighted".equals(cleaned)) {
            return "precision";
        }

        if ("recall_weighted".equals(cleaned)) {
            return "recall";
        }

        if ("f1_weighted".equals(cleaned) || "f1".equals(cleaned)) {
            return "f1_score";
        }

        if ("r2_score".equals(cleaned) || "r²".equals(cleaned) || "r2".equals(cleaned)) {
            return "r2";
        }

        return cleaned;
    }

    private void validateMetricForProjectType(String projectType, String metric) {
        if (metric == null || metric.isBlank()) {
            return;
        }

        String cleanMetric = normalizeMetricAlias(metric);

        if (!getMetricOptionsByProjectType(projectType).contains(cleanMetric)) {
            throw new RuntimeException(
                    "Metric '" + cleanMetric + "' is not allowed for project type '" + projectType + "'. " +
                            "Allowed metrics: " + getMetricOptionsByProjectType(projectType)
            );
        }
    }

    private void validateNoDuplicateMetrics(
            String metric1,
            String metric2,
            String metric3
    ) {
        Set<String> uniqueMetrics = new HashSet<>();
        int chosenCount = 0;

        if (metric1 != null && !metric1.isBlank()) {
            uniqueMetrics.add(normalizeMetricAlias(metric1));
            chosenCount++;
        }

        if (metric2 != null && !metric2.isBlank()) {
            uniqueMetrics.add(normalizeMetricAlias(metric2));
            chosenCount++;
        }

        if (metric3 != null && !metric3.isBlank()) {
            uniqueMetrics.add(normalizeMetricAlias(metric3));
            chosenCount++;
        }

        if (chosenCount <= 0) {
            throw new RuntimeException("At least one metric must be chosen.");
        }

        if (uniqueMetrics.size() != chosenCount) {
            throw new RuntimeException(
                    "Duplicate metrics are not allowed. Please choose unique metrics."
            );
        }
    }

    private String defaultPrimaryMetric(String projectType) {
        if ("regression".equals(projectType)) {
            return "rmse";
        }

        return "f1_score";
    }

    private String buildRegisteredModelName(MlProject project) {
        String baseName = null;

        if (project != null && project.getName() != null && !project.getName().isBlank()) {
            baseName = project.getName();
        } else if (project != null && project.getId() != null) {
            baseName = "project_" + project.getId();
        } else {
            baseName = "project";
        }

        String cleanName = baseName
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = project != null && project.getId() != null
                    ? "project_" + project.getId()
                    : "project";
        }

        if (cleanName.endsWith("_model")) {
            return cleanName;
        }

        return cleanName + "_model";
    }

    private String buildMlflowExperimentName(MlProject project) {
        String baseName = null;

        if (project != null && project.getName() != null && !project.getName().isBlank()) {
            baseName = project.getName();
        } else if (project != null && project.getId() != null) {
            baseName = "project_" + project.getId();
        } else {
            baseName = "project";
        }

        String cleanName = baseName
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (cleanName.isBlank()) {
            cleanName = project != null && project.getId() != null
                    ? "project_" + project.getId()
                    : "project";
        }

        return cleanName;
    }

    private MlProjectResponse mapToResponse(MlProject project) {
        MlProjectResponse response = new MlProjectResponse();

        response.setId(project.getId());
        response.setName(project.getName());
        response.setDescription(project.getDescription());
        response.setDataType(project.getDataType());
        response.setProjectType(project.getProjectType());
        response.setTargetColumn(project.getTargetColumn());

        response.setPrimaryMetric1(project.getPrimaryMetric1());
        response.setPrimaryMetric2(project.getPrimaryMetric2());
        response.setPrimaryMetric3(project.getPrimaryMetric3());

        response.setAutoPromoteBestModel(project.getAutoPromoteBestModel());
        response.setCreatedAt(project.getCreatedAt() != null ? project.getCreatedAt().toString() : "-");
        response.setRuntimeMode(project.getRuntimeMode());
        response.setCustomImageUri(project.getCustomImageUri());
        response.setDockerfilePath(project.getDockerfilePath());
        response.setRequirementsPath(project.getRequirementsPath());

        return response;
    }

    private String normalizeDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "TABULAR";
        }

        String normalized = dataType.trim().toUpperCase();

        return switch (normalized) {
            case "TABULAR", "TABLE", "CSV", "TSV" -> "TABULAR";
            case "IMAGE", "IMAGES", "VISION" -> "IMAGE";
            case "TEXT", "NLP", "LLM" -> "TEXT";
            default -> throw new RuntimeException(
                    "Invalid data type. Allowed values: TABULAR, IMAGE, TEXT"
            );
        };
    }

    

}