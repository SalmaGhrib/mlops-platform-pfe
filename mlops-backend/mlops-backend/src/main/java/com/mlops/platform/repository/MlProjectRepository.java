package com.mlops.platform.repository;

import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MlProjectRepository
        extends JpaRepository<MlProject, Long>, JpaSpecificationExecutor<MlProject> {

    List<MlProject> findByOwnerOrderByCreatedAtDesc(User owner);

    Optional<MlProject> findByIdAndOwner(Long id, User owner);

    /**
     * Phase A de l'auto-sync : ne charge que les IDs des projets,
     * sans matérialiser toutes les entités (et leurs associations).
     */
    @Query("SELECT p.id FROM MlProject p ORDER BY p.id")
    List<Long> findAllProjectIds();
}