-- Back to the state right after the migrations: no events, every zone empty.
DELETE FROM sensor_event;
UPDATE zone_occupancy SET occupancy = 0, last_event_at = NULL;
