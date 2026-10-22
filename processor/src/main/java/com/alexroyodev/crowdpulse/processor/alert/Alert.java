package com.alexroyodev.crowdpulse.processor.alert;

import java.time.Instant;
import java.util.UUID;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

/**
 * One change of a zone's alert level, as stored in the alert table. Immutable.
 *
 * @param capacity the zone's capacity when the change happened
 * @param eventId the event whose occupancy change caused it
 * @param occurredAt that event's occurredAt (event time)
 * @param detectedAt when the system detected the change (processing time)
 */
public record Alert(
		UUID alertId,
		String zoneId,
		AlertLevel from,
		AlertLevel to,
		int occupancy,
		int capacity,
		UUID eventId,
		Instant occurredAt,
		Instant detectedAt) {
}
