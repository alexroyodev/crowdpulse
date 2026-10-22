package com.alexroyodev.crowdpulse.processor.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentFailureTest {

	@ParameterizedTest
	@ValueSource(strings = {
			// The class Spring Kafka recorded for a broken JSON in DeadLetterIT: a Jackson subclass.
			"tools.jackson.core.exc.UnexpectedEndOfInputException",
			"tools.jackson.databind.exc.MismatchedInputException",
			"jakarta.validation.ConstraintViolationException",
			"com.alexroyodev.crowdpulse.processor.event.UnsupportedMessageVersionException" })
	void contentFailuresAreRecognised(String className) {
		assertThat(ContentFailure.isContentFailure(className)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// Creating the zone fixes it.
			"com.alexroyodev.crowdpulse.processor.event.UnknownZoneException",
			"java.lang.IllegalStateException",
			// A class this processor does not know: when in doubt, replay.
			"com.example.SomethingThatDoesNotExist" })
	void anythingElseIsNot(String className) {
		assertThat(ContentFailure.isContentFailure(className)).isFalse();
	}

	@Test
	void noHeaderIsNotAContentFailure() {
		assertThat(ContentFailure.isContentFailure(null)).isFalse();
	}

}
