package com.example.editor.repository.component;


import com.example.editor.model.component.ComponentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentEventRepository extends JpaRepository<ComponentEvent, Long> {

    List<ComponentEvent> findByComponentId(Long componentId);
}
