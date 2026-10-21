package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ZoneRunnerTest {

	@Test
	void sendsRateTimesDurationEventsInOrderAndCountsResponses() throws Exception {
		TrafficGenerator generator = new TrafficGenerator("SUR", 10, new Random(7), Clock.systemUTC());
		List<SimulatedEvent> received = new ArrayList<>();

		long start = System.nanoTime();
		ZoneResult result = new ZoneRunner(generator, event -> {
			received.add(event);
			return 202;
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

		ZoneResult result = new ZoneRunner(generator, event -> ++calls[0] % 2 == 0 ? 500 : ApiClient.CONNECTION_ERROR,
				1_000, Duration.ofMillis(10)).call();

		assertEquals(Map.of(500, 5, ApiClient.CONNECTION_ERROR, 5), result.statuses());
	}

}
