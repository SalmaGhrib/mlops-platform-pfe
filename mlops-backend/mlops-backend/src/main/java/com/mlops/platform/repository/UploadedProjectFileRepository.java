package com.mlops.platform.repository;

import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UploadedProjectFileRepository extends JpaRepository<UploadedProjectFile, Long> {

    List<UploadedProjectFile> findByProject(MlProject project);

    List<UploadedProjectFile> findByProjectId(Long projectId);

    void deleteByProject(MlProject project);

    void deleteByProjectId(Long projectId);
}