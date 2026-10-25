package com.alexroyodev.crowdpulse.event;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.shared.EventType;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;

/** Spec 007: the event's attributes go to the current observation, as high cardinality only. */
class TraceAttributesTest {

	private final TestObservationRegistry registry = TestObservationRegistry.create();
	private final TraceAttributes attributes = new TraceAttributes(registry);
	private final EventRequest request = new EventRequest(UUID.randomUUID(), "NORTE-P01", "NORTE", EventType.ENTRY,
			Instant.parse("2026-10-25T10:00:00Z"));

	@Test
	void describesTheEventAsHighCardinalityKeyValues() {
		Observation.createNotStarted("request", registry).observe(() -> attributes.describe(request));

		assertThat(registry).hasObservationWithNameEqualTo("request").that()
				.hasHighCardinalityKeyValue(TraceAttributes.EVENT_ID, request.eventId().toString())
				.hasHighCardinalityKeyValue(TraceAttributes.ZONE_ID, "NORTE")
				.hasHighCardinalityKeyValue(TraceAttributes.EVENT_TYPE, "ENTRY")
				// Low cardinality would also make it a metric tag: one time series per event.
				.doesNotHaveLowCardinalityKeyValueWithKey(TraceAttributes.EVENT_ID);
	}

	@Test
	void doesNothingWithoutACurrentObservation() {
		assertThatNoException().isThrownBy(() -> attributes.describe(request));
	}

}
