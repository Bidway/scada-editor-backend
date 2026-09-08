package com.example.runtime.write;

import com.example.runtime.config.KafkaProperties;
import com.example.runtime.dto.TagWriteItem;
import com.example.runtime.dto.TagWriteRequest;
import com.example.runtime.dto.TagWriteResult;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Точечная запись всегда приходит массивом — здесь важны две вещи: результат сохраняет
 * порядок и множественность запроса, и негодная строка не топит остальные.
 */
class TagWriteServiceTest {

    private CommandProducer commandProducer;
    private TagWriteService service;

    @BeforeEach
    void setUp() {
        commandProducer = mock(CommandProducer.class);
        KafkaProperties kafkaProperties = new KafkaProperties();
        service = new TagWriteService(commandProducer, kafkaProperties);
    }

    @Test
    @DisplayName("батч из нескольких тегов возвращает результаты в том же порядке")
    void batchPreservesOrderAndCoercesByValueType() {
        TagWriteItem open = item("Tag.V1.ST", "true", "boolean");
        TagWriteItem level = item("Tag.LT1.V", "12.5", "float");
        when(commandProducer.send(eq("Tag.V1.ST"), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(CommandOutcome.applied("ok")));
        when(commandProducer.send(eq("Tag.LT1.V"), eq(12.5)))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandOutcome.failure("REJECTED_NOT_WRITABLE", "только чтение")));

        List<TagWriteResult> results = service.write(request(open, level));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(new TagWriteResult("Tag.V1.ST", true, CommandOutcome.APPLIED, "ok"));
        assertThat(results.get(1))
                .isEqualTo(new TagWriteResult("Tag.LT1.V", false, "REJECTED_NOT_WRITABLE", "только чтение"));
    }

    @Test
    @DisplayName("нераспознанное булево значение не уходит в ПЛК, но не топит соседние строки")
    void invalidValueSkipsSendButKeepsOtherRows() {
        TagWriteItem bad = item("Tag.V1.ST", "не так", "boolean");
        TagWriteItem good = item("Tag.V2.ST", "false", "boolean");
        when(commandProducer.send(eq("Tag.V2.ST"), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(CommandOutcome.applied("ok")));

        List<TagWriteResult> results = service.write(request(bad, good));

        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).status()).isEqualTo(TagWriteResult.INVALID_VALUE);
        assertThat(results.get(1).success()).isTrue();
        verify(commandProducer, never()).send(eq("Tag.V1.ST"), any());
    }

    private static TagWriteItem item(String tagId, String value, String valueType) {
        TagWriteItem item = new TagWriteItem();
        item.setTagId(tagId);
        item.setValue(value);
        item.setValueType(valueType);
        return item;
    }

    private static TagWriteRequest request(TagWriteItem... items) {
        TagWriteRequest request = new TagWriteRequest();
        request.setWrites(List.of(items));
        return request;
    }
}
