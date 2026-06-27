package com.mlops.platform.dto;

import java.util.List;

public class ShareDeploymentRequest {

    private List<Long> userIds;
    private String permission;

    public ShareDeploymentRequest() {
    }

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }
}