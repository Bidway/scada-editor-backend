package com.example.editor.repository;

import com.example.editor.model.ProjectRuntimeFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRuntimeFlagRepository extends JpaRepository<ProjectRuntimeFlag, Long> {

    List<ProjectRuntimeFlag> findAllByInOperationTrue();
}
