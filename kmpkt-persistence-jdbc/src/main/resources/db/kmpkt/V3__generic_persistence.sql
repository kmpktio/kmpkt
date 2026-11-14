-- Portable SQL: runs on SQLite and PostgreSQL.

CREATE TABLE kv_record (
    namespace  TEXT NOT NULL,
    record_key TEXT NOT NULL,
    payload    TEXT NOT NULL,
    PRIMARY KEY (namespace, record_key)
);

CREATE TABLE work_queue (
    id           TEXT NOT NULL PRIMARY KEY,
    queue        TEXT NOT NULL,
    payload      TEXT NOT NULL,
    available_at TEXT NOT NULL,
    attempts     INT  NOT NULL DEFAULT 0,
    status       TEXT NOT NULL
);

CREATE INDEX work_queue_ready ON work_queue (queue, status, available_at);
