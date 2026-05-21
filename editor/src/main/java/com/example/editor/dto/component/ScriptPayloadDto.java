package com.example.editor.dto.component;

import lombok.Data;

/**
 * Именованный скрипт компонента (тело хранится как текст; исполняется на стороне рантайма SCADA).
 */
@Data
public class ScriptPayloadDto {
    private String name;
    /** Исходный код или сериализованное представление (например JS), интерпретируется клиентом/рантаймом. */
    private String script;
}
