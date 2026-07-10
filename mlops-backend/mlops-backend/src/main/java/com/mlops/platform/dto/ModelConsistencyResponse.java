package com.mlops.platform.dto;

import java.util.ArrayList;
import java.util.List;

public class ModelConsistencyResponse {
    private String registeredModelName;
    private String mlflowProductionVersion;
    private String databaseProductionVersion;
    private String activeDeploymentVersion;
    private String activeDockerImage;
    private boolean kubernetesReady;
    private boolean consistent;
    private List<String> problems = new ArrayList<>();

    public String getRegisteredModelName() { return registeredModelName; }
    public void setRegisteredModelName(String registeredModelName) { this.registeredModelName = registeredModelName; }
    public String getMlflowProductionVersion() { return mlflowProductionVersion; }
    public void setMlflowProductionVersion(String mlflowProductionVersion) { this.mlflowProductionVersion = mlflowProductionVersion; }
    public String getDatabaseProductionVersion() { return databaseProductionVersion; }
    public void setDatabaseProductionVersion(String databaseProductionVersion) { this.databaseProductionVersion = databaseProductionVersion; }
    public String getActiveDeploymentVersion() { return activeDeploymentVersion; }
    public void setActiveDeploymentVersion(String activeDeploymentVersion) { this.activeDeploymentVersion = activeDeploymentVersion; }
    public String getActiveDockerImage() { return activeDockerImage; }
    public void setActiveDockerImage(String activeDockerImage) { this.activeDockerImage = activeDockerImage; }
    public boolean isKubernetesReady() { return kubernetesReady; }
    public void setKubernetesReady(boolean kubernetesReady) { this.kubernetesReady = kubernetesReady; }
    public boolean isConsistent() { return consistent; }
    public void setConsistent(boolean consistent) { this.consistent = consistent; }
    public List<String> getProblems() { return problems; }
    public void setProblems(List<String> problems) { this.problems = problems != null ? problems : new ArrayList<>(); }
}
