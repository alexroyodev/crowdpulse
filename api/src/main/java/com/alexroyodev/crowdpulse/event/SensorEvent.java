package com.alexroyodev.crowdpulse.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An accepted event as stored in sensor_event. Immutable: once accepted it is never changed.
 *
 * @param receivedAt when the system saw the event (processing time), unlike occurredAt (event time)
 * @param anomaly true for an EXIT that arrived with the zone already at 0 (rule 5)
 */
record SensorEvent(
		UUID eventId,
		String sensorId,
		String zoneId,
		EventType type,
		Instant occurredAt,
		Instant receivedAt,
		boolean anomaly) {
}
