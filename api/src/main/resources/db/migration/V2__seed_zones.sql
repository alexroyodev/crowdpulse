-- Fictional capacities from spec 001 (total 70 000).
INSERT INTO zone (zone_id, name, capacity) VALUES
    ('NORTE', 'Fondo Norte',   20000),
    ('SUR',   'Fondo Sur',     20000),
    ('ESTE',  'Lateral Este',  15000),
    ('OESTE', 'Lateral Oeste', 15000);

-- Every zone starts empty.
INSERT INTO zone_occupancy (zone_id)
SELECT zone_id FROM zone;
