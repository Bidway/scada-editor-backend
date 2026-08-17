package com.example.editor.service.component;

import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SceneRootResolverTest {

    @Test
    void returnsSceneIdForNestedComponent() {
        Component scene = component(10L, ComponentTypes.SCENE, null);
        Component group = component(20L, "group", scene);
        Component leaf = component(30L, "button", group);

        assertEquals(10L, SceneRootResolver.sceneRootIdOf(leaf));
    }

    @Test
    void returnsOwnIdWhenComponentIsTheScene() {
        Component scene = component(10L, ComponentTypes.SCENE, null);

        assertEquals(10L, SceneRootResolver.sceneRootIdOf(scene));
    }

    /** Проект под сценой не лежит — версионируемого документа у него нет. */
    @Test
    void returnsNullWhenNoSceneAbove() {
        Component project = component(1L, ComponentTypes.PROJECT, null);

        assertNull(SceneRootResolver.sceneRootIdOf(project));
    }

    @Test
    void returnsNullForNull() {
        assertNull(SceneRootResolver.sceneRootIdOf(null));
    }

    private static Component component(Long id, String type, Component parent) {
        Component c = new Component();
        c.setId(id);
        c.setType(type);
        c.setParent(parent);
        return c;
    }
}
