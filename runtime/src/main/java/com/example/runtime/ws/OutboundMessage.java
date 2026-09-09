package com.example.runtime.ws;

import com.example.runtime.stream.ProcedureEvent;
import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.stream.TagUpdate;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Единый лёгкий контракт сообщений runtime -> фронт по WebSocket-сессии.
 * Один тип сообщения "UPDATE" с тремя опциональными массивами.
 */
@Data
@NoArgsConstructor
public class OutboundMessage {
    private String type = "UPDATE";
    private List<TagUpdate> tags;
    private List<PropertyUpdate> properties;
    private List<ProcedureEvent> procedures;

    public OutboundMessage(List<TagUpdate> tags, List<PropertyUpdate> properties, List<ProcedureEvent> procedures) {
        this.tags = tags;
        this.properties = properties;
        this.procedures = procedures;
    }
}
