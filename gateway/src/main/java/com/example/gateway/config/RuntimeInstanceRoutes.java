package com.example.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebSocket мониторинга соединяется напрямую с экземпляром runtime, на котором работает проект:
 * {@code /ws/runtime/<instanceId>/…} → адрес этого экземпляра. Список экземпляров берётся у runtime
 * раз в 10 с. Пересылать WebSocket между экземплярами runtime дорого, поэтому маршрутизирует gateway.
 * Экземпляр, которого нет в списке, недостижим — перехвата нет.
 */
@Component
public class RuntimeInstanceRoutes implements RouteDefinitionLocator {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInstanceRoutes.class);

    private final WebClient client;
    private final ApplicationEventPublisher events;
    private volatile List<RouteDefinition> routes = List.of();

    public RuntimeInstanceRoutes(WebClient.Builder builder, ApplicationEventPublisher events,
                                 @Value("http://${RUNTIME_HOST:localhost}:${RUNTIME_PORT:8085}") String runtimeUrl) {
        this.client = builder.baseUrl(runtimeUrl).build();
        this.events = events;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(routes);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 1_000)
    public void refresh() {
        try {
            JsonNode list = client.get().uri("/api/runtime/instances").retrieve()
                    .bodyToMono(JsonNode.class).block(Duration.ofSeconds(3));
            List<RouteDefinition> next = new ArrayList<>();
            if (list != null) {
                for (JsonNode instance : list) {
                    String id = instance.path("instanceId").asText();
                    String baseUrl = instance.path("baseUrl").asText();
                    if (id.isEmpty() || baseUrl.isEmpty()) {
                        continue;
                    }
                    RouteDefinition route = new RouteDefinition();
                    route.setId("runtime-ws-" + id);
                    route.setUri(URI.create(baseUrl));
                    route.setOrder(-1);
                    PredicateDefinition path = new PredicateDefinition();
                    path.setName("Path");
                    path.setArgs(Map.of("pattern", "/ws/runtime/" + id + "/**"));
                    route.setPredicates(List.of(path));
                    route.setFilters(List.<FilterDefinition>of());
                    next.add(route);
                }
            }
            if (!next.equals(routes)) {
                routes = List.copyOf(next);
                events.publishEvent(new RefreshRoutesEvent(this));
                log.info("Маршруты WebSocket runtime обновлены: {}", next.stream().map(RouteDefinition::getId).toList());
            }
        } catch (Exception e) {
            // Список не получен — прежние маршруты остаются: пропадание runtime не должно рвать уже известные пути.
            log.warn("Список экземпляров runtime не получен: {}", e.getMessage());
        }
    }
}
