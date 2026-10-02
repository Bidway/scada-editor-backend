package com.example.runtime.ws;

import lombok.Data;

import java.util.Map;

/**
 * Сообщение фронт -> runtime по тому же WS-соединению сессии.
 * Основной тип — ACTION (например, нажатие кнопки), запускающий Script.
 * <p>
 * {@code args} — выбор оператора, который скрипт действия видит объектом {@code args}
 * (номер рецепта из меню и т.п.). Только JSON-объект; нет поля — скрипт видит {@code {}}.
 */
@Data
public class InboundMessage {
    private String type;
    private Long scriptId;
    private Map<String, Object> args;
}
