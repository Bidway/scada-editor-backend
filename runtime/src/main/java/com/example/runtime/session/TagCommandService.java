package com.example.runtime.session;

import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.script.TagWriteSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Связывает вызов {@code writeTag('ST', true)} из скрипта с отправкой команды в ПЛК.
 * <p>
 * Цепочка резолва целиком строковая:
 * <pre>
 *   имя свойства  --TagSubscriptionIndex-->  idNode (путь узла через точку)
 *                 --CommandProducer------->  топик команд
 * </pre>
 * Отказ резолва — не ошибка исполнения скрипта, а предупреждение в лог: команда по
 * несуществующему свойству не должна ронять весь обработчик нажатия.
 */
@Service
@Slf4j
public class TagCommandService {

    private final CommandProducer commandProducer;

    public TagCommandService(CommandProducer commandProducer) {
        this.commandProducer = commandProducer;
    }

    /**
     * Приёмник записей для скрипта конкретного компонента: имена свойств скрипт
     * указывает свои, поэтому резолв всегда идёт в пределах этого компонента.
     */
    public TagWriteSink sinkFor(RuntimeSession session, Long componentId) {
        return (propertyName, value) -> write(session, componentId, propertyName, value);
    }

    private void write(RuntimeSession session, Long componentId, String propertyName, Object value) {
        String idNode = session.getIndex().tagIdOfComponentProperty(componentId, propertyName);
        if (idNode == null) {
            log.warn("writeTag('{}'): у компонента {} нет свойства с таким именем или оно не привязано к тегу",
                    propertyName, componentId);
            return;
        }
        // Future намеренно не ждём: writeTag зовётся из скрипта, а тот исполняется на потоке
        // из ограниченного пула script-exec (ScriptEngineService), куда onChange попадает
        // через полосы onchange-N (OnChangeDispatcher), а ACTION — с WebSocket. Ожидание
        // ответа шлюза заняло бы поток пула на всё время ходки в ПЛК, и соседние скрипты
        // встали бы в очередь за ним. С треда Kafka-consumer'а скрипты не исполняются с тех
        // пор, как появился OnChangeDispatcher (scada-abf).
        // Но исход всё же дожидаемся колбэком: отказ шлюза («узел только на чтение»,
        // «нет связи») иначе нигде не всплыл бы — кнопка на мнемосхеме выглядела бы
        // сработавшей, а в ПЛК не менялось бы ничего.
        commandProducer.send(idNode, value).thenAccept(outcome -> {
            if (!outcome.applied()) {
                log.warn("writeTag('{}') по тегу '{}': {} — {}",
                        propertyName, idNode, outcome.status(), outcome.message());
            }
        });
    }
}
