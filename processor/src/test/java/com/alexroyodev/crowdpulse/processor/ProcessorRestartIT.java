package com.alexroyodev.crowdpulse.processor;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 001 CA8 and spec 002 CA10, now for the processor (moved from the API's RestartIT): after a
 * restart the state is the same and processing goes on from it, because PostgreSQL is the source
 * of truth (principle 6). Spec 003 CA6: a restart halfway through the topic loses no event and
 * counts none twice. The application is started twice for real, against one database that
 * outlives both runs; Spring's test context cache is not involved.
 */
class ProcessorRestartIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	// Its own broker: without it the processor would connect to localhost:9092, compose's Kafka.
	private static final KafkaContainer kafka = new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);

	private static JdbcClient jdbc;

	record ZoneRow(String zoneId, int occupancy, String alertLevel) {
	}

	record AlertRow(String zoneId, String fromLevel, String toLevel, int occupancy) {
	}

	@BeforeAll
	static void startDatabase() {
		postgres.start();
		kafka.start();
		jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
		kafka.stop();
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

	@Test
	void restartHalfwayThroughAThousandEventsLosesNoneAndCountsNoneTwice() {
		// Spec 003, CA6. NORTE and SUR get 500 events each: ENTRY, ENTRY, EXIT... never below 0.
		List<SensorEventMessage> messages = new ArrayList<>();
		Map<String, Integer> expected = new HashMap<>();
		for (int i = 0; i < 1_000; i++) {
			String zoneId = i % 2 == 0 ? "NORTE" : "SUR";
			EventType type = (i / 2) % 3 == 2 ? EXIT : ENTRY;
			messages.add(message(zoneId, type, Clock.systemUTC().instant()));
			expected.merge(zoneId, type == ENTRY ? 1 : -1, Integer::sum);
		}

		int storedWhenStopped;
		// The listener starts only once all 1 000 are in the topic, so the stop really is halfway.
		try (ConfigurableApplicationContext app = startProcessor("--spring.kafka.listener.auto-startup=false")) {
			TopicPublisher topic = new TopicPublisher(app.getBean(KafkaTemplate.class), app.getBean(JsonMapper.class));
			messages.forEach(topic::publish);
			app.getBean(KafkaListenerEndpointRegistry.class).start();
			Eventually.await("some events processed", () -> storedEvents() >= 100);
		}
		storedWhenStopped = storedEvents();

		try (ConfigurableApplicationContext app = startProcessor()) {
			Eventually.await("the 1 000 events processed", () -> storedEvents() == 1_000);
		}

		assertThat(storedWhenStopped).as("stopped halfway").isBetween(100, 999);
		assertThat(zones()).contains(new ZoneRow("NORTE", expected.get("NORTE"), "NORMAL"),
				new ZoneRow("SUR", expected.get("SUR"), "NORMAL"));
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE anomaly").query(Integer.class).single()).isZero();
	}

	/** Command-line arguments, not builder properties: those are defaults and application.yml (port 8081) wins. */
	private static ConfigurableApplicationContext startProcessor(String... extraArguments) {
		List<String> arguments = new ArrayList<>(List.of(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()));
		arguments.addAll(List.of(extraArguments));
		return new SpringApplicationBuilder(ProcessorApplication.class).run(arguments.toArray(String[]::new));
	}

	private static void process(ConfigurableApplicationContext app, String zoneId, EventType type) {
		app.getBean(EventProcessor.class).process(message(zoneId, type, Clock.systemUTC().instant()));
	}

	private static int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
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
