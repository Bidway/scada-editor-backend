package com.example.automation.engine;

import com.example.automation.definition.IoDefinition;
import com.example.automation.definition.TaskDefinition;
import com.example.scriptcore.DataFunction;
import com.example.scriptcore.GraalValues;
import com.example.scriptcore.MapProxyObject;
import com.example.scriptcore.ProjectData;
import com.example.scriptcore.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Такт одной задачи — спека, раздел «Модель исполнения задачи».
 * <ol>
 *   <li>Владение недействительно — такт пропущен.</li>
 *   <li>Вход недостоверен или старше {@code stale_after_ms} — скрипт не запускается (INPUT_STALE),
 *       если задача не просила {@code run_on_stale}.</li>
 *   <li>Скрипт исполняется с таймаутом; записи копятся в буфере.</li>
 *   <li>Фиксация «всё или ничего»: только успешный такт применяет записи, переменные и state.</li>
 * </ol>
 * Такты не накладываются: незавершённый прошлый — очередной пропускается как OVERRUN.
 */
@Slf4j
public final class TaskRunner {

    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final long LOG_WINDOW_MS = 60_000;
    private static final int LOG_LIMIT = 10;

    private final long projectId;
    private final long epoch;
    private final TaskDefinition definition;
    private final String definitionHash;
    private final SandboxExecutor scripts;
    private final TagReader tags;
    private final OutputWriter outputs;
    private final VariableBoard variables;
    private final Supplier<ProjectData> projectData;
    private final TaskObserver observer;
    private final BooleanSupplier ownershipValid;
    private final LongSupplier clock;
    private final ObjectMapper mapper;

    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, IoDefinition> outputsByAlias;
    private final Set<String> writableVariables;

    private volatile Map<String, Object> state;
    private volatile boolean firstRun = true;
    private volatile long lastSuccessAtMs;
    private volatile long errorCount;
    private long logWindowStart;
    private int logCount;

    public TaskRunner(long projectId, long epoch, TaskDefinition definition, String definitionHash,
                      Map<String, Object> restoredState, SandboxExecutor scripts, TagReader tags,
                      OutputWriter outputs, VariableBoard variables, Supplier<ProjectData> projectData,
                      TaskObserver observer, BooleanSupplier ownershipValid, LongSupplier clock, ObjectMapper mapper) {
        this.projectId = projectId;
        this.epoch = epoch;
        this.definition = definition;
        this.definitionHash = definitionHash;
        this.state = JsonCopy.deepMap(restoredState);
        this.scripts = scripts;
        this.tags = tags;
        this.outputs = outputs;
        this.variables = variables;
        this.projectData = projectData;
        this.observer = observer;
        this.ownershipValid = ownershipValid;
        this.clock = clock;
        this.mapper = mapper;
        this.outputsByAlias = definition.outputsOrEmpty().stream()
                .collect(Collectors.toMap(IoDefinition::alias, io -> io, (a, b) -> a, LinkedHashMap::new));
        this.writableVariables = Set.copyOf(definition.writesVariablesOrEmpty());
    }

    public Map<String, Object> state() {
        return state;
    }

    public void tick() {
        long now = clock.getAsLong();
        if (!running.compareAndSet(false, true)) {
            report(TaskState.OVERRUN, now, null, null);
            return;
        }
        try {
            if (!ownershipValid.getAsBoolean()) {
                return;
            }
            if (!definition.enabled()) {
                report(TaskState.DISABLED, now, null, null);
                return;
            }
            Map<String, Object> inputValues = new HashMap<>();
            Map<String, Map<String, Object>> inputInfo = new HashMap<>();
            if (!readInputs(now, inputValues, inputInfo) && !definition.runOnStale()) {
                report(TaskState.INPUT_STALE, now, null, null);
                return;
            }

            Map<String, Object> writes = new LinkedHashMap<>();
            Map<String, Object> variableSets = new LinkedHashMap<>();
            long started = System.nanoTime();
            Map<String, Object> result = scripts.run(definition.script(),
                    bindings(now, inputValues, inputInfo, writes, variableSets), List.of("state"),
                    definition.timeoutMs());
            long durationMs = (System.nanoTime() - started) / 1_000_000;

            Map<String, Object> newState = checkState(result.get("state"));
            List<Map.Entry<String, Object>> commands = new ArrayList<>();
            for (Map.Entry<String, Object> write : writes.entrySet()) {
                IoDefinition output = outputsByAlias.get(write.getKey());
                try {
                    commands.add(Map.entry(output.tag(), ValueTypes.toOutput(write.getValue(), output.valueType())));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("выход '" + write.getKey() + "': " + e.getMessage(), e);
                }
            }

            // Фиксация: до этой строки такт мог упасть, ничего не применив.
            commands.forEach(command -> outputs.write(command.getKey(), command.getValue()));
            variableSets.forEach((name, value) -> {
                if (variables.set(name, value)) {
                    observer.variable(projectId, epoch, name, value);
                }
            });
            if (!newState.equals(state)) {
                observer.checkpoint(projectId, epoch, definition.id(), definitionHash, newState);
            }
            state = newState;
            firstRun = false;
            lastSuccessAtMs = now;
            report(TaskState.RUNNING, now, durationMs, collectWriteFailures());
        } catch (Exception e) {
            errorCount++;
            report(TaskState.ERROR, now, null, e.getMessage());
            logLimited(true, "Task '" + definition.name() + "' of project " + projectId + " failed: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** @return {@code false}, если хоть один вход устарел или недостоверен */
    private boolean readInputs(long now, Map<String, Object> values, Map<String, Map<String, Object>> info) {
        boolean fresh = true;
        for (IoDefinition input : definition.inputsOrEmpty()) {
            TagReading reading = tags.read(input.tag());
            Long age = reading == null ? null : now - reading.receivedAtMs();
            boolean stale = reading == null || !reading.good() || age > definition.staleAfterMs();
            fresh &= !stale;
            Object value = reading == null ? null : ValueTypes.toInput(reading.value(), input.valueType());
            values.put(input.alias(), value);
            Map<String, Object> details = new HashMap<>();
            details.put("value", value);
            details.put("good", reading != null && reading.good());
            details.put("ageMs", age == null ? null : age.doubleValue());
            details.put("stale", stale);
            info.put(input.alias(), details);
        }
        return fresh;
    }

    private Map<String, Object> bindings(long now, Map<String, Object> inputValues,
                                         Map<String, Map<String, Object>> inputInfo,
                                         Map<String, Object> writes, Map<String, Object> variableSets) {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("inputs", new MapProxyObject(inputValues));
        bindings.put("input", (ProxyExecutable) args -> {
            Map<String, Object> details = inputInfo.get(stringArg(args, "input"));
            if (details == null) {
                throw new IllegalArgumentException("input(): неизвестный вход " + stringArg(args, "input"));
            }
            return ProxyObject.fromMap(details);
        });
        bindings.put("vars", new MapProxyObject(variables.snapshot()));
        // Снимок берётся один раз на такт: перечитывание посреди расчёта его не меняет.
        bindings.put("data", new DataFunction(projectData.get()));
        bindings.put("write", (ProxyExecutable) args -> {
            String alias = stringArg(args, "write");
            if (!outputsByAlias.containsKey(alias)) {
                throw new IllegalArgumentException("write(): '" + alias + "' нет среди outputs");
            }
            writes.put(alias, valueArg(args));
            return null;
        });
        bindings.put("setVar", (ProxyExecutable) args -> {
            String name = stringArg(args, "setVar");
            if (!writableVariables.contains(name)) {
                throw new IllegalArgumentException("setVar(): '" + name + "' нет среди writes_variables");
            }
            variableSets.put(name, valueArg(args));
            return null;
        });
        bindings.put("state", new MapProxyObject(JsonCopy.deepMap(state)));
        bindings.put("dt", (double) (lastSuccessAtMs == 0 ? definition.periodMs() : now - lastSuccessAtMs));
        bindings.put("firstRun", firstRun);
        bindings.put("log", ProxyObject.fromMap(Map.of(
                "info", (ProxyExecutable) args -> scriptLog(false, args),
                "warn", (ProxyExecutable) args -> scriptLog(true, args))));
        return bindings;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkState(Object value) throws Exception {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("state должен быть объектом");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        if (mapper.writeValueAsBytes(map).length > MAX_STATE_BYTES) {
            throw new IllegalStateException("state больше " + MAX_STATE_BYTES + " байт");
        }
        return map;
    }

    private String collectWriteFailures() {
        List<String> failures = new ArrayList<>();
        for (IoDefinition output : outputsByAlias.values()) {
            String failure = outputs.takeFailure(output.tag());
            if (failure != null) {
                failures.add(output.alias() + " — " + failure);
            }
        }
        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    private void report(TaskState taskState, long now, Long durationMs, String error) {
        observer.status(new TaskStatusUpdate(projectId, definition.id(), definition.name(), taskState, now,
                durationMs, error, errorCount));
    }

    private Object scriptLog(boolean warn, Value[] args) {
        StringBuilder text = new StringBuilder();
        for (Value arg : args) {
            text.append(text.isEmpty() ? "" : " ").append(GraalValues.toJava(arg));
        }
        logLimited(warn, "[" + definition.name() + "] " + text);
        return null;
    }

    private synchronized void logLimited(boolean warn, String message) {
        long now = System.currentTimeMillis();
        if (now - logWindowStart > LOG_WINDOW_MS) {
            logWindowStart = now;
            logCount = 0;
        }
        if (++logCount > LOG_LIMIT) {
            return;
        }
        if (warn) {
            log.warn(message);
        } else {
            log.info(message);
        }
    }

    private static String stringArg(Value[] args, String function) {
        if (args.length < 1 || !args[0].isString()) {
            throw new IllegalArgumentException(function + "(): первым аргументом ожидается имя");
        }
        return args[0].asString();
    }

    private static Object valueArg(Value[] args) {
        return args.length < 2 ? null : GraalValues.toJava(args[1]);
    }
}
