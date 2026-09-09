package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.client.dto.EditorRecipeStepActionDto;
import com.example.runtime.client.dto.EditorRecipeStepDto;
import com.example.runtime.client.dto.EditorRecipeTagDto;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.stream.ProcedureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Движок исполнения процедурных рецептов. Состояние — только в памяти, по ключу
 * (sessionId, recipeId): несколько процедур одновременно в одной сессии (разные
 * линии/таблицы). У runtime своей БД нет — при перезапуске процесса состояние
 * теряется, восстановление — через {@code resumeGuess} (Задача 10), а не через этот кеш.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcedureExecutionService {

    private final EditorClient editorClient;
    private final CommandProducer commandProducer;
    private final TagValueRouter tagValueRouter;
    private final ScriptEngineService scriptEngineService;
    private final RuntimeSessionStore sessionStore;

    private final Map<ExecutionKey, ProcedureExecution> executions = new ConcurrentHashMap<>();

    public ProcedureStatusDto start(String sessionId, String recipeId) {
        RuntimeSession session = requireSession(sessionId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = new ProcedureExecution(recipeId);
        executions.put(new ExecutionKey(sessionId, recipeId), execution);
        enterStep(session, recipe, execution, 0);
        advanceWhileConditionMet(session, recipe, execution);
        return toStatus(recipe, execution);
    }

    private void advanceWhileConditionMet(RuntimeSession session, EditorRecipeDto recipe, ProcedureExecution execution) {
        List<EditorRecipeStepDto> steps = recipe.getSteps();
        while (!execution.completed()
                && evaluateCondition(session, steps.get(execution.stepIndex()), execution.elapsedMs(), execution.confirmed())) {
            EditorRecipeStepDto finishedStep = steps.get(execution.stepIndex());
            publishEvent(session, execution.recipeId(), execution.stepIndex(), finishedStep.getName(),
                    ProcedureEvent.Kind.STEP_COMPLETED, null);
            int next = execution.stepIndex() + 1;
            if (next >= steps.size()) {
                execution.markCompleted();
                publishEvent(session, execution.recipeId(), null, null, ProcedureEvent.Kind.COMPLETED, null);
                return;
            }
            enterStep(session, recipe, execution, next);
        }
    }

    private void enterStep(RuntimeSession session, EditorRecipeDto recipe, ProcedureExecution execution, int index) {
        execution.enterStep(index);
        EditorRecipeStepDto step = recipe.getSteps().get(index);
        applyAction(session, recipe, execution.recipeId(), step);
        publishEvent(session, execution.recipeId(), index, step.getName(), ProcedureEvent.Kind.STEP_STARTED, null);
    }

    private void applyAction(RuntimeSession session, EditorRecipeDto recipe, String recipeId, EditorRecipeStepDto step) {
        if (step.getAction() == null) {
            return;
        }
        for (EditorRecipeStepActionDto entry : step.getAction()) {
            String path = tagPath(recipe, entry.getTag());
            if (path == null) {
                log.warn("Recipe {}: step '{}' action references unknown tag '{}'",
                        recipeId, step.getName(), entry.getTag());
                continue;
            }
            String idNode = session.getIndex().resolveTagPath(path);
            commandProducer.send(idNode, entry.getValue()).thenAccept(outcome -> {
                if (!outcome.applied()) {
                    publishEvent(session, recipeId, null, step.getName(), ProcedureEvent.Kind.WRITE_FAILED,
                            "Тег '" + entry.getTag() + "': " + outcome.message());
                }
            });
        }
    }

    private String tagPath(EditorRecipeDto recipe, String shortName) {
        for (EditorRecipeTagDto tag : recipe.getTags()) {
            if (tag.getName().equals(shortName)) {
                return tag.getTag();
            }
        }
        return null;
    }

    private boolean evaluateCondition(RuntimeSession session, EditorRecipeStepDto step, long elapsedMs, boolean confirmed) {
        try {
            return scriptEngineService.runCondition(step.getCondition_script(), elapsedMs, confirmed,
                    path -> TagValueRouter.coerceTagValue(
                            tagValueRouter.lastValue(session.getIndex().resolveTagPath(path))));
        } catch (Exception e) {
            log.warn("Recipe step '{}' condition_script failed: {}", step.getName(), e.getMessage());
            return false;
        }
    }

    private void publishEvent(RuntimeSession session, String recipeId, Integer stepIndex, String stepName,
                              ProcedureEvent.Kind kind, String message) {
        session.getOutboundBuffer().offerProcedureEvent(new ProcedureEvent(recipeId, stepIndex, stepName, kind, message));
    }

    // Отклонение от буквального текста брифа: там toStatus(execution) отдаёт stepName = null
    // с комментарием "заполняется в Задаче 10". Но заданный тест (Step 1 брифа) требует
    // status.stepName() == "Ждать подтверждения", а recipe уже есть в области видимости
    // вызова start() — поэтому имя шага резолвится здесь же, без методов status()/confirm()/
    // jump() из Задачи 10. См. отчёт, раздел "Сомнения".
    private ProcedureStatusDto toStatus(EditorRecipeDto recipe, ProcedureExecution execution) {
        String stepName = recipe.getSteps().get(execution.stepIndex()).getName();
        return new ProcedureStatusDto(execution.recipeId(), execution.stepIndex(), stepName,
                execution.elapsedMs(), execution.confirmed(), execution.completed(), execution.stalledNotified());
    }

    private RuntimeSession requireSession(String sessionId) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Session not found: " + sessionId);
        }
        return session;
    }
}
