package com.alexroyodev.crowdpulse.alert;

import static com.alexroyodev.crowdpulse.shared.AlertLevel.CRITICAL;
import static com.alexroyodev.crowdpulse.shared.AlertLevel.NORMAL;
import static com.alexroyodev.crowdpulse.shared.AlertLevel.WARNING;

import java.util.Objects;

import com.alexroyodev.crowdpulse.alert.AlertProperties.Threshold;
import com.alexroyodev.crowdpulse.shared.AlertLevel;

/**
 * Decides a zone's alert level from its occupancy (spec 002, rules 2-5). Pure domain logic:
 * no Spring, no database. A level is entered when occupancy reaches its enter threshold and is
 * left only when occupancy drops below its exit threshold, so oscillating around a threshold
 * does not flip the level back and forth (hysteresis).
 */
public final class AlertPolicy {

	private final Threshold warning;
	private final Threshold critical;

	public AlertPolicy(AlertProperties properties) {
		this.warning = properties.warning();
		this.critical = properties.critical();
	}

	public AlertLevel next(AlertLevel current, int occupancy, int capacity) {
		Objects.requireNonNull(current, "current");
		if (reaches(occupancy, capacity, critical.enterPct())) {
			return CRITICAL;
		}
		if (current == CRITICAL && reaches(occupancy, capacity, critical.exitPct())) {
			return CRITICAL;
		}
		if (reaches(occupancy, capacity, warning.enterPct())) {
			return WARNING;
		}
		if (current != NORMAL && reaches(occupancy, capacity, warning.exitPct())) {
			return WARNING;
		}
		return NORMAL;
	}

	/**
	 * occupancy / capacity >= pct / 100, compared exactly with integers (rule 2): no double, no
	 * rounding, so 16 999 of 20 000 (84.995 %) does not reach 85 % although it is shown as 85.0.
	 */
	private static boolean reaches(int occupancy, int capacity, int pct) {
		return (long) occupancy * 100 >= (long) pct * capacity;
	}

}
