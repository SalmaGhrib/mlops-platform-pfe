package com.mlops.platform.dto;

public class DeleteExperimentResponse {

    private Long experimentId;
    private String message;
    private boolean mysqlDeleted;
    private boolean mysqlMetricsDeleted;
    private boolean mlflowRunDeleted;
    private boolean mlflowModelVersionDeleted;

    public DeleteExperimentResponse() {
    }

    public DeleteExperimentResponse(
            Long experimentId,
            String message,
            boolean mysqlDeleted,
            boolean mysqlMetricsDeleted,
            boolean mlflowRunDeleted,
            boolean mlflowModelVersionDeleted
    ) {
        this.experimentId = experimentId;
        this.message = message;
        this.mysqlDeleted = mysqlDeleted;
        this.mysqlMetricsDeleted = mysqlMetricsDeleted;
        this.mlflowRunDeleted = mlflowRunDeleted;
        this.mlflowModelVersionDeleted = mlflowModelVersionDeleted;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isMysqlDeleted() {
        return mysqlDeleted;
    }

    public void setMysqlDeleted(boolean mysqlDeleted) {
        this.mysqlDeleted = mysqlDeleted;
    }

    public boolean isMysqlMetricsDeleted() {
        return mysqlMetricsDeleted;
    }

    public void setMysqlMetricsDeleted(boolean mysqlMetricsDeleted) {
        this.mysqlMetricsDeleted = mysqlMetricsDeleted;
    }

    public boolean isMlflowRunDeleted() {
        return mlflowRunDeleted;
    }

    public void setMlflowRunDeleted(boolean mlflowRunDeleted) {
        this.mlflowRunDeleted = mlflowRunDeleted;
    }

    public boolean isMlflowModelVersionDeleted() {
        return mlflowModelVersionDeleted;
    }

    public void setMlflowModelVersionDeleted(boolean mlflowModelVersionDeleted) {
        this.mlflowModelVersionDeleted = mlflowModelVersionDeleted;
    }
}