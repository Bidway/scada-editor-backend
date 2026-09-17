package com.example.runtime.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Префикс пути тега, который ходит в топике. Первичный ключ — путь не принадлежит двум топикам. */
@Entity
@Table(name = "instance_topic_prefix", schema = "runtime")
@Getter
@Setter
public class InstanceTopicPrefixEntity {

    @Id
    @Column(name = "path_prefix")
    private String pathPrefix;

    @Column(name = "telemetry_topic", nullable = false)
    private String telemetryTopic;
}
