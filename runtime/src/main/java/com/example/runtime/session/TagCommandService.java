package com.example.runtime.session;

import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.script.ScriptFailureRegistry;
import com.example.runtime.script.ScriptWriteSinks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Связывает три функции записи, видимые скрипту, с отправкой команды в ПЛК —
 * {@code writeTag}, {@code writeTagPath}, {@code writeProjectTag} (см. {@link ScriptWriteSinks}).
 * <p>
 * У каждой свой резолв адреса, итог один — {@link CommandProducer}:
 * <pre>
 *   writeTag        имя свойства  --TagSubscriptionIndex-->  idNode (путь узла через точку)
 *   writeTagPath     путь как есть, без резолва — рабочий вариант для тега другого проекта
 *   writeProjectTag  короткий путь --TagSubscriptionIndex.resolveTagPath--> idNode
 *                                  --CommandProducer------->  топик команд
 * </pre>
 * Отказ резолва — не ошибка исполнения скрипта, а предупреждение в лог: команда по
 * несуществующему свойству не должна ронять весь обработчик нажатия.
 */
@Service
@Slf4j
public class TagCommandService {

    private final CommandProducer commandProducer;
    private final ScriptFailureRegistry failures;

    public TagCommandService(CommandProducer commandProducer, ScriptFailureRegistry failures) {
        this.commandProducer = commandProducer;
        this.failures = failures;
    }

    /** Три sink'а для скрипта конкретного компонента — см. {@link ScriptWriteSinks}. */
    public ScriptWriteSinks sinksFor(ProjectRuntime project, Long componentId) {
        return new ScriptWriteSinks(
                (propertyName, value) -> writeByProperty(project, componentId, propertyName, value),
                (path, value) -> writeByPath(project, path, value),
                (path, value) -> writeByProjectTag(project, path, value));
    }

    private void writeByProperty(ProjectRuntime project, Long componentId, String propertyName, Object value) {
        String idNode = project.getIndex().tagIdOfComponentProperty(componentId, propertyName);
        if (idNode == null) {
            log.warn("writeTag('{}'): у компонента {} нет свойства с таким именем или оно не привязано к тегу",
                    propertyName, componentId);
            failures.record(ScriptFailureRegistry.Kind.WRITE_REJECTED, project.getProjectId(),
                    "writeTag('" + propertyName + "') component " + componentId,
                    "нет свойства с таким именем или оно не привязано к тегу");
            return;
        }
        send(project, "writeTag", propertyName, idNode, value);
    }

    /**
     * Путь как есть, без резолва через индекс сессии — единственный способ адресовать тег
     * другого проекта/узла из скрипта. Индекс сессии знает только теги своего дерева, поэтому
     * любая попытка что-то в нём проверить ограничила бы функцию текущим проектом — а это
     * ровно то, для чего есть {@code writeProjectTag}.
     */
    private void writeByPath(ProjectRuntime project, String path, Object value) {
        send(project, "writeTagPath", path, path, value);
    }

    private void writeByProjectTag(ProjectRuntime project, String path, Object value) {
        String idNode = project.getIndex().resolveTagPath(path);
        send(project, "writeProjectTag", path, idNode, value);
    }

    /**
     * Future намеренно не ждём: вызывающие — писатели из скрипта, а тот исполняется на потоке
     * из ограниченного пула script-exec (ScriptEngineService), куда onChange попадает через
     * полосы onchange-N (OnChangeDispatcher), а ACTION — с WebSocket. Ожидание ответа шлюза
     * заняло бы поток пула на всё время ходки в ПЛК, и соседние скрипты встали бы в очередь за
     * ним. С треда Kafka-consumer'а скрипты не исполняются с тех пор, как появился
     * OnChangeDispatcher (scada-abf).
     * Но исход всё же дожидаемся колбэком: отказ шлюза («узел только на чтение», «нет связи»)
     * иначе нигде не всплыл бы — кнопка на мнемосхеме выглядела бы сработавшей, а в ПЛК не
     * менялось бы ничего.
     */
    private void send(ProjectRuntime project, String functionName, String requestedBy, String idNode, Object value) {
        commandProducer.send(idNode, value).thenAccept(outcome -> {
            if (!outcome.applied()) {
                log.warn("{}('{}') по тегу '{}': {} — {}",
                        functionName, requestedBy, idNode, outcome.status(), outcome.message());
                failures.record(ScriptFailureRegistry.Kind.WRITE_REJECTED, project.getProjectId(),
                        functionName + "('" + requestedBy + "') tag " + idNode,
                        outcome.status() + ": " + outcome.message());
            }
        });
    }
}
