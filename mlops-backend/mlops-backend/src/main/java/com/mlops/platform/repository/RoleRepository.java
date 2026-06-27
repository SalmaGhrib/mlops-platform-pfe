package com.mlops.platform.repository;

import com.mlops.platform.entity.Role;
import com.mlops.platform.entity.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleType name);
}