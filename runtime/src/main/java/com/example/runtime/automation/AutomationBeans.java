package com.example.runtime.automation;

import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.automation.engine.StateSink;
import com.example.runtime.automation.engine.TagCache;
import com.example.runtime.automation.store.AutomationStore;
import com.example.runtime.client.EditorClient;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.scriptcore.ProjectData;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * Движок фоновых задач. Действительность владения — «проект поднят в этом runtime»: погашенный
     * проект не пишет ни в ПЛК, ни в базу, даже если такт уже стоял в очереди.
     */
    @Bean(destroyMethod = "shutdown")
    public AutomationEngine automationEngine(AutomationEngineProperties properties, TagCache automationTagCache,
                                             RuntimeCommandSender commands, StateSink stateSink,
                                             AutomationStore store, ObjectMapper mapper, EditorClient editorClient,
                                             ProjectRuntimeStore projectStore) {
        return new AutomationEngine(properties, automationTagCache, commands, stateSink, store, mapper,
                projectId -> ProjectData.parse(editorClient.getProjectData(projectId)),
                projectId -> projectStore.get(projectId) != null);
    }
}
