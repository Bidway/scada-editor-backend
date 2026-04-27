package com.example.editor.repository.template;

import com.example.editor.model.template.TemplateFacePlate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateFacePlateRepository extends JpaRepository<TemplateFacePlate, Long> {
}
