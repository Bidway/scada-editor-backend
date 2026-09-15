package com.example.editor.repository.data;

import com.example.editor.model.data.ProjectDataTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectDataTableRepository extends JpaRepository<ProjectDataTable, Long> {

    List<ProjectDataTable> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
