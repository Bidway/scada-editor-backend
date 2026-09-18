-- Выполняется runtime при каждом старте (spring.sql.init). Идемпотентен.
-- Схема runtime: таблицы в ней создаёт Hibernate (ddl-auto: update), но не саму схему.
CREATE SCHEMA IF NOT EXISTS runtime;

-- Схема automation: рабочее состояние фоновых задач. Раньше ей владел сервис automation.
CREATE SCHEMA IF NOT EXISTS automation;
-- Значения свойств проекта, которые пишут скрипты (on_change, кнопки). В памяти runtime они
-- живут в ProjectRuntime.propertyValues; без этой таблицы перезапуск возвращал режимы и флаги
-- скриптов к default_value (scada-vrkf). Значение telemetry-тегов сюда не пишется.
CREATE TABLE IF NOT EXISTS runtime.property_value (
    project_id  bigint      NOT NULL,
    property_id bigint      NOT NULL,
    value       jsonb       NOT NULL,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (project_id, property_id)
);

CREATE TABLE IF NOT EXISTS automation.task_checkpoint (
    project_id      bigint      NOT NULL,
    task_id         bigint      NOT NULL,
    definition_hash text        NOT NULL,
    state           jsonb       NOT NULL,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (project_id, task_id)
);

CREATE TABLE IF NOT EXISTS automation.variable_value (
    project_id bigint      NOT NULL,
    name       text        NOT NULL,
    value      jsonb,
    quality    text        NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, name)
);

CREATE TABLE IF NOT EXISTS automation.task_status (
    project_id       bigint      NOT NULL,
    task_id          bigint      NOT NULL,
    name             text        NOT NULL,
    state            text        NOT NULL,
    last_run_at      timestamptz,
    last_duration_ms bigint,
    last_error       text,
    error_count      bigint      NOT NULL,
    owner_instance   text,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (project_id, task_id)
);

-- Доработка базы, оставшейся от сервиса automation (этап 2А, 17.09.2026).
ALTER TABLE automation.task_status ADD COLUMN IF NOT EXISTS last_lag_ms bigint;
ALTER TABLE automation.task_checkpoint DROP COLUMN IF EXISTS epoch;
ALTER TABLE automation.variable_value DROP COLUMN IF EXISTS epoch;
DROP TABLE IF EXISTS automation.partition_epoch;

-- Аренда драйверов этапа 1 заменена назначениями (этап 2Б).
DROP TABLE IF EXISTS runtime.driver_lease;
