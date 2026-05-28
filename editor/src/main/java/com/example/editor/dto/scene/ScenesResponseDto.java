package com.example.editor.dto.scene;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
public class ScenesResponseDto {
    private long id;
    private String name;
    private Long project_id;
}
