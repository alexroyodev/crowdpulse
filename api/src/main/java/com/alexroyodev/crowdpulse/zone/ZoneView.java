package com.alexroyodev.crowdpulse.zone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Response body of the zone endpoints (spec 001).
 *
 * @param occupancyPct occupancy as a percentage of capacity, one decimal; may exceed 100
 * @param lastEventAt most recent {@code occurredAt} applied to the zone, or null if none
 */
public record ZoneView(
		String zoneId,
		String name,
		int capacity,
		int occupancy,
		BigDecimal occupancyPct,
		Instant lastEventAt) {

	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	public static ZoneView of(String zoneId, String name, int capacity, int occupancy, Instant lastEventAt) {
		return new ZoneView(zoneId, name, capacity, occupancy, percentage(occupancy, capacity), lastEventAt);
	}

	/**
	 * BigDecimal instead of double so that rounding is exact: 61.725 always becomes 61.7,
	 * with no binary floating-point surprises.
	 */
	static BigDecimal percentage(int occupancy, int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be positive: " + capacity);
		}
		return BigDecimal.valueOf(occupancy)
				.multiply(ONE_HUNDRED)
				.divide(BigDecimal.valueOf(capacity), 1, RoundingMode.HALF_UP);
	}

}
