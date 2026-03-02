package com.example.scadaeditorbackend.editor.repository;


import com.example.scadaeditorbackend.editor.model.Script;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptRepository extends JpaRepository<Script, Long> {

    List<Script> findByComponentId(Long componentId);
}
