package com.example.editor.dto.project;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ProjectsResponseDto {
    private long id;
    private String name;
    /**
     * Настройки рабочего места проекта (закреплённые схемы), см. {@code ProjectImageController}.
     * Поле есть всегда, хотя бы {@code null}: по его отсутствию фронт узнаёт старый бэк.
     */
    private JsonNode image;
}
