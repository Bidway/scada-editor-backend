package com.example.editor.repository;


import com.example.editor.model.ComponentProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentPropertyRepository extends JpaRepository<ComponentProperty, Long> {

    List<ComponentProperty> findByComponentId(Long componentId);

    List<ComponentProperty> findByPropertyType(String propertyType);
}
