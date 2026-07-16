package com.example.runtime.ws;

import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.stream.TagUpdate;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Единый лёгкий контракт сообщений runtime -> фронт по WebSocket-сессии.
 * Один тип сообщения "UPDATE" с двумя опциональными массивами — и для батча
 * из флашера, и для мгновенного ответа на ACTION (тогда tags будет пустым).
 */
@Data
@NoArgsConstructor
public class OutboundMessage {
    private String type = "UPDATE";
    private List<TagUpdate> tags;
    private List<PropertyUpdate> properties;

    public OutboundMessage(List<TagUpdate> tags, List<PropertyUpdate> properties) {
        this.tags = tags;
        this.properties = properties;
    }
}
