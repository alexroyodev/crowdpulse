package com.alexroyodev.crowdpulse.occupancy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OccupancyRepository {

	private final JdbcClient jdbc;

	OccupancyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reads the zone's occupancy and locks its row until the transaction ends. Concurrent
	 * events for the same zone wait here, one after another, so no update is lost (rule 4).
	 * Events for other zones are not blocked. Must be called inside a transaction.
	 *
	 * @return empty if the zone does not exist
	 */
	public Optional<Occupancy> lockByZoneId(String zoneId) {
		return jdbc.sql("""
				SELECT occupancy, last_event_at
				FROM zone_occupancy
				WHERE zone_id = :zoneId
				FOR UPDATE
				""")
				.param("zoneId", zoneId)
				.query((rs, rowNum) -> {
					OffsetDateTime lastEventAt = rs.getObject("last_event_at", OffsetDateTime.class);
					return new Occupancy(rs.getInt("occupancy"), lastEventAt == null ? null : lastEventAt.toInstant());
				})
				.optional();
	}

	public void update(String zoneId, Occupancy occupancy) {
		jdbc.sql("""
				UPDATE zone_occupancy
				SET occupancy = :occupancy, last_event_at = :lastEventAt
				WHERE zone_id = :zoneId
				""")
				.param("occupancy", occupancy.value())
				.param("lastEventAt", toUtc(occupancy.lastEventAt()))
				.param("zoneId", zoneId)
				.update();
	}

	private static OffsetDateTime toUtc(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

}
