package com.alexroyodev.crowdpulse.alert;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code crowdpulse.alerts} in application.yml. The values are fixed by spec
 * 002 (D1); they are configuration so they are not repeated across the code.
 */
@ConfigurationProperties("crowdpulse.alerts")
public record AlertProperties(Threshold warning, Threshold critical) {

	/**
	 * @param enterPct the level is entered when occupancy reaches this percentage
	 * @param exitPct the level is left only when occupancy drops below this percentage (hysteresis)
	 */
	public record Threshold(int enterPct, int exitPct) {

		public Threshold {
			if (exitPct <= 0 || exitPct >= enterPct) {
				throw new IllegalArgumentException(
						"An alert threshold needs 0 < exit-pct < enter-pct, got enter " + enterPct + ", exit " + exitPct);
			}
		}

	}

	public AlertProperties {
		Objects.requireNonNull(warning, "crowdpulse.alerts.warning is required");
		Objects.requireNonNull(critical, "crowdpulse.alerts.critical is required");
		if (warning.enterPct() >= critical.enterPct() || warning.exitPct() >= critical.exitPct()) {
			throw new IllegalArgumentException("crowdpulse.alerts.warning must be below crowdpulse.alerts.critical");
		}
	}

}
