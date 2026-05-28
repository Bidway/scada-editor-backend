package com.example.editor.command.component;

import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ComponentHierarchyValidator {

    public void requireProjectParent(Component parent) {
        if (parent == null) {
            throw new IllegalStateException("Scene requires a project parent (project_id)");
        }
        if (!ComponentTypes.PROJECT.equals(parent.getType())) {
            throw new IllegalStateException(
                    "Scene parent must be a project, got type: " + parent.getType());
        }
    }

    public void validateRootCreate(String type) {
        if (!ComponentTypes.PROJECT.equals(type)) {
            throw new IllegalStateException(
                    "Only components of type 'project' can be created without a parent");
        }
    }

    public void validateParentForCreate(Component parent, String childType) {
        if (parent == null) {
            validateRootCreate(childType);
            return;
        }
        if (ComponentTypes.PROJECT.equals(parent.getType())
                && !ComponentTypes.SCENE.equals(childType)) {
            throw new IllegalStateException(
                    "Only scenes can be direct children of a project");
        }
        if (ComponentTypes.SCENE.equals(childType)) {
            requireProjectParent(parent);
        }
    }
}
