package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.processor.alert.AlertRepository;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 006, CA2 and CA3 through a real Kafka: the processed-events counter tells duplicates
 * apart, and counts an event once even if its first transaction was rolled back. The registry
 * lives in a context shared with other tests, so each test compares before and after.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ProcessingMetricsIT {

	// The repository, not the @Transactional EventProcessor: a failure here rolls its transaction back.
	@MockitoSpyBean
	AlertRepository alerts;

	private final TopicPublisher topic;
	private final MeterRegistry registry;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ProcessingMetricsIT(KafkaTemplate<String, String> kafka, JsonMapper json, MeterRegistry registry, JdbcClient jdbc,
			Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.registry = registry;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void duplicatesAreCountedApart() {
		// CA2: 10 distinct events (the first an EXIT with the zone empty) and copies of 4 of them.
		double applied = processed("ESTE", "applied");
		double exitAtZero = processed("ESTE", "exit_at_zero");
		double duplicates = processed("ESTE", "duplicate");
		List<SensorEventMessage> distinct = new ArrayList<>();
		distinct.add(message("ESTE", EXIT, clock.instant()));
		for (int i = 1; i < 10; i++) {
			distinct.add(message("ESTE", ENTRY, clock.instant()));
		}

		distinct.forEach(topic::publish);
		distinct.subList(0, 4).forEach(topic::publish);
		// Same zone, same partition, after the copies: once it is stored, the copies were consumed.
		SensorEventMessage marker = message("ESTE", ENTRY, clock.instant());
		topic.publish(marker);
		Eventually.await("the marker is stored", () -> isStored(marker));

		// applied + exit_at_zero = the 10 distinct events + the marker; the copies only as duplicates.
		assertThat(processed("ESTE", "applied") - applied).isEqualTo(10);
		assertThat(processed("ESTE", "exit_at_zero") - exitAtZero).isEqualTo(1);
		assertThat(processed("ESTE", "duplicate") - duplicates).isEqualTo(4);
	}

	@Test
	void rolledBackAttemptIsNotCounted() {
		// CA3: OESTE one person below 85 % of 15 000 (V2), so the next ENTRY inserts an alert. That
		// insert fails once, after the event and the occupancy were written: all of it rolls back,
		// the error handler retries and the second attempt commits.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 12749 WHERE zone_id = 'OESTE'").update();
		AtomicBoolean failed = new AtomicBoolean();
		doAnswer(invocation -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("simulated failure inside the transaction");
			}
			return invocation.callRealMethod();
		}).when(alerts).insert(any());
		double applied = processed("OESTE", "applied");
		SensorEventMessage message = message("OESTE", ENTRY, clock.instant());

		topic.publish(message);
		Eventually.await("the event is stored on the retry", () -> isStored(message));

		assertThat(failed).as("the first attempt failed").isTrue();
		assertThat(processed("OESTE", "applied") - applied).isEqualTo(1);
	}

	private double processed(String zone, String outcome) {
		Counter counter = registry.find("crowdpulse.events.processed").tags("zone", zone, "outcome", outcome).counter();
		return counter == null ? 0 : counter.count();
	}

	private boolean isStored(SensorEventMessage message) {
		return jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", message.eventId())
				.query(Integer.class)
				.single() == 1;
	}

}
