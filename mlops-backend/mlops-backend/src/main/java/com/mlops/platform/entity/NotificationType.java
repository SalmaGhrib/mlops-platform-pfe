package com.mlops.platform.entity;

public enum NotificationType {
    RUN_COMPLETED,
    RUN_FAILED,

    DEPLOYMENT_DEPLOYED,
    DEPLOYMENT_FAILED,
    DEPLOYMENT_OUT_OF_SYNC,

    MONITORING_ALERT,
    SYSTEM_INFO
}