package com.alexroyodev.crowdpulse.processor;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.EventType;

/**
 * Spec 001 CA8 and spec 002 CA10, now for the processor (moved from the API's RestartIT): after a
 * restart the state is the same and processing goes on from it, because PostgreSQL is the source
 * of truth (principle 6). The application is started twice for real, against one database that
 * outlives both runs; Spring's test context cache is not involved.
 */
class ProcessorRestartIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	private static JdbcClient jdbc;

	record ZoneRow(String zoneId, int occupancy, String alertLevel) {
	}

	record AlertRow(String zoneId, String fromLevel, String toLevel, int occupancy) {
	}

	@BeforeAll
	static void startDatabase() {
		postgres.start();
		jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@BeforeEach
	void resetStateOnceMigrated() {
		// The first run creates the schema; later tests start from the state right after the migrations.
		if (jdbc.sql("SELECT to_regclass('alert') IS NOT NULL").query(Boolean.class).single()) {
			jdbc.sql("DELETE FROM alert").update();
			jdbc.sql("DELETE FROM sensor_event").update();
			jdbc.sql("UPDATE zone_occupancy SET occupancy = 0, last_event_at = NULL, alert_level = 'NORMAL'").update();
		}
	}

	@Test
	void occupancySurvivesARestart() {
		List<ZoneRow> before;
		try (ConfigurableApplicationContext app = startProcessor()) {
			process(app, "NORTE", ENTRY);
			process(app, "NORTE", ENTRY);
			process(app, "NORTE", ENTRY);
			process(app, "NORTE", EXIT);
			process(app, "SUR", ENTRY);
			before = zones();
		}

		List<ZoneRow> after;
		try (ConfigurableApplicationContext app = startProcessor()) {
			after = zones();
			process(app, "NORTE", ENTRY);
		}

		assertThat(before).contains(new ZoneRow("NORTE", 2, "NORMAL"), new ZoneRow("SUR", 1, "NORMAL"));
		assertThat(after).isEqualTo(before);
		assertThat(zones()).contains(new ZoneRow("NORTE", 3, "NORMAL"));
	}

	@Test
	void alertLevelAndHistorySurviveARestart() {
		// Spec 002, CA10: 171 ENTRY take PALCO (200 seats) past 85 %.
		List<ZoneRow> zonesBefore;
		List<AlertRow> alertsBefore;
		try (ConfigurableApplicationContext app = startProcessor()) {
			for (int i = 0; i < 171; i++) {
				process(app, "PALCO", ENTRY);
			}
			zonesBefore = zones();
			alertsBefore = alerts();
		}

		List<ZoneRow> zonesAfter;
		List<AlertRow> alertsAfter;
		try (ConfigurableApplicationContext app = startProcessor()) {
			zonesAfter = zones();
			alertsAfter = alerts();
			// 170 = 85.0 %: still WARNING, and no new change, because the level was read back from the database.
			process(app, "PALCO", EXIT);
		}

		assertThat(zonesBefore).contains(new ZoneRow("PALCO", 171, "WARNING"));
		assertThat(alertsBefore).containsExactly(new AlertRow("PALCO", "NORMAL", "WARNING", 170));
		assertThat(zonesAfter).isEqualTo(zonesBefore);
		assertThat(alertsAfter).isEqualTo(alertsBefore);
		assertThat(zones()).contains(new ZoneRow("PALCO", 170, "WARNING"));
		assertThat(alerts()).isEqualTo(alertsBefore);
	}

	private static ConfigurableApplicationContext startProcessor() {
		return new SpringApplicationBuilder(ProcessorApplication.class)
				.properties(
						"server.port=0",
						"spring.docker.compose.enabled=false",
						"spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword())
				.run();
	}

	private static void process(ConfigurableApplicationContext app, String zoneId, EventType type) {
		app.getBean(EventProcessor.class).process(message(zoneId, type, Clock.systemUTC().instant()));
	}

	private static List<ZoneRow> zones() {
		return jdbc.sql("SELECT zone_id, occupancy, alert_level FROM zone_occupancy ORDER BY zone_id")
				.query(ZoneRow.class).list();
	}

	private static List<AlertRow> alerts() {
		return jdbc.sql("SELECT zone_id, from_level, to_level, occupancy FROM alert ORDER BY seq")
				.query(AlertRow.class).list();
	}

}
