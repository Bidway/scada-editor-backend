package com.example.editor.dto.component;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Тело удаления компонентов.
 * <p>
 * Голый массив id заменён объектом ради {@code based_on_version}: удаление — такое же изменение
 * сцены, как правка, и обязано проверять версию (scada-ybr). Иначе «последний победил»
 * возвращается через ту дверь, которую в сохранении уже закрыли.
 */
@Getter
@Setter
public class ComponentDeleteRequestDto {

    private List<Long> ids;

    private Integer based_on_version;
}
