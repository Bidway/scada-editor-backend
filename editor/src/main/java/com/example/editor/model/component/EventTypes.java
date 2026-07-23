package com.example.editor.model.component;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Допустимые типы событий компонента. Все они исполняются <b>на фронте</b>:
 * событие порождает жест конкретного оператора (или открытие сцены у конкретного
 * оператора), поэтому его обработчик локален по природе.
 * <p>
 * Серверная логика запускается не отсюда: запись тега — {@link Script} по ACTION,
 * производное состояние — {@code ComponentProperty.onChange} по смене тега. Клиентский
 * обработчик делегирует бэку через {@code runScript(...)}, поэтому «серверного события
 * компонента» в модели нет и колонка вида {@code runs_on} не нужна.
 */
@UtilityClass
public class EventTypes {

    public static final String ON_CLICK = "onClick";
    public static final String ON_DOUBLE_CLICK = "onDoubleClick";
    public static final String ON_MOUSE_DOWN = "onMouseDown";
    public static final String ON_MOUSE_UP = "onMouseUp";
    public static final String ON_HOVER = "onHover";
    public static final String ON_HOVER_OUT = "onHoverOut";
    public static final String ON_OPEN = "onOpen";
    public static final String ON_CLOSE = "onClose";

    public static final Set<String> ALL = Set.of(
            ON_CLICK, ON_DOUBLE_CLICK, ON_MOUSE_DOWN, ON_MOUSE_UP,
            ON_HOVER, ON_HOVER_OUT, ON_OPEN, ON_CLOSE);

    /**
     * Тот же список, но для CHECK-констрейнта в DDL: тип события — не свободная строка.
     * Ошибку {@code property_type} ("Тег"/"TAG"/... без валидации) здесь не повторяем.
     */
    public static final String CHECK = "event_type IN ("
            + "'onClick','onDoubleClick','onMouseDown','onMouseUp',"
            + "'onHover','onHoverOut','onOpen','onClose')";

    public boolean isValid(String eventType) {
        return eventType != null && ALL.contains(eventType);
    }
}
