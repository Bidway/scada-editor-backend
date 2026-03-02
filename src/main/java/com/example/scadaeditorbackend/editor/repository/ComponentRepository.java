package com.example.scadaeditorbackend.editor.repository;


import com.example.scadaeditorbackend.editor.model.Component;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComponentRepository extends JpaRepository<Component, Long> {

    List<Component> findByParentId(Long parentId);

    List<Component> findByType(String type);
}
