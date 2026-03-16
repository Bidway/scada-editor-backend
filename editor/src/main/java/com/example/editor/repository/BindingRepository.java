package com.example.editor.repository;


import com.example.editor.model.Binding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BindingRepository extends JpaRepository<Binding, Long> {

    List<Binding> findByComponentId(Long componentId);

    List<Binding> findByComponentPropertyId(Long propertyId);
}
