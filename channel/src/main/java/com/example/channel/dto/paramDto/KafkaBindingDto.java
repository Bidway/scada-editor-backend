package com.example.channel.dto.paramDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ батч-резолва тегов для runtime: paramId (tagId в терминах editor) -> idNode.
 * Kafka-топик — один общий для всего проекта (настраивается в runtime, не в channel),
 * а idNode узла и есть Kafka-key, по которому runtime сопоставляет входящие сообщения тегу.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KafkaBindingDto {
    private Long paramId;
    private String idNode;
}
