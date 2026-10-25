package com.alexroyodev.crowdpulse.processor.event;

import java.time.Instant;
import java.util.Locale;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

/**
 * What EventProcessor did with one event, once its transaction has committed (spec 006, rule 3).
 *
 * @param outcome applied, ignored as a duplicate, or an EXIT that left the zone at 0
 * @param processedAt when it was processed; for a duplicate it is not stored anywhere
 * @param newLevel the zone's new alert level if it changed, otherwise null
 */
public record Processed(Outcome outcome, Instant processedAt, AlertLevel newLevel) {

	public enum Outcome {

		APPLIED, DUPLICATE, EXIT_AT_ZERO;

		/** As a metric tag: applied, duplicate, exit_at_zero. */
		public String tag() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	static Processed duplicate(Instant processedAt) {
		return new Processed(Outcome.DUPLICATE, processedAt, null);
	}

}
