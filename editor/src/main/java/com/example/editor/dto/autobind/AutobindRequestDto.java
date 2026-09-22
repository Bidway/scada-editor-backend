package com.example.editor.dto.autobind;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Базу каналов выбирают при нажатии: связи «проект editor ↔ база каналов» нигде не хранится. */
public record AutobindRequestDto(@JsonProperty("channel_root") String channelRoot) {
}
