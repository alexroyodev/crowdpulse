package com.alexroyodev.crowdpulse.processor.event;

import java.time.Instant;
import java.util.UUID;

import com.alexroyodev.crowdpulse.shared.EventType;

/**
 * An accepted event as stored in sensor_event. Immutable: once accepted it is never changed.
 *
 * @param receivedAt when the API accepted the event (processing time), unlike occurredAt (event time)
 * @param processedAt when the processor applied it; latency = processedAt - receivedAt (spec 003)
 * @param anomaly true for an EXIT that arrived with the zone already at 0 (rule 5)
 */
record SensorEvent(
		UUID eventId,
		String sensorId,
		String zoneId,
		EventType type,
		Instant occurredAt,
		Instant receivedAt,
		Instant processedAt,
		boolean anomaly) {
}
