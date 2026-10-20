package com.alexroyodev.crowdpulse.event;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code crowdpulse.events} in application.yml.
 *
 * @param maxFutureSkew how far into the future occurredAt may be (spec 001: 5 minutes)
 */
@ConfigurationProperties("crowdpulse.events")
public record EventProperties(Duration maxFutureSkew) {

	public EventProperties {
		Objects.requireNonNull(maxFutureSkew, "crowdpulse.events.max-future-skew is required");
		if (maxFutureSkew.isNegative()) {
			throw new IllegalArgumentException("crowdpulse.events.max-future-skew cannot be negative");
		}
	}

}
