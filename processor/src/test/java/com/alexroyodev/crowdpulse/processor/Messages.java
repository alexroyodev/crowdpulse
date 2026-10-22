package com.alexroyodev.crowdpulse.processor;

import java.time.Instant;
import java.util.UUID;

import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/** Test helper: messages as the API would publish them. */
public final class Messages {

	private Messages() {
	}

	public static SensorEventMessage message(UUID eventId, String zoneId, EventType type, Instant occurredAt,
			Instant receivedAt) {
		return new SensorEventMessage(eventId, zoneId + "-P01", zoneId, type, occurredAt, receivedAt);
	}

	/** A new event that occurred one second before {@code receivedAt}. */
	public static SensorEventMessage message(String zoneId, EventType type, Instant receivedAt) {
		return message(UUID.randomUUID(), zoneId, type, receivedAt.minusSeconds(1), receivedAt);
	}

}
