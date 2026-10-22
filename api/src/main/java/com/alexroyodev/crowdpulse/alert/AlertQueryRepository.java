package com.alexroyodev.crowdpulse.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.alexroyodev.crowdpulse.shared.AlertLevel;
import com.alexroyodev.crowdpulse.shared.Percentages;

/** Read side of GET /api/v1/alerts. The processor writes the history (spec 003). */
@Repository
class AlertQueryRepository {

	private static final String SELECT_ALERTS = """
			SELECT alert_id, zone_id, from_level, to_level, occupancy, capacity, event_id, occurred_at, detected_at
			FROM alert
			""";

	private final JdbcClient jdbc;

	AlertQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The most recent changes first; seq is the insertion order, stable even with equal instants. */
	List<AlertView> findLatest(int limit) {
		return jdbc.sql(SELECT_ALERTS + "ORDER BY seq DESC LIMIT :limit")
				.param("limit", limit)
				.query(AlertQueryRepository::toView)
				.list();
	}

	List<AlertView> findLatestOfZone(String zoneId, int limit) {
		return jdbc.sql(SELECT_ALERTS + "WHERE zone_id = :zoneId ORDER BY seq DESC LIMIT :limit")
				.param("zoneId", zoneId)
				.param("limit", limit)
				.query(AlertQueryRepository::toView)
				.list();
	}

	private static AlertView toView(ResultSet rs, int rowNum) throws SQLException {
		int occupancy = rs.getInt("occupancy");
		int capacity = rs.getInt("capacity");
		return new AlertView(
				rs.getObject("alert_id", UUID.class),
				rs.getString("zone_id"),
				AlertLevel.valueOf(rs.getString("from_level")),
				AlertLevel.valueOf(rs.getString("to_level")),
				occupancy,
				capacity,
				Percentages.of(occupancy, capacity),
				rs.getObject("event_id", UUID.class),
				rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				rs.getObject("detected_at", OffsetDateTime.class).toInstant());
	}

}
