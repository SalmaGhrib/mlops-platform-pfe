package com.mlops.platform.dto;

import com.mlops.platform.entity.ModelDeploymentShare;
import com.mlops.platform.entity.User;

import java.time.LocalDateTime;

public class ModelDeploymentShareResponse {

    private Long id;
    private Long deploymentId;
    private Long userId;
    private String email;
    private String fullName;
    private String permission;
    private LocalDateTime createdAt;

    public ModelDeploymentShareResponse() {
    }

    public static ModelDeploymentShareResponse fromEntity(ModelDeploymentShare share) {
        ModelDeploymentShareResponse response = new ModelDeploymentShareResponse();

        if (share == null) {
            return response;
        }

        response.setId(share.getId());
        response.setDeploymentId(share.getDeploymentId());
        response.setUserId(share.getSharedWithUserId());
        response.setPermission(share.getPermission());
        response.setCreatedAt(share.getCreatedAt());

        User user = share.getSharedWithUser();

        if (user != null) {
            response.setEmail(user.getEmail());

            String fullName = (
                    (user.getFirstName() == null ? "" : user.getFirstName()) + " " +
                    (user.getLastName() == null ? "" : user.getLastName())
            ).trim();

            response.setFullName(fullName.isBlank() ? user.getEmail() : fullName);
        }

        return response;
    }

    public Long getId() {
        return id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPermission() {
        return permission;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}