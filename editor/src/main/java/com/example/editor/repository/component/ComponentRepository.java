package com.example.editor.repository.component;


import com.example.editor.model.component.Component;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long> {

    List<Component> findByParentId(Long parentId);

    List<Component> findByType(String type);
}
