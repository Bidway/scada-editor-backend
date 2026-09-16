package com.example.editor.service.automation;

import com.example.editor.dto.automation.AutomationTaskTemplateDto;
import com.example.editor.dto.automation.AutomationTemplateIoDto;
import com.example.editor.exception.AutomationValidationError;
import com.example.editor.exception.AutomationValidationException;
import com.example.editor.repository.automation.AutomationTaskTemplateRepository;
import com.example.scriptcore.ScriptSyntaxChecker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.example.editor.service.automation.AutomationRules.checkIo;
import static com.example.editor.service.automation.AutomationRules.checkTiming;
import static com.example.editor.service.automation.AutomationRules.error;

/**
 * Проверка шаблона задачи перед сохранением. Правила те же, что у задачи в
 * {@link AutomationSetValidator} (общая часть — в {@link AutomationRules}), кроме тега: у шаблона
 * его нет, а {@code example_tag} — подсказка и не проверяется.
 * <p>
 * Нарушения собираются все сразу и уезжают тем же {@link AutomationValidationException}: фронт
 * разбирает ответ {@code automation_invalid} одним кодом.
 */
@Component
public class AutomationTaskTemplateValidator {

    private final AutomationTaskTemplateRepository repository;
    private final ScriptSyntaxChecker syntaxChecker;

    public AutomationTaskTemplateValidator(AutomationTaskTemplateRepository repository,
                                           ScriptSyntaxChecker syntaxChecker) {
        this.repository = repository;
        this.syntaxChecker = syntaxChecker;
    }

    /**
     * @param selfId id сохраняемого шаблона; {@code null} при создании. Нужен, чтобы шаблон не
     *               спорил об имени сам с собой при {@code PUT} без переименования
     */
    public void validate(AutomationTaskTemplateDto template, Long selfId) {
        List<AutomationValidationError> errors = new ArrayList<>();

        String name = template.name();
        if (name == null || name.isBlank()) {
            errors.add(error(null, "name", "У шаблона нет имени"));
        } else {
            repository.findByName(name)
                    .filter(other -> !Objects.equals(other.getId(), selfId))
                    .ifPresent(other -> errors.add(error(name, "name", "Шаблон с таким именем уже есть")));
        }
        checkTiming(errors, name, template.periodMs(), template.staleAfterMs(), template.timeoutMs());
        checkTemplateIo(errors, name, "inputs", template.inputs());
        checkTemplateIo(errors, name, "outputs", template.outputs());
        syntaxChecker.check(template.script()).ifPresent(syntax -> errors.add(error(name, "script",
                (syntax.line() == null ? "" : "строка " + syntax.line() + ": ") + syntax.message())));

        if (!errors.isEmpty()) {
            throw new AutomationValidationException(errors);
        }
    }

    private void checkTemplateIo(List<AutomationValidationError> errors, String template, String field,
                                 List<AutomationTemplateIoDto> items) {
        Set<String> aliases = new HashSet<>();
        for (AutomationTemplateIoDto io : items) {
            checkIo(errors, template, field, io.alias(), io.valueType(), aliases);
        }
    }
}
