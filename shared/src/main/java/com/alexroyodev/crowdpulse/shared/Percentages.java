package com.alexroyodev.crowdpulse.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Occupancy as a percentage of capacity, the same for the API's views and the processor's logs. */
public final class Percentages {

	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	private Percentages() {
	}

	/**
	 * One decimal, may exceed 100. BigDecimal instead of double so that rounding is exact:
	 * 61.725 always becomes 61.7, with no binary floating-point surprises.
	 */
	public static BigDecimal of(int occupancy, int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be positive: " + capacity);
		}
		return BigDecimal.valueOf(occupancy)
				.multiply(ONE_HUNDRED)
				.divide(BigDecimal.valueOf(capacity), 1, RoundingMode.HALF_UP);
	}

}
