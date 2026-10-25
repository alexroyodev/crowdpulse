package com.alexroyodev.crowdpulse.event;

import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Spec 007: the event's attributes on the span of the request that carries it, so its trace can be
 * found by eventId. High cardinality: Micrometer puts these in the span only, never in a metric
 * (spec 006, rule 5); low cardinality ones would go to both.
 */
@Component
class TraceAttributes {

	static final String EVENT_ID = "crowdpulse.event.id";
	static final String ZONE_ID = "crowdpulse.zone.id";
	static final String EVENT_TYPE = "crowdpulse.event.type";

	private final ObservationRegistry observations;

	TraceAttributes(ObservationRegistry observations) {
		this.observations = observations;
	}

	/** On the current observation (Spring MVC's, for the request); nothing if there is none. */
	void describe(EventRequest request) {
		Observation current = observations.getCurrentObservation();
		if (current == null) {
			return;
		}
		current.highCardinalityKeyValue(EVENT_ID, request.eventId().toString())
				.highCardinalityKeyValue(ZONE_ID, request.zoneId())
				.highCardinalityKeyValue(EVENT_TYPE, request.type().name());
	}

}
