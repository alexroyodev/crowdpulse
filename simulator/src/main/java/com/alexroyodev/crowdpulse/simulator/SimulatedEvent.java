package com.alexroyodev.crowdpulse.simulator;

import java.time.Instant;
import java.util.UUID;

/**
 * One event as a sensor would send it (event contract v1).
 *
 * @param duplicate true when this is a resend of an event already sent (same eventId)
 */
record SimulatedEvent(UUID eventId, String sensorId, String zoneId, String type, Instant occurredAt,
		boolean duplicate) {

	SimulatedEvent asDuplicate() {
		return new SimulatedEvent(eventId, sensorId, zoneId, type, occurredAt, true);
	}

	/** JSON body for POST /api/v1/events. Values are UUIDs, zone ids, an enum and an instant: nothing to escape. */
	String toJson() {
		return """
				{"eventId":"%s","sensorId":"%s","zoneId":"%s","type":"%s","occurredAt":"%s"}"""
				.formatted(eventId, sensorId, zoneId, type, occurredAt);
	}

}
