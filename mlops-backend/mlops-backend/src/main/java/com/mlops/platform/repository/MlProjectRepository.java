package com.mlops.platform.repository;

import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MlProjectRepository extends JpaRepository<MlProject, Long> {

    List<MlProject> findByOwnerOrderByCreatedAtDesc(User owner);

    Optional<MlProject> findByIdAndOwner(Long id, User owner);
}