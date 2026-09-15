package com.example.editor.dto.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Тело {@code PUT}: весь набор сразу и версия, от которой отталкивался клиент. */
public record ProjectDataSaveRequestDto(
        @JsonProperty("based_on_version") Integer basedOnVersion,
        List<ProjectDataTableDto> tables) {
}
