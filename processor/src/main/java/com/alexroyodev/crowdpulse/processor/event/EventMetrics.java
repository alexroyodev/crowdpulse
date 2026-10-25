package com.alexroyodev.crowdpulse.processor.event;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.alexroyodev.crowdpulse.processor.event.Processed.Outcome;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Spec 006: counters and timers of the events the processor has applied. Micrometer turns the
 * dots into underscores and adds _total or _seconds for Prometheus, e.g.
 * crowdpulse_events_processed_total. Tags have few values: zone, outcome, level; never eventId.
 */
@Component
class EventMetrics {

	private final MeterRegistry registry;
	private final Timer processingLatency;
	private final Timer sensorDelay;

	EventMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.processingLatency = histogram("crowdpulse.event.processing.latency",
				"processedAt - receivedAt: from accepted by the API to applied by the processor");
		this.sensorDelay = histogram("crowdpulse.event.sensor.delay",
				"receivedAt - occurredAt: how late the event reached the API (sensor clock)");
	}

	/**
	 * Called once the event's transaction has committed (spec 006, rule 3). A rolled-back attempt
	 * never gets here, so a retried event counts once.
	 */
	void record(SensorEventMessage message, Processed processed) {
		registry.counter("crowdpulse.events.processed", "zone", message.zoneId(), "outcome", processed.outcome().tag())
				.increment();
		// A duplicate's processedAt is not stored and says nothing about the event's journey.
		if (processed.outcome() != Outcome.DUPLICATE) {
			// Rule 4: system clocks only for the latency; the sensor's clock goes apart.
			processingLatency.record(notNegative(Duration.between(message.receivedAt(), processed.processedAt())));
			sensorDelay.record(notNegative(Duration.between(message.occurredAt(), message.receivedAt())));
		}
		if (processed.newLevel() != null) {
			registry.counter("crowdpulse.alerts", "zone", message.zoneId(), "level",
					processed.newLevel().name().toLowerCase(Locale.ROOT)).increment();
		}
	}

	/**
	 * A timer drops negative values. A sensor whose clock is ahead (the API accepts up to 5 min in
	 * the future) has no delay, so it counts as 0 rather than disappearing from the histogram.
	 */
	private static Duration notNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}

	/** With buckets, so Prometheus can compute percentiles (histogram_quantile); 1 ms to 60 s. */
	private Timer histogram(String name, String description) {
		return Timer.builder(name)
				.description(description)
				.publishPercentileHistogram()
				.minimumExpectedValue(Duration.ofMillis(1))
				.maximumExpectedValue(Duration.ofSeconds(60))
				.register(registry);
	}

}
