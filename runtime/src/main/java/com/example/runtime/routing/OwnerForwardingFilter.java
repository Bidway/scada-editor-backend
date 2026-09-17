package com.example.runtime.routing;

import com.example.runtime.assignment.InstanceProjectEntity;
import com.example.runtime.assignment.InstanceProjectRepository;
import com.example.runtime.assignment.InstanceTopicPrefixEntity;
import com.example.runtime.assignment.InstanceTopicPrefixRepository;
import com.example.runtime.assignment.InstanceTopicRepository;
import com.example.runtime.assignment.PathPrefixes;
import com.example.runtime.instance.InstanceEntity;
import com.example.runtime.instance.InstanceIdentity;
import com.example.runtime.instance.InstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запрос по проекту, назначенному другому экземпляру, уходит владельцу: gateway шлёт REST на любой
 * экземпляр и не разбирает тела. Пересылка ровно одна: запрос помечается {@code X-Runtime-Forwarded}.
 * Перехвата нет: владелец недоступен — {@code 503}.
 */
@Component
@Order(0)
@Slf4j
public class OwnerForwardingFilter extends OncePerRequestFilter {

    static final String FORWARDED = "X-Runtime-Forwarded";

    private static final Pattern AUTOMATION = Pattern.compile("^/api/automation/projects/(\\d+)/data/.*");
    private static final Pattern SESSION = Pattern.compile("^/api/runtime/sessions/([A-Za-z0-9_-]+)\\.[^/]+(/.*)?$");
    private static final Set<String> COPIED_HEADERS = Set.of("x-username", "x-user-id", "content-type", "accept");

    private final InstanceIdentity identity;
    private final InstanceRepository instances;
    private final InstanceProjectRepository projects;
    private final InstanceTopicPrefixRepository prefixes;
    private final InstanceTopicRepository topics;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public OwnerForwardingFilter(InstanceIdentity identity, InstanceRepository instances,
                                 InstanceProjectRepository projects, InstanceTopicPrefixRepository prefixes,
                                 InstanceTopicRepository topics) {
        this(identity, instances, projects, prefixes, topics, RestClient.builder().requestFactory(timeouts()));
    }

    OwnerForwardingFilter(InstanceIdentity identity, InstanceRepository instances, InstanceProjectRepository projects,
                          InstanceTopicPrefixRepository prefixes, InstanceTopicRepository topics,
                          RestClient.Builder builder) {
        this.identity = identity;
        this.instances = instances;
        this.projects = projects;
        this.prefixes = prefixes;
        this.topics = topics;
        this.client = builder.build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean routed = path.startsWith("/api/runtime/sessions") || path.startsWith("/api/runtime/recipes/")
                || path.equals("/api/runtime/tags/write") || AUTOMATION.matcher(path).matches();
        return !routed;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        Optional<String> owner;
        try {
            owner = ownerOf(cached);
        } catch (NotAssignedException e) {
            writeError(response, 409, e.getMessage());
            return;
        }
        if (owner.isEmpty() || owner.get().equals(identity.instanceId())) {
            chain.doFilter(cached, response);
            return;
        }
        if (cached.getHeader(FORWARDED) != null) {
            writeError(response, 409, "Проект не назначен этому экземпляру runtime (" + identity.instanceId()
                    + "): назначение только что сменили — повторите запрос");
            return;
        }
        forward(cached, response, owner.get());
    }

    /** Экземпляр-владелец запроса; пусто — выполнить здесь (например, создание сессии без проекта в теле). */
    private Optional<String> ownerOf(CachedBodyRequest request) throws NotAssignedException {
        String path = request.getRequestURI();
        Matcher session = SESSION.matcher(path);
        if (session.matches()) {
            return Optional.of(session.group(1));
        }
        Matcher automation = AUTOMATION.matcher(path);
        if (automation.matches()) {
            return Optional.of(projectOwner(Long.parseLong(automation.group(1))));
        }
        String queryProject = request.getParameter("projectId");
        if (queryProject != null && queryProject.matches("\\d+")) {
            return Optional.of(projectOwner(Long.parseLong(queryProject)));
        }
        JsonNode body = readJson(request.body());
        if (path.equals("/api/runtime/tags/write")) {
            JsonNode tag = body == null ? null : body.path("writes").path(0).path("tagId");
            return tag == null || !tag.isTextual() ? Optional.empty() : Optional.of(topicOwner(tag.asText()));
        }
        JsonNode projectId = body == null ? null : body.get("projectId");
        if (projectId != null && projectId.canConvertToLong()) {
            return Optional.of(projectOwner(projectId.asLong()));
        }
        return Optional.empty();
    }

    private String projectOwner(long projectId) throws NotAssignedException {
        return projects.findById(projectId).map(InstanceProjectEntity::getInstanceId)
                .orElseThrow(() -> new NotAssignedException("Проект " + projectId
                        + " не назначен ни одному экземпляру runtime"));
    }

    private String topicOwner(String path) throws NotAssignedException {
        return prefixes.findAll().stream()
                .filter(p -> PathPrefixes.covers(p.getPathPrefix(), path))
                .map(InstanceTopicPrefixEntity::getTelemetryTopic)
                .findFirst()
                .flatMap(topics::findById)
                .map(t -> t.getInstanceId())
                .orElseThrow(() -> new NotAssignedException("Путь " + path
                        + " не покрыт топиками ни одного экземпляра runtime"));
    }

    private void forward(CachedBodyRequest request, HttpServletResponse response, String ownerId) throws IOException {
        Optional<InstanceEntity> owner = instances.findById(ownerId);
        if (owner.isEmpty()) {
            writeError(response, 503, "Экземпляр " + ownerId + ", на котором работает проект, неизвестен");
            return;
        }
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        String url = owner.get().getBaseUrl() + request.getRequestURI() + query;
        try {
            RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.getMethod()))
                    .uri(url)
                    .headers(headers -> {
                        for (String name : Collections.list(request.getHeaderNames())) {
                            if (COPIED_HEADERS.contains(name.toLowerCase())) {
                                headers.add(name, request.getHeader(name));
                            }
                        }
                        headers.set(FORWARDED, identity.instanceId());
                    });
            // Пустое тело не передаётся: у GET его быть не должно.
            if (request.body().length > 0) {
                spec.body(request.body());
            }
            ResponseEntity<byte[]> answer = spec
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(byte[].class);
            response.setStatus(answer.getStatusCode().value());
            if (answer.getHeaders().getContentType() != null) {
                response.setContentType(answer.getHeaders().getContentType().toString());
            }
            if (answer.getBody() != null) {
                response.getOutputStream().write(answer.getBody());
            }
        } catch (ResourceAccessException e) {
            log.warn("Экземпляр {} недоступен по адресу {}: {}", ownerId, owner.get().getBaseUrl(), e.getMessage());
            writeError(response, 503, "Экземпляр " + ownerId + ", на котором работает проект, недоступен");
        } catch (RestClientResponseException e) {
            response.setStatus(e.getStatusCode().value());
            response.getOutputStream().write(e.getResponseBodyAsByteArray());
        }
    }

    private JsonNode readJson(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String json = mapper.writeValueAsString(java.util.Map.of(
                "timestamp", java.time.LocalDateTime.now().toString(), "status", status, "message", message));
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    private static final class NotAssignedException extends Exception {
        NotAssignedException(String message) {
            super(message);
        }
    }
}
