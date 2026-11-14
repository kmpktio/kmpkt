-- Portable SQL: runs on SQLite and PostgreSQL.

CREATE TABLE event (
    event_id       TEXT    NOT NULL PRIMARY KEY,
    aggregate_type TEXT    NOT NULL,
    aggregate_id   TEXT    NOT NULL,
    version        BIGINT  NOT NULL,
    type           TEXT    NOT NULL,
    payload        TEXT    NOT NULL,
    occurred_at    TEXT    NOT NULL,
    is_snapshot    BOOLEAN NOT NULL DEFAULT FALSE,
    -- Stable, append-order position in the whole log (StoredEvent.position) - across every stream and every
    -- aggregate type, which is what lets a subscriber spanning several aggregates see their events in the
    -- order they happened. Assigned via event_sequence below, not a native AUTOINCREMENT/SERIAL column, so the
    -- same SQL works on both engines - same reasoning as event_id being generated in application code.
    seq            BIGINT  NOT NULL,
    CONSTRAINT event_stream_version UNIQUE (aggregate_type, aggregate_id, version)
);

CREATE INDEX event_seq ON event (seq);
CREATE INDEX event_stream_snapshot ON event (aggregate_type, aggregate_id, is_snapshot, version);

-- One row, one counter: the log's global position. A counter per aggregate_type would mean no single order.
CREATE TABLE event_sequence (
    name  TEXT   NOT NULL PRIMARY KEY,
    value BIGINT NOT NULL
);
