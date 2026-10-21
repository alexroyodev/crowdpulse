package com.alexroyodev.crowdpulse.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.alert.AlertLevel;

class ZoneViewTest {

	@Test
	void roundsToOneDecimalAsInTheSpecExample() {
		// 12 345 / 20 000 = 61.725 %
		assertThat(ZoneView.percentage(12_345, 20_000)).isEqualTo(new BigDecimal("61.7"));
	}

	@Test
	void emptyZoneIsZeroWithOneDecimal() {
		assertThat(ZoneView.percentage(0, 20_000)).isEqualTo(new BigDecimal("0.0"));
	}

	@Test
	void canExceedOneHundred() {
		assertThat(ZoneView.percentage(25_000, 20_000)).isEqualTo(new BigDecimal("125.0"));
	}

	@Test
	void smallValuesRoundDown() {
		// 1 / 8 000 = 0.0125 %
		assertThat(ZoneView.percentage(1, 8_000)).isEqualTo(new BigDecimal("0.0"));
	}

	@Test
	void halfRoundsUp() {
		// 1 / 400 = 0.25 %: HALF_UP gives 0.3 (HALF_EVEN would give 0.2)
		assertThat(ZoneView.percentage(1, 400)).isEqualTo(new BigDecimal("0.3"));
	}

	@Test
	void rejectsNonPositiveCapacity() {
		assertThatThrownBy(() -> ZoneView.percentage(1, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ofComputesThePercentage() {
		Instant lastEventAt = Instant.parse("2026-10-19T18:30:05.123Z");

		ZoneView view = ZoneView.of("NORTE", "Fondo Norte", 20_000, 12_345, lastEventAt, AlertLevel.NORMAL);

		assertThat(view).isEqualTo(new ZoneView("NORTE", "Fondo Norte", 20_000, 12_345, new BigDecimal("61.7"),
				lastEventAt, AlertLevel.NORMAL));
	}

}
