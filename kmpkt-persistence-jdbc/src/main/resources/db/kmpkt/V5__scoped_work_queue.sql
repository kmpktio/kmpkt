-- Portable SQL: runs on SQLite and PostgreSQL.

CREATE TABLE work_queue_v5 (
    id           TEXT NOT NULL,
    queue        TEXT NOT NULL,
    payload      TEXT NOT NULL,
    available_at TEXT NOT NULL,
    attempts     INT  NOT NULL DEFAULT 0,
    status       TEXT NOT NULL,
    PRIMARY KEY (queue, id)
);

INSERT INTO work_queue_v5 (id, queue, payload, available_at, attempts, status)
SELECT id, queue, payload, available_at, attempts, status
FROM work_queue;

DROP TABLE work_queue;

ALTER TABLE work_queue_v5 RENAME TO work_queue;

CREATE INDEX work_queue_ready ON work_queue (queue, status, available_at);
