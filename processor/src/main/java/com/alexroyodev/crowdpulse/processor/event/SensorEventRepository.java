package com.alexroyodev.crowdpulse.processor.event;

import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SensorEventRepository {

	private final JdbcClient jdbc;

	SensorEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Stores the event unless one with the same eventId already exists. The primary key
	 * decides, not a previous SELECT, so there is no race between "check" and "insert".
	 *
	 * @return true if stored, false if it was a duplicate
	 */
	boolean insertIfAbsent(SensorEvent event) {
		int rows = jdbc.sql("""
				INSERT INTO sensor_event
				    (event_id, sensor_id, zone_id, type, occurred_at, received_at, processed_at, anomaly)
				VALUES
				    (:eventId, :sensorId, :zoneId, :type, :occurredAt, :receivedAt, :processedAt, :anomaly)
				ON CONFLICT (event_id) DO NOTHING
				""")
				.param("eventId", event.eventId())
				.param("sensorId", event.sensorId())
				.param("zoneId", event.zoneId())
				.param("type", event.type().name())
				.param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
				.param("receivedAt", event.receivedAt().atOffset(ZoneOffset.UTC))
				.param("processedAt", event.processedAt().atOffset(ZoneOffset.UTC))
				.param("anomaly", event.anomaly())
				.update();
		return rows == 1;
	}

}
