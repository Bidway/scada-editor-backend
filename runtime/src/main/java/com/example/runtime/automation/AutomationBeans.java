package com.example.runtime.automation;

import com.example.runtime.automation.engine.TagCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Бины движка фоновых задач, у которых нет своего класса-компонента. */
@Configuration
public class AutomationBeans {

    /**
     * Последние значения входов задач. Питается от TagValueRouter: читатель телеметрии один,
     * но возраст входа задача считает по часам сервиса, а не по метке ПЛК, поэтому хранилище своё.
     */
    @Bean
    public TagCache automationTagCache() {
        return new TagCache();
    }
}
