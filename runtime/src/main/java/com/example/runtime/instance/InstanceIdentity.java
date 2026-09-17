package com.example.runtime.instance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Имя и адрес экземпляра runtime. Обязательны: по имени экземпляру назначаются топики и проекты,
 * по адресу другие экземпляры и gateway пересылают ему запросы. Имени хоста по умолчанию нет —
 * при переносе на другой сервер назначения молча перестали бы совпадать.
 */
@Component
public class InstanceIdentity {

    /** Точка — разделитель в sessionId ({@code <instanceId>.<uuid>}), поэтому в имени её быть не может. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String instanceId;
    private final String baseUrl;

    public InstanceIdentity(@Value("${runtime.instance-id:}") String instanceId,
                            @Value("${runtime.advertised-url:}") String baseUrl) {
        if (instanceId == null || !ID.matcher(instanceId).matches()) {
            throw new IllegalStateException("RUNTIME_INSTANCE_ID обязателен: латиница, цифры, '_' и '-', до 64 символов; "
                    + "получено '" + instanceId + "'");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("RUNTIME_ADVERTISED_URL обязателен: адрес, по которому экземпляр "
                    + instanceId + " доступен другим экземплярам и gateway, например http://10.0.0.12:8085");
        }
        URI uri = URI.create(baseUrl.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalStateException("RUNTIME_ADVERTISED_URL не похож на адрес: '" + baseUrl + "'");
        }
        this.instanceId = instanceId;
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
    }

    public String instanceId() {
        return instanceId;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
