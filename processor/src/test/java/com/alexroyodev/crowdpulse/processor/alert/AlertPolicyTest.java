package com.alexroyodev.crowdpulse.processor.alert;

import static com.alexroyodev.crowdpulse.shared.AlertLevel.NORMAL;
import static com.alexroyodev.crowdpulse.shared.AlertLevel.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.alexroyodev.crowdpulse.processor.alert.AlertProperties.Threshold;
import com.alexroyodev.crowdpulse.shared.AlertLevel;

class AlertPolicyTest {

	private static final int CAPACITY = 20_000; // NORTE, as in the spec's example table

	private final AlertPolicy policy = new AlertPolicy(new AlertProperties(new Threshold(85, 80), new Threshold(95, 90)));

	@ParameterizedTest(name = "{0} at {1} -> {2}")
	@CsvSource({
			// The example table of spec 002, row by row
			"NORMAL,   17000, WARNING",   // 85.0 %: enters WARNING
			"WARNING,  16999, WARNING",   // 84.995 %: hysteresis, stays
			"WARNING,  16000, WARNING",   // 80.0 %: not below 80, stays
			"WARNING,  15999, NORMAL",    // 79.995 %: leaves WARNING
			"WARNING,  19000, CRITICAL",  // 95.0 %: enters CRITICAL
			"CRITICAL, 20500, CRITICAL",  // 102.5 %: no level above CRITICAL
			"CRITICAL, 17999, WARNING",   // 89.995 %: leaves CRITICAL
			// Edges
			"NORMAL,   16999, NORMAL",    // 84.995 % is shown as 85.0 but does not reach 85 (exact comparison)
			"NORMAL,       0, NORMAL",
			"WARNING,  18999, WARNING",   // 94.995 %
			"CRITICAL, 18000, CRITICAL",  // 90.0 %: not below 90, stays
			"CRITICAL, 15999, NORMAL",    // below both exits: two levels down at once
			"NORMAL,   19000, CRITICAL",  // two levels up at once
			"WARNING,  17000, WARNING",   // same occupancy, same level
	})
	void decidesTheLevel(AlertLevel current, int occupancy, AlertLevel expected) {
		assertThat(policy.next(current, occupancy, CAPACITY)).isEqualTo(expected);
	}

	@Test
	void oscillatingAroundTheThresholdChangesTheLevelOnlyOnce() {
		// CA3 at the domain level: 17 000 <-> 16 999 ten times.
		AlertLevel level = NORMAL;
		List<AlertLevel> changes = new ArrayList<>();
		int occupancy = 16_999;
		for (int i = 0; i < 20; i++) {
			occupancy = (occupancy == 16_999) ? 17_000 : 16_999;
			AlertLevel next = policy.next(level, occupancy, CAPACITY);
			if (next != level) {
				changes.add(next);
			}
			level = next;
		}

		assertThat(changes).containsExactly(WARNING);
	}

	@Test
	void worksWithSmallCapacities() {
		// PALCO, 200 seats: 170 is exactly 85 %, 190 exactly 95 %.
		assertThat(policy.next(NORMAL, 169, 200)).isEqualTo(NORMAL);
		assertThat(policy.next(NORMAL, 170, 200)).isEqualTo(WARNING);
		assertThat(policy.next(WARNING, 190, 200)).isEqualTo(AlertLevel.CRITICAL);
	}

}
