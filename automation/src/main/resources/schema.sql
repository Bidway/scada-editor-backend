CREATE SCHEMA IF NOT EXISTS automation;

CREATE TABLE IF NOT EXISTS automation.partition_epoch (
    partition   int         PRIMARY KEY,
    epoch       bigint      NOT NULL,
    owner       text        NOT NULL,
    acquired_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS automation.task_checkpoint (
    project_id      bigint      NOT NULL,
    task_id         bigint      NOT NULL,
    epoch           bigint      NOT NULL,
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
    epoch      bigint      NOT NULL,
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
