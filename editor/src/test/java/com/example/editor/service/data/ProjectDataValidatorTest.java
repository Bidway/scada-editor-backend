package com.example.editor.service.data;

import com.example.editor.dto.data.ProjectDataColumnDto;
import com.example.editor.dto.data.ProjectDataRowDto;
import com.example.editor.dto.data.ProjectDataTableDto;
import com.example.editor.exception.ProjectDataValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDataValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectDataValidator validator = new ProjectDataValidator(mapper);

    /** Все нарушения — одним списком: неверный тип и повтор ключа видны за одно сохранение. */
    @Test
    void collectsTypeMismatchAndDuplicateKey() throws Exception {
        ProjectDataTableDto table = new ProjectDataTableDto("solutions", "Растворы", null,
                List.of(new ProjectDataColumnDto("density", null, "float", true, null)),
                List.of(row("ALK", "{\"density\": \"плотная\"}"), row("ALK", "{\"density\": 1.2}")));

        ProjectDataValidationException ex = assertThrows(ProjectDataValidationException.class,
                () -> validator.validate(List.of(table)));

        assertTrue(ex.getErrors().stream()
                .anyMatch(e -> "rows.values".equals(e.field()) && e.message().contains("density")));
        assertTrue(ex.getErrors().stream()
                .anyMatch(e -> "solutions".equals(e.table()) && "rows.key".equals(e.field())));
    }

    /** Набор держится в памяти каждой сессии runtime и каждого проекта automation. */
    @Test
    void rejectsSetLargerThanLimit() {
        ProjectDataTableDto table = new ProjectDataTableDto("notes", null, null,
                List.of(new ProjectDataColumnDto("text", null, "string", false, null)),
                List.of(new ProjectDataRowDto("one",
                        Map.of("text", TextNode.valueOf("x".repeat(ProjectDataValidator.MAX_SET_BYTES))))));

        ProjectDataValidationException ex = assertThrows(ProjectDataValidationException.class,
                () -> validator.validate(List.of(table)));

        assertTrue(ex.getErrors().stream().anyMatch(e -> "tables".equals(e.field())));
    }

    private ProjectDataRowDto row(String key, String valuesJson) throws Exception {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        mapper.readTree(valuesJson).fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
        return new ProjectDataRowDto(key, values);
    }
}
