package com.example.editor.dto.scene;

import lombok.Data;

@Data
public class SceneCreateDto {
    private String name;
    /** Id проекта — родителя сцены. */
    private Long project_id;
}
