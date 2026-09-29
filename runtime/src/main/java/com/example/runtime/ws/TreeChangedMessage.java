package com.example.runtime.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Проект переключён на другой выпуск: монитор перечитывает сцены, следом приходит SNAPSHOT. */
public record TreeChangedMessage(int versionNo) {

    @JsonProperty("type")
    public String type() {
        return "TREE_CHANGED";
    }
}
