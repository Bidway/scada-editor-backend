package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.client.dto.EditorRecipeStepActionDto;
import com.example.runtime.client.dto.EditorRecipeStepDto;
import com.example.runtime.client.dto.EditorRecipeTagDto;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.SessionTagChangedEvent;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.stream.ProcedureEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // Тот же приём, что OnChangeDispatcher в TagValueRouter: onSessionTagChanged приходит
    // синхронно с треда kafka-tags-consumer (Spring ApplicationEventPublisher по умолчанию
    // синхронный), а тело обработчика делает блокирующий HTTP-вызов editorClient.getRecipe(...)
    // и прогоняет GraalVM-условие. Без выноса на отдельный пул просевший/недоступный editor
    // останавливал бы приём телеметрии для всех сессий на этом треде/партиции — не только для
    // сессий с активными процедурами. final-поле со своим инициализатором, а не параметр
    // конструктора: @RequiredArgsConstructor его не тронет (как уже сделано для `executions`),
    // и не придётся менять уже закрытый в Задаче 9 тестовый конструктор.
    private final ExecutorService onTagChangeExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "procedure-tag-change");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        onTagChangeExecutor.shutdownNow();
    }

    // Лок берётся на сам объект исполнения (не на сервис): одно и то же ProcedureExecution
    // одновременно двигают несколько источников — планировщик (tick), до двух тредов
    // onTagChangeExecutor и HTTP-треды (start/confirm/jump/status). Поля ProcedureExecution
    // ничем не защищены, и без лока это гонка: один поток посчитал условие шага i истинным и
    // собирается продвинуться на i+1, а второй уже продвинул то же исполнение — в итоге
    // процедура проскакивает мимо непроверенного условия либо дважды применяет action
    // (повторная запись в ПЛК). Лок именно на экземпляр, чтобы разные процедуры и сессии
    // оставались независимыми и не блокировали друг друга.
    public ProcedureStatusDto start(String sessionId, String recipeId) {
        RuntimeSession session = requireSession(sessionId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = new ProcedureExecution(recipeId);
        // Лок берётся до публикации в executions: иначе в окне между put и началом обработки
        // другой поток мог бы подхватить наполовину инициализированное исполнение.
        synchronized (execution) {
            executions.put(new ExecutionKey(sessionId, recipeId), execution);
            enterStep(session, recipe, execution, 0);
            advanceWhileConditionMet(session, recipe, execution);
            return toStatus(recipe, execution);
        }
    }

    public ProcedureStatusDto status(String sessionId, String recipeId) {
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = requireExecution(sessionId, recipeId);
        synchronized (execution) {
            return toStatus(recipe, execution);
        }
    }

    public ProcedureStatusDto confirm(String sessionId, String recipeId) {
        RuntimeSession session = requireSession(sessionId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = requireExecution(sessionId, recipeId);
        synchronized (execution) {
            execution.confirm();
            advanceWhileConditionMet(session, recipe, execution);
            return toStatus(recipe, execution);
        }
    }

    // Отклонение от буквального текста брифа: там jump() после enterStep(...) сразу же
    // зовёт advanceWhileConditionMet(...), как это делает start(). Но у степа с тривиальным
    // (null) condition_script (см. twoStepRecipe() в тесте) это значит, что jump на такой
    // шаг немедленно откатывается обратно вперёд — оператор прыгнул на шаг 0, а получил
    // тот же шаг 1, с которого начал. Заданный тест (jump_reappliesStepActionAndRearms-
    // Condition) явно требует, чтобы после jump() индекс остался тем, куда прыгнули.
    // jump — ручной оверрайд оператора: он не должен молча "доездить" вперёд по цепочке
    // тривиальных условий у него на глазах. Переоценка условия шага, на который прыгнули,
    // всё равно случится — по следующему тику (tick()) или изменению тега
    // (onSessionTagChanged), так же, как для только что подтверждённого/начатого шага.
    public ProcedureStatusDto jump(String sessionId, String recipeId, int stepIndex) {
        RuntimeSession session = requireSession(sessionId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        if (stepIndex < 0 || stepIndex >= recipe.getSteps().size()) {
            throw new IllegalArgumentException("Step index out of range: " + stepIndex);
        }
        ProcedureExecution execution = executions.computeIfAbsent(
                new ExecutionKey(sessionId, recipeId), k -> new ProcedureExecution(recipeId));
        synchronized (execution) {
            enterStep(session, recipe, execution, stepIndex);
            return toStatus(recipe, execution);
        }
    }

    public void abort(String sessionId, String recipeId) {
        if (executions.remove(new ExecutionKey(sessionId, recipeId)) != null) {
            publishEvent(requireSession(sessionId), recipeId, null, null, ProcedureEvent.Kind.ABORTED, null);
        }
    }

    /** Ничего не меняет — только предлагает индекс шага, на котором, вероятно, остановилась процедура. */
    public int resumeGuess(String sessionId, String recipeId) {
        RuntimeSession session = requireSession(sessionId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        List<EditorRecipeStepDto> steps = recipe.getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (evaluateCondition(session, steps.get(i), 0L, false)) {
                return Math.min(i + 1, steps.size() - 1);
            }
        }
        return 0;
    }

    /**
     * Вызывается по {@link SessionTagChangedEvent} на треде Kafka-consumer'а. Сам обработчик
     * (сетевой вызов editor + GraalVM) уводится в {@link #onTagChangeExecutor} — см. комментарий
     * у поля.
     */
    @EventListener
    public void onSessionTagChanged(SessionTagChangedEvent event) {
        onTagChangeExecutor.submit(() -> handleSessionTagChanged(event));
    }

    /** Пересчитывает все активные процедуры сессии. Выполняется на {@link #onTagChangeExecutor}. */
    private void handleSessionTagChanged(SessionTagChangedEvent event) {
        try {
            RuntimeSession session = sessionStore.get(event.sessionId());
            if (session == null) {
                return;
            }
            for (Map.Entry<ExecutionKey, ProcedureExecution> entry : executions.entrySet()) {
                if (!entry.getKey().sessionId().equals(event.sessionId()) || entry.getValue().completed()) {
                    continue;
                }
                EditorRecipeDto recipe = editorClient.getRecipe(entry.getKey().recipeId());
                synchronized (entry.getValue()) {
                    advanceWhileConditionMet(session, recipe, entry.getValue());
                }
            }
        } catch (Exception e) {
            // Runnable в ExecutorService.submit(...) — Future никто не читает, без этого
            // сбой обработки события молча терялся бы.
            log.warn("Failed to handle tag change for session {}: {}", event.sessionId(), e.getMessage());
        }
    }

    /** Ловит условия на чистой задержке (не зависят от изменения тега) и зависшие шаги. */
    @Scheduled(fixedRateString = "${runtime.procedure.tick-interval-ms:1000}")
    void tick() {
        // Тело тика уводится на onTagChangeExecutor по той же причине, что и обработка события
        // изменения тега: spring.task.scheduling.pool.size не задан, значит у всех @Scheduled
        // runtime один общий тред, и на нём же сидит OutboundFlusher.flush() (WS-кадры всех
        // сессий каждые 40 мс). Блокирующий editorClient.getRecipe(...) и прогон GraalVM на
        // этом треде задерживали бы доставку WS всем сессиям, а не только тем, где есть процедуры.
        onTagChangeExecutor.submit(this::runTick);
    }

    /** Тело тика. Выполняется на {@link #onTagChangeExecutor}. */
    private void runTick() {
        try {
            for (Map.Entry<ExecutionKey, ProcedureExecution> entry : executions.entrySet()) {
                ProcedureExecution execution = entry.getValue();
                if (execution.completed()) {
                    continue;
                }
                RuntimeSession session = sessionStore.get(entry.getKey().sessionId());
                if (session == null) {
                    continue;
                }
                EditorRecipeDto recipe = editorClient.getRecipe(entry.getKey().recipeId());
                synchronized (execution) {
                    advanceWhileConditionMet(session, recipe, execution);
                    checkStalled(session, recipe, entry.getKey().recipeId(), execution);
                }
            }
        } catch (Exception e) {
            log.warn("Procedure tick failed: {}", e.getMessage());
        }
    }

    private void checkStalled(RuntimeSession session, EditorRecipeDto recipe, String recipeId, ProcedureExecution execution) {
        if (execution.completed() || execution.stalledNotified()) {
            return;
        }
        Long timeoutMs = recipe.getSteps().get(execution.stepIndex()).getTimeout_ms();
        if (timeoutMs != null && execution.elapsedMs() >= timeoutMs) {
            execution.markStalledNotified();
            publishEvent(session, recipeId, execution.stepIndex(), recipe.getSteps().get(execution.stepIndex()).getName(),
                    ProcedureEvent.Kind.STALLED, null);
        }
    }

    private ProcedureExecution requireExecution(String sessionId, String recipeId) {
        ProcedureExecution execution = executions.get(new ExecutionKey(sessionId, recipeId));
        if (execution == null) {
            throw new IllegalStateException("No active procedure " + recipeId + " for session " + sessionId
                    + " — call start first");
        }
        return execution;
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
                            tagValueRouter.lastValue(session.getIndex().resolveTagPath(path))),
                    (componentName, propertyName) -> readProjectProperty(session, step, componentName, propertyName));
        } catch (Exception e) {
            log.warn("Recipe step '{}' condition_script failed: {}", step.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Значение берётся из состояния сессии, а не из editor: его засевает {@code default_value}
     * при старте сессии, и его же меняют скрипты компонентов — условие видит то же, что оператор.
     * Неоднозначный адрес не разрешается «первым попавшимся»: условие шага решает, идти ли
     * процедуре дальше, и чужое одноимённое свойство здесь хуже, чем {@code null}. Лог — debug:
     * условие пересчитывается каждый тик, и warn на опечатке в адресе заливал бы лог.
     */
    private Object readProjectProperty(RuntimeSession session, EditorRecipeStepDto step,
                                       String componentName, String propertyName) {
        List<Long> ids = session.getIndex().propertyIdsByComponentName(componentName, propertyName);
        if (ids.size() != 1) {
            log.debug("Recipe step '{}': readProjectProperty('{}', '{}') — {}", step.getName(), componentName,
                    propertyName, ids.isEmpty() ? "no such property" : "ambiguous across " + ids.size() + " components");
            return null;
        }
        Object value = session.getPropertyValues().get(ids.get(0));
        return value instanceof String s ? TagValueRouter.coerceTagValue(s) : value;
    }

    private void publishEvent(RuntimeSession session, String recipeId, Integer stepIndex, String stepName,
                              ProcedureEvent.Kind kind, String message) {
        session.getOutboundBuffer().offerProcedureEvent(new ProcedureEvent(recipeId, stepIndex, stepName, kind, message));
    }

    // Отклонение от буквального текста брифа Задачи 9: там toStatus(execution) отдаёт
    // stepName = null с комментарием "заполняется в Задаче 10". Но заданный тест той
    // задачи требует status.stepName() == "Ждать подтверждения", а recipe уже есть в
    // области видимости вызова start() — поэтому имя шага резолвится здесь же. Задача 10
    // добавила только guard на null при завершённой процедуре (см. ниже).
    private ProcedureStatusDto toStatus(EditorRecipeDto recipe, ProcedureExecution execution) {
        String stepName = execution.completed() ? null : recipe.getSteps().get(execution.stepIndex()).getName();
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
