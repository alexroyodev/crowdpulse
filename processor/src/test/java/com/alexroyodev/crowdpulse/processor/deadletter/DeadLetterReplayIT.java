package com.alexroyodev.crowdpulse.processor.deadletter;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 004, D2 / rule 5 / CA5: once the cause is fixed, POST /admin/dead-letters/replay puts the
 * dead-lettered messages back on the events topic. Each is replayed once, and replaying an event
 * that was already applied does not count it twice. Before each test the replay group is moved to
 * the end of the DLT, so no test depends on what another one left behind.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class DeadLetterReplayIT {

	/** A zone that is not in the catalogue until the test "fixes the cause" by adding it. */
	private static final String NEW_ZONE = "GRADA";

	@LocalServerPort
	int port;

	private final TopicPublisher topic;
	private final TopicProbe events;
	private final TopicProbe deadLetters;
	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	DeadLetterReplayIT(KafkaTemplate<String, String> kafka, JsonMapper json, ConsumerFactory<String, String> consumers,
			JdbcClient jdbc, Clock clock) {
		this.consumers = consumers;
		this.topic = new TopicPublisher(kafka, json);
		this.events = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
		this.deadLetters = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.kafka = kafka;
		this.json = json;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	private final ConsumerFactory<String, String> consumers;

	@BeforeEach
	void markTopicsAndSkipPendingDeadLetters() {
		events.mark();
		deadLetters.mark();
		Properties manualCommit = new Properties();
		manualCommit.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		try (Consumer<String, String> consumer = consumers.createConsumer(DeadLetterReplayer.GROUP, "-test", null,
				manualCommit)) {
			List<TopicPartition> partitions = consumer.partitionsFor(Topics.SENSOR_EVENTS_DLT).stream()
					.map(info -> new TopicPartition(info.topic(), info.partition()))
					.toList();
			Map<TopicPartition, OffsetAndMetadata> end = new HashMap<>();
			consumer.endOffsets(partitions).forEach((partition, offset) -> end.put(partition, new OffsetAndMetadata(offset)));
			consumer.commitSync(end);
		}
	}

	@AfterEach
	void removeTheNewZone() {
		// Before reset-state.sql (which runs after this): the catalogue only changes here.
		jdbc.sql("DELETE FROM alert WHERE zone_id = :zoneId").param("zoneId", NEW_ZONE).update();
		jdbc.sql("DELETE FROM sensor_event WHERE zone_id = :zoneId").param("zoneId", NEW_ZONE).update();
		jdbc.sql("DELETE FROM zone_occupancy WHERE zone_id = :zoneId").param("zoneId", NEW_ZONE).update();
		jdbc.sql("DELETE FROM zone WHERE zone_id = :zoneId").param("zoneId", NEW_ZONE).update();
	}

	@Test
	void replayAppliesWhatFailedOnceTheCauseIsFixedAndOnlyOnce() {
		SensorEventMessage message = message(NEW_ZONE, ENTRY, clock.instant());
		topic.publish(message);
		Eventually.await("the event is dead-lettered", () -> deadLetters.published().size() == 1);

		// Fixing the cause: the zone now exists.
		jdbc.sql("INSERT INTO zone (zone_id, name, capacity) VALUES (:zoneId, 'Grada nueva', 1000)")
				.param("zoneId", NEW_ZONE).update();
		jdbc.sql("INSERT INTO zone_occupancy (zone_id) VALUES (:zoneId)").param("zoneId", NEW_ZONE).update();
		events.mark();

		assertThat(replay()).isEqualTo("{\"replayed\":1}");

		Eventually.await("the replayed event is applied", () -> isStored(message.eventId()));
		assertThat(occupancyOf(NEW_ZONE)).isEqualTo(1);
		// Back on the events topic as the API published it: same key and value, version header, no DLT headers.
		ConsumerRecord<String, String> replayed = events.published().get(0);
		assertThat(replayed.key()).isEqualTo(NEW_ZONE);
		assertThat(json.readValue(replayed.value(), SensorEventMessage.class)).isEqualTo(message);
		assertThat(new String(replayed.headers().lastHeader(Topics.MESSAGE_VERSION_HEADER).value(), UTF_8))
				.isEqualTo(Topics.MESSAGE_VERSION);
		assertThat(replayed.headers()).extracting(Header::key).noneMatch(key -> key.startsWith("kafka_dlt-"));
		// Nothing new in the DLT since then, so a second call has nothing to replay.
		assertThat(replay()).isEqualTo("{\"replayed\":0}");
	}

	@Test
	void replayingAnEventThatWasAlreadyAppliedDoesNotCountItTwice() throws Exception {
		// E.g. dead-lettered by an earlier processor version and also applied later on.
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
		topic.publish(message);
		Eventually.await("the event is applied", () -> isStored(message.eventId()));
		putInTheDeadLetterTopic(message);

		assertThat(replay()).isEqualTo("{\"replayed\":1}");
		// The redelivered copy is consumed before this later event of the same zone (same partition).
		SensorEventMessage marker = message("NORTE", ENTRY, clock.instant());
		topic.publish(marker);

		Eventually.await("the marker is applied", () -> isStored(marker.eventId()));
		assertThat(occupancyOf("NORTE")).isEqualTo(2);
	}

	private String replay() {
		return RestClient.create("http://localhost:" + port).post().uri("/admin/dead-letters/replay")
				.retrieve().body(String.class);
	}

	private void putInTheDeadLetterTopic(SensorEventMessage message) throws ExecutionException, InterruptedException {
		ProducerRecord<String, String> record = new ProducerRecord<>(Topics.SENSOR_EVENTS_DLT, message.zoneId(),
				json.writeValueAsString(message));
		record.headers().add(Topics.MESSAGE_VERSION_HEADER, Topics.MESSAGE_VERSION.getBytes(UTF_8));
		kafka.send(record).get();
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
