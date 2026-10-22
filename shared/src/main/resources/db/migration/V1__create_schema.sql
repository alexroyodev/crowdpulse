-- Zone catalogue: static reference data, changed only by migrations.
CREATE TABLE zone (
    zone_id   VARCHAR(20)  PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    capacity  INTEGER      NOT NULL CHECK (capacity > 0)
);

-- Current state per zone, updated with every accepted event.
-- The CHECK is a safety net: the domain already keeps occupancy at 0 or above.
CREATE TABLE zone_occupancy (
    zone_id        VARCHAR(20) PRIMARY KEY REFERENCES zone (zone_id),
    occupancy      INTEGER     NOT NULL DEFAULT 0 CHECK (occupancy >= 0),
    last_event_at  TIMESTAMPTZ NULL
);

-- Full history of accepted events; rows are never updated or deleted.
-- event_id is the primary key, so a duplicate eventId cannot be stored twice.
CREATE TABLE sensor_event (
    event_id     UUID        PRIMARY KEY,
    sensor_id    VARCHAR(50) NOT NULL,
    zone_id      VARCHAR(20) NOT NULL REFERENCES zone (zone_id),
    type         VARCHAR(5)  NOT NULL CHECK (type IN ('ENTRY', 'EXIT')),
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    anomaly      BOOLEAN     NOT NULL DEFAULT FALSE
);
