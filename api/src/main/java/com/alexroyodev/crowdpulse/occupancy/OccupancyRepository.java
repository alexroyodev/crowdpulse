package com.alexroyodev.crowdpulse.occupancy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.alexroyodev.crowdpulse.alert.AlertLevel;

@Repository
public class OccupancyRepository {

	private final JdbcClient jdbc;

	OccupancyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reads the zone's occupancy, capacity and alert level, and locks its occupancy row until the
	 * transaction ends. Concurrent events for the same zone wait here, one after another, so no
	 * update is lost (rule 4) and no threshold crossing is evaluated twice (spec 002, rule 8).
	 * Events for other zones are not blocked. {@code FOR UPDATE OF o} locks only the occupancy
	 * row, not the catalogue row of the zone. Must be called inside a transaction.
	 *
	 * @return empty if the zone does not exist
	 */
	public Optional<LockedZone> lockByZoneId(String zoneId) {
		return jdbc.sql("""
				SELECT o.occupancy, o.last_event_at, o.alert_level, z.capacity
				FROM zone_occupancy o
				JOIN zone z ON z.zone_id = o.zone_id
				WHERE o.zone_id = :zoneId
				FOR UPDATE OF o
				""")
				.param("zoneId", zoneId)
				.query((rs, rowNum) -> {
					OffsetDateTime lastEventAt = rs.getObject("last_event_at", OffsetDateTime.class);
					Occupancy occupancy = new Occupancy(rs.getInt("occupancy"),
							lastEventAt == null ? null : lastEventAt.toInstant());
					return new LockedZone(occupancy, rs.getInt("capacity"), AlertLevel.valueOf(rs.getString("alert_level")));
				})
				.optional();
	}

	public void update(String zoneId, Occupancy occupancy, AlertLevel alertLevel) {
		jdbc.sql("""
				UPDATE zone_occupancy
				SET occupancy = :occupancy, last_event_at = :lastEventAt, alert_level = :alertLevel
				WHERE zone_id = :zoneId
				""")
				.param("occupancy", occupancy.value())
				.param("lastEventAt", toUtc(occupancy.lastEventAt()))
				.param("alertLevel", alertLevel.name())
				.param("zoneId", zoneId)
				.update();
	}

	private static OffsetDateTime toUtc(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

}
