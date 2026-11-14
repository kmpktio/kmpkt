-- Portable SQL: runs on SQLite and PostgreSQL.

CREATE TABLE event_v4 (
    event_id       TEXT    NOT NULL PRIMARY KEY,
    log_name       TEXT    NOT NULL DEFAULT 'events',
    aggregate_type TEXT    NOT NULL,
    aggregate_id   TEXT    NOT NULL,
    version        BIGINT  NOT NULL,
    type           TEXT    NOT NULL,
    payload        TEXT    NOT NULL,
    occurred_at    TEXT    NOT NULL,
    is_snapshot    BOOLEAN NOT NULL DEFAULT FALSE,
    seq            BIGINT  NOT NULL,
    CONSTRAINT event_v4_stream_version UNIQUE (log_name, aggregate_type, aggregate_id, version)
);

INSERT INTO event_v4 (event_id, log_name, aggregate_type, aggregate_id, version, type, payload, occurred_at, is_snapshot, seq)
SELECT event_id, 'events', aggregate_type, aggregate_id, version, type, payload, occurred_at, is_snapshot, seq
FROM event;

DROP TABLE event;

ALTER TABLE event_v4 RENAME TO event;

CREATE INDEX event_seq ON event (log_name, seq);
CREATE INDEX event_stream_snapshot ON event (log_name, aggregate_type, aggregate_id, is_snapshot, version);
