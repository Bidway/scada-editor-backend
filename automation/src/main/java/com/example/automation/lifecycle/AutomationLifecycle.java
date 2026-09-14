package com.example.automation.lifecycle;

import com.example.automation.kafka.TopicAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Единственное место порядка старта и остановки сервиса. */
@Component
@RequiredArgsConstructor
public class AutomationLifecycle implements SmartLifecycle {

    private final TopicAdmin topicAdmin;

    private volatile boolean running;

    @Override
    public void start() {
        topicAdmin.ensureTopics();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
