-- Portable SQL: runs on SQLite and PostgreSQL.
-- What the two transports record: a position per projection, a row per event per policy runner.

-- How far each projection has got, and whether it is currently wedged. rejected_at is here rather than in the
-- transport's memory so that a projector stuck on a poisoned event is still stuck after a restart, which is
-- exactly when it matters: a crash loop or a deployment.
CREATE TABLE projection_position (
    projection    TEXT   NOT NULL PRIMARY KEY,
    -- Named for what it holds rather than just "position", which is a keyword in PostgreSQL (the SQL-standard
    -- POSITION function) and says nothing the table name does not already say.
    last_position BIGINT NOT NULL,
    rejected_at   TEXT
);

-- One row per event a policy runner has claimed. Not a position: a policy's work has left the system, so what
-- matters is whether this event was handled and whether it failed. A row with failed_at set is the
-- dead-letter record, and it is kept while completed rows are collected.
CREATE TABLE processed_event (
    name           TEXT   NOT NULL,
    event_position BIGINT NOT NULL,
    started_at     TEXT   NOT NULL,
    completed_at   TEXT,
    failed_at      TEXT,
    PRIMARY KEY (name, event_position)
);

-- Claiming reads the highest position for a name and asks whether anything is still unsettled; both are
-- covered by this.
CREATE INDEX processed_event_unsettled ON processed_event (name, completed_at, failed_at);
