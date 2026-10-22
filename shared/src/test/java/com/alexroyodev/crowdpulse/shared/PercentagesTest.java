package com.alexroyodev.crowdpulse.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Moved from ZoneViewTest together with the method (plan 003, section 2). */
class PercentagesTest {

	@Test
	void roundsToOneDecimalAsInTheSpecExample() {
		// 12 345 / 20 000 = 61.725 %
		assertThat(Percentages.of(12_345, 20_000)).isEqualTo(new BigDecimal("61.7"));
	}

	@Test
	void emptyZoneIsZeroWithOneDecimal() {
		assertThat(Percentages.of(0, 20_000)).isEqualTo(new BigDecimal("0.0"));
	}

	@Test
	void canExceedOneHundred() {
		assertThat(Percentages.of(25_000, 20_000)).isEqualTo(new BigDecimal("125.0"));
	}

	@Test
	void smallValuesRoundDown() {
		// 1 / 8 000 = 0.0125 %
		assertThat(Percentages.of(1, 8_000)).isEqualTo(new BigDecimal("0.0"));
	}

	@Test
	void halfRoundsUp() {
		// 1 / 400 = 0.25 %: HALF_UP gives 0.3 (HALF_EVEN would give 0.2)
		assertThat(Percentages.of(1, 400)).isEqualTo(new BigDecimal("0.3"));
	}

	@Test
	void rejectsNonPositiveCapacity() {
		assertThatThrownBy(() -> Percentages.of(1, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
