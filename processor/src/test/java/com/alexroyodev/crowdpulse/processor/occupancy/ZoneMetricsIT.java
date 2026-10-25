package com.alexroyodev.crowdpulse.processor.occupancy;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.AlertLevel;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Spec 006, CA4: the zone gauges show what PostgreSQL has. refresh() is called directly instead
 * of waiting for the scheduler; it is the same method the scheduler calls.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ZoneMetricsIT {

	private final ZoneMetrics zoneMetrics;
	private final EventProcessor processor;
	private final MeterRegistry registry;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ZoneMetricsIT(ZoneMetrics zoneMetrics, EventProcessor processor, MeterRegistry registry, JdbcClient jdbc,
			Clock clock) {
		this.zoneMetrics = zoneMetrics;
		this.processor = processor;
		this.registry = registry;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void gaugesMatchTheDatabaseAfterProcessingEvents() {
		processor.process(message("NORTE", ENTRY, clock.instant()));
		processor.process(message("NORTE", ENTRY, clock.instant()));
		processor.process(message("NORTE", EXIT, clock.instant()));
		processor.process(message("SUR", ENTRY, clock.instant()));

		zoneMetrics.refresh();

		List<ZoneState> stored = jdbc.sql("""
				SELECT o.zone_id, o.occupancy, z.capacity, o.alert_level
				FROM zone_occupancy o JOIN zone z ON z.zone_id = o.zone_id
				""")
				.query((rs, rowNum) -> new ZoneState(rs.getString("zone_id"), rs.getInt("occupancy"),
						rs.getInt("capacity"), AlertLevel.valueOf(rs.getString("alert_level"))))
				.list();
		assertThat(stored).isNotEmpty();
		for (ZoneState zone : stored) {
			assertThat(gauge("crowdpulse.zone.occupancy", zone.zoneId())).as(zone.zoneId()).isEqualTo(zone.occupancy());
			assertThat(gauge("crowdpulse.zone.capacity", zone.zoneId())).as(zone.zoneId()).isEqualTo(zone.capacity());
		}
		assertThat(gauge("crowdpulse.zone.occupancy", "NORTE")).isEqualTo(1);
		assertThat(gauge("crowdpulse.zone.occupancy", "SUR")).isEqualTo(1);
	}

	@Test
	void gaugesShowWhatIsStoredEvenWithoutProcessingAnyEvent() {
		// As after a restart: the state comes from before, nothing has been processed since.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 13000, alert_level = 'WARNING' WHERE zone_id = 'ESTE'").update();

		zoneMetrics.refresh();

		assertThat(gauge("crowdpulse.zone.occupancy", "ESTE")).isEqualTo(13_000);
		assertThat(gauge("crowdpulse.zone.alert.level", "ESTE")).isEqualTo(1);
	}

	private double gauge(String name, String zoneId) {
		return registry.get(name).tag("zone", zoneId).gauge().value();
	}

}
