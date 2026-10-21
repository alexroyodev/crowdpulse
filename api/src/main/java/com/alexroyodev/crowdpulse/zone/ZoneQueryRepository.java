package com.alexroyodev.crowdpulse.zone;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.alexroyodev.crowdpulse.alert.AlertLevel;

/** Read side of the zone endpoints: catalogue joined with current occupancy. No cache (CA8). */
@Repository
class ZoneQueryRepository {

	private static final String SELECT_ZONES = """
			SELECT z.zone_id, z.name, z.capacity, o.occupancy, o.last_event_at, o.alert_level
			FROM zone z
			JOIN zone_occupancy o ON o.zone_id = z.zone_id
			""";

	private final JdbcClient jdbc;

	ZoneQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<ZoneView> findAll() {
		return jdbc.sql(SELECT_ZONES + "ORDER BY z.zone_id")
				.query(ZoneQueryRepository::toView)
				.list();
	}

	Optional<ZoneView> findById(String zoneId) {
		return jdbc.sql(SELECT_ZONES + "WHERE z.zone_id = :zoneId")
				.param("zoneId", zoneId)
				.query(ZoneQueryRepository::toView)
				.optional();
	}

	private static ZoneView toView(ResultSet rs, int rowNum) throws SQLException {
		// The PostgreSQL driver maps TIMESTAMPTZ to OffsetDateTime, not directly to Instant.
		OffsetDateTime lastEventAt = rs.getObject("last_event_at", OffsetDateTime.class);
		return ZoneView.of(
				rs.getString("zone_id"),
				rs.getString("name"),
				rs.getInt("capacity"),
				rs.getInt("occupancy"),
				lastEventAt == null ? null : lastEventAt.toInstant(),
				AlertLevel.valueOf(rs.getString("alert_level")));
	}

}
