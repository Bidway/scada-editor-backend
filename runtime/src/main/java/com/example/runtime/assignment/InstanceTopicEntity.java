package com.example.runtime.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Топик телеметрии, назначенный экземпляру, и его пара командных топиков. Первичный ключ — топик. */
@Entity
@Table(name = "instance_topic", schema = "runtime")
@Getter
@Setter
public class InstanceTopicEntity {

    @Id
    @Column(name = "telemetry_topic")
    private String telemetryTopic;

    @Column(name = "commands_topic", nullable = false, unique = true)
    private String commandsTopic;

    @Column(name = "results_topic", nullable = false, unique = true)
    private String resultsTopic;

    @Column(name = "instance_id", nullable = false, length = 64)
    private String instanceId;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;
}
