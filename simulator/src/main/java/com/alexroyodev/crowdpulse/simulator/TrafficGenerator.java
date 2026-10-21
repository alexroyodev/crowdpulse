package com.alexroyodev.crowdpulse.simulator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Decides the next event of one zone. No I/O: it receives a Random (seeded in tests) and the
 * progress of the simulation, so its behaviour can be tested deterministically.
 * Not thread-safe: each zone has its own generator, used by one thread.
 */
class TrafficGenerator {

	private static final int SENSORS_PER_ZONE = 5;

	private final String zoneId;
	private final int duplicatesPct;
	private final Random random;
	private final Clock clock;
	private final List<SimulatedEvent> sent = new ArrayList<>();
	private int occupancy;

	TrafficGenerator(String zoneId, int duplicatesPct, Random random, Clock clock) {
		this.zoneId = zoneId;
		this.duplicatesPct = duplicatesPct;
		this.random = random;
		this.clock = clock;
	}

	/**
	 * @param progress fraction of the simulation already elapsed, from 0.0 to 1.0
	 */
	SimulatedEvent next(double progress) {
		if (!sent.isEmpty() && random.nextInt(100) < duplicatesPct) {
			return sent.get(random.nextInt(sent.size())).asDuplicate();
		}

		// Crowd arrives at the start and leaves at the end: P(ENTRY) goes from 0.9 down to 0.1.
		double entryProbability = 0.9 - 0.8 * Math.clamp(progress, 0.0, 1.0);
		boolean entry = occupancy == 0 || random.nextDouble() < entryProbability;
		occupancy += entry ? 1 : -1;

		String sensorId = "%s-P%02d".formatted(zoneId, 1 + random.nextInt(SENSORS_PER_ZONE));
		SimulatedEvent event = new SimulatedEvent(UUID.randomUUID(), sensorId, zoneId,
				entry ? "ENTRY" : "EXIT", clock.instant(), false);
		sent.add(event);
		return event;
	}

	/** Unique entries minus unique exits generated so far: what the API should report. Never negative. */
	int occupancy() {
		return occupancy;
	}

	String zoneId() {
		return zoneId;
	}

}
