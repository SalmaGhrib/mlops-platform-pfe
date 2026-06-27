package com.mlops.platform.dto;

public class MonitoringSummaryResponse {

    private double totalDeployments;
    private double activeProductionModels;

    private double completedRuns;
    private double failedRuns;
    private double runningRuns;

    private double successfulDeployments;
    private double failedDeployments;
    private double deploymentsInProgress;

    private double averageRunDurationSeconds;
private double maxRunDurationSeconds;
private double lastRunDurationSeconds;


private double averageStandardRunDurationSeconds;
private double lastStandardRunDurationSeconds;
private double averageDockerRunDurationSeconds;
private double lastDockerRunDurationSeconds;

private long standardRunsCount;
private long dockerRunsCount;

    private double runSuccessRate;
    private double deploymentSuccessRate;
    private double averageBackendLatencyMs;
private double averageBackendLatencySeconds;

    private double unavailableModelDeployments;

    public MonitoringSummaryResponse() {
    }

    public double getTotalDeployments() {
        return totalDeployments;
    }

    public void setTotalDeployments(double totalDeployments) {
        this.totalDeployments = totalDeployments;
    }

    public double getActiveProductionModels() {
        return activeProductionModels;
    }

    public void setActiveProductionModels(double activeProductionModels) {
        this.activeProductionModels = activeProductionModels;
    }

    public double getCompletedRuns() {
        return completedRuns;
    }

    public void setCompletedRuns(double completedRuns) {
        this.completedRuns = completedRuns;
    }

    public double getFailedRuns() {
        return failedRuns;
    }

    public void setFailedRuns(double failedRuns) {
        this.failedRuns = failedRuns;
    }


public double getAverageRunDurationSeconds() {
    return averageRunDurationSeconds;
}

public void setAverageRunDurationSeconds(double averageRunDurationSeconds) {
    this.averageRunDurationSeconds = averageRunDurationSeconds;
}

public double getMaxRunDurationSeconds() {
    return maxRunDurationSeconds;
}

public void setMaxRunDurationSeconds(double maxRunDurationSeconds) {
    this.maxRunDurationSeconds = maxRunDurationSeconds;
}

public double getLastRunDurationSeconds() {
    return lastRunDurationSeconds;
}

public void setLastRunDurationSeconds(double lastRunDurationSeconds) {
    this.lastRunDurationSeconds = lastRunDurationSeconds;
}
    public double getRunningRuns() {
        return runningRuns;
    }

    public void setRunningRuns(double runningRuns) {
        this.runningRuns = runningRuns;
    }

    public double getSuccessfulDeployments() {
        return successfulDeployments;
    }

    public void setSuccessfulDeployments(double successfulDeployments) {
        this.successfulDeployments = successfulDeployments;
    }

    public double getFailedDeployments() {
        return failedDeployments;
    }

    public void setFailedDeployments(double failedDeployments) {
        this.failedDeployments = failedDeployments;
    }

    public double getDeploymentsInProgress() {
        return deploymentsInProgress;
    }

    public void setDeploymentsInProgress(double deploymentsInProgress) {
        this.deploymentsInProgress = deploymentsInProgress;
    }

    public double getRunSuccessRate() {
        return runSuccessRate;
    }

    public void setRunSuccessRate(double runSuccessRate) {
        this.runSuccessRate = runSuccessRate;
    }

    public double getDeploymentSuccessRate() {
        return deploymentSuccessRate;
    }

    public void setDeploymentSuccessRate(double deploymentSuccessRate) {
        this.deploymentSuccessRate = deploymentSuccessRate;
    }

    public double getUnavailableModelDeployments() {
        return unavailableModelDeployments;
    }

    public void setUnavailableModelDeployments(double unavailableModelDeployments) {
        this.unavailableModelDeployments = unavailableModelDeployments;
    }

    public double getAverageBackendLatencyMs() {
    return averageBackendLatencyMs;
}

public void setAverageBackendLatencyMs(double averageBackendLatencyMs) {
    this.averageBackendLatencyMs = averageBackendLatencyMs;
}

public double getAverageBackendLatencySeconds() {
    return averageBackendLatencySeconds;
}

public void setAverageBackendLatencySeconds(double averageBackendLatencySeconds) {
    this.averageBackendLatencySeconds = averageBackendLatencySeconds;
}


public double getAverageStandardRunDurationSeconds() {
    return averageStandardRunDurationSeconds;
}

public void setAverageStandardRunDurationSeconds(double averageStandardRunDurationSeconds) {
    this.averageStandardRunDurationSeconds = averageStandardRunDurationSeconds;
}

public double getLastStandardRunDurationSeconds() {
    return lastStandardRunDurationSeconds;
}

public void setLastStandardRunDurationSeconds(double lastStandardRunDurationSeconds) {
    this.lastStandardRunDurationSeconds = lastStandardRunDurationSeconds;
}

public double getAverageDockerRunDurationSeconds() {
    return averageDockerRunDurationSeconds;
}

public void setAverageDockerRunDurationSeconds(double averageDockerRunDurationSeconds) {
    this.averageDockerRunDurationSeconds = averageDockerRunDurationSeconds;
}

public double getLastDockerRunDurationSeconds() {
    return lastDockerRunDurationSeconds;
}

public void setLastDockerRunDurationSeconds(double lastDockerRunDurationSeconds) {
    this.lastDockerRunDurationSeconds = lastDockerRunDurationSeconds;
}

public long getStandardRunsCount() {
    return standardRunsCount;
}

public void setStandardRunsCount(long standardRunsCount) {
    this.standardRunsCount = standardRunsCount;
}

public long getDockerRunsCount() {
    return dockerRunsCount;
}

public void setDockerRunsCount(long dockerRunsCount) {
    this.dockerRunsCount = dockerRunsCount;
}
}