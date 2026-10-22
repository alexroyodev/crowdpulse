package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.Concurrently;
import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 003 through a real Kafka: messages published as the API does are applied by the listener.
 * Each test waits until its own messages are processed, so none is left for the next test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class KafkaProcessingIT {

	private final TopicPublisher topic;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	KafkaProcessingIT(KafkaTemplate<String, String> kafka, JsonMapper json, JdbcClient jdbc, Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void publishedEventIsAppliedWithinFiveSeconds() {
		// CA1, the processor's half (the 202 is the API's EventPublishingIT).
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
		long start = System.nanoTime();

		topic.publish(message);

		Eventually.await("NORTE has one person", () -> occupancyOf("NORTE") == 1);
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
		Instant receivedAt = instantOf("received_at", message.eventId());
		Instant processedAt = instantOf("processed_at", message.eventId());
		assertThat(receivedAt).isBeforeOrEqualTo(processedAt);
	}

	@Test
	void sameEventPublishedTwiceCountsOnce() {
		// CA4. The second copy has no visible effect to wait for, so a later event of the same zone
		// (same partition, after it) tells when the duplicate has been consumed too.
		SensorEventMessage message = message("SUR", ENTRY, clock.instant());
		SensorEventMessage marker = message("SUR", ENTRY, clock.instant());

		topic.publish(message);
		topic.publish(message);
		topic.publish(marker);

		Eventually.await("the marker is stored", () -> isStored(marker.eventId()));
		assertThat(occupancyOf("SUR")).isEqualTo(2);
		assertThat(storedEvents()).isEqualTo(2);
	}

	@Test
	void eventsOfAZoneAreAppliedInTheOrderTheyWerePublished() {
		// CA5: ENTRY, EXIT, ENTRY, EXIT... on an empty zone. In this order the zone never goes below
		// 0; any EXIT applied before its ENTRY would be an anomaly. Other zones are interleaved.
		List<UUID> esteEvents = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			SensorEventMessage message = message("ESTE", i % 2 == 0 ? ENTRY : EXIT, clock.instant());
			esteEvents.add(message.eventId());
			topic.publish(message);
			topic.publish(message(i % 2 == 0 ? "OESTE" : "NORTE", ENTRY, clock.instant()));
		}

		Eventually.await("the 50 ESTE events are stored",
				() -> jdbc.sql("SELECT count(*) FROM sensor_event WHERE zone_id = 'ESTE'").query(Integer.class).single() == 50);
		assertThat(occupancyOf("ESTE")).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE zone_id = 'ESTE' AND anomaly")
				.query(Integer.class).single()).isZero();
		// One consumer applies them one by one, so the stored processing times follow the publishing order.
		assertThat(jdbc.sql("SELECT event_id FROM sensor_event WHERE zone_id = 'ESTE' ORDER BY processed_at")
				.query(UUID.class).list()).containsExactlyElementsOf(esteEvents);
	}

	@Test
	void thousandEntriesPublishedFromTwentyThreadsCountExactlyAThousand() throws Exception {
		// CA8: spec 001's 1 000 concurrent ENTRY, through the asynchronous path.
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
			tasks.add(() -> {
				topic.publish(message);
				return true;
			});
		}

		Concurrently.run(20, tasks);

		Eventually.await("1 000 events stored", () -> storedEvents() == 1_000);
		assertThat(occupancyOf("NORTE")).isEqualTo(1_000);
	}

	@Test
	void thresholdsAndAnomaliesWorkThroughTheTopic() {
		// CA8: a level change (spec 002) and an anomaly (spec 001, rule 5) by the asynchronous path.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 169 WHERE zone_id = 'PALCO'").update();
		SensorEventMessage crossing = message("PALCO", ENTRY, clock.instant());
		SensorEventMessage exitOnEmpty = message("ESTE", EXIT, clock.instant());

		topic.publish(crossing);
		topic.publish(exitOnEmpty);

		Eventually.await("both events stored", () -> isStored(crossing.eventId()) && isStored(exitOnEmpty.eventId()));
		assertThat(jdbc.sql("SELECT from_level || '->' || to_level FROM alert WHERE event_id = :id")
				.param("id", crossing.eventId()).query(String.class).single()).isEqualTo("NORMAL->WARNING");
		assertThat(jdbc.sql("SELECT anomaly FROM sensor_event WHERE event_id = :id")
				.param("id", exitOnEmpty.eventId()).query(Boolean.class).single()).isTrue();
		assertThat(occupancyOf("ESTE")).isZero();
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

	private boolean isStored(UUID eventId) {
		return jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", eventId).query(Integer.class).single() == 1;
	}

	private Instant instantOf(String column, UUID eventId) {
		return jdbc.sql("SELECT " + column + " FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
				.single();
	}

}
