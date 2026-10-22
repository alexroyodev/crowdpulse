package com.alexroyodev.crowdpulse.shared;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Message from the API to the processor, version 1 (spec 003): the event as the sensor sent it
 * plus {@code receivedAt}, added by the API. Published as JSON on {@link Topics#SENSOR_EVENTS}
 * with {@code zoneId} as key and the version in the {@link Topics#MESSAGE_VERSION_HEADER} header.
 * An incompatible change goes in a new version, never in this one. The constraints are the same as
 * the API's EventRequest; the processor checks them on every message it receives.
 *
 * @param receivedAt when the API accepted the event (processing time), unlike occurredAt (event time)
 */
public record SensorEventMessage(
		@NotNull UUID eventId,
		@NotBlank String sensorId,
		@NotBlank String zoneId,
		@NotNull EventType type,
		@NotNull Instant occurredAt,
		@NotNull Instant receivedAt) {
}
