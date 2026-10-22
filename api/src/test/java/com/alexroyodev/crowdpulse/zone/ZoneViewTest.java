package com.alexroyodev.crowdpulse.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

/** The rounding rules of the percentage are tested in shared PercentagesTest (plan 003, section 2). */
class ZoneViewTest {

	@Test
	void ofComputesThePercentage() {
		Instant lastEventAt = Instant.parse("2026-10-19T18:30:05.123Z");

		ZoneView view = ZoneView.of("NORTE", "Fondo Norte", 20_000, 12_345, lastEventAt, AlertLevel.NORMAL);

		assertThat(view).isEqualTo(new ZoneView("NORTE", "Fondo Norte", 20_000, 12_345, new BigDecimal("61.7"),
				lastEventAt, AlertLevel.NORMAL));
	}

}
