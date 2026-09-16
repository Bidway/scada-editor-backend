package com.example.editor.repository.automation;

import com.example.editor.model.automation.AutomationTaskTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutomationTaskTemplateRepository extends JpaRepository<AutomationTaskTemplate, Long> {

    List<AutomationTaskTemplate> findAllByOrderByNameAsc();

    Optional<AutomationTaskTemplate> findByName(String name);
}
