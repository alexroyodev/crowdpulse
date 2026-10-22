package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 004, CA1 and CA2: PostgreSQL stops answering (docker pause) while the processor works
 * through 1 000 events. The processor waits for it without limit: when it is back, the occupancy
 * is exact, nothing is out of order and nothing went to the dead-letter topic. Meanwhile its
 * health is DOWN, and the outage is logged once when it starts and once when it ends. Its own
 * containers, so that pausing the database does not affect the other tests.
 */
@Import(DatabaseOutageIT.OwnContainers.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		// The listener starts once the 1 000 events are in the topic, so the pause falls halfway.
		properties = "spring.kafka.listener.auto-startup=false")
@ExtendWith(OutputCaptureExtension.class)
class DatabaseOutageIT {

	// As in CA1. The first failure only shows when the query in flight hits socketTimeout (10 s); a
	// shorter pause would not exhaust the 3 ordinary attempts, and the DLT check would prove nothing.
	private static final Duration OUTAGE = Duration.ofSeconds(30);

	@TestConfiguration(proxyBeanMethods = false)
	static class OwnContainers {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);
		}

	}

	@LocalServerPort
	int port;

	private final PostgreSQLContainer postgres;
	private final TopicPublisher topic;
	private final TopicProbe deadLetters;
	private final KafkaListenerEndpointRegistry listeners;
	private final JdbcClient jdbc;

	@Autowired
	DatabaseOutageIT(PostgreSQLContainer postgres, KafkaTemplate<String, String> kafka, JsonMapper json,
			ConsumerFactory<String, String> consumers, KafkaListenerEndpointRegistry listeners, JdbcClient jdbc) {
		this.postgres = postgres;
		this.topic = new TopicPublisher(kafka, json);
		this.deadLetters = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.listeners = listeners;
		this.jdbc = jdbc;
	}

	@Test
	void eventsWaitForTheDatabaseAndNoneIsLostDuplicatedReorderedOrDeadLettered(CapturedOutput output)
			throws Exception {
		// NORTE and SUR get 500 events each: ENTRY, ENTRY, EXIT... Out of order, an EXIT could hit 0.
		Map<String, Integer> expected = new HashMap<>();
		List<SensorEventMessage> messages = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			String zoneId = i % 2 == 0 ? "NORTE" : "SUR";
			EventType type = (i / 2) % 3 == 2 ? EXIT : ENTRY;
			messages.add(message(zoneId, type, Clock.systemUTC().instant()));
			expected.merge(zoneId, type == ENTRY ? 1 : -1, Integer::sum);
		}
		deadLetters.mark();
		messages.forEach(topic::publish);
		listeners.start();
		Eventually.await("some events processed", () -> storedEvents() >= 200);

		pause();
		String healthDuringOutage;
		try {
			Thread.sleep(OUTAGE.toMillis() / 2);
			// No queries from the test while paused: they would fail too. The health check is the processor's.
			healthDuringOutage = health();
			Thread.sleep(OUTAGE.toMillis() / 2);
		}
		finally {
			unpause();
		}

		// After the pause the next retry may be up to 30 s away (1, 2, 4, 8, 16, 30 s...).
		Eventually.await("the 1 000 events processed", Duration.ofSeconds(90), () -> storedEvents() == 1_000);
		assertThat(healthDuringOutage).contains("\"status\":\"DOWN\"");
		assertThat(occupancyOf("NORTE")).isEqualTo(expected.get("NORTE"));
		assertThat(occupancyOf("SUR")).isEqualTo(expected.get("SUR"));
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE anomaly").query(Integer.class).single()).isZero();
		assertThat(deadLetters.published()).isEmpty();
		assertThat(output.getOut().lines().filter(line -> line.contains("Database unavailable"))).hasSize(1);
		assertThat(output.getOut().lines().filter(line -> line.contains("Database available again"))).hasSize(1);
	}

	private String health() {
		return RestClient.create("http://127.0.0.1:" + port).get().uri("/actuator/health")
				.exchange((request, response) -> new String(response.getBody().readAllBytes()));
	}

	private void pause() {
		DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
	}

	private void unpause() {
		DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
