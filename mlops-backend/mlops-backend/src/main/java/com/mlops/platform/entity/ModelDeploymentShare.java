package com.mlops.platform.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "model_deployment_shares",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_deployment_shared_user",
                        columnNames = {"deployment_id", "shared_with_user_id"}
                )
        }
)
public class ModelDeploymentShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id", insertable = false, updatable = false)
    private ModelDeployment deployment;

    @Column(name = "shared_with_user_id", nullable = false)
    private Long sharedWithUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shared_with_user_id", insertable = false, updatable = false)
    private User sharedWithUser;

    @Column(nullable = false)
    private String permission = "PREDICT_ONLY";

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.permission == null || this.permission.isBlank()) {
            this.permission = "PREDICT_ONLY";
        }
    }

    public Long getId() {
        return id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public ModelDeployment getDeployment() {
        return deployment;
    }

    public Long getSharedWithUserId() {
        return sharedWithUserId;
    }

    public User getSharedWithUser() {
        return sharedWithUser;
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

    public void setDeployment(ModelDeployment deployment) {
        this.deployment = deployment;
    }

    public void setSharedWithUserId(Long sharedWithUserId) {
        this.sharedWithUserId = sharedWithUserId;
    }

    public void setSharedWithUser(User sharedWithUser) {
        this.sharedWithUser = sharedWithUser;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}