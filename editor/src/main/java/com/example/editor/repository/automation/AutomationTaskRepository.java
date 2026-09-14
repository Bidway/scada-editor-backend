package com.example.editor.repository.automation;

import com.example.editor.model.automation.AutomationTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationTaskRepository extends JpaRepository<AutomationTask, Long> {

    List<AutomationTask> findByProjectIdOrderByIdAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}
