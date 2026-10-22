-- Spec 002 (D3): a small demo zone. The other zones (15 000 and 20 000 seats) never reach
-- the alert thresholds in a simulator run; 200 seats do within seconds.
INSERT INTO zone (zone_id, name, capacity) VALUES ('PALCO', 'Palco VIP', 200);

INSERT INTO zone_occupancy (zone_id) VALUES ('PALCO');
