package com.example.runtime.session;

import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.script.ScriptWriteSinks;
import com.example.runtime.script.TagWriteSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Три sink'а для скрипта резолвят адрес по-разному: {@code byProperty} — имя свойства
 * компонента через индекс сессии (как раньше), {@code byPath} — путь как есть, без резолва
 * (чтобы полный путь работал и для тега другого проекта), {@code byProjectTag} — короткий
 * путь через {@link TagSubscriptionIndex#resolveTagPath}. Итог у всех трёх один — команда
 * в {@link CommandProducer} по итоговому пути.
 */
class TagCommandServiceTest {

    private static final Long COMPONENT_ID = 42L;

    private CommandProducer commandProducer;
    private TagSubscriptionIndex index;
    private RuntimeSession session;
    private TagCommandService service;

    @BeforeEach
    void setUp() {
        commandProducer = mock(CommandProducer.class);
        index = mock(TagSubscriptionIndex.class);
        session = mock(RuntimeSession.class);
        when(session.getIndex()).thenReturn(index);
        when(commandProducer.send(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandOutcome.applied("ok")));

        service = new TagCommandService(commandProducer);
    }

    @Test
    @DisplayName("byProperty резолвит имя свойства в путь тега через индекс компонента")
    void byProperty_resolvesPropertyNameToTagPath() {
        when(index.tagIdOfComponentProperty(COMPONENT_ID, "ST"))
                .thenReturn("Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST");

        sink().byProperty().write("ST", true);

        verify(commandProducer).send(eq("Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST"), eq(true));
    }

    @Test
    @DisplayName("byProperty неизвестного имени ничего не отправляет")
    void byProperty_unknownNameSendsNothing() {
        when(index.tagIdOfComponentProperty(COMPONENT_ID, "Нет такого")).thenReturn(null);

        sink().byProperty().write("Нет такого", 1);

        verify(commandProducer, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("byPath шлёт путь как есть, без обращения к индексу — работает и для другого проекта")
    void byPath_sendsPathVerbatimWithoutResolution() {
        String foreignProjectTag = "ДругойСайт-2.PLC-7.AI_M.AI1.M";

        sink().byPath().write(foreignProjectTag, 3.5);

        verify(commandProducer).send(eq(foreignProjectTag), eq(3.5));
    }

    @Test
    @DisplayName("byProjectTag резолвит короткий путь через индекс проекта")
    void byProjectTag_resolvesShortPathViaIndex() {
        when(index.resolveTagPath("FQT_ST.LINE1FQT1.ST"))
                .thenReturn("Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST");

        sink().byProjectTag().write("FQT_ST.LINE1FQT1.ST", 7);

        verify(commandProducer).send(eq("Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST"), eq(7));
    }

    @Test
    @DisplayName("три sink'а из sinksFor не путаются между собой")
    void allThreeSinksAreDistinct() {
        ScriptWriteSinks sinks = service.sinksFor(session, COMPONENT_ID);

        assertThat(sinks.byProperty()).isNotNull();
        assertThat(sinks.byPath()).isNotNull();
        assertThat(sinks.byProjectTag()).isNotNull();
    }

    private ScriptWriteSinks sink() {
        return service.sinksFor(session, COMPONENT_ID);
    }
}
