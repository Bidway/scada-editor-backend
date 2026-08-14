package com.example.editor.dto.component;

import lombok.Data;

@Data
public class BindingResponseDto {
    private Long id;
    private Long component_property_id;
    /**
     * Имя свойства, к которому привязан биндинг, — дубль ссылки {@code component_property_id},
     * и он здесь не для удобства чтения.
     * <p>
     * Ответ компонента служит ещё и снимком версии, а снимок переживает то, на что ссылается:
     * свойство, удалённое после снимка, восстановление создаёт заново с новым id, и номер из
     * снимка адресует пустоту. Имя же — настоящий ключ свойства везде в системе (по нему
     * сопоставляет {@code applyProperties}, по нему адресуют {@code RecipeValue} и
     * {@code writeTag}), поэтому оно и делает снимок самодостаточным (scada-3hw).
     */
    private String component_property_name;
    private String name;
    private String script;
}
