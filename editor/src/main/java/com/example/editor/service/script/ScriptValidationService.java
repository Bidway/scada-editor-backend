package com.example.editor.service.script;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.scriptcore.ScriptSyntaxChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Проверка синтаксиса скриптов компонента при сохранении (scada-8fw). Без неё битый скрипт
 * ложился в базу молча и падал только при первом исполнении — на объекте, а не в редакторе.
 * <p>
 * Скрипт только разбирается, не исполняется ({@link ScriptSyntaxChecker}). Все проверяемые виды
 * исполняются как тело функции — {@code return} на верхнем уровне допустим:
 * <ul>
 *   <li>{@code scripts.script} и {@code on_change} — в {@code runtime}, {@code (function(){ … })()};</li>
 *   <li>{@code component_event.script} и {@code code} биндинга — во фронте, {@code new Function(…, code)}.</li>
 * </ul>
 * У биндинга {@code script} — JSON-конфиг, JavaScript лежит в его поле {@code code}. Не-JSON
 * оставляем как есть: формат биндинга — дело фронта, здесь проверяется только код.
 * <p>
 * Пустой скрипт допустим — исполнители его пропускают.
 */
@Service
public class ScriptValidationService {

    private final ScriptSyntaxChecker syntaxChecker;
    private final ObjectMapper objectMapper;

    public ScriptValidationService(ScriptSyntaxChecker syntaxChecker, ObjectMapper objectMapper) {
        this.syntaxChecker = syntaxChecker;
        this.objectMapper = objectMapper;
    }

    /** Обходит дерево компонентов и бросает {@link IllegalArgumentException} (→ 400) на первой ошибке. */
    public void validateTree(List<ComponentCreateDto> components) {
        if (components == null) {
            return;
        }
        for (ComponentCreateDto component : components) {
            validateComponent(component);
        }
    }

    /** Отдельное свойство — для точечных эндпоинтов {@code /api/editor/properties}. */
    public void validateProperty(PropertyCreateDto property, String componentName) {
        if (property != null) {
            check(property.getOnChange(), "on_change of property", property.getName(), componentName);
        }
    }

    private void validateComponent(ComponentCreateDto component) {
        String owner = component.getName();
        if (component.getProperties() != null) {
            for (PropertyCreateDto p : component.getProperties()) {
                validateProperty(p, owner);
            }
        }
        if (component.getScripts() != null) {
            for (ScriptCreateDto s : component.getScripts()) {
                check(s.getScript(), "script", s.getName(), owner);
            }
        }
        if (component.getEvents() != null) {
            for (EventPayloadDto e : component.getEvents()) {
                check(e.getScript(), "event", e.getEvent_type(), owner);
            }
        }
        if (component.getBindings() != null) {
            for (BindingPayloadDto b : component.getBindings()) {
                check(bindingCode(b.getScript()), "binding", b.getName(), owner);
            }
        }
        validateTree(component.getChildren());
    }

    private String bindingCode(String bindingScript) {
        if (bindingScript == null || bindingScript.isBlank()) {
            return null;
        }
        try {
            JsonNode code = objectMapper.readTree(bindingScript).get("code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void check(String source, String kind, String name, String componentName) {
        if (source == null || source.isBlank()) {
            return;
        }
        syntaxChecker.check(source).ifPresent(error -> {
            throw new IllegalArgumentException("Script syntax error in " + kind + " '" + name
                    + "' of component '" + componentName + "'"
                    + (error.line() != null ? " at line " + error.line() : "")
                    + ": " + error.message());
        });
    }
}
