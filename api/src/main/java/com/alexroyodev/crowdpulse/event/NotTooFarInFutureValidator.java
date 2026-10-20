package com.alexroyodev.crowdpulse.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Spring creates this validator and injects its constructor arguments; unit tests build it
 * by hand with a fixed Clock. The limit is inclusive: exactly maxFutureSkew ahead is valid.
 */
class NotTooFarInFutureValidator implements ConstraintValidator<NotTooFarInFuture, Instant> {

	private final Clock clock;
	private final Duration maxFutureSkew;

	NotTooFarInFutureValidator(Clock clock, EventProperties properties) {
		this.clock = clock;
		this.maxFutureSkew = properties.maxFutureSkew();
	}

	@Override
	public boolean isValid(Instant occurredAt, ConstraintValidatorContext context) {
		if (occurredAt == null) {
			return true;
		}
		Instant limit = clock.instant().plus(maxFutureSkew);
		return !occurredAt.isAfter(limit);
	}

}
