package com.example.editor.service.automation;

import com.example.editor.dto.automation.AutomationIoDto;
import com.example.editor.dto.automation.AutomationTaskDto;
import com.example.editor.dto.automation.AutomationVariableDto;
import com.example.editor.exception.AutomationValidationException;
import com.example.scriptcore.ScriptSyntaxChecker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationSetValidatorTest {

    private static ScriptSyntaxChecker checker;

    @BeforeAll
    static void startChecker() {
        checker = new ScriptSyntaxChecker();
    }

    @AfterAll
    static void stopChecker() {
        checker.close();
    }

    /** Две задачи, пишущие одну переменную, перетягивали бы её значение. */
    @Test
    void rejectsSecondWriterOfVariable() {
        AutomationSetValidator validator = new AutomationSetValidator(checker);
        List<AutomationVariableDto> variables = List.of(new AutomationVariableDto("mode", "int", null, null));
        List<AutomationTaskDto> tasks = List.of(
                task("A", List.of(), List.of("mode")),
                task("B", List.of(), List.of("mode")));

        AutomationValidationException ex = assertThrows(AutomationValidationException.class,
                () -> validator.validate(tasks, variables, null));

        assertTrue(ex.getErrors().stream()
                .anyMatch(e -> "B".equals(e.task()) && "writes_variables".equals(e.field())));
    }

    /** Два регулятора на один насос хуже, чем ни одного. */
    @Test
    void rejectsSameOutputTagInTwoTasks() {
        AutomationSetValidator validator = new AutomationSetValidator(checker);
        AutomationIoDto pump = new AutomationIoDto("U", "Барановичи-1.BN1_MCA1.M_V.LINE1M1.V", "float");
        List<AutomationTaskDto> tasks = List.of(
                task("A", List.of(pump), List.of()),
                task("B", List.of(pump), List.of()));

        AutomationValidationException ex = assertThrows(AutomationValidationException.class,
                () -> validator.validate(tasks, List.of(), null));

        assertTrue(ex.getErrors().stream()
                .anyMatch(e -> "B".equals(e.task()) && "outputs".equals(e.field())));
    }

    private static AutomationTaskDto task(String name, List<AutomationIoDto> outputs, List<String> writes) {
        return new AutomationTaskDto(null, name, true, 1000, 100, 5000, false,
                List.of(), outputs, writes, "return;");
    }
}
