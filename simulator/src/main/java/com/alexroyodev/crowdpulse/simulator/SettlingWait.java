package com.alexroyodev.crowdpulse.simulator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Spec 003, D4: since the API processes events asynchronously, a 202 does not mean the occupancy
 * already changed. After sending, the simulator reads the occupancies every 500 ms until they show
 * the expected changes or 30 s have passed. Reading, clock and pause are injected for the tests.
 */
final class SettlingWait {

	static final Duration INTERVAL = Duration.ofMillis(500);
	static final Duration MAX_WAIT = Duration.ofSeconds(30);

	/** Reads the occupancy of every zone (GET /api/v1/zones). */
	interface Reader {
		Map<String, Integer> read() throws IOException, InterruptedException;
	}

	/**
	 * @param after the last occupancies read
	 * @param waited from the first read to the last one
	 * @param settled whether {@code after} showed the expected changes
	 */
	record Result(Map<String, Integer> after, Duration waited, boolean settled) {
	}

	private final Reader reader;
	private final Clock clock;
	private final Pause pause;

	SettlingWait(Reader reader, Clock clock, Pause pause) {
		this.reader = reader;
		this.clock = clock;
		this.pause = pause;
	}

	Result await(Predicate<Map<String, Integer>> settled) throws IOException, InterruptedException {
		Instant start = clock.instant();
		while (true) {
			Map<String, Integer> after = reader.read();
			Duration waited = Duration.between(start, clock.instant());
			if (settled.test(after)) {
				return new Result(after, waited, true);
			}
			if (waited.compareTo(MAX_WAIT) >= 0) {
				return new Result(after, waited, false);
			}
			pause.sleep(INTERVAL);
		}
	}

}
