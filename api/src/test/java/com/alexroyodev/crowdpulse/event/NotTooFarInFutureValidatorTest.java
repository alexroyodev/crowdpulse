package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** CA5: occurredAt up to 5 minutes ahead of the server clock is accepted, beyond that it is not. */
class NotTooFarInFutureValidatorTest {

	private static final Instant NOW = Instant.parse("2026-10-19T18:30:00Z");

	private final NotTooFarInFutureValidator validator = new NotTooFarInFutureValidator(
			Clock.fixed(NOW, ZoneOffset.UTC), new EventProperties(Duration.ofMinutes(5)));

	@Test
	void fourMinutesAheadIsValid() {
		assertThat(validator.isValid(NOW.plus(Duration.ofMinutes(4)), null)).isTrue();
	}

	@Test
	void exactlyFiveMinutesAheadIsValid() {
		assertThat(validator.isValid(NOW.plus(Duration.ofMinutes(5)), null)).isTrue();
	}

	@Test
	void oneMillisecondBeyondFiveMinutesIsInvalid() {
		assertThat(validator.isValid(NOW.plus(Duration.ofMinutes(5)).plusMillis(1), null)).isFalse();
	}

	@Test
	void pastIsValid() {
		assertThat(validator.isValid(NOW.minus(Duration.ofHours(1)), null)).isTrue();
	}

	@Test
	void nullIsLeftToNotNull() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

	@Test
	void propertiesRequireANonNegativeSkew() {
		assertThatThrownBy(() -> new EventProperties(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new EventProperties(Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
