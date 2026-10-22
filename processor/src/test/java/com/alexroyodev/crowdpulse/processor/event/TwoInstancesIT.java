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

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.ProcessorApplication;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 004, CA4: two processors in the same consumer group share the 3 partitions; one is closed
 * halfway through 1 000 events and the other takes its partitions over and finishes. The result
 * is exact and nothing is out of order. No code of its own: one consumer per partition, offsets
 * committed after the transaction, the zone lock and the eventId (ADR 0002, ADR 0004).
 */
class TwoInstancesIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	private static final KafkaContainer kafka = new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);

	private static JdbcClient jdbc;

	@BeforeAll
	static void startContainers() {
		postgres.start();
		kafka.start();
		jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
	}

	@AfterAll
	static void stopContainers() {
		postgres.stop();
		kafka.stop();
	}

	@Test
	void whenOneOfTwoProcessorsStopsTheOtherFinishesExactlyAndInOrder() {
		Map<String, Integer> expected = new HashMap<>();
		List<SensorEventMessage> messages = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			// Three zones, so that the partitions of both instances have work.
			String zoneId = List.of("NORTE", "SUR", "ESTE").get(i % 3);
			EventType type = (i / 3) % 3 == 2 ? EXIT : ENTRY;
			messages.add(message(zoneId, type, Clock.systemUTC().instant()));
			expected.merge(zoneId, type == ENTRY ? 1 : -1, Integer::sum);
		}

		int storedWhenOneStopped;
		try (ConfigurableApplicationContext first = startProcessor()) {
			try (ConfigurableApplicationContext second = startProcessor()) {
				// Both listeners start only once the 1 000 events are in the topic.
				TopicPublisher topic = new TopicPublisher(first.getBean(KafkaTemplate.class), first.getBean(JsonMapper.class));
				messages.forEach(topic::publish);
				listenerOf(first).start();
				listenerOf(second).start();
				Eventually.await("both instances have partitions", Duration.ofSeconds(30),
						() -> !partitionsOf(first).isEmpty() && !partitionsOf(second).isEmpty());
				assertThat(partitionsOf(first).size() + partitionsOf(second).size()).isEqualTo(3);
				Eventually.await("some events processed", () -> storedEvents() >= 200);
			}
			storedWhenOneStopped = storedEvents();
			Eventually.await("the remaining instance has every partition", Duration.ofSeconds(30),
					() -> partitionsOf(first).size() == 3);
			Eventually.await("the 1 000 events processed", Duration.ofSeconds(60), () -> storedEvents() == 1_000);
		}

		assertThat(storedWhenOneStopped).as("stopped halfway").isLessThan(1_000);
		expected.forEach((zoneId, occupancy) -> assertThat(occupancyOf(zoneId)).as(zoneId).isEqualTo(occupancy));
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE anomaly").query(Integer.class).single()).isZero();
	}

	/** Command-line arguments, not builder properties: those are defaults and application.yml (port 8081) wins. */
	private static ConfigurableApplicationContext startProcessor() {
		return new SpringApplicationBuilder(ProcessorApplication.class).run(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.kafka.listener.auto-startup=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
	}

	private static MessageListenerContainer listenerOf(ConfigurableApplicationContext app) {
		return app.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(SensorEventListener.ID);
	}

	private static List<TopicPartition> partitionsOf(ConfigurableApplicationContext app) {
		var assigned = listenerOf(app).getAssignedPartitions();
		return assigned == null ? List.of() : List.copyOf(assigned);
	}

	private static int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

	private static int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
