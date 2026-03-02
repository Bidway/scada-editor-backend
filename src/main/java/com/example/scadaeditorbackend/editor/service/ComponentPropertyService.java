package com.example.scadaeditorbackend.editor.service;



import com.example.scadaeditorbackend.editor.model.ComponentProperty;

import java.util.List;

public interface ComponentPropertyService {

    ComponentProperty create(ComponentProperty property);

    ComponentProperty update(Long id, ComponentProperty property);

    void delete(Long id);

    ComponentProperty getById(Long id);

    List<ComponentProperty> getByComponentId(Long componentId);
}
