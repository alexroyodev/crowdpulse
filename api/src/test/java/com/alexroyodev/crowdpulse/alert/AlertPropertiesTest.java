package com.alexroyodev.crowdpulse.alert;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.alert.AlertProperties.Threshold;

class AlertPropertiesTest {

	@Test
	void acceptsTheValuesOfTheSpec() {
		assertThatCode(() -> new AlertProperties(new Threshold(85, 80), new Threshold(95, 90))).doesNotThrowAnyException();
	}

	@Test
	void exitMustBeBelowEnter() {
		assertThatThrownBy(() -> new Threshold(85, 85)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Threshold(85, 90)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Threshold(85, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void warningMustBeBelowCritical() {
		assertThatThrownBy(() -> new AlertProperties(new Threshold(95, 90), new Threshold(85, 80)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AlertProperties(new Threshold(95, 80), new Threshold(95, 90)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void bothLevelsAreRequired() {
		assertThatThrownBy(() -> new AlertProperties(null, new Threshold(95, 90))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AlertProperties(new Threshold(85, 80), null)).isInstanceOf(NullPointerException.class);
	}

}
