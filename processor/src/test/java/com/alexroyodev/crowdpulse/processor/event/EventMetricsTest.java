package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.alexroyodev.crowdpulse.processor.event.Processed.Outcome;
import com.alexroyodev.crowdpulse.shared.AlertLevel;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Spec 006: what EventMetrics records for each outcome. No Spring: an in-memory registry. */
class EventMetricsTest {

	private static final Instant RECEIVED_AT = Instant.parse("2026-10-25T18:00:00Z");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final EventMetrics metrics = new EventMetrics(registry);

	@Test
	void countsEachOutcomeByZone() {
		metrics.record(norte(RECEIVED_AT.minusSeconds(1)), new Processed(Outcome.APPLIED, RECEIVED_AT, null));
		metrics.record(norte(RECEIVED_AT.minusSeconds(1)), new Processed(Outcome.APPLIED, RECEIVED_AT, null));
		metrics.record(norte(RECEIVED_AT.minusSeconds(1)), Processed.duplicate(RECEIVED_AT));
		metrics.record(norte(RECEIVED_AT.minusSeconds(1)), new Processed(Outcome.EXIT_AT_ZERO, RECEIVED_AT, null));

		assertThat(processed("NORTE", "applied")).isEqualTo(2);
		assertThat(processed("NORTE", "duplicate")).isEqualTo(1);
		assertThat(processed("NORTE", "exit_at_zero")).isEqualTo(1);
	}

	@Test
	void latencyIsProcessedAtMinusReceivedAtAndSensorDelayIsReceivedAtMinusOccurredAt() {
		metrics.record(norte(RECEIVED_AT.minusSeconds(3)),
				new Processed(Outcome.APPLIED, RECEIVED_AT.plusMillis(250), null));

		assertThat(latency().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
		assertThat(sensorDelay().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3_000);
	}

	@Test
	void sensorClockAheadCountsAsNoDelay() {
		metrics.record(norte(RECEIVED_AT.plusSeconds(60)), new Processed(Outcome.APPLIED, RECEIVED_AT, null));

		assertThat(sensorDelay().count()).isEqualTo(1);
		assertThat(sensorDelay().totalTime(TimeUnit.MILLISECONDS)).isZero();
	}

	@Test
	void duplicatesAreNotTimed() {
		metrics.record(norte(RECEIVED_AT.minusSeconds(1)), Processed.duplicate(RECEIVED_AT.plusSeconds(5)));

		assertThat(latency().count()).isZero();
		assertThat(sensorDelay().count()).isZero();
	}

	@Test
	void alertCountedOnlyWhenTheLevelChanges() {
		metrics.record(norte(RECEIVED_AT), new Processed(Outcome.APPLIED, RECEIVED_AT, null));
		metrics.record(norte(RECEIVED_AT), new Processed(Outcome.APPLIED, RECEIVED_AT, AlertLevel.WARNING));

		assertThat(registry.get("crowdpulse.alerts").tags("zone", "NORTE", "level", "warning").counter().count())
				.isEqualTo(1);
		assertThat(registry.find("crowdpulse.alerts").counters()).hasSize(1);
	}

	private static SensorEventMessage norte(Instant occurredAt) {
		return message(UUID.randomUUID(), "NORTE", ENTRY, occurredAt, RECEIVED_AT);
	}

	private double processed(String zone, String outcome) {
		return registry.get("crowdpulse.events.processed").tags("zone", zone, "outcome", outcome).counter().count();
	}

	private Timer latency() {
		return registry.get("crowdpulse.event.processing.latency").timer();
	}

	private Timer sensorDelay() {
		return registry.get("crowdpulse.event.sensor.delay").timer();
	}

}
