package com.alexroyodev.crowdpulse.processor.event;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.processor.event.Processed.Outcome;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;

/** Spec 007: the event and its outcome go to the current observation, as high cardinality only. */
class TraceAttributesTest {

	private final TestObservationRegistry registry = TestObservationRegistry.create();
	private final TraceAttributes attributes = new TraceAttributes(registry);
	private final SensorEventMessage message = new SensorEventMessage(UUID.randomUUID(), "SUR-P01", "SUR",
			EventType.EXIT, Instant.parse("2026-10-25T10:00:00Z"), Instant.parse("2026-10-25T10:00:01Z"));

	@Test
	void describesTheEventAndItsOutcome() {
		Observation.createNotStarted("delivery", registry).observe(() -> {
			attributes.describe(message);
			attributes.outcome(new Processed(Outcome.EXIT_AT_ZERO, Instant.parse("2026-10-25T10:00:02Z"), null));
		});

		assertThat(registry).hasObservationWithNameEqualTo("delivery").that()
				.hasHighCardinalityKeyValue(TraceAttributes.EVENT_ID, message.eventId().toString())
				.hasHighCardinalityKeyValue(TraceAttributes.ZONE_ID, "SUR")
				.hasHighCardinalityKeyValue(TraceAttributes.EVENT_TYPE, "EXIT")
				.hasHighCardinalityKeyValue(TraceAttributes.OCCURRED_AT, "2026-10-25T10:00:00Z")
				.hasHighCardinalityKeyValue(TraceAttributes.OUTCOME, "exit_at_zero")
				.doesNotHaveLowCardinalityKeyValueWithKey(TraceAttributes.EVENT_ID);
	}

	@Test
	void runsTheDeadLetterPublishInAnObservationThatSaysSo() {
		AtomicBoolean published = new AtomicBoolean();

		attributes.deadLettered("content", () -> published.set(true));

		Assertions.assertThat(published).isTrue();
		assertThat(registry).hasObservationWithNameEqualTo(TraceAttributes.DEAD_LETTER_OBSERVATION).that()
				.hasBeenStopped()
				.hasHighCardinalityKeyValue(TraceAttributes.OUTCOME, "dead_lettered")
				.hasHighCardinalityKeyValue(TraceAttributes.DEAD_LETTER_REASON, "content");
	}

	@Test
	void doesNothingWithoutACurrentObservation() {
		assertThatNoException().isThrownBy(() -> {
			attributes.describe(message);
			attributes.outcome(Processed.duplicate(Instant.parse("2026-10-25T10:00:02Z")));
		});
	}

}
