package com.example.editor.dto.data;

import java.util.List;

public record ProjectDataTableDto(
        String name,
        String title,
        String description,
        List<ProjectDataColumnDto> columns,
        List<ProjectDataRowDto> rows) {
}
