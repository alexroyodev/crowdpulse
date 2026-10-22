package com.alexroyodev.crowdpulse.event;

import java.time.Instant;
import java.util.UUID;

import com.alexroyodev.crowdpulse.shared.EventType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/events (event contract v1, spec 001). Validated at the edge: the
 * service only ever receives valid requests. Whether zoneId exists is checked by the service,
 * because it needs the database.
 */
public record EventRequest(
		@NotNull UUID eventId,
		@NotBlank @Size(max = 50) String sensorId,
		@NotBlank String zoneId,
		@NotNull EventType type,
		@NotNull @NotTooFarInFuture Instant occurredAt) {
}
