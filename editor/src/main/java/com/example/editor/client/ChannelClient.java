package com.example.editor.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Чтение базы каналов для автопривязки. Прямо в channel, минуя gateway: сервисы между собой ходят
 * без JWT, как runtime в editor. Разовый запрос на нажатие кнопки, не горячий путь.
 */
@Component
public class ChannelClient {

    private final RestClient restClient;

    public ChannelClient(RestClient.Builder builder,
                         @Value("${editor.channel-base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public ChannelTree fetchTree(String root) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/api/channel/node/fullHierarchy").queryParam("rootPath", "{root}").build(root))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ChannelUnavailableException("База каналов недоступна: " + e.getMessage(), e);
        }
        List<String> nodes = new ArrayList<>();
        List<ChannelTree.Param> params = new ArrayList<>();
        if (body != null) {
            body.path("nodes").forEach(node -> nodes.add(node.path("key").asText()));
            body.path("params").forEach(param -> params.add(new ChannelTree.Param(
                    param.path("parentKey").asText(null), param.path("name").asText(null),
                    param.path("value").asText(null))));
        }
        return new ChannelTree(nodes, params);
    }
}
