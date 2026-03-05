package com.example.editor.repository;


import com.example.editor.model.Script;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptRepository extends JpaRepository<Script, Long> {

    List<Script> findByComponentId(Long componentId);
}
