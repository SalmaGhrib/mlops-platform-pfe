package com.mlops.platform.service;

import com.mlops.platform.dto.ModelRegistryResponse;
import com.mlops.platform.dto.ModelVersionResponse;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mlops.platform.entity.ModelDeployment;
import com.mlops.platform.entity.DeploymentStatus;
import com.mlops.platform.repository.ModelDeploymentRepository;
import com.mlops.platform.dto.SearchPageResponse;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ModelRegistryService {

    private final MlExperimentRepository mlExperimentRepository;
    private final MlExperimentMetricRepository mlExperimentMetricRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final MlflowRegistryClientService mlflowRegistryClientService;
    private final MlflowTrackingClientService mlflowTrackingClientService;
    private final ModelDeploymentRepository modelDeploymentRepository;

    public ModelRegistryService(
            MlExperimentRepository mlExperimentRepository,
            MlExperimentMetricRepository mlExperimentMetricRepository,
            UserRepository userRepository,
            JwtService jwtService,
            MlflowRegistryClientService mlflowRegistryClientService,
            MlflowTrackingClientService mlflowTrackingClientService,
            ModelDeploymentRepository modelDeploymentRepository
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlExperimentMetricRepository = mlExperimentMetricRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.mlflowRegistryClientService = mlflowRegistryClientService;
        this.mlflowTrackingClientService = mlflowTrackingClientService;
        this.modelDeploymentRepository = modelDeploymentRepository;
    }

    /**
     * Nouvelle page Model Registry :
     * retourne les modèles des projets du user connecté avec :
     * - latest version
     * - production version
     * - métriques production
     * - score global production
     * - algorithmes production
     */
    public List<ModelRegistryResponse> getMyRegisteredModels(String authHeader) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOwnerOrderByCreatedAtDesc(owner);

        return buildRegistryResponses(experiments);
    }

    /**
     * Synchronise MLflow Registry -> MySQL puis retourne l'affichage.
     */
    @Transactional
    public List<ModelRegistryResponse> syncAndGetMyRegisteredModels(String authHeader) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOwnerOrderByCreatedAtDesc(owner);

        Set<String> registeredModelNames = experiments.stream()
                .map(MlExperiment::getRegisteredModelName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String registeredModelName : registeredModelNames) {
            try {
                mlflowTrackingClientService.syncModelVersionsToDatabase(registeredModelName);
            } catch (Exception e) {
                System.out.println("Erreur sync registry model=" + registeredModelName);
                System.out.println(e.getMessage());
            }
        }

        List<MlExperiment> refreshedExperiments =
                mlExperimentRepository.findByProjectOwnerOrderByCreatedAtDesc(owner);

        return buildRegistryResponses(refreshedExperiments);
    }

    /**
     * Ancienne fonctionnalité : liste des versions d'un registered model.
     */
    public List<ModelVersionResponse> getVersionsByRegisteredModel(
            String authHeader,
            String registeredModelName
    ) {
        User owner = getUserFromAuthorizationHeader(authHeader);

        List<MlExperiment> experiments =
                mlExperimentRepository.findByProjectOwnerOrderByCreatedAtDesc(owner);

        MlExperiment matchedExperiment = null;
        MlflowRegistryClientService.ProductionModelInfo productionInfo = null;

        for (MlExperiment experiment : experiments) {
            List<String> candidates = buildModelNameCandidates(experiment);

            for (String candidate : candidates) {
                if (!candidate.equals(registeredModelName)) {
                    continue;
                }

                productionInfo = mlflowRegistryClientService.getProductionVersion(candidate);

                if (productionInfo != null && productionInfo.getVersion() != null) {
                    matchedExperiment = experiment;
                    break;
                }
            }

            if (matchedExperiment != null) {
                break;
            }
        }

        if (matchedExperiment == null || productionInfo == null) {
            return List.of();
        }

        return List.of(mapToProductionVersionResponse(matchedExperiment, productionInfo));
    }

    private List<ModelRegistryResponse> buildRegistryResponses(List<MlExperiment> experiments) {
        if (experiments == null || experiments.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, List<MlExperiment>> grouped = experiments.stream()
                .filter(exp -> exp.getProject() != null)
                .filter(exp -> exp.getRegisteredModelName() != null && !exp.getRegisteredModelName().isBlank())
                .collect(Collectors.groupingBy(
                        exp -> exp.getProject().getId() + "::" + exp.getRegisteredModelName(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<ModelRegistryResponse> result = new ArrayList<>();

        for (List<MlExperiment> group : grouped.values()) {
            result.add(buildRegistryResponse(group));
        }

        result.sort((a, b) -> {
            String au = a.getUpdatedAt() != null ? a.getUpdatedAt() : "";
            String bu = b.getUpdatedAt() != null ? b.getUpdatedAt() : "";
            return bu.compareTo(au);
        });

        return result;
    }

    /**
     * Recherche dynamique des modèles du user connecté avec filtres
     * (projectType / dataType) et pagination sur le regroupement par modèle.
     *
     * Pas de @Transactional : la construction des réponses peut déclencher des
     * appels MLflow (resolveProductionVersionFromMysqlOrMlflow) ; on ne garde
     * jamais une connexion DB ouverte pendant des appels réseau.
     */
    public SearchPageResponse<ModelRegistryResponse> searchMyRegisteredModels(
            String authHeader,
            String q,
            String projectType,
            String dataType,
            int page,
            int size
    ) {
        User owner = getUserFromAuthorizationHeader(authHeader);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));

        Specification<MlExperiment> spec =
                modelRegistrySearchSpecification(owner, q, projectType, dataType);

        List<MlExperiment> matching = mlExperimentRepository.findAll(spec);

        Map<String, List<MlExperiment>> grouped = matching.stream()
                .filter(exp -> exp.getProject() != null)
                .filter(exp -> exp.getRegisteredModelName() != null && !exp.getRegisteredModelName().isBlank())
                .collect(Collectors.groupingBy(
                        exp -> exp.getProject().getId() + "::" + exp.getRegisteredModelName(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<ModelRegistryResponse> all = new ArrayList<>();
        for (List<MlExperiment> group : grouped.values()) {
            all.add(buildRegistryResponse(group));
        }

        long total = all.size();
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<ModelRegistryResponse> items = from >= all.size()
                ? List.of()
                : all.subList(from, to);

        return new SearchPageResponse<>(items, total, safePage, safeSize, to < all.size());
    }

    private Specification<MlExperiment> modelRegistrySearchSpecification(
            User owner,
            String q,
            String projectType,
            String dataType
    ) {
        return (Root<MlExperiment> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List<Expression<Integer>> tokenScores = new ArrayList<>();

            Join<Object, Object> project = root.join("project", JoinType.INNER);

            predicates.add(cb.equal(project.get("owner"), owner));
            predicates.add(cb.isNotNull(root.get("registeredModelName")));
            predicates.add(cb.notEqual(root.get("registeredModelName"), ""));

            if (projectType != null && !projectType.isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(project.get("projectType")),
                        projectType.trim().toLowerCase()
                ));
            }
            if (dataType != null && !dataType.isBlank()) {
                predicates.add(cb.equal(
                        cb.upper(project.get("dataType")),
                        dataType.trim().toUpperCase()
                ));
            }

            if (q != null && !q.isBlank()) {
                String[] tokens = q.trim().toLowerCase().split("\\s+");
                for (String token : tokens) {
                    String like = "%" + token + "%";

                    Expression<Integer> score = cb.<Integer>selectCase()
                            .when(cb.like(cb.lower(root.get("registeredModelName")), like), 100)
                            .otherwise(0);
                    score = cb.sum(score, cb.<Integer>selectCase()
                            .when(cb.like(cb.lower(root.get("modelName")), like), 60)
                            .otherwise(0));
                    score = cb.sum(score, cb.<Integer>selectCase()
                            .when(cb.like(cb.lower(root.get("experimentName")), like), 50)
                            .otherwise(0));
                    score = cb.sum(score, cb.<Integer>selectCase()
                            .when(cb.like(cb.lower(project.get("name")), like), 40)
                            .otherwise(0));
                    score = cb.sum(score, cb.<Integer>selectCase()
                            .when(cb.like(cb.lower(project.get("description")), like), 30)
                            .otherwise(0));

                    predicates.add(cb.greaterThan(score, 0));
                    tokenScores.add(score);
                }
            }

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                if (tokenScores.isEmpty()) {
                    query.orderBy(cb.desc(root.get("createdAt")));
                } else {
                    Expression<Integer> total = tokenScores.stream()
                            .reduce(cb::sum)
                            .orElse(cb.literal(0));
                    query.orderBy(
                            cb.desc(total),
                            cb.desc(root.get("createdAt"))
                    );
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private ModelRegistryResponse buildRegistryResponse(List<MlExperiment> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }

        group.sort(this::compareExperimentsByVersionDescThenDateDesc);

        MlExperiment latestExperiment = group.get(0);

        String registeredModelName = latestExperiment.getRegisteredModelName();
        String latestVersion = findLatestVersion(group);
        String productionVersion = resolveProductionVersionFromMysqlOrMlflow(group, registeredModelName);

        MlExperiment productionExperiment = findProductionExperiment(group, productionVersion);

        if (productionExperiment == null) {
            productionExperiment = latestExperiment;
        }

        return mapToRegistryResponse(
                latestExperiment,
                productionExperiment,
                latestVersion,
                productionVersion
        );
    }

    private ModelRegistryResponse mapToRegistryResponse(
            MlExperiment latestExperiment,
            MlExperiment productionExperiment,
            String latestVersion,
            String productionVersion
    ) {
        UploadedProjectFile modelFile = productionExperiment.getModelFile();
        UploadedProjectFile datasetFile = productionExperiment.getDatasetFile();

        String registeredModelName = firstNotBlank(
                productionExperiment.getRegisteredModelName(),
                latestExperiment.getRegisteredModelName()
        );

        boolean hasProduction =
                productionVersion != null
                        && !productionVersion.isBlank()
                        && !productionVersion.equals("-")
                        && productionExperiment.getModelVersion() != null
                        && productionExperiment.getModelVersion().equals(productionVersion);

        ModelRegistryResponse response = new ModelRegistryResponse();

        response.setRegisteredModelName(registeredModelName);
        response.setModelName(productionExperiment.getModelName());

        response.setLatestVersion(latestVersion);
        response.setProductionVersion(productionVersion);
        response.setProductionAlias("production");
        response.setMlflowStatus("READY");
        response.setStatus(
                productionExperiment.getStatus() != null
                        ? productionExperiment.getStatus().name()
                        : "-"
        );

        response.setProjectId(productionExperiment.getProject().getId());
        response.setProjectName(productionExperiment.getProject().getName());
        response.setDataType(productionExperiment.getProject().getDataType());
        response.setProjectType(productionExperiment.getProject().getProjectType());

        response.setLatestExperimentId(latestExperiment.getId());
        response.setLatestExperimentName(latestExperiment.getExperimentName());

        response.setProductionExperimentId(productionExperiment.getId());
        response.setProductionExperimentName(productionExperiment.getExperimentName());

        response.setPrimaryMetric(productionExperiment.getPrimaryMetric());
        response.setPrimaryMetricValue(productionExperiment.getPrimaryMetricValue());
        response.setDirection(productionExperiment.getDirection());
        response.setGlobalScore(productionExperiment.getGlobalScore());

        response.setModelFileName(modelFile != null ? modelFile.getOriginalFileName() : null);
        response.setDatasetFileName(datasetFile != null ? datasetFile.getOriginalFileName() : null);

        response.setMlflowRunId(productionExperiment.getMlflowRunId());
        response.setKubeflowRunId(productionExperiment.getKubeflowRunId());
        response.setSource("MYSQL_SYNCED_FROM_MLFLOW");

        response.setMetrics(getMetricsMap(productionExperiment));

        response.setDetectedAlgorithms(productionExperiment.getDetectedAlgorithms());
        response.setUsedAlgorithms(productionExperiment.getUsedAlgorithms());
        

        response.setHasProduction(hasProduction);

        modelDeploymentRepository
.findByRegisteredModelNameAndModelVersionAndActiveTrue(
        registeredModelName,
        productionVersion
)
.ifPresentOrElse(
        deployment -> {
            response.setDeployed(
                    DeploymentStatus.DEPLOYED.equals(deployment.getDeploymentStatus())
                            && Boolean.TRUE.equals(deployment.getActive())
            );
            response.setDeploymentId(deployment.getId());
            response.setDeploymentStatus(
                    deployment.getDeploymentStatus() != null
                            ? deployment.getDeploymentStatus().name()
                            : null
            );
            response.setPredictionEndpoint(deployment.getPredictionEndpoint());
        },
        () -> {
            response.setDeployed(false);
            response.setDeploymentStatus("NOT_DEPLOYED");
            response.setPredictionEndpoint(null);
            response.setDeploymentId(null);
        }
);

        response.setCreatedAt(
                productionExperiment.getCreatedAt() != null
                        ? productionExperiment.getCreatedAt().toString()
                        : "-"
        );

        response.setUpdatedAt(
                productionExperiment.getUpdatedAt() != null
                        ? productionExperiment.getUpdatedAt().toString()
                        : "-"
        );

        return response;
    }

    private Map<String, Double> getMetricsMap(MlExperiment experiment) {
        Map<String, Double> result = new LinkedHashMap<>();

        if (experiment == null || experiment.getId() == null) {
            return result;
        }

        List<MlExperimentMetric> metrics = mlExperimentMetricRepository.findByExperiment(experiment);

        if (metrics == null || metrics.isEmpty()) {
            return result;
        }

        for (MlExperimentMetric metric : metrics) {
            if (metric.getMetricName() == null || metric.getMetricValue() == null) {
                continue;
            }

            result.put(metric.getMetricName(), metric.getMetricValue());
        }

        return result;
    }

    private String resolveProductionVersionFromMysqlOrMlflow(
            List<MlExperiment> group,
            String registeredModelName
    ) {
        String mysqlProduction = findProductionVersion(group);

        if (mysqlProduction != null && !mysqlProduction.isBlank() && !mysqlProduction.equals("-")) {
            return mysqlProduction;
        }

        try {
            String mlflowProduction = mlflowTrackingClientService.getProductionVersion(registeredModelName);

            if (mlflowProduction != null && !mlflowProduction.isBlank()) {
                return mlflowProduction;
            }
        } catch (Exception e) {
            System.out.println("Cannot fetch production version from MLflow for " + registeredModelName);
        }

        return "-";
    }

    private String findLatestVersion(List<MlExperiment> group) {
        return group.stream()
                .map(MlExperiment::getModelVersion)
                .filter(version -> version != null && !version.isBlank())
                .max(this::compareVersionStrings)
                .orElse("-");
    }

    private String findProductionVersion(List<MlExperiment> group) {
        Optional<String> productionFromFlag = group.stream()
                .filter(exp -> Boolean.TRUE.equals(exp.getProduction()))
                .map(MlExperiment::getModelVersion)
                .filter(version -> version != null && !version.isBlank())
                .max(this::compareVersionStrings);

        if (productionFromFlag.isPresent()) {
            return productionFromFlag.get();
        }

        Optional<String> productionFromColumn = group.stream()
                .map(MlExperiment::getProductionVersion)
                .filter(version -> version != null && !version.isBlank())
                .max(this::compareVersionStrings);

        return productionFromColumn.orElse("-");
    }

    private MlExperiment findProductionExperiment(List<MlExperiment> group, String productionVersion) {
    if (group == null || group.isEmpty()) {
        return null;
    }

    /*
     * Priorité absolue : le vrai flag MySQL production=true.
     */
    Optional<MlExperiment> byFlag = group.stream()
            .filter(exp -> Boolean.TRUE.equals(exp.getProduction()))
            .findFirst();

    if (byFlag.isPresent()) {
        return byFlag.get();
    }

    /*
     * Fallback seulement si aucun flag production=true n'existe.
     */
    if (productionVersion != null && !productionVersion.isBlank() && !productionVersion.equals("-")) {
        return group.stream()
                .filter(exp -> exp.getModelVersion() != null)
                .filter(exp -> exp.getModelVersion().equals(productionVersion))
                .findFirst()
                .orElse(null);
    }

    return null;
}

    private int compareExperimentsByVersionDescThenDateDesc(MlExperiment a, MlExperiment b) {
        int versionCompare = compareVersionStrings(
                b.getModelVersion(),
                a.getModelVersion()
        );

        if (versionCompare != 0) {
            return versionCompare;
        }

        String au = a.getUpdatedAt() != null ? a.getUpdatedAt().toString() : "";
        String bu = b.getUpdatedAt() != null ? b.getUpdatedAt().toString() : "";

        return bu.compareTo(au);
    }

    private int compareVersionStrings(String a, String b) {
        if (a == null || a.isBlank()) {
            a = "0";
        }

        if (b == null || b.isBlank()) {
            b = "0";
        }

        try {
            Integer ai = Integer.parseInt(a.trim());
            Integer bi = Integer.parseInt(b.trim());
            return ai.compareTo(bi);
        } catch (Exception ignored) {
            return a.compareTo(b);
        }
    }

    private MlflowRegistryClientService.ProductionModelInfo findProductionInfo(
            MlExperiment experiment,
            String registryNameCandidate
    ) {
        List<String> candidates = buildModelNameCandidates(experiment);

        if (registryNameCandidate != null && !registryNameCandidate.isBlank()) {
            candidates.add(0, registryNameCandidate);
        }

        Set<String> uniqueCandidates = new LinkedHashSet<>(candidates);

        for (String candidate : uniqueCandidates) {
            MlflowRegistryClientService.ProductionModelInfo productionInfo =
                    mlflowRegistryClientService.getProductionVersion(candidate);

            if (productionInfo != null && productionInfo.getVersion() != null) {
                return productionInfo;
            }
        }

        return null;
    }

    private String resolveBestRegisteredName(MlExperiment experiment) {
        List<String> candidates = buildModelNameCandidates(experiment);

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }

        return null;
    }

    private List<String> buildModelNameCandidates(MlExperiment experiment) {
        List<String> candidates = new ArrayList<>();

        if (experiment.getRegisteredModelName() != null && !experiment.getRegisteredModelName().isBlank()) {
            candidates.add(experiment.getRegisteredModelName());
        }

        if (experiment.getModelName() != null && !experiment.getModelName().isBlank()) {
            candidates.add(experiment.getModelName());
        }

        if (
                experiment.getProject() != null &&
                        experiment.getProject().getName() != null &&
                        experiment.getModelName() != null
        ) {
            String safeProjectName = experiment.getProject()
                    .getName()
                    .trim()
                    .replaceAll("[^a-zA-Z0-9_-]", "-")
                    .toLowerCase();

            candidates.add(safeProjectName + "-" + experiment.getModelName());
            candidates.add(safeProjectName + "_" + experiment.getModelName());
        }

        return candidates;
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

    private ModelVersionResponse mapToProductionVersionResponse(
            MlExperiment experiment,
            MlflowRegistryClientService.ProductionModelInfo productionInfo
    ) {
        UploadedProjectFile modelFile = experiment.getModelFile();
        UploadedProjectFile datasetFile = experiment.getDatasetFile();

        String displayedRegisteredModelName =
                productionInfo.getRegisteredModelName() != null
                        ? productionInfo.getRegisteredModelName()
                        : experiment.getRegisteredModelName();

        return new ModelVersionResponse(
                experiment.getId(),
                experiment.getExperimentName(),

                displayedRegisteredModelName,
                experiment.getModelName(),

                productionInfo.getVersion(),
                productionInfo.getAlias(),
                productionInfo.getMlflowStatus(),
                "PRODUCTION",

                experiment.getProject().getId(),
                experiment.getProject().getName(),

                experiment.getPrimaryMetric(),
                experiment.getPrimaryMetricValue(),

                modelFile != null ? modelFile.getOriginalFileName() : null,
                datasetFile != null ? datasetFile.getOriginalFileName() : null,

                productionInfo.getRunId(),
                productionInfo.getSource(),

                productionInfo.getCreatedAt() != null ? productionInfo.getCreatedAt() :
                        experiment.getCreatedAt() != null ? experiment.getCreatedAt().toString() : "-",

                productionInfo.getUpdatedAt() != null ? productionInfo.getUpdatedAt() :
                        experiment.getUpdatedAt() != null ? experiment.getUpdatedAt().toString() : "-"
        );
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }
}