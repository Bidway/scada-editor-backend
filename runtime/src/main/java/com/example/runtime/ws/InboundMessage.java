package com.example.runtime.ws;

import lombok.Data;

/**
 * Сообщение фронт -> runtime по тому же WS-соединению сессии.
 * Сейчас единственный тип — ACTION (например, нажатие кнопки), запускающий Script.
 */
@Data
public class InboundMessage {
    private String type;
    private Long scriptId;
}
