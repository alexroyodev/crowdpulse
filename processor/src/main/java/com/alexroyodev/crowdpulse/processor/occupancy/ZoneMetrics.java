package com.alexroyodev.crowdpulse.processor.occupancy;

import java.util.List;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Spec 006, rule 1: occupancy, capacity and alert level of each zone as gauges, read from
 * PostgreSQL (the source of truth, principle 6), never kept in memory by the processor. So after a
 * restart they show the stored values straight away, not 0.
 */
@Component
public class ZoneMetrics {

	private static final Logger log = LoggerFactory.getLogger(ZoneMetrics.class);

	private final OccupancyRepository occupancies;
	private final MultiGauge occupancy;
	private final MultiGauge capacity;
	private final MultiGauge alertLevel;

	ZoneMetrics(OccupancyRepository occupancies, MeterRegistry registry) {
		this.occupancies = occupancies;
		this.occupancy = MultiGauge.builder("crowdpulse.zone.occupancy")
				.description("People in the zone, as stored in PostgreSQL")
				.register(registry);
		this.capacity = MultiGauge.builder("crowdpulse.zone.capacity")
				.description("Capacity of the zone")
				.register(registry);
		this.alertLevel = MultiGauge.builder("crowdpulse.zone.alert.level")
				.description("Alert level of the zone: 0 normal, 1 warning, 2 critical")
				.register(registry);
	}

	/**
	 * Reads every zone and replaces the gauges' values; new zones get their own series. The first
	 * run is at startup (no initial delay). Querying here, not on each Prometheus scrape: with the
	 * database down, a scrape would wait for the connection timeout. The cost is that the gauges
	 * can be up to one period behind the database.
	 */
	@Scheduled(fixedDelayString = "${crowdpulse.metrics.zone-refresh}")
	public void refresh() {
		List<ZoneState> zones;
		try {
			zones = occupancies.findAll();
		}
		catch (DataAccessException ex) {
			// DatabaseOutage already logs the outage; the gauges keep their last values meanwhile.
			log.atDebug().setMessage("Zone metrics not refreshed: database unavailable")
					.addKeyValue("cause", ex.getMostSpecificCause().toString())
					.log();
			return;
		}
		occupancy.register(rows(zones, ZoneState::occupancy), true);
		capacity.register(rows(zones, ZoneState::capacity), true);
		alertLevel.register(rows(zones, ZoneMetrics::levelNumber), true);
	}

	private static List<MultiGauge.Row<?>> rows(List<ZoneState> zones, ToIntFunction<ZoneState> value) {
		return zones.stream()
				.<MultiGauge.Row<?>>map(zone -> MultiGauge.Row.of(Tags.of("zone", zone.zoneId()), value.applyAsInt(zone)))
				.toList();
	}

	/** Explicit, not ordinal(): reordering the enum must not change what the dashboard shows. */
	private static int levelNumber(ZoneState zone) {
		return switch (zone.alertLevel()) {
			case NORMAL -> 0;
			case WARNING -> 1;
			case CRITICAL -> 2;
		};
	}

}
