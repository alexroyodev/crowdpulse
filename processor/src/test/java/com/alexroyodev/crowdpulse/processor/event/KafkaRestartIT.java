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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
 * Spec 004, CA3: Kafka stops answering (docker pause, 10 s) while the processor works through
 * 1 000 events. When it is back every event is applied once. No code of its own: the client
 * reconnects, and an offset commit lost in the outage only means a redelivery, which the stored
 * eventId absorbs (ADR 0004). Those mechanisms were validated by mutation in AtLeastOnceIT.
 */
@Import(KafkaRestartIT.OwnContainers.class)
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false",
		// 10 per fetch instead of 500: records already fetched are processed even with Kafka paused
		// (they only need PostgreSQL), so with big fetches the outage might not stop anything.
		"spring.kafka.consumer.max-poll-records=10" })
class KafkaRestartIT {

	// Under session.timeout.ms (45 s): the group is not rebuilt, the consumer just waits.
	private static final Duration OUTAGE = Duration.ofSeconds(10);

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

	private final KafkaContainer kafka;
	private final TopicPublisher topic;
	private final TopicProbe deadLetters;
	private final KafkaListenerEndpointRegistry listeners;
	private final JdbcClient jdbc;

	@Autowired
	KafkaRestartIT(KafkaContainer kafka, KafkaTemplate<String, String> template, JsonMapper json,
			ConsumerFactory<String, String> consumers, KafkaListenerEndpointRegistry listeners, JdbcClient jdbc) {
		this.kafka = kafka;
		this.topic = new TopicPublisher(template, json);
		this.deadLetters = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.listeners = listeners;
		this.jdbc = jdbc;
	}

	@Test
	void kafkaOutageWhileConsumingLosesNoEventAndCountsNoneTwice() throws Exception {
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
		try {
			Thread.sleep(OUTAGE.toMillis());
		}
		finally {
			unpause();
		}
		int storedAfterTheOutage = storedEvents();

		Eventually.await("the 1 000 events processed", Duration.ofSeconds(60), () -> storedEvents() == 1_000);
		assertThat(storedAfterTheOutage).as("the outage fell halfway").isLessThan(1_000);
		assertThat(occupancyOf("NORTE")).isEqualTo(expected.get("NORTE"));
		assertThat(occupancyOf("SUR")).isEqualTo(expected.get("SUR"));
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE anomaly").query(Integer.class).single()).isZero();
		assertThat(deadLetters.published()).isEmpty();
	}

	private void pause() {
		DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
	}

	private void unpause() {
		DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
