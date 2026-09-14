package com.example.editor.model.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Сторожевой таймер проекта: ядро {@code automation} раз в {@link #periodMs} увеличивает счётчик в
 * {@link #tag}, пока владеет проектом и такты идут. Не больше одной строки на проект.
 */
@Entity
@Table(name = "automation_watchdog", schema = "editor")
@Getter
@Setter
public class AutomationWatchdog {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false)
    private String tag;

    @Column(name = "period_ms", nullable = false)
    private int periodMs;
}
