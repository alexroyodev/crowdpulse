-- Spec 002: current alert level of each zone, next to its occupancy. It lives in the
-- row that EventService already locks with FOR UPDATE, so it is read and written
-- under the same per-zone lock as the occupancy.
ALTER TABLE zone_occupancy
    ADD COLUMN alert_level VARCHAR(8) NOT NULL DEFAULT 'NORMAL'
        CHECK (alert_level IN ('NORMAL', 'WARNING', 'CRITICAL'));

-- Rule 11: every zone starts at the level of its current occupancy, with no history.
-- 85 and 95 are the entry thresholds of spec 002 (D1); later events use the configured ones.
UPDATE zone_occupancy o
SET alert_level = CASE
        WHEN o.occupancy * 100 >= 95 * z.capacity THEN 'CRITICAL'
        WHEN o.occupancy * 100 >= 85 * z.capacity THEN 'WARNING'
        ELSE 'NORMAL'
    END
FROM zone z
WHERE z.zone_id = o.zone_id;

-- History of level changes; rows are never updated or deleted.
-- seq gives a stable "newest first" order even when two rows share detected_at.
-- event_id UNIQUE: one event causes at most one level change.
-- capacity is copied so the stored percentage stays true if the catalogue changes.
CREATE TABLE alert (
    seq          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     UUID        NOT NULL UNIQUE,
    zone_id      VARCHAR(20) NOT NULL REFERENCES zone (zone_id),
    from_level   VARCHAR(8)  NOT NULL CHECK (from_level IN ('NORMAL', 'WARNING', 'CRITICAL')),
    to_level     VARCHAR(8)  NOT NULL CHECK (to_level IN ('NORMAL', 'WARNING', 'CRITICAL')),
    occupancy    INTEGER     NOT NULL CHECK (occupancy >= 0),
    capacity     INTEGER     NOT NULL CHECK (capacity > 0),
    event_id     UUID        NOT NULL UNIQUE REFERENCES sensor_event (event_id),
    occurred_at  TIMESTAMPTZ NOT NULL,
    detected_at  TIMESTAMPTZ NOT NULL,
    CHECK (from_level <> to_level)
);
