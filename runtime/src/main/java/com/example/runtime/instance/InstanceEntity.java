package com.example.runtime.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Визитка экземпляра runtime: кто он и где живёт. Ни на что не влияет, кроме маршрутизации. */
@Entity
@Table(name = "instance", schema = "runtime")
@Getter
@Setter
public class InstanceEntity {

    @Id
    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    private String description;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
