package com.example.editor.repository.automation;

import com.example.editor.model.automation.AutomationVariable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationVariableRepository extends JpaRepository<AutomationVariable, Long> {

    List<AutomationVariable> findByProjectIdOrderByNameAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}
