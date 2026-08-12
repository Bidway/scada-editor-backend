package com.example.editor.controller;

import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.component.ComponentState;
import com.example.editor.model.component.Script;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.repository.component.ComponentStateRepository;
import com.example.editor.repository.component.ScriptRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Уникальность имён внутри компонента держит не только код, но и база.
 * <p>
 * Проверки на входе есть ({@link ComponentValidationIT}), но они — единственное, что стояло
 * между дублем и данными: у {@code component_event} констрейнт в БД был, у свойств, скриптов и
 * состояний — нет (scada-95o). Имя здесь адрес: значения наборов ищут строку по имени,
 * {@code writeTag} адресует свойство, {@code runScript} — скрипт, {@code setState} — состояние.
 * Дубль делает любую из этих привязок неоднозначной, и обнаружится это уже на стенде.
 * <p>
 * Поэтому тесты идут мимо API, прямо через репозитории: через контроллер такой записи не
 * сделать, а проверить надо именно то, что поймает база, если проверку в коде однажды уберут.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComponentUniquenessIT extends EditorApiTestSupport {

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private ComponentPropertyRepository propertyRepository;

    @Autowired
    private ScriptRepository scriptRepository;

    @Autowired
    private ComponentStateRepository stateRepository;

    private Component componentWithEverything() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"scripts\":[{\"name\":\"Пуск\",\"script\":\"a()\"}],"
                + "\"states\":[{\"name\":\"Открыт\",\"image\":{},\"isDefault\":true}]}]").get(0);
        return componentRepository.findById(created.get("id").asLong()).orElseThrow();
    }

    @Test
    void duplicatePropertyName_isRejectedByDatabase() throws Exception {
        Component component = componentWithEverything();

        ComponentProperty duplicate = new ComponentProperty();
        duplicate.setComponent(component);
        duplicate.setName("Уставка");
        duplicate.setPropertyType("Тег");
        duplicate.setValueType("int");
        duplicate.setLogging(false);

        assertThatThrownBy(() -> propertyRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateScriptName_isRejectedByDatabase() throws Exception {
        Component component = componentWithEverything();

        Script duplicate = Script.builder()
                .component(component)
                .name("Пуск")
                .script("b()")
                .build();

        assertThatThrownBy(() -> scriptRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateStateName_isRejectedByDatabase() throws Exception {
        Component component = componentWithEverything();

        ComponentState duplicate = ComponentState.builder()
                .component(component)
                .name("Открыт")
                .isDefault(false)
                .build();

        assertThatThrownBy(() -> stateRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
