package com.example.runtime.ws;

import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.stream.TagUpdate;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Первый кадр после подключения WebSocket: всё состояние проекта на момент подключения.
 * Проект работает и без наблюдателей, поэтому открывший монитор приходит в середину процесса
 * и без снимка видел бы пустой экран до следующих изменений — у долгого шага мойки это
 * десятки минут. Дальше идут обычные {@code UPDATE}.
 */
@JsonPropertyOrder({"type", "tags", "properties", "procedures"})
public record SnapshotMessage(List<TagUpdate> tags, List<PropertyUpdate> properties,
                              List<ProcedureStatusDto> procedures) {

    @JsonProperty("type")
    public String type() {
        return "SNAPSHOT";
    }
}
