package com.example.editor.dto.autobind;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Итог автопривязки. bound — свойств, получивших тег из базы; changed — из них тех, у кого тег
 * действительно поменялся; kept — привязанные к другому устройству, их не трогали. in_operation — runtime увидит новые теги только после повторного подъёма.
 */
public record AutobindReportDto(
        @JsonProperty("channel_root") String channelRoot,
        int bound,
        int changed,
        List<Scene> scenes,
        @JsonProperty("not_found") List<Miss> notFound,
        @JsonProperty("missing_fields") List<Miss> missingFields,
        List<Kept> kept,
        @JsonProperty("in_operation") boolean inOperation) {

    public record Scene(@JsonProperty("scene_id") Long sceneId, String name,
                        @JsonProperty("version_no") Integer versionNo) {
    }

    /**
     * Свойство уже привязано к другому устройству, чем нашлось по имени, — оставлено как было.
     * current — нынешний тег, found — что предлагала база каналов (scada-w7gh).
     */
    public record Kept(@JsonProperty("component_id") Long componentId, String name, String property,
                       String scene, String current, String found) {
    }

    /** property пуст у not_found: там не найден сам объект. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Miss(@JsonProperty("component_id") Long componentId, String name, String property,
                       String scene) {
    }
}
