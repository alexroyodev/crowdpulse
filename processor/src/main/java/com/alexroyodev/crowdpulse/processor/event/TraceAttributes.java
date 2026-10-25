package com.alexroyodev.crowdpulse.processor.event;

import org.springframework.stereotype.Component;

import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Spec 007: the event and what happened to it, on the span of the current delivery of its message
 * (Spring Kafka's listener observation). High cardinality: in the span only, never in a metric
 * (spec 006, rule 5). Same names as the API's.
 */
@Component
public class TraceAttributes {

	static final String EVENT_ID = "crowdpulse.event.id";
	static final String ZONE_ID = "crowdpulse.zone.id";
	static final String EVENT_TYPE = "crowdpulse.event.type";
	static final String OCCURRED_AT = "crowdpulse.event.occurred_at";
	static final String OUTCOME = "crowdpulse.outcome";
	static final String DEAD_LETTER_REASON = "crowdpulse.dead_letter.reason";
	/** Also a timer, crowdpulse_dead_letter_seconds, like every observation: how long the publish took. */
	static final String DEAD_LETTER_OBSERVATION = "crowdpulse.dead.letter";

	private final ObservationRegistry observations;

	TraceAttributes(ObservationRegistry observations) {
		this.observations = observations;
	}

	void describe(SensorEventMessage message) {
		Observation current = observations.getCurrentObservation();
		if (current == null) {
			return;
		}
		current.highCardinalityKeyValue(EVENT_ID, message.eventId().toString())
				.highCardinalityKeyValue(ZONE_ID, message.zoneId())
				.highCardinalityKeyValue(EVENT_TYPE, message.type().name())
				// Only an attribute: no span starts at occurredAt, the sensor's clock (rule 4).
				.highCardinalityKeyValue(OCCURRED_AT, message.occurredAt().toString());
	}

	/** Only for a committed attempt: a rolled-back one throws before it gets here (rule 3). */
	void outcome(Processed processed) {
		set(OUTCOME, processed.outcome().tag());
	}

	/**
	 * Runs the dead-letter publish in a span of its own, child of the last delivery. The delivery's
	 * observation has already stopped when the error handler calls the recoverer (its span is
	 * exported without anything added later), though its context is still current.
	 *
	 * @param reason content or other, as in the dead-letter metric
	 */
	public void deadLettered(String reason, Runnable publish) {
		Observation.createNotStarted(DEAD_LETTER_OBSERVATION, observations)
				.highCardinalityKeyValue(OUTCOME, "dead_lettered")
				.highCardinalityKeyValue(DEAD_LETTER_REASON, reason)
				.observe(publish);
	}

	private void set(String key, String value) {
		Observation current = observations.getCurrentObservation();
		if (current != null) {
			current.highCardinalityKeyValue(key, value);
		}
	}

}
