package com.mlops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.dto.MetricPriorityDto;
import com.mlops.platform.dto.MlflowModelVersionDto;
import com.mlops.platform.entity.ExperimentStatus;
import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlExperimentMetric;
import com.mlops.platform.repository.MlExperimentMetricRepository;
import com.mlops.platform.repository.MlExperimentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MlflowTrackingClientService {

    @Value("${mlflow.tracking.uri:http://mlflow-service.mlops.svc.cluster.local:5000}")
    private String mlflowTrackingUri;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MlExperimentRepository mlExperimentRepository;
    private final MlExperimentMetricRepository mlExperimentMetricRepository;

    public MlflowTrackingClientService(
            MlExperimentRepository mlExperimentRepository,
            MlExperimentMetricRepository mlExperimentMetricRepository
    ) {
        this.mlExperimentRepository = mlExperimentRepository;
        this.mlExperimentMetricRepository = mlExperimentMetricRepository;
    }

    public static class MlflowRunDetails {
        private String runId;
        private String experimentId;
        private String runName;
        private String status;
        private String modelVersion;
        private String registeredModelName;
        private Map<String, Double> metrics = new LinkedHashMap<>();
        private Map<String, String> tags = new LinkedHashMap<>();

        public MlflowRunDetails() {
        }

        public MlflowRunDetails(
                String runId,
                String experimentId,
                String runName,
                String status,
                String modelVersion,
                String registeredModelName,
                Map<String, Double> metrics
        ) {
            this.runId = runId;
            this.experimentId = experimentId;
            this.runName = runName;
            this.status = status;
            this.modelVersion = modelVersion;
            this.registeredModelName = registeredModelName;
            this.metrics = metrics != null ? metrics : new LinkedHashMap<>();
            this.tags = new LinkedHashMap<>();
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getExperimentId() {
            return experimentId;
        }

        public void setExperimentId(String experimentId) {
            this.experimentId = experimentId;
        }

        public String getRunName() {
            return runName;
        }

        public void setRunName(String runName) {
            this.runName = runName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public void setModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
        }

        public String getRegisteredModelName() {
            return registeredModelName;
        }

        public void setRegisteredModelName(String registeredModelName) {
            this.registeredModelName = registeredModelName;
        }

        public Map<String, Double> getMetrics() {
            return metrics;
        }

        public void setMetrics(Map<String, Double> metrics) {
            this.metrics = metrics != null ? metrics : new LinkedHashMap<>();
        }

        public Map<String, String> getTags() {
            return tags;
        }

        public void setTags(Map<String, String> tags) {
            this.tags = tags != null ? tags : new LinkedHashMap<>();
        }

        public String getTag(String key) {
            if (key == null || tags == null) {
                return null;
            }
            return tags.get(key);
        }
    }

    public static class ModelVersionInfo {
        private String registeredModelName;
        private String version;
        private String runId;
        private String status;

        public ModelVersionInfo() {
        }

        public ModelVersionInfo(String registeredModelName, String version, String runId, String status) {
            this.registeredModelName = registeredModelName;
            this.version = version;
            this.runId = runId;
            this.status = status;
        }

        public String getRegisteredModelName() {
            return registeredModelName;
        }

        public void setRegisteredModelName(String registeredModelName) {
            this.registeredModelName = registeredModelName;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public MlflowRunDetails findRunByExperimentNameAndRunName(
            String experimentName,
            String runName,
            String registeredModelName
    ) {
        try {
            String experimentId = findExperimentIdByName(experimentName);

            if (experimentId == null || experimentId.isBlank()) {
                System.out.println("Aucune expérience MLflow trouvée : " + experimentName);
                return null;
            }

            MlflowRunDetails details;

            if (runName != null && !runName.isBlank()) {
                details = searchLatestRunByRunName(experimentId, runName);

                if (details == null || details.getRunId() == null || details.getRunId().isBlank()) {
                    return null;
                }
            } else {
                details = searchLatestRun(experimentId);
            }

            if (details == null || details.getRunId() == null || details.getRunId().isBlank()) {
                return null;
            }

            applyModelVersionFallbacks(details, registeredModelName);
            return details;

        } catch (Exception e) {
            System.out.println("Erreur findRunByExperimentNameAndRunName : " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public MlflowRunDetails syncExperimentWithMlflow(MlExperiment experiment) {
        if (experiment == null) {
            return null;
        }

        MlflowRunDetails runDetails = findBestMlflowRunForExperiment(experiment);

        if (runDetails == null) {
            return null;
        }

        if (runDetails.getExperimentId() == null || runDetails.getExperimentId().isBlank()) {
            String experimentName = firstNotBlank(
                    experiment.getMlflowExperimentName(),
                    experiment.getProject() != null ? experiment.getProject().getName() : null
            );

            if (experimentName != null && !experimentName.isBlank()) {
                runDetails.setExperimentId(findExperimentIdByName(experimentName));
            }
        }

        return runDetails;
    }

public void setModelAlias(String registeredModelName, String modelVersion, String alias) {
    try {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new IllegalArgumentException("registeredModelName est obligatoire.");
        }

        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion est obligatoire.");
        }

        String cleanAlias = alias;

        if (cleanAlias == null || cleanAlias.isBlank()) {
            cleanAlias = "production";
        }

        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/registered-models/alias";

        String requestBody = "{"
                + "\"name\":\"" + jsonEscape(registeredModelName.trim()) + "\","
                + "\"alias\":\"" + jsonEscape(cleanAlias.trim()) + "\","
                + "\"version\":\"" + jsonEscape(modelVersion.trim()) + "\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        System.out.println("[MLFLOW] Alias updated: "
                + registeredModelName.trim()
                + " @" + cleanAlias.trim()
                + " -> v" + modelVersion.trim());

    } catch (Exception e) {
        System.out.println("[MLFLOW] Erreur setModelAlias : " + e.getMessage());
        throw new RuntimeException("Impossible de mettre à jour l'alias MLflow.", e);
    }
}


public void setProductionAliasSafe(String registeredModelName, String modelVersion) {
    setModelAlias(registeredModelName, modelVersion, "production");
}
    public String getMlflowExperimentIdForRun(String runId) {
        MlflowRunDetails details = getRunDetailsFromRunId(runId, null);
        return details != null ? details.getExperimentId() : null;
    }
    
    public MlflowRunDetails findBestMlflowRunForExperiment(MlExperiment experiment) {
    if (experiment == null) {
        return null;
    }

    /*
     * Le nom important ici est le Registered Model MLflow.
     *
     * Exemple :
     * - expérience MLflow : dep2
     * - registered model  : dep2_model
     */
    String preferredRegisteredModelName = firstNotBlank(
            experiment.getRegisteredModelName(),
            buildRegisteredModelNameFromProjectOrExperiment(experiment)
    );

    MlflowRunDetails runDetails;

    /*
     * 1) Matching direct par mlflowRunId déjà stocké en MySQL.
     */
    if (experiment.getMlflowRunId() != null && !experiment.getMlflowRunId().isBlank()) {
        runDetails = getRunDetailsFromRunId(
                experiment.getMlflowRunId(),
                preferredRegisteredModelName
        );

        if (runDetails != null && runDetails.getRunId() != null && !runDetails.getRunId().isBlank()) {
            return runDetails;
        }
    }

    /*
     * 2) Matching par ID MySQL/Spring tagué dans MLflow.
     */
    if (experiment.getId() != null) {
        String experimentId = String.valueOf(experiment.getId());

        List<String> springIdTags = List.of(
                "spring_experiment_id",
                "springExperimentId",
                "mysql_experiment_id",
                "experiment_db_id"
        );

        for (String tagKey : springIdTags) {
            runDetails = findLatestRunByTag(
                    tagKey,
                    experimentId,
                    preferredRegisteredModelName
            );

            if (runDetails != null && runDetails.getRunId() != null && !runDetails.getRunId().isBlank()) {
                return runDetails;
            }
        }
    }

    /*
     * 3) Matching par tags Kubeflow/Kubernetes.
     */
    List<String[]> tagCandidates = List.of(
            new String[]{"kubeflow_run_id", experiment.getKubeflowRunId()},
            new String[]{"kubeflowRunId", experiment.getKubeflowRunId()},
            new String[]{"kubeflow.run_id", experiment.getKubeflowRunId()},

            new String[]{"kubeflow_run_name", experiment.getKubeflowRunName()},
            new String[]{"kubeflowRunName", experiment.getKubeflowRunName()},
            new String[]{"kubeflow.run_name", experiment.getKubeflowRunName()},

            new String[]{"training_job_name", experiment.getTrainingJobName()},
            new String[]{"trainingJobName", experiment.getTrainingJobName()},
            new String[]{"kubernetes_job_name", experiment.getTrainingJobName()},

            new String[]{"mlflow.runName", experiment.getExperimentName()},
            new String[]{"run_name", experiment.getExperimentName()}
    );

    for (String[] candidate : tagCandidates) {
        if (candidate == null || candidate.length < 2) {
            continue;
        }

        String tagKey = candidate[0];
        String tagValue = candidate[1];

        if (tagKey == null || tagKey.isBlank()
                || tagValue == null || tagValue.isBlank()) {
            continue;
        }

        runDetails = findLatestRunByTag(
                tagKey.trim(),
                tagValue.trim(),
                preferredRegisteredModelName
        );

        if (runDetails != null && runDetails.getRunId() != null && !runDetails.getRunId().isBlank()) {
            return runDetails;
        }
    }

    /*
     * 4) Matching via Model Registry.
     *
     * C'est le plus important pour ton cas actuel :
     * MLflow affiche bien dep2_model v1, mais la ligne MySQL reste RUNNING.
     * Donc on récupère le run_id depuis la version enregistrée dans le Registry.
     */
    runDetails = findRunFromRegistryByExperimentIdentity(
            experiment,
            preferredRegisteredModelName
    );

    if (runDetails != null && runDetails.getRunId() != null && !runDetails.getRunId().isBlank()) {
        return runDetails;
    }

    /*
     * 5) Recherche dans les expériences MLflow candidates.
     */
    List<String> mlflowExperimentCandidates = buildMlflowExperimentNameCandidates(experiment);

    for (String mlflowExperimentCandidate : mlflowExperimentCandidates) {
        if (mlflowExperimentCandidate == null || mlflowExperimentCandidate.isBlank()) {
            continue;
        }

        /*
         * 5.1) Recherche par nom exact du run.
         */
        if (experiment.getExperimentName() != null && !experiment.getExperimentName().isBlank()) {
            runDetails = findRunByExperimentNameAndRunName(
                    mlflowExperimentCandidate,
                    experiment.getExperimentName(),
                    preferredRegisteredModelName
            );

            if (runDetails != null && matchesExperimentIdentity(experiment, runDetails)) {
                return runDetails;
            }

            if (runDetails != null
                    && runDetails.getRunName() != null
                    && runDetails.getRunName().equalsIgnoreCase(experiment.getExperimentName())) {
                return runDetails;
            }
        }

        /*
         * 5.2) Recherche par le nom standard utilisé par les scripts générés.
         * Avant, on exigeait forcément des tags d'identité.
         * Maintenant, si le run est bien lié au Registered Model attendu via le Registry,
         * on peut l'accepter même si le script standard n'a pas loggé tous les tags.
         */
        runDetails = findRunByExperimentNameAndRunName(
                mlflowExperimentCandidate,
                "custom_run",
                preferredRegisteredModelName
        );

        if (runDetails != null && matchesExperimentIdentity(experiment, runDetails)) {
            return runDetails;
        }

        if (runDetails != null
                && canAttachMlflowRunToExperimentFromRegistry(
                experiment,
                runDetails,
                preferredRegisteredModelName
        )) {
            return runDetails;
        }

        /*
         * 5.3) Dernier fallback : parcourir les runs récents.
         */
        List<MlflowRunDetails> runs = findAllRunsByExperimentName(
                mlflowExperimentCandidate,
                preferredRegisteredModelName
        );

        MlflowRunDetails matched = pickBestMatchingRunForExperiment(experiment, runs);

        if (matched != null && matched.getRunId() != null && !matched.getRunId().isBlank()) {
            return matched;
        }

        for (MlflowRunDetails candidateRun : runs) {
            if (canAttachMlflowRunToExperimentFromRegistry(
                    experiment,
                    candidateRun,
                    preferredRegisteredModelName
            )) {
                return candidateRun;
            }
        }
    }

    return null;
}

private String buildRegisteredModelNameFromProjectOrExperiment(MlExperiment experiment) {
    if (experiment == null) {
        return null;
    }

    /*
     * Si le registeredModelName existe déjà, on le garde.
     */
    if (experiment.getRegisteredModelName() != null
            && !experiment.getRegisteredModelName().isBlank()) {
        return experiment.getRegisteredModelName().trim();
    }

    /*
     * Sinon, on essaie depuis le nom du projet.
     * Exemple :
     * project.name = pp11
     * registered model = pp11_model
     */
    if (experiment.getProject() != null
            && experiment.getProject().getName() != null
            && !experiment.getProject().getName().isBlank()) {
        return experiment.getProject().getName().trim() + "_model";
    }

    /*
     * Dernier fallback depuis mlflowExperimentName.
     */
    if (experiment.getMlflowExperimentName() != null
            && !experiment.getMlflowExperimentName().isBlank()) {
        String name = experiment.getMlflowExperimentName().trim();

        if (name.endsWith("_model")) {
            return name;
        }

        return name + "_model";
    }

    return null;
}


private String extractBaseExperimentNameFromRegisteredModel(String registeredModelName) {
    if (registeredModelName == null || registeredModelName.isBlank()) {
        return null;
    }

    String cleanName = registeredModelName.trim();

    if (cleanName.endsWith("_model")) {
        return cleanName.substring(0, cleanName.length() - "_model".length());
    }

    return cleanName;
}

private List<String> buildMlflowExperimentNameCandidates(MlExperiment experiment) {
    List<String> candidates = new ArrayList<>();

    if (experiment == null) {
        return candidates;
    }

    addCandidate(candidates, experiment.getMlflowExperimentName());

    /*
     * Les schedulers peuvent recevoir une entité détachée. Ne jamais forcer
     * l'initialisation d'un proxy project hors transaction : cela provoquait
     * LazyInitializationException et interrompait toute la synchronisation.
     */
    if (experiment.getProject() != null
            && Hibernate.isInitialized(experiment.getProject())) {
        addCandidate(candidates, experiment.getProject().getName());
    }

    // The registered model name must never be used as an MLflow experiment name.
    // The run name remains only a final compatibility fallback.
    addCandidate(candidates, experiment.getExperimentName());

    return candidates;
}
private MlflowRunDetails pickBestMatchingRunForExperiment(
        MlExperiment experiment,
        List<MlflowRunDetails> runs
) {
    if (experiment == null || runs == null || runs.isEmpty()) {
        return null;
    }

    for (MlflowRunDetails candidate : runs) {
        if (candidate == null) {
            continue;
        }

        if (matchesExperimentIdentity(experiment, candidate)) {
            return candidate;
        }
    }

    return null;
}

private boolean matchesExperimentIdentity(MlExperiment experiment, MlflowRunDetails candidate) {
    if (experiment == null || candidate == null) {
        return false;
    }

    if (experiment.getId() != null) {
        String springId = String.valueOf(experiment.getId());

        if (springId.equals(candidate.getTag("spring_experiment_id"))
                || springId.equals(candidate.getTag("springExperimentId"))
                || springId.equals(candidate.getTag("mysql_experiment_id"))
                || springId.equals(candidate.getTag("experiment_db_id"))) {
            return true;
        }
    }

    if (experiment.getExperimentName() != null
            && experiment.getExperimentName().equalsIgnoreCase(candidate.getRunName())) {
        return true;
    }

    if (experiment.getKubeflowRunId() != null
            && experiment.getKubeflowRunId().equalsIgnoreCase(candidate.getTag("kubeflow_run_id"))) {
        return true;
    }

    if (experiment.getKubeflowRunName() != null
            && experiment.getKubeflowRunName().equalsIgnoreCase(candidate.getTag("kubeflow_run_name"))) {
        return true;
    }

    if (experiment.getTrainingJobName() != null) {
        String trainingJobTag = firstNotBlank(
                candidate.getTag("training_job_name"),
                candidate.getTag("trainingJobName"),
                candidate.getTag("kubernetes_job_name")
        );

        if (experiment.getTrainingJobName().equalsIgnoreCase(trainingJobTag)) {
            return true;
        }
    }

    return false;
}

private MlflowRunDetails findRunFromRegistryByExperimentIdentity(
        MlExperiment experiment,
        String preferredRegisteredModelName
) {
    if (experiment == null) {
        return null;
    }

    List<String> registeredModelCandidates = buildRegisteredModelNameCandidates(preferredRegisteredModelName);

    for (String registeredModelName : registeredModelCandidates) {
        List<MlflowModelVersionDto> versions = getAllModelVersionsWithMetrics(registeredModelName);

        if (versions == null || versions.isEmpty()) {
            continue;
        }

        /*
         * 1) Matching strict par tags d'identité.
         */
        for (MlflowModelVersionDto versionDto : versions) {
            if (versionDto == null || versionDto.getRunId() == null || versionDto.getRunId().isBlank()) {
                continue;
            }

            MlflowRunDetails details = getRunDetailsFromRunId(versionDto.getRunId(), registeredModelName);

            if (details == null) {
                continue;
            }

            if (experiment.getId() != null) {
                String springId = String.valueOf(experiment.getId());

                if (springId.equals(firstNotBlank(
                        details.getTag("spring_experiment_id"),
                        details.getTag("springExperimentId"),
                        details.getTag("mysql_experiment_id"),
                        details.getTag("experiment_db_id")
                ))) {
                    System.out.println("[MLFLOW SYNC] Registry match by spring_experiment_id="
                            + springId
                            + " model="
                            + registeredModelName
                            + " version="
                            + versionDto.getVersion()
                            + " runId="
                            + details.getRunId());
                    return details;
                }
            }

            if (experiment.getKubeflowRunId() != null
                    && experiment.getKubeflowRunId().equalsIgnoreCase(details.getTag("kubeflow_run_id"))) {
                return details;
            }

            if (experiment.getTrainingJobName() != null) {
                String trainingJobTag = firstNotBlank(
                        details.getTag("training_job_name"),
                        details.getTag("trainingJobName"),
                        details.getTag("kubernetes_job_name")
                );

                if (experiment.getTrainingJobName().equalsIgnoreCase(trainingJobTag)) {
                    return details;
                }
            }
        }

        /*
         * 2) Fallback contrôlé pour STANDARD_RUNTIME.
         *
         * Cas observé :
         * - Kubeflow est SUCCEEDED.
         * - MLflow a bien créé dep2_model v1.
         * - Mais le script standard n'a pas loggé spring_experiment_id/kubeflow_run_id.
         *
         * On accepte alors la dernière version du Registered Model si :
         * - la ligne MySQL attend encore MLflow,
         * - le run MLflow n'est pas déjà lié à une autre expérience,
         * - le run MLflow est FINISHED/COMPLETED,
         * - la version appartient bien au registered model attendu.
         */
        for (MlflowModelVersionDto versionDto : versions) {
            if (versionDto == null || versionDto.getRunId() == null || versionDto.getRunId().isBlank()) {
                continue;
            }

            MlflowRunDetails details = getRunDetailsFromRunId(versionDto.getRunId(), registeredModelName);

            if (canAttachMlflowRunToExperimentFromRegistry(
                    experiment,
                    details,
                    registeredModelName
            )) {
                System.out.println("[MLFLOW SYNC] Registry fallback matched run without identity tags."
                        + " experimentId=" + experiment.getId()
                        + ", model=" + registeredModelName
                        + ", version=" + versionDto.getVersion()
                        + ", runId=" + versionDto.getRunId());
                return details;
            }
        }
    }

    return null;
}

private boolean canAttachMlflowRunToExperimentFromRegistry(
        MlExperiment experiment,
        MlflowRunDetails details,
        String expectedRegisteredModelName
) {
    if (experiment == null || details == null) {
        return false;
    }

    String runId = details.getRunId();

    if (runId == null || runId.isBlank()) {
        return false;
    }

    if (expectedRegisteredModelName == null || expectedRegisteredModelName.isBlank()) {
        return false;
    }

    /*
     * Règle définitive :
     * le fallback sans tags d'identité est autorisé uniquement pour STANDARD_RUNTIME.
     * Pour CUSTOM_DOCKERFILE, le rattachement doit être strict et passer par
     * matchesExperimentIdentity(). Cela empêche plusieurs lignes MySQL de recevoir
     * la même ModelVersion MLflow.
     */
    String runtimeMode = experiment.getRuntimeMode();

    boolean standardRuntime =
            runtimeMode == null
                    || runtimeMode.isBlank()
                    || runtimeMode.equalsIgnoreCase("STANDARD_RUNTIME");

    if (!standardRuntime) {
        return false;
    }

    /* Ne jamais utiliser un run déjà lié à une autre expérience MySQL. */
    Optional<MlExperiment> alreadyLinked =
            mlExperimentRepository.findByMlflowRunId(runId.trim());

    if (alreadyLinked.isPresent()) {
        MlExperiment linked = alreadyLinked.get();

        if (linked.getId() != null
                && experiment.getId() != null
                && !linked.getId().equals(experiment.getId())) {
            return false;
        }
    }

    /* Ce fallback ne s'applique qu'aux lignes qui attendent encore MLflow. */
    if (experiment.getMlflowRunId() != null
            && !experiment.getMlflowRunId().isBlank()) {
        return false;
    }

    String kubeflowStatus = experiment.getKubeflowStatus();

    boolean kubeflowSucceeded =
            kubeflowStatus == null
                    || kubeflowStatus.isBlank()
                    || kubeflowStatus.toUpperCase(Locale.ROOT).contains("SUCCEEDED")
                    || kubeflowStatus.toUpperCase(Locale.ROOT).contains("SUCCESS")
                    || kubeflowStatus.toUpperCase(Locale.ROOT).contains("COMPLETED");

    if (!kubeflowSucceeded) {
        return false;
    }

    String mlflowStatus = details.getStatus();

    boolean mlflowFinished =
            mlflowStatus != null
                    && (mlflowStatus.equalsIgnoreCase("FINISHED")
                    || mlflowStatus.equalsIgnoreCase("COMPLETED")
                    || mlflowStatus.equalsIgnoreCase("SUCCEEDED"));

    if (!mlflowFinished) {
        return false;
    }

    String runRegisteredModel = firstNotBlank(
            details.getRegisteredModelName(),
            details.getTag("registered_model_name"),
            details.getTag("registeredModelName")
    );

    if (runRegisteredModel == null || runRegisteredModel.isBlank()) {
        return false;
    }

    boolean sameRegistry = buildRegisteredModelNameCandidates(expectedRegisteredModelName)
            .stream()
            .anyMatch(name -> name != null
                    && name.equalsIgnoreCase(runRegisteredModel.trim()));

    if (!sameRegistry) {
        return false;
    }

    boolean hasModelVersion =
            details.getModelVersion() != null
                    && !details.getModelVersion().isBlank();

    boolean hasMetrics =
            details.getMetrics() != null
                    && !details.getMetrics().isEmpty();

    return hasModelVersion || hasMetrics;
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

    public List<MlflowRunDetails> findAllRunsByExperimentName(
            String experimentName,
            String preferredRegisteredModelName
    ) {
        List<MlflowRunDetails> result = new ArrayList<>();

        try {
            String experimentId = findExperimentIdByName(experimentName);

            if (experimentId == null || experimentId.isBlank()) {
                System.out.println("No MLflow experiment found: " + experimentName);
                return result;
            }

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/runs/search";

            String pageToken = null;

            do {
                StringBuilder requestBody = new StringBuilder();

                requestBody.append("{")
                        .append("\"experiment_ids\":[\"").append(jsonEscape(experimentId)).append("\"],")
                        .append("\"max_results\":1000,")
                        .append("\"order_by\":[\"attributes.start_time DESC\"]");

                if (pageToken != null && !pageToken.isBlank()) {
                    requestBody.append(",\"page_token\":\"")
                            .append(jsonEscape(pageToken))
                            .append("\"");
                }

                requestBody.append("}");

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));

                HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        entity,
                        String.class
                );

                if (!response.getStatusCode().is2xxSuccessful()) {
                    System.out.println("MLflow runs/search failed: " + response.getStatusCode());
                    return result;
                }

                String body = response.getBody();

                if (body == null || body.isBlank()) {
                    return result;
                }

                JsonNode root = objectMapper.readTree(body);
                JsonNode runs = root.path("runs");

                if (runs.isArray()) {
                    for (JsonNode runNode : runs) {
                        MlflowRunDetails details = buildRunDetailsFromRunNode(
                                runNode,
                                experimentId,
                                preferredRegisteredModelName
                        );

                        if (details == null || details.getRunId() == null || details.getRunId().isBlank()) {
                            continue;
                        }

                        applyModelVersionFallbacks(details, details.getRegisteredModelName());

if (!isRealPlatformRun(details)) {
    System.out.println("[MLFLOW SYNC] Ignored non-platform MLflow run: "
            + details.getRunName()
            + " / "
            + details.getRunId());
    continue;
}

result.add(details);
                    }
                }

                pageToken = root.path("next_page_token").asText(null);

                if (pageToken != null && pageToken.isBlank()) {
                    pageToken = null;
                }

            } while (pageToken != null);

            return result;

        } catch (Exception e) {
            System.out.println("Erreur findAllRunsByExperimentName : " + e.getMessage());
            e.printStackTrace();
            return result;
        }
    }


    public boolean isSuccessfulMlflowRun(MlflowRunDetails details) {
        if (details == null) {
            return false;
        }

        String status = details.getStatus();
        boolean mlflowFinished =
                status != null && status.equalsIgnoreCase("FINISHED");

        if (!mlflowFinished) {
            return false;
        }

        Map<String, Double> metrics = details.getMetrics();
        Map<String, String> tags = details.getTags();

        /*
         * return_code is optional for STANDARD_RUNTIME.
         * When present, a non-zero value is a hard failure.
         */
        Double numericReturnCode =
                metrics != null ? metrics.get("return_code") : null;

        if (numericReturnCode != null
                && numericReturnCode.doubleValue() != 0.0) {
            return false;
        }

        String returnCodeTag = tags == null
                ? null
                : firstNotBlank(
                        tags.get("return_code"),
                        tags.get("mlops.return_code")
                );

        if (returnCodeTag != null) {
            try {
                if (Double.parseDouble(returnCodeTag.trim()) != 0.0) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                // Unknown textual value: do not reject an otherwise FINISHED run.
            }
        }

        boolean hasUsefulMetric =
                metrics != null
                        && metrics.entrySet().stream()
                        .anyMatch(entry ->
                                entry.getKey() != null
                                        && entry.getValue() != null
                                        && !entry.getKey().equalsIgnoreCase("return_code")
                        );

        boolean hasModelEvidence =
                (details.getModelVersion() != null
                        && !details.getModelVersion().isBlank())
                        || (details.getRegisteredModelName() != null
                        && !details.getRegisteredModelName().isBlank())
                        || (tags != null
                        && firstNotBlank(
                                tags.get("main_model_file"),
                                tags.get("model_file"),
                                tags.get("mlflow_model_uri"),
                                tags.get("model_version"),
                                tags.get("registered_model_name")
                        ) != null);

        return hasUsefulMetric && hasModelEvidence;
    }

    public MlflowRunDetails findLatestRunByTag(
            String tagKey,
            String tagValue,
            String preferredRegisteredModelName
    ) {
        try {
            if (tagKey == null || tagKey.isBlank()) {
                return null;
            }

            if (tagValue == null || tagValue.isBlank()) {
                return null;
            }

            String filter = "tags.`" + tagKey.trim() + "` = '" + escapeMlflowFilterValue(tagValue.trim()) + "'";

            String requestBody = "{"
                    + "\"filter\":\"" + jsonEscape(filter) + "\","
                    + "\"max_results\":1,"
                    + "\"order_by\":[\"attributes.start_time DESC\"]"
                    + "}";

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/runs/search";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            String body = response.getBody();

            System.out.println("===== MLflow search by tag =====");
            System.out.println("tagKey = " + tagKey);
            System.out.println("tagValue = " + tagValue);
            System.out.println("filter = " + filter);
            System.out.println("status = " + response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }

            if (body == null || body.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode runs = root.path("runs");

            if (!runs.isArray() || runs.isEmpty()) {
                return null;
            }

            JsonNode firstRun = runs.get(0);
            String experimentId = firstRun.path("info").path("experiment_id").asText(null);

            MlflowRunDetails details = buildRunDetailsFromRunNode(
                    firstRun,
                    experimentId,
                    preferredRegisteredModelName
            );

            applyModelVersionFallbacks(details, preferredRegisteredModelName);

            return details;

        } catch (Exception e) {
            System.out.println("Erreur findLatestRunByTag : " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public MlflowRunDetails getRunDetailsFromRunId(String runId, String preferredRegisteredModelName) {
        try {
            JsonNode runDetails = getRunDetails(runId);

            if (runDetails == null) {
                return null;
            }

            JsonNode run = runDetails.path("run");

            if (run.isMissingNode() || run.isNull()) {
                return null;
            }

            String experimentId = run.path("info").path("experiment_id").asText(null);

            MlflowRunDetails details = buildRunDetailsFromRunNode(
                    run,
                    experimentId,
                    preferredRegisteredModelName
            );

            applyModelVersionFallbacks(details, preferredRegisteredModelName);

            return details;

        } catch (Exception e) {
            System.out.println("Erreur getRunDetailsFromRunId : " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private MlflowRunDetails searchLatestRunByRunName(String experimentId, String runName) {
        try {
            String filter = "tags.`mlflow.runName` = '" + escapeMlflowFilterValue(runName) + "'";

            String requestBody = "{"
                    + "\"experiment_ids\":[\"" + jsonEscape(experimentId) + "\"],"
                    + "\"filter\":\"" + jsonEscape(filter) + "\","
                    + "\"max_results\":1,"
                    + "\"order_by\":[\"attributes.start_time DESC\"]"
                    + "}";

            return executeRunSearch(experimentId, requestBody);

        } catch (Exception e) {
            System.out.println("Erreur searchLatestRunByRunName : " + e.getMessage());
            return null;
        }
    }

    private MlflowRunDetails searchLatestRun(String experimentId) {
        try {
            String requestBody = "{"
                    + "\"experiment_ids\":[\"" + jsonEscape(experimentId) + "\"],"
                    + "\"max_results\":1,"
                    + "\"order_by\":[\"attributes.start_time DESC\"]"
                    + "}";

            return executeRunSearch(experimentId, requestBody);

        } catch (Exception e) {
            System.out.println("Erreur searchLatestRun : " + e.getMessage());
            return null;
        }
    }

    private MlflowRunDetails executeRunSearch(String experimentId, String requestBody) throws Exception {
        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/runs/search";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            System.out.println("Erreur MLflow runs/search : " + response.getStatusCode());
            return null;
        }

        String body = response.getBody();

        if (body == null || body.isBlank()) {
            return null;
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode runs = root.path("runs");

        if (!runs.isArray() || runs.isEmpty()) {
            return null;
        }

        JsonNode firstRun = runs.get(0);

        MlflowRunDetails details = buildRunDetailsFromRunNode(firstRun, experimentId, null);
        applyModelVersionFallbacks(details, details != null ? details.getRegisteredModelName() : null);

        return details;
    }

    private MlflowRunDetails buildRunDetailsFromRunNode(
            JsonNode runNode,
            String fallbackExperimentId,
            String preferredRegisteredModelName
    ) {
        if (runNode == null || runNode.isNull() || runNode.isMissingNode()) {
            return null;
        }

        String runId = runNode.path("info").path("run_id").asText(null);

        if (runId == null || runId.isBlank()) {
            return null;
        }

        String experimentId = runNode.path("info").path("experiment_id").asText(fallbackExperimentId);
        String status = runNode.path("info").path("status").asText(null);
        String runName = extractRunName(runNode);
        Map<String, Double> metrics = extractMetricsFromRunNode(runNode);
        Map<String, String> tags = extractTagsFromRunNode(runNode);

        String registeredModelName = firstNotBlank(
                tags.get("registered_model_name"),
                tags.get("registeredModelName"),
                preferredRegisteredModelName
        );

        String modelVersion = firstNotBlank(
                tags.get("model_version"),
                tags.get("registered_model_version"),
                tags.get("version")
        );

        MlflowRunDetails details = new MlflowRunDetails(
                runId,
                experimentId,
                runName,
                status,
                modelVersion,
                registeredModelName,
                metrics
        );

        details.setTags(tags);

        return details;
    }

    private void applyModelVersionFallbacks(MlflowRunDetails details, String preferredRegisteredModelName) {
        if (details == null || details.getRunId() == null || details.getRunId().isBlank()) {
            return;
        }

        String candidateModelName = firstNotBlank(
                details.getRegisteredModelName(),
                preferredRegisteredModelName,
                details.getTag("registered_model_name"),
                details.getTag("registeredModelName"),
                details.getTag("platform_registered_model_name")
        );

        /*
         * Source de vérité versioning :
         * la version d'un run vient du MLflow Model Registry en cherchant
         * une ModelVersion dont run_id == run_id du run.
         *
         * On ne doit pas utiliser l'alias production comme version du run.
         * On évite aussi de croire des tags model_version posés par un script utilisateur.
         */
        ModelVersionInfo info = findModelVersionInfoByRunId(candidateModelName, details.getRunId());

        if (info != null) {
            if (info.getRegisteredModelName() != null && !info.getRegisteredModelName().isBlank()) {
                details.setRegisteredModelName(info.getRegisteredModelName());
            }

            if (info.getVersion() != null && !info.getVersion().isBlank()) {
                details.setModelVersion(info.getVersion());
                return;
            }
        }

        /*
         * Fallback uniquement pour les runs créés par la plateforme.
         * Les scripts utilisateurs peuvent écrire n'importe quel tag "model_version".
         * Ces tags ne doivent pas polluer la base.
         */
        boolean platformManaged =
                "true".equalsIgnoreCase(firstNotBlank(details.getTag("mlops.managed"), details.getTag("platform.managed")))
                        || details.getTag("platform_runner") != null
                        || details.getTag("spring_experiment_id") != null
                        || details.getTag("training_job_name") != null;

        if (platformManaged) {
            String tagModelVersion = firstNotBlank(
                    details.getTag("model_version"),
                    details.getTag("registered_model_version"),
                    details.getTag("platform_model_version")
            );

            if (tagModelVersion != null && !tagModelVersion.isBlank()) {
                details.setModelVersion(tagModelVersion.trim());
            }
        }
    }

    public String findExperimentIdByName(String experimentName) {
        if (experimentName == null || experimentName.isBlank()) {
            return null;
        }

        String cleanName = experimentName.trim();

        try {
            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/experiments/get-by-name?experiment_name="
                    + URLEncoder.encode(cleanName, StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<Void>(headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            String experimentId = root.path("experiment").path("experiment_id").asText(null);
            return experimentId == null || experimentId.isBlank() ? null : experimentId;

        } catch (HttpClientErrorException.NotFound e) {
            System.out.println("[MLFLOW] Experiment not found yet: " + cleanName);
            return null;
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            if (e.getStatusCode() == HttpStatus.NOT_FOUND
                    || (responseBody != null && responseBody.contains("RESOURCE_DOES_NOT_EXIST"))) {
                System.out.println("[MLFLOW] Experiment not found yet: " + cleanName);
                return null;
            }
            System.err.println("[MLFLOW] findExperimentIdByName failed. name=" + cleanName
                    + ", status=" + e.getStatusCode() + ", response=" + responseBody);
            return null;
        } catch (Exception e) {
            System.err.println("[MLFLOW] findExperimentIdByName failed. name=" + cleanName
                    + ", reason=" + e.getMessage());
            return null;
        }
    }

    public String createExperiment(String experimentName) {
        if (experimentName == null || experimentName.isBlank()) {
            throw new IllegalArgumentException("MLflow experiment name is required.");
        }

        String cleanName = experimentName.trim();
        String existingId = findExperimentIdByName(cleanName);
        if (existingId != null && !existingId.isBlank()) {
            return existingId;
        }

        try {
            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/experiments/create";

            String requestBody = "{\"name\":\"" + jsonEscape(cleanName) + "\"}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            String body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new IllegalStateException("MLflow did not return an experiment id.");
            }

            String experimentId = objectMapper.readTree(body).path("experiment_id").asText(null);
            if (experimentId == null || experimentId.isBlank()) {
                throw new IllegalStateException("MLflow experiment id is missing.");
            }

            System.out.println("[MLFLOW] Experiment created. name=" + cleanName + ", id=" + experimentId);
            return experimentId;

        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && responseBody.contains("RESOURCE_ALREADY_EXISTS")) {
                String existing = findExperimentIdByName(cleanName);
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
            }
            throw new IllegalStateException("Unable to create MLflow experiment " + cleanName
                    + ". Response: " + responseBody, e);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create MLflow experiment " + cleanName, e);
        }
    }

    public String getOrCreateExperimentId(String experimentName) {
        String existingId = findExperimentIdByName(experimentName);
        return existingId != null && !existingId.isBlank()
                ? existingId
                : createExperiment(experimentName);
    }

    public boolean deleteExperimentById(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            return true;
        }

        try {
            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/experiments/delete";
            String requestBody = "{\"experiment_id\":\"" + jsonEscape(experimentId.trim()) + "\"}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            if (e.getStatusCode() == HttpStatus.NOT_FOUND
                    || (body != null && body.contains("RESOURCE_DOES_NOT_EXIST"))) {
                return true;
            }
            System.err.println("[MLFLOW] deleteExperimentById failed. id=" + experimentId
                    + ", status=" + e.getStatusCode() + ", response=" + body);
            return false;
        } catch (Exception e) {
            System.err.println("[MLFLOW] deleteExperimentById failed. id=" + experimentId
                    + ", reason=" + e.getMessage());
            return false;
        }
    }

    public JsonNode getRunDetails(String runId) {
        try {
            if (runId == null || runId.isBlank()) {
                return null;
            }

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/runs/get?run_id="
                    + URLEncoder.encode(runId.trim(), StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                System.out.println("MLflow getRunDetails failed status = " + response.getStatusCode());
                return null;
            }

            String body = response.getBody();

            if (body == null || body.isBlank()) {
                return null;
            }

            return objectMapper.readTree(body);

        } catch (Exception e) {
            System.out.println("Erreur getRunDetails : " + e.getMessage());
            return null;
        }
    }

    public String getRunStatus(String runId) {
        try {
            JsonNode runDetails = getRunDetails(runId);

            if (runDetails == null) {
                return null;
            }

            return runDetails.path("run").path("info").path("status").asText(null);

        } catch (Exception e) {
            System.out.println("Erreur getRunStatus : " + e.getMessage());
            return null;
        }
    }

    public String findModelVersionByRunId(String registeredModelName, String runId) {
        ModelVersionInfo info = findModelVersionInfoByRunId(registeredModelName, runId);
        return info == null ? null : info.getVersion();
    }

    public ModelVersionInfo findModelVersionInfoByRunId(
        String preferredRegisteredModelName,
        String runId
) {
    try {
        if (runId == null || runId.isBlank()) {
            return null;
        }

        List<String> candidateNames = buildRegisteredModelNameCandidates(preferredRegisteredModelName);

        for (String candidateName : candidateNames) {
            ModelVersionInfo info = findModelVersionInfoByRegisteredModelGet(candidateName, runId);

            if (info != null && info.getVersion() != null && !info.getVersion().isBlank()) {
                return info;
            }
        }

        return null;

    } catch (Exception e) {
        System.out.println("Erreur findModelVersionInfoByRunId : " + e.getMessage());
        return null;
    }
}

    private ModelVersionInfo findModelVersionInfoByNameAndRunId(
        String registeredModelName,
        String runId
) {
    return findModelVersionInfoByRegisteredModelGet(registeredModelName, runId);
}

private ModelVersionInfo findModelVersionInfoByRegisteredModelGet(
        String registeredModelName,
        String runId
) {
    try {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return null;
        }

        if (runId == null || runId.isBlank()) {
            return null;
        }

        List<MlflowModelVersionDto> versions = getVersionsFromRegisteredModel(registeredModelName.trim());

        if (versions == null || versions.isEmpty()) {
            return null;
        }

        for (MlflowModelVersionDto version : versions) {
            if (version == null || version.getRunId() == null) {
                continue;
            }

            if (version.getRunId().trim().equalsIgnoreCase(runId.trim())) {
                return new ModelVersionInfo(
                        version.getRegisteredModelName(),
                        version.getVersion(),
                        version.getRunId(),
                        version.getStatus()
                );
            }
        }

        return null;

    } catch (Exception e) {
        System.out.println("Erreur findModelVersionInfoByRegisteredModelGet : " + e.getMessage());
        return null;
    }
}
    private List<String> buildRegisteredModelNameCandidates(String registeredModelName) {
    List<String> candidates = new ArrayList<>();

    if (registeredModelName == null || registeredModelName.isBlank()) {
        return candidates;
    }

    String cleanName = registeredModelName.trim();

    /*
     * Règle stricte :
     * - Si on reçoit bofh_model, on garde bofh_model uniquement.
     * - On ne doit PAS essayer bofh, car bofh est le nom du projet, pas le registry.
     */
    addCandidate(candidates, cleanName);

    /*
     * Cas utile :
     * Si on reçoit bofh par erreur, on essaie aussi bofh_model.
     */
    if (!cleanName.endsWith("_model")) {
        addCandidate(candidates, cleanName + "_model");
    }

    return candidates;
}

    private void addCandidate(List<String> candidates, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        String cleanValue = value.trim();

        for (String existing : candidates) {
            if (existing.equalsIgnoreCase(cleanValue)) {
                return;
            }
        }

        candidates.add(cleanValue);
    }

    public List<MlflowModelVersionDto> getAllModelVersionsWithMetrics(String registeredModelName) {
    try {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return new ArrayList<>();
        }

        List<MlflowModelVersionDto> result = new ArrayList<>();

        List<String> candidateNames = buildRegisteredModelNameCandidates(registeredModelName);

        for (String candidateName : candidateNames) {
            result = getVersionsFromRegisteredModel(candidateName);

            if (result != null && !result.isEmpty()) {
                registeredModelName = candidateName;
                break;
            }
        }

        if (result == null || result.isEmpty()) {
            return new ArrayList<>();
        }

        String productionVersion = getProductionVersion(registeredModelName);

        for (MlflowModelVersionDto dto : result) {
            if (dto == null) {
                continue;
            }

            dto.setProduction(
                    dto.getVersion() != null
                            && productionVersion != null
                            && dto.getVersion().equals(productionVersion)
            );

            if (dto.getRunId() != null && !dto.getRunId().isBlank()) {
                dto.setMetrics(getMetricsByRunId(dto.getRunId()));
            }
        }

        result.sort((a, b) -> {
            try {
                int va = Integer.parseInt(a.getVersion());
                int vb = Integer.parseInt(b.getVersion());
                return Integer.compare(vb, va);
            } catch (Exception e) {
                return 0;
            }
        });

        return result;

    } catch (Exception e) {
        System.out.println("Erreur getAllModelVersionsWithMetrics : " + e.getMessage());
        return new ArrayList<>();
    }
}
    @Transactional
    public List<MlflowModelVersionDto> syncModelVersionsToDatabase(String registeredModelName) {
        List<MlflowModelVersionDto> versions = getAllModelVersionsWithMetrics(registeredModelName);

        if (versions == null || versions.isEmpty()) {
            return new ArrayList<>();
        }

        for (MlflowModelVersionDto versionDto : versions) {
            try {
                syncSingleModelVersionToDatabase(versionDto);
            } catch (Exception e) {
                System.out.println("Cannot sync version to DB: " + e.getMessage());
            }
        }

        return versions;
    }


    /**
     * Une version ne doit être attachée à la plateforme que si :
     * - le run MLflow est réellement terminé avec succès ;
     * - l'expérience MySQL associée est COMPLETED ;
     * - le run contient un artefact modèle et des métriques utiles.
     *
     * Cette règle empêche les expériences FAILED/CANCELLED/RUNNING de recevoir
     * un numéro de version dans l'interface et dans la base.
     */
    private boolean isVersionEligibleForPlatform(
            MlExperiment experiment,
            MlflowRunDetails runDetails
    ) {
        if (experiment == null || runDetails == null) {
            return false;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            return false;
        }

        if (runDetails.getRunId() == null || runDetails.getRunId().isBlank()) {
            return false;
        }

        if (!isSuccessfulMlflowRun(runDetails)) {
            return false;
        }

        return true;
    }

    private boolean isVersionEligibleForPlatform(
            MlExperiment experiment,
            String runId
    ) {
        if (experiment == null || runId == null || runId.isBlank()) {
            return false;
        }

        MlflowRunDetails details = getRunDetailsFromRunId(
                runId.trim(),
                experiment.getRegisteredModelName()
        );

        return isVersionEligibleForPlatform(experiment, details);
    }

    /**
     * Nettoie tout état de version sur une expérience non réussie.
     * On ne supprime pas la version MLflow technique déjà créée, mais on évite
     * qu'elle soit comptée, affichée ou promue par la plateforme.
     */
    private void clearVersionStateForNonCompletedExperiment(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return;
        }

        experiment.setModelVersion(null);
        experiment.setLatestVersion(null);
        experiment.setProductionVersion(null);
        experiment.setProduction(false);
    }

    private boolean isTerminalFailure(MlExperiment experiment) {
        if (experiment == null || experiment.getStatus() == null) {
            return false;
        }

        return experiment.getStatus() == ExperimentStatus.FAILED
                || experiment.getStatus() == ExperimentStatus.CANCELLED;
    }

    private void clearVersionStateOnlyForTerminalFailure(MlExperiment experiment) {
        if (isTerminalFailure(experiment)) {
            clearVersionStateForNonCompletedExperiment(experiment);
        } else if (experiment != null) {
            experiment.setProduction(false);
            experiment.setProductionVersion(null);
        }
    }

    private void syncSingleModelVersionToDatabase(
            MlflowModelVersionDto versionDto
    ) {
        if (versionDto == null
                || versionDto.getRunId() == null
                || versionDto.getRunId().isBlank()) {
            return;
        }

        Optional<MlExperiment> optionalExperiment =
                mlExperimentRepository.findByMlflowRunId(
                        versionDto.getRunId().trim()
                );

        if (optionalExperiment.isEmpty()) {
            return;
        }

        MlExperiment experiment = optionalExperiment.get();

        MlflowRunDetails runDetails = getRunDetailsFromRunId(
                versionDto.getRunId(),
                versionDto.getRegisteredModelName()
        );

        /*
         * Règle centrale :
         * une expérience FAILED/CANCELLED/RUNNING ne reçoit jamais
         * modelVersion, productionVersion ou production=true.
         */
        if (!isVersionEligibleForPlatform(experiment, runDetails)) {
            clearVersionStateForNonCompletedExperiment(experiment);

            experiment.setMessage(
                    "Version MLflow ignorée : seuls les runs COMPLETED "
                            + "et réellement réussis sont comptabilisés."
            );

            mlExperimentRepository.saveAndFlush(experiment);

            System.out.println(
                    "[MLFLOW_REGISTRY_SYNC] Version ignored for non-successful experiment."
                            + " experimentId=" + experiment.getId()
                            + ", status=" + experiment.getStatus()
                            + ", runId=" + versionDto.getRunId()
                            + ", technicalMlflowVersion=" + versionDto.getVersion()
            );
            return;
        }

        experiment.setMlflowRunId(versionDto.getRunId().trim());

        if (versionDto.getRegisteredModelName() != null
                && !versionDto.getRegisteredModelName().isBlank()) {
            String cleanRegisteredModelName =
                    versionDto.getRegisteredModelName().trim();

            experiment.setRegisteredModelName(cleanRegisteredModelName);

            /*
             * registeredModelName = my_model
             * mlflowExperimentName = my
             */
            if (experiment.getMlflowExperimentName() == null
                    || experiment.getMlflowExperimentName().isBlank()
                    || experiment.getMlflowExperimentName().endsWith("_model")) {

                String cleanExperimentName = cleanRegisteredModelName;

                if (cleanExperimentName.endsWith("_model")) {
                    cleanExperimentName = cleanExperimentName.substring(
                            0,
                            cleanExperimentName.length() - "_model".length()
                    );
                }

                experiment.setMlflowExperimentName(cleanExperimentName);
            }
        }

        /*
         * La seule version acceptée est celle réellement retournée
         * par le Registry pour le run réussi.
         */
        if (versionDto.getVersion() != null
                && !versionDto.getVersion().isBlank()) {
            experiment.setModelVersion(versionDto.getVersion().trim());
        }

        String productionVersion =
                getProductionVersion(experiment.getRegisteredModelName());

        experiment.setProductionVersion(
                productionVersion == null || productionVersion.isBlank()
                        ? null
                        : productionVersion.trim()
        );

        experiment.setProduction(
                experiment.getProductionVersion() != null
                        && experiment.getModelVersion() != null
                        && experiment.getProductionVersion()
                        .equals(experiment.getModelVersion())
        );

        Map<String, Double> metrics =
                getMetricsByRunId(versionDto.getRunId());

        if (metrics != null && !metrics.isEmpty()) {
            if (experiment.getPrimaryMetric() != null
                    && !experiment.getPrimaryMetric().isBlank()) {
                Double primaryMetricValue = getMetricValueIgnoreCase(
                        metrics,
                        experiment.getPrimaryMetric()
                );

                if (primaryMetricValue != null) {
                    experiment.setPrimaryMetricValue(primaryMetricValue);
                }
            }

            if (experiment.getPrimaryMetricValue() == null) {
                updatePrimaryMetricFromAvailableMetrics(
                        experiment,
                        metrics
                );
            }

            if (experiment.getPrimaryMetric() != null
                    && experiment.getPrimaryMetricValue() != null) {
                experiment.setGlobalScore(
                        calculateSimpleGlobalScore(
                                experiment.getPrimaryMetric(),
                                experiment.getPrimaryMetricValue()
                        )
                );
            }
        }

        syncDetectedAndUsedAlgorithmsFromMlflowTags(
                experiment,
                versionDto.getRunId()
        );

        experiment.setMessage(
                "Synchronisation MLflow Registry terminée pour un run réussi."
        );
        experiment.setErrorMessage(null);

        MlExperiment savedExperiment =
                mlExperimentRepository.saveAndFlush(experiment);

        if (metrics != null && !metrics.isEmpty()) {
            saveExperimentMetrics(savedExperiment, metrics);
        }
    }

    public boolean deleteRegisteredModel(String registeredModelName) {
    try {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return false;
        }

        String cleanName = registeredModelName.trim();

        /*
         * 1. Supprimer toutes les versions du modèle.
         */
        List<MlflowModelVersionDto> versions = getAllModelVersionsWithMetrics(cleanName);

        if (versions != null) {
            for (MlflowModelVersionDto version : versions) {
                if (version == null || version.getVersion() == null || version.getVersion().isBlank()) {
                    continue;
                }

                try {
                    deleteModelVersion(cleanName, version.getVersion());
                } catch (Exception e) {
                    System.out.println("[MLFLOW DELETE] Cannot delete model version "
                            + cleanName + " v" + version.getVersion()
                            + " reason=" + e.getMessage());
                }
            }
        }

        /*
         * 2. Supprimer le Registered Model complet.
         */
        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/registered-models/delete";

        String requestBody = "{"
                + "\"name\":\"" + jsonEscape(cleanName) + "\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                entity,
                String.class
        );

        return response.getStatusCode().is2xxSuccessful();

    } catch (Exception e) {
        System.out.println("Erreur deleteRegisteredModel MLflow : " + e.getMessage());
        return false;
    }
}
public boolean deleteMlflowExperimentByName(String experimentName) {
    try {
        if (experimentName == null || experimentName.isBlank()) {
            return false;
        }

        String experimentId = findExperimentIdByName(experimentName);

        if (experimentId == null || experimentId.isBlank()) {
            return false;
        }

        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/experiments/delete";

        String requestBody = "{"
                + "\"experiment_id\":\"" + jsonEscape(experimentId) + "\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        return response.getStatusCode().is2xxSuccessful();

    } catch (Exception e) {
        System.out.println("Erreur deleteMlflowExperimentByName : " + e.getMessage());
        return false;
    }
}

    private void syncDetectedAndUsedAlgorithmsFromMlflowTags(MlExperiment experiment, String runId) {
        if (experiment == null || runId == null || runId.isBlank()) {
            return;
        }

        JsonNode runDetailsNode = getRunDetails(runId);

        if (runDetailsNode == null) {
            return;
        }

        JsonNode runNode = runDetailsNode.path("run");
        Map<String, String> tags = extractTagsFromRunNode(runNode);

        String detectedAlgorithms = firstNotBlank(
                tags.get("detected_algorithms"),
                tags.get("detectedAlgorithms"),
                tags.get("mlops.detected_algorithms"),
                tags.get("mlops.detectedAlgorithms")
        );

        String usedAlgorithms = firstNotBlank(
                tags.get("used_algorithms"),
                tags.get("usedAlgorithms"),
                tags.get("mlops.used_algorithms"),
                tags.get("mlops.usedAlgorithms")
        );

        if (detectedAlgorithms != null && !detectedAlgorithms.isBlank()) {
            experiment.setDetectedAlgorithms(detectedAlgorithms);
        }

        if (usedAlgorithms != null && !usedAlgorithms.isBlank()) {
            experiment.setUsedAlgorithms(usedAlgorithms);
        }
    }

    public void syncModelRegistryForExperiment(
            MlExperiment experiment
    ) {
        if (experiment == null) {
            return;
        }

        if (experiment.getStatus() != ExperimentStatus.COMPLETED) {
            clearVersionStateOnlyForTerminalFailure(experiment);
            mlExperimentRepository.saveAndFlush(experiment);

            System.out.println(
                    "[MLFLOW_REGISTRY_SYNC] Postponed until local status is COMPLETED."
                            + " experimentId=" + experiment.getId()
                            + ", status=" + experiment.getStatus()
            );
            return;
        }

        if (experiment.getMlflowRunId() == null
                || experiment.getMlflowRunId().isBlank()) {
            clearVersionStateOnlyForTerminalFailure(experiment);
            mlExperimentRepository.saveAndFlush(experiment);
            return;
        }

        MlflowRunDetails runDetails = getRunDetailsFromRunId(
                experiment.getMlflowRunId(),
                experiment.getRegisteredModelName()
        );

        if (!isVersionEligibleForPlatform(experiment, runDetails)) {
            clearVersionStateOnlyForTerminalFailure(experiment);
            mlExperimentRepository.saveAndFlush(experiment);

            System.out.println(
                    "[MLFLOW_REGISTRY_SYNC] Run is not eligible for versioning."
                            + " experimentId=" + experiment.getId()
                            + ", runId=" + experiment.getMlflowRunId()
            );
            return;
        }

        String registeredModelName = firstNotBlank(
                experiment.getRegisteredModelName(),
                runDetails.getRegisteredModelName()
        );

        if (registeredModelName == null
                || registeredModelName.isBlank()) {
            return;
        }

        registeredModelName = registeredModelName.trim();

        List<MlflowModelVersionDto> versions =
                getAllModelVersionsWithMetrics(registeredModelName);

        if (versions == null || versions.isEmpty()) {
            return;
        }

        String productionVersion =
                getProductionVersion(registeredModelName);

        String latestSuccessfulVersion = null;
        String versionForRun = null;

        for (MlflowModelVersionDto versionDto : versions) {
            if (versionDto == null
                    || versionDto.getVersion() == null
                    || versionDto.getVersion().isBlank()
                    || versionDto.getRunId() == null
                    || versionDto.getRunId().isBlank()) {
                continue;
            }

            MlflowRunDetails candidateRun = getRunDetailsFromRunId(
                    versionDto.getRunId(),
                    registeredModelName
            );

            /*
             * latestVersion côté plateforme = dernière version réussie,
             * pas simplement le plus grand numéro technique MLflow.
             */
            if (candidateRun != null
                    && isSuccessfulMlflowRun(candidateRun)
                    && (latestSuccessfulVersion == null
                    || compareVersionStrings(
                    versionDto.getVersion(),
                    latestSuccessfulVersion
            ) > 0)) {
                latestSuccessfulVersion = versionDto.getVersion();
            }

            if (experiment.getMlflowRunId()
                    .equals(versionDto.getRunId())
                    && isSuccessfulMlflowRun(candidateRun)) {
                versionForRun = versionDto.getVersion();
            }
        }

        if (versionForRun == null || versionForRun.isBlank()) {
            experiment.setProduction(false);
            experiment.setProductionVersion(null);
            mlExperimentRepository.saveAndFlush(experiment);
            System.out.println(
                    "[MLFLOW_REGISTRY_SYNC] Model version not visible yet; retry later."
                            + " experimentId=" + experiment.getId()
                            + ", runId=" + experiment.getMlflowRunId()
            );
            return;
        }

        experiment.setRegisteredModelName(registeredModelName);
        experiment.setModelVersion(versionForRun.trim());
        experiment.setLatestVersion(
                latestSuccessfulVersion == null
                        ? versionForRun.trim()
                        : latestSuccessfulVersion.trim()
        );

        experiment.setProductionVersion(
                productionVersion == null || productionVersion.isBlank()
                        ? null
                        : productionVersion.trim()
        );

        experiment.setProduction(
                experiment.getProductionVersion() != null
                        && experiment.getProductionVersion()
                        .equals(experiment.getModelVersion())
        );

        mlExperimentRepository.saveAndFlush(experiment);
    }

    private int compareVersionStrings(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    private List<MlflowModelVersionDto> searchModelVersionsByFilter(String registeredModelName) {
    /*
     * Source de vérité pour le versioning :
     * /registered-models/get ne retourne que latest_versions.
     * Cela peut cacher les anciennes versions et provoquer :
     * - plusieurs runs affichés avec la même version,
     * - plusieurs badges Production.
     *
     * On utilise donc /model-versions/search pour récupérer TOUTES les versions.
     */
    return searchAllModelVersions(registeredModelName);
}

private boolean isRealPlatformRun(MlflowRunDetails details) {
    if (details == null || details.getRunId() == null || details.getRunId().isBlank()) {
        return false;
    }

    Map<String, String> tags = details.getTags();

    String springExperimentId = tags != null ? firstNotBlank(
            tags.get("spring_experiment_id"),
            tags.get("springExperimentId"),
            tags.get("mysql_experiment_id"),
            tags.get("experiment_db_id")
    ) : null;

    String platformRunner = tags != null ? firstNotBlank(
            tags.get("platform_runner"),
            tags.get("runner"),
            tags.get("runtime_mode")
    ) : null;

    String registeredModelName = firstNotBlank(
            details.getRegisteredModelName(),
            tags != null ? tags.get("registered_model_name") : null,
            tags != null ? tags.get("registeredModelName") : null
    );

    String kubeflowRunId = tags != null ? firstNotBlank(
            tags.get("kubeflow_run_id"),
            tags.get("kubeflowRunId"),
            tags.get("kubeflow.run_id")
    ) : null;

    String kubeflowRunName = tags != null ? firstNotBlank(
            tags.get("kubeflow_run_name"),
            tags.get("kubeflowRunName"),
            tags.get("kubeflow.run_name")
    ) : null;

    String trainingJobName = tags != null ? firstNotBlank(
            tags.get("training_job_name"),
            tags.get("trainingJobName"),
            tags.get("kubernetes_job_name")
    ) : null;

    boolean hasIdentityTags =
            (springExperimentId != null && !springExperimentId.isBlank())
                    || (registeredModelName != null && !registeredModelName.isBlank()
                    && platformRunner != null && !platformRunner.isBlank())
                    || (kubeflowRunId != null && !kubeflowRunId.isBlank())
                    || (kubeflowRunName != null && !kubeflowRunName.isBlank())
                    || (trainingJobName != null && !trainingJobName.isBlank());

    if (hasIdentityTags) {
        return true;
    }

    /*
     * Fallback nécessaire pour STANDARD_RUNTIME :
     * certains scripts créent bien une Model Version dans MLflow Registry
     * mais ne loggent pas tous les tags de plateforme.
     */
    boolean hasRegistryModel =
            registeredModelName != null && !registeredModelName.isBlank();

    boolean hasModelVersion =
            details.getModelVersion() != null && !details.getModelVersion().isBlank();

    boolean hasMetrics =
            details.getMetrics() != null && !details.getMetrics().isEmpty();

    return hasRegistryModel && (hasModelVersion || hasMetrics);
}
    private List<MlflowModelVersionDto> getVersionsFromRegisteredModel(String registeredModelName) {
    /*
     * Très important :
     * cette méthode doit retourner toutes les versions du modèle, pas seulement
     * latest_versions. latest_versions n'est pas suffisant pour relier un run
     * à sa vraie version.
     */
    List<MlflowModelVersionDto> allVersions = searchAllModelVersions(registeredModelName);

    if (allVersions != null && !allVersions.isEmpty()) {
        return allVersions;
    }

    return getLatestVersionsFromRegisteredModel(registeredModelName);
}

private List<MlflowModelVersionDto> searchAllModelVersions(
        String registeredModelName
) {
    List<MlflowModelVersionDto> result = new ArrayList<>();

    if (registeredModelName == null || registeredModelName.isBlank()) {
        return result;
    }

    String cleanName = registeredModelName.trim();
    String filter = "name = '" + escapeMlflowFilterValue(cleanName) + "'";
    String pageToken = null;

    try {
        do {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromHttpUrl(cleanMlflowUri(mlflowTrackingUri))
                    .path("/api/2.0/mlflow/model-versions/search")
                    .queryParam("filter", filter)
                    .queryParam("max_results", 1000);

            if (pageToken != null && !pageToken.isBlank()) {
                uriBuilder.queryParam("page_token", pageToken);
            }

            URI uri = uriBuilder
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println(
                        "[MLFLOW] model-versions/search failed"
                                + ", model=" + cleanName
                                + ", status=" + response.getStatusCode()
                );
                return result;
            }

            String body = response.getBody();

            if (body == null || body.isBlank()) {
                return result;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode modelVersions = root.path("model_versions");

            if (modelVersions.isArray()) {
                for (JsonNode versionNode : modelVersions) {
                    MlflowModelVersionDto dto =
                            mapMlflowVersionNode(versionNode, cleanName);

                    if (dto != null) {
                        result.add(dto);
                    }
                }
            }

            pageToken = root.path("next_page_token").asText(null);

            if (pageToken != null && pageToken.isBlank()) {
                pageToken = null;
            }

        } while (pageToken != null);

        result.sort(
                (first, second) -> compareVersionStrings(
                        second.getVersion(),
                        first.getVersion()
                )
        );

        return result;

    } catch (Exception e) {
        String message = e.getMessage();

        if (message != null
                && message.contains("RESOURCE_DOES_NOT_EXIST")) {
            System.out.println(
                    "[MLFLOW] Registered model not found: " + cleanName
            );
        } else {
            System.err.println(
                    "[MLFLOW] searchAllModelVersions failed for "
                            + cleanName
                            + ": "
                            + message
            );
            e.printStackTrace();
        }

        return result;
    }
}

private List<MlflowModelVersionDto> getLatestVersionsFromRegisteredModel(String registeredModelName) {
    try {
        List<MlflowModelVersionDto> result = new ArrayList<>();

        if (registeredModelName == null || registeredModelName.isBlank()) {
            return result;
        }

        String cleanName = registeredModelName.trim();

        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/registered-models/get?name="
                + URLEncoder.encode(cleanName, StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class
        );

        String body = response.getBody();

        if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
            return result;
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode registeredModel = root.path("registered_model");
        JsonNode latestVersions = registeredModel.path("latest_versions");

        if (!latestVersions.isArray()) {
            return result;
        }

        for (JsonNode versionNode : latestVersions) {
            result.add(mapMlflowVersionNode(versionNode, cleanName));
        }

        return result;

    } catch (Exception e) {
        String message = e.getMessage();

        if (message != null && message.contains("RESOURCE_DOES_NOT_EXIST")) {
            System.out.println("[MLFLOW] Registered model not found: " + registeredModelName);
        } else {
            System.out.println("Erreur getLatestVersionsFromRegisteredModel : " + message);
        }

        return new ArrayList<>();
    }
}
    private MlflowModelVersionDto mapMlflowVersionNode(JsonNode versionNode, String defaultModelName) {
        MlflowModelVersionDto dto = new MlflowModelVersionDto();

        dto.setRegisteredModelName(versionNode.path("name").asText(defaultModelName));
        dto.setVersion(versionNode.path("version").asText(null));
        String source = versionNode.path("source").asText(null);
        String runId = versionNode.path("run_id").asText(null);
        if (runId == null || runId.isBlank()) {
            runId = extractRunIdFromModelSource(source);
        }
        dto.setRunId(runId);
        dto.setStatus(versionNode.path("status").asText(null));
        dto.setSource(source);
        dto.setCurrentStage(versionNode.path("current_stage").asText(null));
        dto.setProduction(false);

        return dto;
    }

    private String extractRunIdFromModelSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.replace("\\", "/").trim();
        int artifactsIndex = normalized.lastIndexOf("/artifacts/");
        if (artifactsIndex <= 0) {
            return null;
        }
        String beforeArtifacts = normalized.substring(0, artifactsIndex);
        int slashIndex = beforeArtifacts.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex >= beforeArtifacts.length() - 1) {
            return null;
        }
        String candidate = beforeArtifacts.substring(slashIndex + 1).trim();
        return candidate.isBlank() ? null : candidate;
    }

    public Map<String, Double> getMetricsByRunId(String runId) {
        try {
            Map<String, Double> metricsMap = new LinkedHashMap<>();

            JsonNode runDetails = getRunDetails(runId);

            if (runDetails == null) {
                return metricsMap;
            }

            JsonNode metrics = runDetails.path("run").path("data").path("metrics");

            if (!metrics.isArray()) {
                return metricsMap;
            }

            for (JsonNode metric : metrics) {
                String key = metric.path("key").asText(null);
                JsonNode valueNode = metric.path("value");

                if (key == null || key.isBlank()) {
                    continue;
                }

                if (valueNode.isNumber()) {
                    metricsMap.put(key, valueNode.asDouble());
                } else {
                    try {
                        metricsMap.put(key, Double.parseDouble(valueNode.asText()));
                    } catch (Exception ignored) {
                    }
                }
            }

            return metricsMap;

        } catch (Exception e) {
            System.out.println("Erreur getMetricsByRunId : " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveExperimentMetrics(MlExperiment experiment, Map<String, Double> metrics) {
        if (experiment == null || experiment.getId() == null || metrics == null || metrics.isEmpty()) {
            return;
        }

        mlExperimentMetricRepository.deleteByExperimentId(experiment.getId());
        mlExperimentMetricRepository.flush();

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }

            MlExperimentMetric metric = new MlExperimentMetric();
            metric.setExperiment(experiment);
            metric.setMetricName(entry.getKey().trim());
            metric.setMetricValue(entry.getValue());
            metric.setDirection(inferMetricDirection(entry.getKey()));
            metric.setProductionVersion(experiment.getProductionVersion());
            metric.setProduction(Boolean.TRUE.equals(experiment.getProduction()));
            metric.setGlobalScore(experiment.getGlobalScore());

            mlExperimentMetricRepository.save(metric);
        }

        mlExperimentMetricRepository.flush();
    }

    private void updatePrimaryMetricFromAvailableMetrics(MlExperiment experiment, Map<String, Double> metrics) {
        if (experiment == null || metrics == null || metrics.isEmpty()) {
            return;
        }

        List<String> priority = List.of(
                "f1_score",
                "f1_weighted",
                "f1",
                "accuracy",
                "precision",
                "precision_weighted",
                "recall",
                "recall_weighted",
                "r2",
                "r2_score",
                "rmse",
                "mae",
                "mse"
        );

        for (String metricName : priority) {
            Double value = getMetricValueIgnoreCase(metrics, metricName);

            if (value != null) {
                experiment.setPrimaryMetric(metricName);
                experiment.setDirection(inferMetricDirection(metricName));
                experiment.setPrimaryMetricValue(value);
                return;
            }
        }

        Map.Entry<String, Double> firstMetric = metrics.entrySet().iterator().next();
        experiment.setPrimaryMetric(firstMetric.getKey());
        experiment.setDirection(inferMetricDirection(firstMetric.getKey()));
        experiment.setPrimaryMetricValue(firstMetric.getValue());
    }

    private Double getMetricValueIgnoreCase(Map<String, Double> metrics, String metricName) {
        if (metrics == null || metrics.isEmpty() || metricName == null || metricName.isBlank()) {
            return null;
        }

        String normalizedMetricName = metricName.trim().toLowerCase();

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }

            String key = entry.getKey().trim().toLowerCase();

            if (key.equals(normalizedMetricName)
                    || key.contains(normalizedMetricName)
                    || normalizedMetricName.contains(key)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private Double calculateSimpleGlobalScore(String metricName, Double metricValue) {
        if (metricName == null || metricName.isBlank() || metricValue == null) {
            return null;
        }

        String direction = inferMetricDirection(metricName);

        if ("min".equalsIgnoreCase(direction)) {
            return 1.0 / (1.0 + Math.max(metricValue, 0.0));
        }

        if (metricValue >= 0.0 && metricValue <= 1.0) {
            return metricValue;
        }

        if (metricValue > 1.0 && metricValue <= 100.0) {
            return metricValue / 100.0;
        }

        return metricValue > 0 ? 1.0 : 0.0;
    }

    private String inferMetricDirection(String metricName) {
        if (metricName == null) {
            return "max";
        }

        String lower = metricName.toLowerCase();

        if (lower.contains("rmse")
                || lower.contains("mse")
                || lower.contains("mae")
                || lower.contains("loss")
                || lower.contains("error")) {
            return "min";
        }

        return "max";
    }

    public String getProductionVersion(String registeredModelName) {
    try {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            return null;
        }

        List<String> candidateNames = buildRegisteredModelNameCandidates(registeredModelName);

        for (String candidateName : candidateNames) {
            String productionVersion = getProductionVersionForSingleName(candidateName);

            if (productionVersion != null && !productionVersion.isBlank()) {
                return productionVersion;
            }
        }

        return null;

    } catch (Exception e) {
        System.out.println("Erreur getProductionVersion : " + e.getMessage());
        return null;
    }
}

    private String getProductionVersionForSingleName(String registeredModelName) {
        try {
            if (registeredModelName == null || registeredModelName.isBlank()) {
                return null;
            }

            String cleanName = registeredModelName.trim();

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/registered-models/alias?name="
                    + URLEncoder.encode(cleanName, StandardCharsets.UTF_8)
                    + "&alias="
                    + URLEncoder.encode("production", StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                return getProductionVersionFromVersions(cleanName);
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode modelVersion = root.path("model_version");

            if (modelVersion.isMissingNode() || modelVersion.isNull()) {
                return getProductionVersionFromVersions(cleanName);
            }

            return modelVersion.path("version").asText(null);

        } catch (Exception e) {
            return getProductionVersionFromVersions(registeredModelName);
        }
    }

    private String getProductionVersionFromVersions(String registeredModelName) {
        try {
            List<MlflowModelVersionDto> versions = searchModelVersionsByFilter(registeredModelName);

            if (versions == null || versions.isEmpty()) {
                versions = getVersionsFromRegisteredModel(registeredModelName);
            }

            for (MlflowModelVersionDto version : versions) {
                if (version.getCurrentStage() != null
                        && "Production".equalsIgnoreCase(version.getCurrentStage())) {
                    return version.getVersion();
                }
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }


    /**
     * Les fonctions de comparaison/promotion ne voient que les versions
     * provenant de runs réellement réussis.
     */
    private boolean isSuccessfulRegisteredModelVersion(
            MlflowModelVersionDto versionDto
    ) {
        if (versionDto == null
                || versionDto.getRunId() == null
                || versionDto.getRunId().isBlank()) {
            return false;
        }

        MlflowRunDetails runDetails = getRunDetailsFromRunId(
                versionDto.getRunId(),
                versionDto.getRegisteredModelName()
        );

        return isSuccessfulMlflowRun(runDetails);
    }

    public MlflowModelVersionDto chooseBestVersion(
            String registeredModelName,
            List<MetricPriorityDto> priorities
    ) {
        List<MlflowModelVersionDto> versions =
                getAllModelVersionsWithMetrics(registeredModelName)
                        .stream()
                        .filter(this::isSuccessfulRegisteredModelVersion)
                        .toList();

        if (versions.isEmpty()) {
            return null;
        }

        if (priorities == null || priorities.isEmpty()) {
            return versions.get(0);
        }

        priorities.sort(Comparator.comparingInt(
                p -> p.getPriority() == null ? 999 : p.getPriority()
        ));

        MlflowModelVersionDto best = null;

        for (MlflowModelVersionDto candidate : versions) {
            if (best == null) {
                best = candidate;
                continue;
            }

            int comparison = compareVersionsByMetrics(candidate, best, priorities);

            if (comparison > 0) {
                best = candidate;
            }
        }

        return best;
    }

    private int compareVersionsByMetrics(
            MlflowModelVersionDto candidate,
            MlflowModelVersionDto currentBest,
            List<MetricPriorityDto> priorities
    ) {
        for (MetricPriorityDto priority : priorities) {
            String metricName = priority.getMetricName();
            String mode = priority.getMode();

            if (metricName == null || metricName.isBlank()) {
                continue;
            }

            Double candidateValue = getMetricValueIgnoreCase(candidate.getMetrics(), metricName);
            Double bestValue = getMetricValueIgnoreCase(currentBest.getMetrics(), metricName);

            if (candidateValue == null && bestValue == null) {
                continue;
            }

            if (candidateValue != null && bestValue == null) {
                return 1;
            }

            if (candidateValue == null) {
                return -1;
            }

            int cmp = Double.compare(candidateValue, bestValue);

            if (cmp == 0) {
                continue;
            }

            if ("MIN".equalsIgnoreCase(mode)) {
                return cmp < 0 ? 1 : -1;
            }

            return cmp > 0 ? 1 : -1;
        }

        try {
            int candidateVersion = Integer.parseInt(candidate.getVersion());
            int bestVersion = Integer.parseInt(currentBest.getVersion());
            return Integer.compare(candidateVersion, bestVersion);
        } catch (Exception e) {
            return 0;
        }
    }

    public MlflowModelVersionDto promoteBestVersionToProduction(
            String registeredModelName,
            List<MetricPriorityDto> priorities
    ) {
        try {
            MlflowModelVersionDto bestVersion = chooseBestVersion(registeredModelName, priorities);

            if (bestVersion == null || bestVersion.getVersion() == null) {
                return null;
            }

            setProductionAlias(registeredModelName, bestVersion.getVersion());

            List<MlflowModelVersionDto> versions = getAllModelVersionsWithMetrics(registeredModelName);

            for (MlflowModelVersionDto version : versions) {
                if (bestVersion.getVersion().equals(version.getVersion())) {
                    return version;
                }
            }

            bestVersion.setProduction(true);
            return bestVersion;

        } catch (Exception e) {
            System.out.println("Erreur promoteBestVersionToProduction : " + e.getMessage());
            return null;
        }
    }

    public void setProductionAlias(String registeredModelName, String version) {
        if (registeredModelName == null || registeredModelName.isBlank()) {
            throw new IllegalArgumentException("registeredModelName is required");
        }

        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }

        String cleanName = registeredModelName.trim();
        String cleanVersion = version.trim();
        String url = cleanMlflowUri(mlflowTrackingUri)
                + "/api/2.0/mlflow/registered-models/alias";

        String requestBody = "{"
                + "\"name\":\"" + jsonEscape(cleanName) + "\","
                + "\"alias\":\"production\","
                + "\"version\":\"" + jsonEscape(cleanVersion) + "\""
                + "}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "MLflow rejected production alias update: "
                            + response.getStatusCode()
            );
        }

        String verifiedVersion = getProductionVersion(cleanName);
        if (verifiedVersion == null
                || !cleanVersion.equals(verifiedVersion.trim())) {
            throw new IllegalStateException(
                    "MLflow production alias verification failed. expected="
                            + cleanVersion + ", actual=" + verifiedVersion
            );
        }

        System.out.println(
                "[MLFLOW_ALIAS] production verified. model="
                        + cleanName + ", version=" + cleanVersion
        );
    }

    public boolean deleteRun(String runId) {
        try {
            if (runId == null || runId.isBlank()) {
                return false;
            }

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/runs/delete";

            String requestBody = "{"
                    + "\"run_id\":\"" + jsonEscape(runId.trim()) + "\""
                    + "}";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            System.out.println("Erreur deleteRun MLflow : " + e.getMessage());
            return false;
        }
    }

    public boolean deleteModelVersion(String registeredModelName, String modelVersion) {
        try {
            if (registeredModelName == null || registeredModelName.isBlank()) {
                return false;
            }

            if (modelVersion == null || modelVersion.isBlank()) {
                return false;
            }

            String url = cleanMlflowUri(mlflowTrackingUri)
                    + "/api/2.0/mlflow/model-versions/delete";

            String requestBody = "{"
                    + "\"name\":\"" + jsonEscape(registeredModelName.trim()) + "\","
                    + "\"version\":\"" + jsonEscape(modelVersion.trim()) + "\""
                    + "}";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            System.out.println("Erreur deleteModelVersion MLflow : " + e.getMessage());
            return false;
        }
    }

    private String extractRunName(JsonNode runNode) {
        if (runNode == null) {
            return null;
        }

        JsonNode tags = runNode.path("data").path("tags");

        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = tag.path("key").asText("");

                if ("mlflow.runName".equals(key)) {
                    return tag.path("value").asText(null);
                }
            }
        }

        return null;
    }

    private Map<String, String> extractTagsFromRunNode(JsonNode runNode) {
        Map<String, String> tagsMap = new LinkedHashMap<>();

        if (runNode == null || runNode.isNull() || runNode.isMissingNode()) {
            return tagsMap;
        }

        JsonNode tags = runNode.path("data").path("tags");

        if (!tags.isArray()) {
            return tagsMap;
        }

        for (JsonNode tag : tags) {
            String key = tag.path("key").asText(null);
            String value = tag.path("value").asText(null);

            if (key != null && !key.isBlank()) {
                tagsMap.put(key, value);
            }
        }

        return tagsMap;
    }

    private Map<String, Double> extractMetricsFromRunNode(JsonNode runNode) {
        Map<String, Double> metricsMap = new LinkedHashMap<>();

        if (runNode == null || runNode.isNull() || runNode.isMissingNode()) {
            return metricsMap;
        }

        JsonNode metrics = runNode.path("data").path("metrics");

        if (!metrics.isArray()) {
            return metricsMap;
        }

        for (JsonNode metric : metrics) {
            String key = metric.path("key").asText(null);
            JsonNode valueNode = metric.path("value");

            if (key == null || key.isBlank()) {
                continue;
            }

            if (valueNode.isNumber()) {
                metricsMap.put(key, valueNode.asDouble());
            } else {
                try {
                    metricsMap.put(key, Double.parseDouble(valueNode.asText()));
                } catch (Exception ignored) {
                }
            }
        }

        return metricsMap;
    }

    private String cleanMlflowUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "http://mlflow-service.mlops.svc.cluster.local:5000";
        }

        if (uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }

        return uri;
    }

    private String escapeMlflowFilterValue(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("'", "\\'");
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}