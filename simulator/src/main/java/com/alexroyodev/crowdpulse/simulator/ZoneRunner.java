package com.alexroyodev.crowdpulse.simulator;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;

/**
 * Sends one zone's events, one after another, at a fixed rate. Sending synchronously in a single
 * thread keeps the zone's events in order, so the API never sees an EXIT before its ENTRY
 * (the same per-zone ordering Kafka will give in phase 2).
 */
class ZoneRunner implements Callable<ZoneResult> {

	private final TrafficGenerator generator;
	private final ToIntFunction<SimulatedEvent> send;
	private final double ratePerSecond;
	private final Duration duration;

	ZoneRunner(TrafficGenerator generator, ToIntFunction<SimulatedEvent> send, double ratePerSecond, Duration duration) {
		this.generator = generator;
		this.send = send;
		this.ratePerSecond = ratePerSecond;
		this.duration = duration;
	}

	@Override
	public ZoneResult call() throws InterruptedException {
		long durationNanos = duration.toNanos();
		long start = System.nanoTime();
		int newEvents = 0;
		int duplicates = 0;
		Map<Integer, Integer> statuses = new TreeMap<>();

		for (long i = 0;; i++) {
			// Event i is due at start + i / rate. If we are late, send at once instead of waiting.
			long offset = (long) (i * 1_000_000_000L / ratePerSecond);
			if (offset >= durationNanos) {
				break;
			}
			long wait = start + offset - System.nanoTime();
			if (wait > 0) {
				Thread.sleep(Duration.ofNanos(wait));
			}

			SimulatedEvent event = generator.next((double) offset / durationNanos);
			int status = send.applyAsInt(event);
			statuses.merge(status, 1, Integer::sum);
			if (event.duplicate()) {
				duplicates++;
			}
			else {
				newEvents++;
			}
		}
		return new ZoneResult(generator.zoneId(), newEvents, duplicates, statuses, generator.occupancy());
	}

}
