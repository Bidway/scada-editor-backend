package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.client.dto.EditorRecipeStepActionDto;
import com.example.runtime.client.dto.EditorRecipeStepDto;
import com.example.runtime.client.dto.EditorRecipeTagDto;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.ProjectTagChangedEvent;
import com.example.runtime.persistence.ProcedureStateEntity;
import com.example.runtime.persistence.ProcedureStateRepository;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Движок исполнения процедурных рецептов. Ключ — (projectId, recipeId): несколько процедур
 * одновременно в одном проекте (разные линии), но один рецепт в проекте — только один.
 * Состояние держится в памяти и дублируется в {@code runtime.procedure_state}, поэтому
 * мойка переживает и уход оператора, и перезапуск сервиса.
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
    private final ProjectRuntimeStore projectStore;
    private final ProcedureStateRepository stateRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
    public ProcedureStatusDto start(Long projectId, String recipeId, String sessionId, String username) {
        ProjectRuntime project = requireProject(projectId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ExecutionKey key = new ExecutionKey(projectId, recipeId);

        ProcedureExecution running = executions.get(key);
        if (running != null && !running.completed()) {
            synchronized (running) {
                throw new ProcedureAlreadyRunningException(projectId, recipeId, toStatus(recipe, running));
            }
        }

        ProcedureExecution execution = new ProcedureExecution(recipeId);
        // Лок берётся до публикации в executions: иначе в окне между put и началом обработки
        // другой поток мог бы подхватить наполовину инициализированное исполнение.
        synchronized (execution) {
            executions.put(key, execution);
            log.info("Проект {}: процедура {} запущена пользователем {}", projectId, recipeId, username);
            Initiator initiator = new Initiator(username, sessionId);
            enterStep(project, recipe, execution, 0, initiator);
            advanceWhileConditionMet(project, recipe, execution, initiator);
            return toStatus(recipe, execution);
        }
    }

    public ProcedureStatusDto status(Long projectId, String recipeId) {
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = requireExecution(projectId, recipeId);
        synchronized (execution) {
            return toStatus(recipe, execution);
        }
    }

    public ProcedureStatusDto confirm(Long projectId, String recipeId, Integer expectedStepIndex,
                                      String sessionId, String username) {
        ProjectRuntime project = requireProject(projectId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        ProcedureExecution execution = requireExecution(projectId, recipeId);
        synchronized (execution) {
            // stepIndex необязателен. Если фронт его прислал — это шаг, который оператор видел
            // на экране; несовпадение означает, что шаг успели закрыть, и подтверждать нужно
            // уже другой. Молча закрыть следующий было бы подтверждением не того, что видели.
            if (expectedStepIndex != null && expectedStepIndex != execution.stepIndex()) {
                throw new ProcedureStepMismatchException(toStatus(recipe, execution));
            }
            execution.confirm();
            log.info("Проект {}: шаг {} процедуры {} подтверждён пользователем {}",
                    projectId, execution.stepIndex(), recipeId, username);
            save(projectId, recipe, execution, username);
            advanceWhileConditionMet(project, recipe, execution, new Initiator(username, sessionId));
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
    public ProcedureStatusDto jump(Long projectId, String recipeId, int stepIndex,
                                   String sessionId, String username) {
        ProjectRuntime project = requireProject(projectId);
        EditorRecipeDto recipe = editorClient.getRecipe(recipeId);
        if (stepIndex < 0 || stepIndex >= recipe.getSteps().size()) {
            throw new IllegalArgumentException("Step index out of range: " + stepIndex);
        }
        ProcedureExecution execution = executions.computeIfAbsent(
                new ExecutionKey(projectId, recipeId), k -> new ProcedureExecution(recipeId));
        synchronized (execution) {
            log.info("Проект {}: процедура {} переведена на шаг {} пользователем {}",
                    projectId, recipeId, stepIndex, username);
            enterStep(project, recipe, execution, stepIndex, accumulatedActions(recipe, stepIndex),
                    new Initiator(username, sessionId));
            return toStatus(recipe, execution);
        }
    }

    /**
     * Состояние тегов, в котором процедура стоит на шаге {@code index}: действия шагов 0..index,
     * по каждому тегу — последнее значение. Шаг рецепта пишет только то, что меняется
     * относительно предыдущего шага, поэтому при прыжке одних действий целевого шага мало —
     * клапаны остались бы в положении того шага, откуда прыгнули. Порядок — по последнему
     * присваиванию, так что действия самого целевого шага уходят последними.
     */
    private List<EditorRecipeStepActionDto> accumulatedActions(EditorRecipeDto recipe, int index) {
        Map<String, EditorRecipeStepActionDto> state = new LinkedHashMap<>();
        for (int i = 0; i <= index; i++) {
            List<EditorRecipeStepActionDto> actions = recipe.getSteps().get(i).getAction();
            if (actions == null) {
                continue;
            }
            for (EditorRecipeStepActionDto action : actions) {
                state.remove(action.getTag());
                state.put(action.getTag(), action);
            }
        }
        return new ArrayList<>(state.values());
    }

    public void abort(Long projectId, String recipeId, String sessionId, String username) {
        // Сначала база, потом память: если удаление из базы упадёт, процедура останется живой и
        // видимой, а не исчезнет из памяти, чтобы молча воскреснуть после перезапуска.
        stateRepository.deleteByProjectIdAndRecipeId(projectId, recipeId);
        if (executions.remove(new ExecutionKey(projectId, recipeId)) != null) {
            ProjectRuntime project = projectStore.get(projectId);
            if (project != null) {
                publishEvent(project, recipeId, null, null, ProcedureEvent.Kind.ABORTED, null,
                        new Initiator(username, sessionId));
            }
            log.info("Проект {}: процедура {} прервана пользователем {}", projectId, recipeId, username);
        }
    }

    /**
     * Статусы незавершённых процедур проекта — для кадра {@code SNAPSHOT}. Открывший монитор
     * должен сразу увидеть, на каком шаге мойка, а не ждать следующего перехода: у долгого шага
     * он может наступить через десятки минут. Рецепт, который editor не отдал, пропускается —
     * снимок без одной процедуры лучше, чем отказ в подключении.
     */
    public List<ProcedureStatusDto> activeStatuses(Long projectId) {
        List<ProcedureStatusDto> result = new ArrayList<>();
        executions.forEach((key, execution) -> {
            if (!key.projectId().equals(projectId) || execution.completed()) {
                return;
            }
            try {
                EditorRecipeDto recipe = editorClient.getRecipe(key.recipeId());
                synchronized (execution) {
                    result.add(toStatus(recipe, execution));
                }
            } catch (Exception e) {
                log.warn("Проект {}: статус процедуры {} не попал в снимок: {}",
                        projectId, key.recipeId(), e.getMessage());
            }
        });
        return result;
    }

    /**
     * Поднимает незавершённые процедуры проекта из БД и продолжает их с сохранённого шага.
     * Действия шага не переприменяются — мойка уже в этом состоянии, повторная запись дёрнула
     * бы клапаны. Условие переоценивается на первом же тике.
     */
    public void restore(Long projectId) {
        for (ProcedureStateEntity saved : stateRepository.findByProjectIdIn(List.of(projectId))) {
            ProcedureExecution execution = ProcedureExecution.restored(saved.getRecipeId(),
                    saved.getStepIndex(), saved.getStepEnteredAt(), saved.isConfirmed());
            executions.put(new ExecutionKey(projectId, saved.getRecipeId()), execution);
            log.info("Проект {}: восстановлена процедура {} на шаге {}",
                    projectId, saved.getRecipeId(), saved.getStepIndex());
        }
    }

    /** Записать состояние всех процедур проекта — при выводе проекта из эксплуатации. */
    public void persistAll(Long projectId) {
        executions.forEach((key, execution) -> {
            if (!key.projectId().equals(projectId) || execution.completed()) {
                return;
            }
            synchronized (execution) {
                EditorRecipeDto recipe = editorClient.getRecipe(key.recipeId());
                save(projectId, recipe, execution, null);
                log.warn("Проект {} выводится из эксплуатации с незавершённой процедурой {} на шаге {}",
                        projectId, key.recipeId(), execution.stepIndex());
            }
        });
    }

    /** Только для тестов: имитация перезапуска сервиса — память чистая, БД нетронута. */
    void forgetInMemory(Long projectId) {
        executions.keySet().removeIf(key -> key.projectId().equals(projectId));
    }

    /**
     * Снимок состояния процедуры в БД. Пишется на каждом переходе шага: у «Дезинфекции» это
     * 28 шагов за 36 минут, то есть единицы записей в секунду в пике — нормальная частота.
     */
    private void save(Long projectId, EditorRecipeDto recipe, ProcedureExecution execution, String startedBy) {
        ProcedureStateEntity row = stateRepository
                .findByProjectIdAndRecipeId(projectId, execution.recipeId())
                .orElseGet(() -> {
                    ProcedureStateEntity created = new ProcedureStateEntity();
                    created.setProjectId(projectId);
                    created.setRecipeId(execution.recipeId());
                    created.setStartedAt(java.time.Instant.now());
                    // Только при создании строки: на переходах шагов автор запуска не меняется,
                    // иначе «кто запустил» превратилось бы в «кто нажал последним».
                    created.setStartedBy(startedBy);
                    return created;
                });
        row.setStepIndex(execution.stepIndex());
        row.setStepEnteredAt(execution.stepStartedAt());
        row.setConfirmed(execution.confirmed());
        row.setAccumulatedActions(objectMapper.valueToTree(accumulatedActions(recipe, execution.stepIndex())));
        stateRepository.save(row);
    }

    /**
     * Вызывается по {@link ProjectTagChangedEvent} на треде Kafka-consumer'а. Событие проектное,
     * а не сессионное: условия процедуры обязаны пересчитываться и тогда, когда на проект никто
     * не смотрит. Сам обработчик (сетевой вызов editor + GraalVM) уводится в
     * {@link #onTagChangeExecutor} — см. комментарий у поля.
     */
    @EventListener
    public void onProjectTagChanged(ProjectTagChangedEvent event) {
        onTagChangeExecutor.submit(() -> handleProjectTagChanged(event));
    }

    /** Пересчитывает все активные процедуры проекта. Выполняется на {@link #onTagChangeExecutor}. */
    private void handleProjectTagChanged(ProjectTagChangedEvent event) {
        try {
            ProjectRuntime project = projectStore.get(event.projectId());
            if (project == null) {
                return;
            }
            for (Map.Entry<ExecutionKey, ProcedureExecution> entry : executions.entrySet()) {
                if (!entry.getKey().projectId().equals(event.projectId()) || entry.getValue().completed()) {
                    continue;
                }
                EditorRecipeDto recipe = editorClient.getRecipe(entry.getKey().recipeId());
                synchronized (entry.getValue()) {
                    advanceWhileConditionMet(project, recipe, entry.getValue(), Initiator.RUNTIME);
                }
            }
        } catch (Exception e) {
            // Runnable в ExecutorService.submit(...) — Future никто не читает, без этого
            // сбой обработки события молча терялся бы.
            log.warn("Не удалось пересчитать процедуры проекта {}: {}", event.projectId(), e.getMessage());
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
                ProjectRuntime project = projectStore.get(entry.getKey().projectId());
                if (project == null) {
                    continue;
                }
                EditorRecipeDto recipe = editorClient.getRecipe(entry.getKey().recipeId());
                synchronized (execution) {
                    advanceWhileConditionMet(project, recipe, execution, Initiator.RUNTIME);
                    checkStalled(project, recipe, entry.getKey().recipeId(), execution);
                }
            }
        } catch (Exception e) {
            log.warn("Procedure tick failed: {}", e.getMessage());
        }
    }

    private void checkStalled(ProjectRuntime project, EditorRecipeDto recipe, String recipeId, ProcedureExecution execution) {
        if (execution.completed() || execution.stalledNotified()) {
            return;
        }
        Long timeoutMs = recipe.getSteps().get(execution.stepIndex()).getTimeout_ms();
        if (timeoutMs != null && execution.elapsedMs() >= timeoutMs) {
            execution.markStalledNotified();
            String stepName = recipe.getSteps().get(execution.stepIndex()).getName();
            // Без наблюдателей WS-кадр некому доставить, поэтому «завис» обязан быть и в логе.
            log.warn("Проект {}: шаг {} процедуры {} завис — условие не выполнилось за timeout_ms",
                    project.getProjectId(), stepName, recipeId);
            publishEvent(project, recipeId, execution.stepIndex(), stepName,
                    ProcedureEvent.Kind.STALLED, null, Initiator.RUNTIME);
        }
    }

    private ProcedureExecution requireExecution(Long projectId, String recipeId) {
        ProcedureExecution execution = executions.get(new ExecutionKey(projectId, recipeId));
        if (execution == null) {
            throw new IllegalStateException("В проекте " + projectId + " нет активной процедуры "
                    + recipeId + " — сначала запустите её");
        }
        return execution;
    }

    /**
     * Проект должен быть в эксплуатации: пока флаг не выставлен, у runtime нет ни его тегов,
     * ни состояния свойств, и условия шага читали бы null.
     */
    private ProjectRuntime requireProject(Long projectId) {
        ProjectRuntime project = projectStore.get(projectId);
        if (project == null) {
            throw new ProjectNotInOperationException(projectId);
        }
        return project;
    }

    private void advanceWhileConditionMet(ProjectRuntime project, EditorRecipeDto recipe, ProcedureExecution execution,
                                          Initiator initiator) {
        List<EditorRecipeStepDto> steps = recipe.getSteps();
        while (!execution.completed()
                && evaluateCondition(project, steps.get(execution.stepIndex()), execution.elapsedMs(), execution.confirmed())) {
            EditorRecipeStepDto finishedStep = steps.get(execution.stepIndex());
            log.info("Проект {}: шаг {} процедуры {} завершён",
                    project.getProjectId(), finishedStep.getName(), execution.recipeId());
            publishEvent(project, execution.recipeId(), execution.stepIndex(), finishedStep.getName(),
                    ProcedureEvent.Kind.STEP_COMPLETED, null, initiator);
            int next = execution.stepIndex() + 1;
            if (next >= steps.size()) {
                execution.markCompleted();
                stateRepository.deleteByProjectIdAndRecipeId(project.getProjectId(), execution.recipeId());
                log.info("Проект {}: процедура {} выполнена целиком", project.getProjectId(), execution.recipeId());
                publishEvent(project, execution.recipeId(), null, null, ProcedureEvent.Kind.COMPLETED, null, initiator);
                return;
            }
            enterStep(project, recipe, execution, next, initiator);
        }
    }

    private void enterStep(ProjectRuntime project, EditorRecipeDto recipe, ProcedureExecution execution, int index,
                           Initiator initiator) {
        enterStep(project, recipe, execution, index, recipe.getSteps().get(index).getAction(), initiator);
    }

    private void enterStep(ProjectRuntime project, EditorRecipeDto recipe, ProcedureExecution execution, int index,
                           List<EditorRecipeStepActionDto> actions, Initiator initiator) {
        execution.enterStep(index);
        EditorRecipeStepDto step = recipe.getSteps().get(index);
        applyAction(project, recipe, execution.recipeId(), step, actions, initiator);
        // Состояние пишется после применения действий: восстановление должно поднимать шаг,
        // который реально применён в ПЛК, а не тот, куда мы только собирались войти.
        save(project.getProjectId(), recipe, execution, initiator.by());
        log.info("Проект {}: процедура {} вошла в шаг {}",
                project.getProjectId(), execution.recipeId(), step.getName());
        publishEvent(project, execution.recipeId(), index, step.getName(), ProcedureEvent.Kind.STEP_STARTED, null, initiator);
    }

    private void applyAction(ProjectRuntime project, EditorRecipeDto recipe, String recipeId, EditorRecipeStepDto step,
                             List<EditorRecipeStepActionDto> actions, Initiator initiator) {
        if (actions == null) {
            return;
        }
        for (EditorRecipeStepActionDto entry : actions) {
            String path = tagPath(recipe, entry.getTag());
            if (path == null) {
                log.warn("Recipe {}: step '{}' action references unknown tag '{}'",
                        recipeId, step.getName(), entry.getTag());
                continue;
            }
            String idNode = project.getIndex().resolveTagPath(path);
            commandProducer.send(idNode, entry.getValue()).thenAccept(outcome -> {
                if (!outcome.applied()) {
                    // Без наблюдателей отказ шлюза некому показать — значит, он обязан быть в логе.
                    log.warn("Проект {}: запись тега '{}' на шаге '{}' не применена: {}",
                            project.getProjectId(), entry.getTag(), step.getName(), outcome.message());
                    publishEvent(project, recipeId, null, step.getName(), ProcedureEvent.Kind.WRITE_FAILED,
                            "Тег '" + entry.getTag() + "': " + outcome.message(), initiator);
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

    private boolean evaluateCondition(ProjectRuntime project, EditorRecipeStepDto step, long elapsedMs, boolean confirmed) {
        try {
            return scriptEngineService.runCondition(step.getCondition_script(), elapsedMs, confirmed,
                    path -> TagValueRouter.coerceTagValue(
                            tagValueRouter.lastValue(project.getIndex().resolveTagPath(path))),
                    (componentName, propertyName) -> readProjectProperty(project, step, componentName, propertyName),
                    project.getProjectData());
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
    private Object readProjectProperty(ProjectRuntime project, EditorRecipeStepDto step,
                                       String componentName, String propertyName) {
        List<Long> ids = project.getIndex().propertyIdsByComponentName(componentName, propertyName);
        if (ids.size() != 1) {
            log.debug("Recipe step '{}': readProjectProperty('{}', '{}') — {}", step.getName(), componentName,
                    propertyName, ids.isEmpty() ? "no such property" : "ambiguous across " + ids.size() + " components");
            return null;
        }
        Object value = project.getPropertyValues().get(ids.get(0));
        return value instanceof String s ? TagValueRouter.coerceTagValue(s) : value;
    }

    /**
     * Событие уходит всем наблюдателям проекта. Их может не быть вовсе — тогда доставлять
     * некому, и единственным следом остаётся лог: копить события «на будущее» нельзя, мойка,
     * набивающая очередь сутками, — это утечка.
     */
    private void publishEvent(ProjectRuntime project, String recipeId, Integer stepIndex, String stepName,
                              ProcedureEvent.Kind kind, String message, Initiator initiator) {
        ProcedureEvent event = new ProcedureEvent(recipeId, stepIndex, stepName, kind, message,
                initiator.by(), initiator.sessionId());
        for (RuntimeSession session : project.sessions()) {
            session.getOutboundBuffer().offerProcedureEvent(event);
        }
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

    /**
     * Кто вызвал переход: имя из {@code X-Username} и экран, с которого нажали. Переходы,
     * случившиеся по условию шага внутри цепочки после нажатия, приписываются нажавшему —
     * это следствие его действия. {@link #RUNTIME} — переходы без участия человека.
     */
    private record Initiator(String by, String sessionId) {
        static final Initiator RUNTIME = new Initiator(null, null);
    }
}
