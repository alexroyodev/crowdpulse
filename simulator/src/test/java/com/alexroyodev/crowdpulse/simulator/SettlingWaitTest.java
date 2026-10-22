package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Without network nor real waiting: the pause moves a fake clock forward. */
class SettlingWaitTest {

	private static final Map<String, Integer> EXPECTED = Map.of("NORTE", 40);

	private final MovableClock clock = new MovableClock();

	@Test
	void settledAtTheFirstReadWaitsNothing() throws Exception {
		SettlingWait.Result result = waitWith(List.of(EXPECTED));

		assertTrue(result.settled());
		assertEquals(Duration.ZERO, result.waited());
		assertEquals(EXPECTED, result.after());
	}

	@Test
	void keepsReadingEveryHalfSecondUntilItSettles() throws Exception {
		SettlingWait.Result result = waitWith(List.of(Map.of("NORTE", 37), Map.of("NORTE", 39), EXPECTED));

		assertTrue(result.settled());
		assertEquals(Duration.ofSeconds(1), result.waited());
		assertEquals(EXPECTED, result.after());
	}

	@Test
	void givesUpAfterThirtySecondsWithTheLastRead() throws Exception {
		SettlingWait.Result result = waitWith(List.of(Map.of("NORTE", 39)));

		assertFalse(result.settled());
		assertEquals(Duration.ofSeconds(30), result.waited());
		assertEquals(Map.of("NORTE", 39), result.after());
	}

	/** Reads the given values in order and then keeps repeating the last one. */
	private SettlingWait.Result waitWith(List<Map<String, Integer>> reads) throws Exception {
		Deque<Map<String, Integer>> pending = new ArrayDeque<>(reads);
		SettlingWait wait = new SettlingWait(() -> pending.size() > 1 ? pending.poll() : pending.peek(), clock,
				clock::advance);
		return wait.await(EXPECTED::equals);
	}

	private static final class MovableClock extends Clock {

		private Instant now = Instant.parse("2026-10-22T18:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
