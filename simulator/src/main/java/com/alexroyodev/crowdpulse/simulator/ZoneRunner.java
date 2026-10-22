package com.alexroyodev.crowdpulse.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Sends one zone's events, one after another, at a fixed rate. Sending synchronously in a single
 * thread keeps the zone's events in order, so the API never sees an EXIT before its ENTRY
 * (the same per-zone ordering Kafka will give in phase 2).
 *
 * <p>Spec 005: like a sensor, a 503 is retried with the same event after Retry-After, for up to
 * 60 s. The zone sends nothing else meanwhile, so its order holds.
 */
class ZoneRunner implements Callable<ZoneResult> {

	static final int SERVICE_UNAVAILABLE = 503;
	static final Duration MAX_RETRY = Duration.ofSeconds(60);

	private final TrafficGenerator generator;
	private final Function<SimulatedEvent, ApiClient.Response> send;
	private final double ratePerSecond;
	private final Duration duration;
	private final Clock clock;
	private final Pause pause;

	ZoneRunner(TrafficGenerator generator, Function<SimulatedEvent, ApiClient.Response> send, double ratePerSecond,
			Duration duration) {
		this(generator, send, ratePerSecond, duration, Clock.systemUTC(), Thread::sleep);
	}

	/** With the clock and pause of the 503 retries injected, for the tests. */
	ZoneRunner(TrafficGenerator generator, Function<SimulatedEvent, ApiClient.Response> send, double ratePerSecond,
			Duration duration, Clock clock, Pause pause) {
		this.generator = generator;
		this.send = send;
		this.ratePerSecond = ratePerSecond;
		this.duration = duration;
		this.clock = clock;
		this.pause = pause;
	}

	@Override
	public ZoneResult call() throws InterruptedException {
		long durationNanos = duration.toNanos();
		long start = System.nanoTime();
		int newEvents = 0;
		int duplicates = 0;
		int retries = 0;
		int retriedEvents = 0;
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
			ApiClient.Response response = send.apply(event);
			Instant firstAttempt = clock.instant();
			int retriesOfThisEvent = 0;
			while (response.status() == SERVICE_UNAVAILABLE
					&& Duration.between(firstAttempt, clock.instant()).compareTo(MAX_RETRY) < 0) {
				pause.sleep(response.retryAfter());
				response = send.apply(event);
				retriesOfThisEvent++;
			}
			retries += retriesOfThisEvent;
			retriedEvents += retriesOfThisEvent > 0 ? 1 : 0;
			// The last response is the one that counts: a 503 that ended in 202 is an accepted event.
			statuses.merge(response.status(), 1, Integer::sum);
			if (event.duplicate()) {
				duplicates++;
			}
			else {
				newEvents++;
			}
		}
		return new ZoneResult(generator.zoneId(), newEvents, duplicates, statuses, generator.occupancy(), retries,
				retriedEvents);
	}

}
