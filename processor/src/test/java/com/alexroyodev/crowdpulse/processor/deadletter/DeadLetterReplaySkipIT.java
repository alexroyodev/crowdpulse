package com.alexroyodev.crowdpulse.processor.deadletter;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 005, CA2 and CA3: the replay skips content failures (a broken JSON stays in the DLT and
 * does not bounce), and {@code ?all=true} replays the whole DLT, skipped ones included, without
 * counting anything twice. Its own containers: {@code all} rereads the DLT from the beginning, so
 * it must hold only what this test put there.
 */
@Import(DeadLetterReplaySkipIT.OwnContainers.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DeadLetterReplaySkipIT {

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

	private static final String NEW_ZONE = "GRADA";

	@LocalServerPort
	int port;

	private final TopicPublisher topic;
	private final TopicProbe events;
	/** Never marked: everything in the DLT since the beginning. */
	private final TopicProbe wholeDeadLetterTopic;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	DeadLetterReplaySkipIT(KafkaTemplate<String, String> kafka, JsonMapper json,
			ConsumerFactory<String, String> consumers, JdbcClient jdbc, Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.events = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
		this.wholeDeadLetterTopic = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void replaySkipsContentFailuresAndAllReplaysEverything() {
		String brokenJson = "{\"eventId\": ";
		SensorEventMessage unknownZone = message(NEW_ZONE, ENTRY, clock.instant());
		topic.publishRaw("NORTE", brokenJson, Topics.MESSAGE_VERSION);
		topic.publish(unknownZone);
		Eventually.await("both in the DLT", () -> wholeDeadLetterTopic.published().size() == 2);
		// Fixing the cause of one of them: the zone now exists.
		jdbc.sql("INSERT INTO zone (zone_id, name, capacity) VALUES (:zoneId, 'Grada nueva', 1000)")
				.param("zoneId", NEW_ZONE).update();
		jdbc.sql("INSERT INTO zone_occupancy (zone_id) VALUES (:zoneId)").param("zoneId", NEW_ZONE).update();

		// CA2: the broken JSON is skipped, the other is applied.
		events.mark();
		assertThat(replay("")).isEqualTo("{\"replayed\":1,\"skipped\":1}");
		Eventually.await("the fixed event is applied", () -> isStored(unknownZone.eventId()));
		assertThat(events.published()).singleElement()
				.satisfies(record -> assertThat(record.key()).isEqualTo(NEW_ZONE));
		assertThat(wholeDeadLetterTopic.published()).as("the broken JSON did not bounce").hasSize(2);

		// CA3: ?all=true replays both from the beginning; the broken JSON comes back to the DLT.
		assertThat(replay("?all=true")).isEqualTo("{\"replayed\":2,\"skipped\":0}");
		Eventually.await("the broken JSON is back in the DLT", () -> wholeDeadLetterTopic.published().size() == 3);
		assertThat(wholeDeadLetterTopic.published().get(2).value()).isEqualTo(brokenJson);
		// The replayed copy of the applied event comes before this one (same zone, same partition).
		SensorEventMessage marker = message(NEW_ZONE, ENTRY, clock.instant());
		topic.publish(marker);
		Eventually.await("the marker is applied", () -> isStored(marker.eventId()));
		assertThat(occupancyOf(NEW_ZONE)).as("the replayed copy did not count twice").isEqualTo(2);
	}

	private String replay(String query) {
		return RestClient.create("http://127.0.0.1:" + port).post().uri("/admin/dead-letters/replay" + query)
				.retrieve().body(String.class);
	}

	private boolean isStored(UUID eventId) {
		return jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", eventId).query(Integer.class).single() == 1;
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
