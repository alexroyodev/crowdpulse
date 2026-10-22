package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ZoneRunnerTest {

	@Test
	void sendsRateTimesDurationEventsInOrderAndCountsResponses() throws Exception {
		TrafficGenerator generator = new TrafficGenerator("SUR", 10, new Random(7), Clock.systemUTC());
		List<SimulatedEvent> received = new ArrayList<>();

		long start = System.nanoTime();
		ZoneResult result = new ZoneRunner(generator, event -> {
			received.add(event);
			return ApiClient.Response.of(202);
		}, 200, Duration.ofMillis(250)).call();
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		// 200 ev/s for 0.25 s = 50 events, no matter how fast the machine is.
		assertEquals(50, result.sent());
		assertEquals(received.size(), result.sent());
		assertEquals(Map.of(202, 50), result.statuses());
		assertEquals(received.stream().filter(SimulatedEvent::duplicate).count(), result.duplicates());
		assertEquals(generator.occupancy(), result.expectedDelta());
		// Paced, not sent as fast as possible: the last event is due at 245 ms.
		assertTrue(elapsed.toMillis() >= 240, "finished too early: " + elapsed);
	}

	@Test
	void countsEveryStatusSeparately() throws Exception {
		TrafficGenerator generator = new TrafficGenerator("SUR", 0, new Random(7), Clock.systemUTC());
		int[] calls = { 0 };

		ZoneResult result = new ZoneRunner(generator,
				event -> ApiClient.Response.of(++calls[0] % 2 == 0 ? 500 : ApiClient.CONNECTION_ERROR),
				1_000, Duration.ofMillis(10)).call();

		assertEquals(Map.of(500, 5, ApiClient.CONNECTION_ERROR, 5), result.statuses());
	}

	// --- Spec 005: a 503 is retried like a sensor would. The pause moves a fake clock, so no real waiting. ---

	@Test
	void aRejectedEventIsResentUntilItIsAccepted() throws Exception {
		MovableClock clock = new MovableClock();
		Deque<Integer> responses = new ArrayDeque<>(List.of(503, 503, 202));
		List<SimulatedEvent> sent = new ArrayList<>();

		ZoneResult result = oneEvent(event -> {
			sent.add(event);
			return new ApiClient.Response(responses.isEmpty() ? 202 : responses.poll(), Duration.ofSeconds(2));
		}, clock);

		assertEquals(3, sent.size());
		assertEquals(1, sent.stream().distinct().count(), "the same event every time");
		assertEquals(Map.of(202, 1), result.statuses());
		assertEquals(2, result.retries());
		assertEquals(1, result.retriedEvents());
		assertEquals(Duration.ofSeconds(4), clock.elapsed(), "waited what Retry-After asked");
	}

	@Test
	void aRejectionThatLastsMoreThanSixtySecondsCountsAsFailed() throws Exception {
		MovableClock clock = new MovableClock();

		ZoneResult result = oneEvent(event -> new ApiClient.Response(503, Duration.ofSeconds(1)), clock);

		assertEquals(Map.of(503, 1), result.statuses());
		assertEquals(60, result.retries());
		assertEquals(Duration.ofSeconds(60), clock.elapsed());
	}

	@Test
	void theZoneSendsNothingElseWhileRetrying() throws Exception {
		TrafficGenerator generator = new TrafficGenerator("SUR", 0, new Random(7), Clock.systemUTC());
		MovableClock clock = new MovableClock();
		List<SimulatedEvent> sent = new ArrayList<>();
		int[] calls = { 0 };

		new ZoneRunner(generator, event -> {
			sent.add(event);
			// The first event is rejected twice; every other attempt is accepted.
			return ApiClient.Response.of(++calls[0] <= 2 ? 503 : 202);
		}, 1_000, Duration.ofMillis(5), clock, clock::advance).call();

		// 5 events, the first one sent three times in a row before the second.
		assertEquals(7, sent.size());
		assertEquals(sent.get(0), sent.get(1));
		assertEquals(sent.get(0), sent.get(2));
		assertEquals(5, sent.stream().distinct().count());
	}

	@Test
	void retryAfterIsReadInSecondsWithOneSecondByDefault() {
		assertEquals(Duration.ofSeconds(3), ApiClient.retryAfter("3"));
		assertEquals(Duration.ofSeconds(1), ApiClient.retryAfter(null));
		assertEquals(Duration.ofSeconds(1), ApiClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
		assertEquals(Duration.ofSeconds(1), ApiClient.retryAfter("-5"));
	}

	/** Sends exactly one event (rate 1 000 ev/s for 1 ms), retrying with the fake clock. */
	private static ZoneResult oneEvent(Function<SimulatedEvent, ApiClient.Response> send, MovableClock clock)
			throws Exception {
		TrafficGenerator generator = new TrafficGenerator("SUR", 0, new Random(7), Clock.systemUTC());
		return new ZoneRunner(generator, send, 1_000, Duration.ofMillis(1), clock, clock::advance).call();
	}

	private static final class MovableClock extends Clock {

		private final Instant start = Instant.parse("2026-10-22T18:00:00Z");
		private Instant now = start;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		Duration elapsed() {
			return Duration.between(start, now);
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
