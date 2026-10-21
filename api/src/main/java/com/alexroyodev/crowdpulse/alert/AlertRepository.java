package com.alexroyodev.crowdpulse.alert;

import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

	private final JdbcClient jdbc;

	AlertRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Stores a level change. Must run in the transaction that stored its event (rule 7). */
	public void insert(Alert alert) {
		jdbc.sql("""
				INSERT INTO alert
				    (alert_id, zone_id, from_level, to_level, occupancy, capacity, event_id, occurred_at, detected_at)
				VALUES
				    (:alertId, :zoneId, :from, :to, :occupancy, :capacity, :eventId, :occurredAt, :detectedAt)
				""")
				.param("alertId", alert.alertId())
				.param("zoneId", alert.zoneId())
				.param("from", alert.from().name())
				.param("to", alert.to().name())
				.param("occupancy", alert.occupancy())
				.param("capacity", alert.capacity())
				.param("eventId", alert.eventId())
				.param("occurredAt", alert.occurredAt().atOffset(ZoneOffset.UTC))
				.param("detectedAt", alert.detectedAt().atOffset(ZoneOffset.UTC))
				.update();
	}

}
