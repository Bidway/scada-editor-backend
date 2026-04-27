package com.example.editor.repository.component;

import com.example.editor.model.component.ComponentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ComponentStateRepository extends JpaRepository<ComponentState, Long> {
}
