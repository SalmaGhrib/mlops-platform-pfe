package com.mlops.platform.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * État complet du pipeline d'un run : liste ordonnée des étapes,
 * progression globale réelle, runtime et statut terminal.
 */
public class PipelineStatusResponse {

    private Long experimentId;
    private String runtimeMode;
    private String runStatus;
    private String kubeflowStatus;
    private String buildStatus;
    private Integer globalProgress;
    private String updatedAt;
    private List<RunPipelineStepDto> steps = new ArrayList<>();

    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }

    public String getRuntimeMode() { return runtimeMode; }
    public void setRuntimeMode(String runtimeMode) { this.runtimeMode = runtimeMode; }

    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }

    public String getKubeflowStatus() { return kubeflowStatus; }
    public void setKubeflowStatus(String kubeflowStatus) { this.kubeflowStatus = kubeflowStatus; }

    public String getBuildStatus() { return buildStatus; }
    public void setBuildStatus(String buildStatus) { this.buildStatus = buildStatus; }

    public Integer getGlobalProgress() { return globalProgress; }
    public void setGlobalProgress(Integer globalProgress) { this.globalProgress = globalProgress; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public List<RunPipelineStepDto> getSteps() { return steps; }
    public void setSteps(List<RunPipelineStepDto> steps) { this.steps = steps; }
}
