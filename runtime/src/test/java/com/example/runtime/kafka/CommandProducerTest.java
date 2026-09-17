package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandProducerTest {

    @Test
    @DisplayName("запись в переменную проекта отклоняется и в ПЛК не уходит")
    void projectVariableIsNotWritable() {
        KafkaProperties properties = new KafkaProperties();
        // init() не вызывается: producer не создан, и отказ обязан случиться до обращения к нему.
        CommandProducer producer = new CommandProducer(properties, new ObjectMapper(),
                new PendingCommandRegistry(properties), new com.example.runtime.assignment.AssignmentState());

        CommandOutcome outcome = producer.send("@var.line1.mode", 1).join();

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.status()).isEqualTo(CommandOutcome.REJECTED_VARIABLE);
    }
}
