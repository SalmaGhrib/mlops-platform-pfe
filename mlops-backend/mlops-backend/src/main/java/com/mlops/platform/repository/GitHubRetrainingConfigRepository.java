package com.mlops.platform.repository;

import com.mlops.platform.entity.GitHubRetrainingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GitHubRetrainingConfigRepository
        extends JpaRepository<GitHubRetrainingConfig, Long> {

    Optional<GitHubRetrainingConfig> findByProjectId(Long projectId);

    Optional<GitHubRetrainingConfig> findBySecretHash(String secretHash);

    @Query("""
            update GitHubRetrainingConfig g
               set g.enabled = false,
                   g.secretHash = null,
                   g.secretSuffix = null,
                   g.lastCommitSha = null,
                   g.updatedAt = current_timestamp
             where g.projectId = :projectId
            """)
    int revokeKey(@Param("projectId") Long projectId);

    @Query("""
            update GitHubRetrainingConfig g
               set g.enabled = :enabled,
                   g.secretHash = null,
                   g.secretSuffix = null,
                   g.lastCommitSha = null,
                   g.updatedAt = current_timestamp
             where g.projectId = :projectId
            """)
    int markInactive(@Param("projectId") Long projectId, @Param("enabled") boolean enabled);
}