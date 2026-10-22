package com.alexroyodev.crowdpulse.zone;

import java.math.BigDecimal;
import java.time.Instant;

import com.alexroyodev.crowdpulse.shared.AlertLevel;
import com.alexroyodev.crowdpulse.shared.Percentages;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Response body of the zone endpoints (spec 001).
 *
 * @param occupancyPct occupancy as a percentage of capacity, one decimal; may exceed 100
 * @param lastEventAt most recent {@code occurredAt} applied to the zone, or null if none; always
 *        written in UTC with milliseconds, as in the spec example (2026-10-19T18:30:05.123Z)
 * @param alertLevel current alert level of the zone (spec 002)
 */
public record ZoneView(
		String zoneId,
		String name,
		int capacity,
		int occupancy,
		BigDecimal occupancyPct,
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant lastEventAt,
		AlertLevel alertLevel) {

	public static ZoneView of(String zoneId, String name, int capacity, int occupancy, Instant lastEventAt,
			AlertLevel alertLevel) {
		return new ZoneView(zoneId, name, capacity, occupancy, Percentages.of(occupancy, capacity), lastEventAt,
				alertLevel);
	}

}
