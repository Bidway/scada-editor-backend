package com.example.runtime.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Проект, назначенный экземпляру. Первичный ключ — проект не крутится на двух экземплярах. */
@Entity
@Table(name = "instance_project", schema = "runtime")
@Getter
@Setter
public class InstanceProjectEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "instance_id", nullable = false, length = 64)
    private String instanceId;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;
}
