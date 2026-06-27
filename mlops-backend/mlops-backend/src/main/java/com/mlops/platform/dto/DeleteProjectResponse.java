package com.mlops.platform.dto;

public class DeleteProjectResponse {

    private Long projectId;
    private String message;
    private int deletedExperimentsCount;
    private boolean mysqlProjectDeleted;

    public DeleteProjectResponse() {
    }

    public DeleteProjectResponse(
            Long projectId,
            String message,
            int deletedExperimentsCount,
            boolean mysqlProjectDeleted
    ) {
        this.projectId = projectId;
        this.message = message;
        this.deletedExperimentsCount = deletedExperimentsCount;
        this.mysqlProjectDeleted = mysqlProjectDeleted;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getDeletedExperimentsCount() {
        return deletedExperimentsCount;
    }

    public void setDeletedExperimentsCount(int deletedExperimentsCount) {
        this.deletedExperimentsCount = deletedExperimentsCount;
    }

    public boolean isMysqlProjectDeleted() {
        return mysqlProjectDeleted;
    }

    public void setMysqlProjectDeleted(boolean mysqlProjectDeleted) {
        this.mysqlProjectDeleted = mysqlProjectDeleted;
    }
}