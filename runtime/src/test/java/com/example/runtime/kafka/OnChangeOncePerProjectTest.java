package com.example.runtime.kafka;

import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.script.OnChangeDispatcher;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.OnChangeBinding;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagCommandService;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.runtime.stream.SessionOutboundBuffer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Риск, который закрывает этот тест: пока onChange исполнялся внутри цикла по сессиям, при двух
 * открытых мониторах каждый серверный скрипт запускался дважды — и запись в ПЛК внутри него
 * уходила дважды. У записи в контроллер не должно быть кратности числу открытых экранов.
 */
class OnChangeOncePerProjectTest {

    private static final String TAG = "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST";
    private static final Long PROJECT = 8501L;

    @Test
    void скрипт_исполняется_один_раз_на_двух_наблюдателей() {
        AtomicInteger executions = new AtomicInteger();

        TagSubscriptionIndex index = mock(TagSubscriptionIndex.class);
        when(index.getAllTagIds()).thenReturn(Set.of(TAG));
        when(index.getInitialPropertyValues()).thenReturn(Map.of());
        when(index.onChangeBindingsForTag(TAG))
                .thenReturn(List.of(new OnChangeBinding(1L, 10L, "props.x = 1;")));
        when(index.propertyIdsOfComponent(10L)).thenReturn(List.of(1L));
        when(index.propertyName(1L)).thenReturn("x");

        ProjectRuntime project = new ProjectRuntime(PROJECT, index, null);
        project.addObserver(observer("s-1"));
        project.addObserver(observer("s-2"));

        ProjectRuntimeStore projectStore = mock(ProjectRuntimeStore.class);
        when(projectStore.get(PROJECT)).thenReturn(project);

        ScriptEngineService scripts = mock(ScriptEngineService.class);
        when(scripts.runOnChange(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    executions.incrementAndGet();
                    return Map.of("x", 1);
                });

        // Диспетчер исполняет задачу на месте: полосы здесь не проверяем.
        OnChangeDispatcher dispatcher = mock(OnChangeDispatcher.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(dispatcher).submit(any(Long.class), any(Runnable.class));

        TagValueRouter router = new TagValueRouter(mock(RuntimeSessionStore.class), projectStore,
                scripts, mock(TagCommandService.class), dispatcher, new ObjectMapper(),
                mock(ApplicationEventPublisher.class), new com.example.runtime.automation.engine.TagCache());
        router.registerProject(project);

        router.onMessage(new KafkaTagMessageEvent(TAG,
                "{\"value\":\"true\",\"quality\":\"GOOD\",\"sourceTs\":\"2026-09-16T10:00:00Z\"}"));

        assertThat(executions.get()).isEqualTo(1);
    }

    private static RuntimeSession observer(String id) {
        RuntimeSession session = mock(RuntimeSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getOutboundBuffer()).thenReturn(new SessionOutboundBuffer());
        return session;
    }
}
