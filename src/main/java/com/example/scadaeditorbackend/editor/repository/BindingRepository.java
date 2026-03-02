package com.example.scadaeditorbackend.editor.repository;


import com.example.scadaeditorbackend.editor.model.Binding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BindingRepository extends JpaRepository<Binding, Long> {

    List<Binding> findByComponentId(Long componentId);

    List<Binding> findByComponentPropertyId(Long propertyId);
}
