package com.example.runtime.kafka;

/**
 * Публикуется {@link TagKafkaConsumer} на каждое прочитанное сообщение единого
 * Kafka-топика проекта. {@code key} — это {@code Node.idNode} узла-источника значения.
 */
public record KafkaTagMessageEvent(String key, String value) {
}
