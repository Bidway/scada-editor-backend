package com.example.automation.lifecycle;

import com.example.automation.command.CommandGateway;
import com.example.automation.engine.StateSink;
import com.example.automation.kafka.OwnershipConsumer;
import com.example.automation.kafka.StatePublisher;
import com.example.automation.kafka.TagStreamConsumer;
import com.example.automation.kafka.TopicAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Единственное место порядка старта и остановки. Старт: топики (иначе отказ) → команды и
 * публикация → теги → владение последним, когда всё, что нужно задачам, уже работает.
 * Остановка — в обратном порядке: сначала отдать проекты со сбросом состояния, потом закрыть каналы.
 */
@Component
@RequiredArgsConstructor
public class AutomationLifecycle implements SmartLifecycle {

    private final TopicAdmin topicAdmin;
    private final CommandGateway commandGateway;
    private final StatePublisher statePublisher;
    private final TagStreamConsumer tagStream;
    private final OwnershipConsumer ownership;
    private final StateSink stateSink;

    private volatile boolean running;

    @Override
    public void start() {
        topicAdmin.ensureTopics();
        commandGateway.start();
        statePublisher.start();
        tagStream.start();
        ownership.start();
        running = true;
    }

    @Override
    public void stop() {
        ownership.stop();
        tagStream.stop();
        stateSink.flush();
        statePublisher.stop();
        commandGateway.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
