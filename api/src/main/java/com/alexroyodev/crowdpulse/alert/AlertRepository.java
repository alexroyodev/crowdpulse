package com.alexroyodev.crowdpulse.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

@Repository
public class AlertRepository {

	private static final String SELECT_ALERTS = """
			SELECT alert_id, zone_id, from_level, to_level, occupancy, capacity, event_id, occurred_at, detected_at
			FROM alert
			""";

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

	/** The most recent changes first; seq is the insertion order, stable even with equal instants. */
	List<Alert> findLatest(int limit) {
		return jdbc.sql(SELECT_ALERTS + "ORDER BY seq DESC LIMIT :limit")
				.param("limit", limit)
				.query(AlertRepository::toAlert)
				.list();
	}

	List<Alert> findLatestOfZone(String zoneId, int limit) {
		return jdbc.sql(SELECT_ALERTS + "WHERE zone_id = :zoneId ORDER BY seq DESC LIMIT :limit")
				.param("zoneId", zoneId)
				.param("limit", limit)
				.query(AlertRepository::toAlert)
				.list();
	}

	private static Alert toAlert(ResultSet rs, int rowNum) throws SQLException {
		return new Alert(
				rs.getObject("alert_id", UUID.class),
				rs.getString("zone_id"),
				AlertLevel.valueOf(rs.getString("from_level")),
				AlertLevel.valueOf(rs.getString("to_level")),
				rs.getInt("occupancy"),
				rs.getInt("capacity"),
				rs.getObject("event_id", UUID.class),
				rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				rs.getObject("detected_at", OffsetDateTime.class).toInstant());
	}

}
