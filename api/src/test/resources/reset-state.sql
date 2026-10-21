-- Back to the state right after the migrations: no events, no alerts, every zone empty.
-- alert first: it references sensor_event.
DELETE FROM alert;
DELETE FROM sensor_event;
UPDATE zone_occupancy SET occupancy = 0, last_event_at = NULL, alert_level = 'NORMAL';
