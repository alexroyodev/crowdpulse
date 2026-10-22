package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 003, rule 7 / CA3b: a message the processor cannot process ends in the dead-letter topic,
 * with an ERROR log, and the following events of its zone are still processed. What retrying
 * cannot fix goes there at the first attempt; a passing failure is retried and processed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class DeadLetterIT {

	@MockitoSpyBean
	EventProcessor processor;

	private final TopicPublisher topic;
	private final TopicProbe deadLetters;
	private final JsonMapper json;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	DeadLetterIT(KafkaTemplate<String, String> kafka, ConsumerFactory<String, String> consumers, JsonMapper json,
			JdbcClient jdbc, Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.deadLetters = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.json = json;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@BeforeEach
	void markDeadLetterTopic() {
		deadLetters.mark();
	}

	@Test
	void brokenJsonGoesToTheDeadLetterTopicAndTheZoneGoesOn(CapturedOutput output) {
		String value = "{\"eventId\": ";

		topic.publishRaw("NORTE", value, Topics.MESSAGE_VERSION);

		ConsumerRecord<String, String> dead = singleDeadLetter();
		assertThat(dead.key()).isEqualTo("NORTE");
		assertThat(dead.value()).isEqualTo(value);
		assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.SENSOR_EVENTS);
		assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo("crowdpulse-processor");
		assertThat(errorLogLines(output)).singleElement().asString()
				.contains("\"level\":\"ERROR\"", "\"zoneId\":\"NORTE\"", "\"cause\":\"tools.jackson");
		assertNextEventOfTheZoneIsProcessed("NORTE");
	}

	@Test
	void messageWithoutVersionOrWithAnotherOneGoesToTheDeadLetterTopic() {
		SensorEventMessage noVersion = message("SUR", ENTRY, clock.instant());
		SensorEventMessage versionTwo = message("SUR", ENTRY, clock.instant());

		topic.publishRaw("SUR", json.writeValueAsString(noVersion), null);
		topic.publishRaw("SUR", json.writeValueAsString(versionTwo), "2");

		Eventually.await("two messages in the dead-letter topic", () -> deadLetters.published().size() == 2);
		assertThat(deadLetters.published())
				.extracting(record -> header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
				.containsOnly(UnsupportedMessageVersionException.class.getName());
		// Never interpreted by guessing: the processor did not even see them.
		verify(processor, times(0)).process(any());
		assertNextEventOfTheZoneIsProcessed("SUR");
	}

	@Test
	void messageWithAMissingFieldGoesToTheDeadLetterTopic(CapturedOutput output) {
		UUID eventId = UUID.randomUUID();
		String withoutSensorId = """
				{"eventId":"%s","zoneId":"ESTE","type":"ENTRY","occurredAt":"%s","receivedAt":"%s"}
				""".formatted(eventId, clock.instant(), clock.instant());

		topic.publishRaw("ESTE", withoutSensorId, Topics.MESSAGE_VERSION);

		ConsumerRecord<String, String> dead = singleDeadLetter();
		assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
				.isEqualTo("jakarta.validation.ConstraintViolationException");
		assertThat(errorLogLines(output)).singleElement().asString().contains("\"eventId\":\"" + eventId + "\"");
		verify(processor, times(0)).process(any());
		assertNextEventOfTheZoneIsProcessed("ESTE");
	}

	@Test
	void unknownZoneGoesToTheDeadLetterTopicAtTheFirstAttempt() {
		// The API answers 422 first (D2); this is a message that did not come from the API.
		SensorEventMessage message = message("NOEXISTE", ENTRY, clock.instant());

		topic.publish(message);

		ConsumerRecord<String, String> dead = singleDeadLetter();
		assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(UnknownZoneException.class.getName());
		verify(processor, times(1)).process(argThat(m -> m.eventId().equals(message.eventId())));
	}

	@Test
	void passingFailureIsRetriedAndProcessedWithoutDeadLetter() {
		// E.g. the database unavailable for a moment: the first attempt fails, the retry works.
		AtomicBoolean failed = new AtomicBoolean();
		doAnswer(invocation -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("simulated passing failure");
			}
			return invocation.callRealMethod();
		}).when(processor).process(any());
		SensorEventMessage message = message("OESTE", ENTRY, clock.instant());

		topic.publish(message);

		Eventually.await("the event is stored", () -> isStored(message.eventId()));
		verify(processor, times(2)).process(argThat(m -> m.eventId().equals(message.eventId())));
		assertThat(deadLetters.published()).isEmpty();
		assertThat(occupancyOf("OESTE")).isEqualTo(1);
	}

	private void assertNextEventOfTheZoneIsProcessed(String zoneId) {
		SensorEventMessage next = message(zoneId, ENTRY, clock.instant());
		topic.publish(next);
		Eventually.await("the next event of " + zoneId + " is stored", () -> isStored(next.eventId()));
		assertThat(occupancyOf(zoneId)).isEqualTo(1);
	}

	private ConsumerRecord<String, String> singleDeadLetter() {
		Eventually.await("a message in the dead-letter topic", () -> !deadLetters.published().isEmpty());
		List<ConsumerRecord<String, String>> records = deadLetters.published();
		assertThat(records).hasSize(1);
		return records.get(0);
	}

	private static List<String> errorLogLines(CapturedOutput output) {
		return output.getOut().lines().filter(line -> line.contains("Event sent to dead-letter topic")).toList();
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		return new String(record.headers().lastHeader(name).value(), UTF_8);
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
