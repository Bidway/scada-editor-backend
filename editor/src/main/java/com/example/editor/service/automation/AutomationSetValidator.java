package com.example.editor.service.automation;

import com.example.editor.dto.automation.AutomationIoDto;
import com.example.editor.dto.automation.AutomationTaskDto;
import com.example.editor.dto.automation.AutomationVariableDto;
import com.example.editor.dto.automation.AutomationWatchdogDto;
import com.example.editor.exception.AutomationValidationError;
import com.example.editor.exception.AutomationValidationException;
import com.example.scriptcore.ScriptSyntaxChecker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.example.editor.service.automation.AutomationRules.checkIo;
import static com.example.editor.service.automation.AutomationRules.checkRange;
import static com.example.editor.service.automation.AutomationRules.checkTiming;
import static com.example.editor.service.automation.AutomationRules.error;
import static com.example.editor.service.automation.AutomationRules.isValueType;

/**
 * Проверка набора автоматизации проекта перед сохранением. Собирает все нарушения, а не падает
 * на первом: инженер правит форму один раз, а не по кругу.
 * <p>
 * Существование пути тега не проверяется: к {@code channel} {@code editor} не обращается.
 * Молчащий вход виден в статусе задачи в {@code automation} как {@code INPUT_STALE}.
 */
@Component
public class AutomationSetValidator {

    public static final String VARIABLE_TAG_PREFIX = "@var.";

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private final ScriptSyntaxChecker syntaxChecker;

    public AutomationSetValidator(ScriptSyntaxChecker syntaxChecker) {
        this.syntaxChecker = syntaxChecker;
    }

    public void validate(List<AutomationTaskDto> tasks, List<AutomationVariableDto> variables,
                         AutomationWatchdogDto watchdog) {
        List<AutomationValidationError> errors = new ArrayList<>();

        Set<String> variableNames = new HashSet<>();
        for (AutomationVariableDto variable : variables) {
            String name = variable.name();
            if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
                errors.add(error(null, "variables.name", "Имя переменной '" + name + "' недопустимо"));
            } else if (!variableNames.add(name)) {
                errors.add(error(null, "variables.name", "Переменная '" + name + "' объявлена дважды"));
            }
            if (!isValueType(variable.valueType())) {
                errors.add(error(null, "variables.value_type",
                        "Переменная '" + name + "': value_type должен быть одним из "
                                + AutomationRules.VALUE_TYPES));
            }
        }

        Set<String> taskNames = new HashSet<>();
        Map<String, String> variableWriter = new HashMap<>();
        Map<String, String> outputOwner = new HashMap<>();
        for (AutomationTaskDto task : tasks) {
            String name = task.name();
            if (name == null || name.isBlank()) {
                errors.add(error(null, "name", "У задачи нет имени"));
            } else if (!taskNames.add(name)) {
                errors.add(error(name, "name", "Задача с таким именем уже есть"));
            }
            checkTiming(errors, name, task.periodMs(), task.staleAfterMs(), task.timeoutMs());
            checkTaskIo(errors, name, "inputs", task.inputs());
            checkTaskIo(errors, name, "outputs", task.outputs());

            for (AutomationIoDto output : task.outputs()) {
                if (output.tag() == null) {
                    continue;
                }
                String owner = outputOwner.putIfAbsent(output.tag(), name);
                if (owner != null && !owner.equals(name)) {
                    errors.add(error(name, "outputs", "Тег " + output.tag() + " уже пишет задача '" + owner + "'"));
                }
            }
            for (String variable : task.writesVariables()) {
                if (!variableNames.contains(variable)) {
                    errors.add(error(name, "writes_variables", "Переменная '" + variable + "' не объявлена"));
                }
                String writer = variableWriter.putIfAbsent(variable, name);
                if (writer != null && !writer.equals(name)) {
                    errors.add(error(name, "writes_variables",
                            "Переменную '" + variable + "' уже пишет задача '" + writer + "'"));
                }
            }
            syntaxChecker.check(task.script()).ifPresent(syntax -> errors.add(error(name, "script",
                    (syntax.line() == null ? "" : "строка " + syntax.line() + ": ") + syntax.message())));
        }

        // Граница с данными проекта: значение без писателя меняет только человек в редакторе,
        // и живёт оно в таблицах данных, а не в переменных automation.
        for (String variable : variableNames) {
            if (!variableWriter.containsKey(variable)) {
                errors.add(error(null, "variables.name", "Переменная '" + variable
                        + "': у переменной нет задачи-писателя — константы проекта храните в таблицах данных"));
            }
        }

        if (watchdog != null) {
            if (watchdog.tag() == null || watchdog.tag().isBlank()) {
                errors.add(error(null, "watchdog.tag", "Не задан тег сторожевого таймера"));
            } else if (outputOwner.containsKey(watchdog.tag())) {
                errors.add(error(outputOwner.get(watchdog.tag()), "watchdog.tag",
                        "Тег сторожевого таймера уже пишет задача"));
            }
            checkRange(errors, null, "watchdog.period_ms", watchdog.periodMs(), 100, 60_000);
        }

        if (!errors.isEmpty()) {
            throw new AutomationValidationException(errors);
        }
    }

    /** Вход или выход задачи: общая часть — в {@link AutomationRules}, тег проверяется только здесь. */
    private void checkTaskIo(List<AutomationValidationError> errors, String task, String field,
                             List<AutomationIoDto> items) {
        Set<String> aliases = new HashSet<>();
        for (AutomationIoDto io : items) {
            checkIo(errors, task, field, io.alias(), io.valueType(), aliases);
            if (io.tag() == null || io.tag().isBlank()) {
                errors.add(error(task, field, "У '" + io.alias() + "' не задан тег"));
            } else if (io.tag().startsWith(VARIABLE_TAG_PREFIX)) {
                // Переменные читаются через vars.<имя> и пишутся через writes_variables/setVar.
                errors.add(error(task, field, "'" + io.alias() + "': переменная проекта не может быть входом "
                        + "или выходом — используйте vars и writes_variables"));
            }
        }
    }

}
