package com.example.editor.repository;


import com.example.editor.model.ComponentProperty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComponentPropertyRepository extends JpaRepository<ComponentProperty, Long> {

    List<ComponentProperty> findByComponentId(Long componentId);

    List<ComponentProperty> findByPropertyType(String propertyType);
}
