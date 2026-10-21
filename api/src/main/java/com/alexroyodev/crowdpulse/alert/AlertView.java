package com.alexroyodev.crowdpulse.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.alexroyodev.crowdpulse.zone.ZoneView;
import com.fasterxml.jackson.annotation.JsonFormat;

/** One entry of GET /api/v1/alerts (spec 002). Instants in UTC with milliseconds, like the zones. */
public record AlertView(
		UUID alertId,
		String zoneId,
		AlertLevel from,
		AlertLevel to,
		int occupancy,
		int capacity,
		BigDecimal occupancyPct,
		UUID eventId,
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant occurredAt,
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant detectedAt) {

	static AlertView of(Alert alert) {
		return new AlertView(alert.alertId(), alert.zoneId(), alert.from(), alert.to(), alert.occupancy(),
				alert.capacity(), ZoneView.percentage(alert.occupancy(), alert.capacity()), alert.eventId(),
				alert.occurredAt(), alert.detectedAt());
	}

}
