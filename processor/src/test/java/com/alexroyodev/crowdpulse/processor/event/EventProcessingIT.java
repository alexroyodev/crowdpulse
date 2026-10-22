package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * The processing rules of spec 001, applied by the processor (spec 003, rule 1). Moved from the
 * API's EventIngestionIT: the same cases and assertions, without HTTP in between. What the API
 * still decides (400, 422, 202) is tested there.
 */
@Import({ TestcontainersConfiguration.class, EventProcessingIT.FixedClockConfig.class })
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class EventProcessingIT {

	static final Instant NOW = Instant.parse("2026-10-19T18:30:00Z");

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}

	private final EventProcessor processor;
	private final JdbcClient jdbc;

	@Autowired
	EventProcessingIT(EventProcessor processor, JdbcClient jdbc) {
		this.processor = processor;
		this.jdbc = jdbc;
	}

	@Test
	void entriesAndExitsUpdateOccupancy() {
		// Spec 001, CA1: 3 ENTRY + 1 EXIT -> 2. The newest occurredAt is not the last one sent.
		processor.process(message(UUID.randomUUID(), "NORTE", ENTRY, NOW.minusSeconds(40), NOW));
		processor.process(message(UUID.randomUUID(), "NORTE", ENTRY, NOW.minusSeconds(10), NOW));
		processor.process(message(UUID.randomUUID(), "NORTE", ENTRY, NOW.minusSeconds(30), NOW));
		processor.process(message(UUID.randomUUID(), "NORTE", EXIT, NOW.minusSeconds(20), NOW));

		assertThat(occupancyOf("NORTE")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT last_event_at FROM zone_occupancy WHERE zone_id = 'NORTE'")
				.query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
				.single()).isEqualTo(Instant.parse("2026-10-19T18:29:50Z"));
	}

	@Test
	void duplicateEventIsCountedOnce() {
		// Spec 001, CA2; spec 003, CA4: the API publishes both copies, the processor ignores the second.
		SensorEventMessage message = message(UUID.randomUUID(), "SUR", ENTRY, NOW.minusSeconds(5), NOW);

		processor.process(message);
		processor.process(message);

		assertThat(occupancyOf("SUR")).isEqualTo(1);
		assertThat(storedEvents()).isEqualTo(1);
	}

	@Test
	void unknownZoneIsRejectedAndNothingIsStored() {
		// Spec 001, CA4. The API answers 422 first (D2); this is the processor's own check.
		SensorEventMessage message = message(UUID.randomUUID(), "NOEXISTE", ENTRY, NOW.minusSeconds(5), NOW);

		assertThatThrownBy(() -> processor.process(message))
				.isInstanceOf(UnknownZoneException.class)
				.hasMessageContaining("NOEXISTE");

		assertThat(storedEvents()).isZero();
	}

	@Test
	void exitOnEmptyZoneIsKeptAtZeroAndMarkedAsAnomaly() {
		// Spec 001, CA7 (rule 5)
		UUID eventId = UUID.randomUUID();

		processor.process(message(eventId, "ESTE", EXIT, NOW.minusSeconds(5), NOW));

		assertThat(occupancyOf("ESTE")).isZero();
		Boolean anomaly = jdbc.sql("SELECT anomaly FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query(Boolean.class)
				.single();
		assertThat(anomaly).isTrue();
	}

	@Test
	void storesEventTimeReceptionTimeAndProcessingTimeSeparately() {
		// Spec 003, rule 6: occurredAt from the sensor, receivedAt from the API, processedAt here.
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = NOW.minusSeconds(7);
		Instant receivedAt = NOW.minusMillis(250);

		processor.process(message(eventId, "OESTE", ENTRY, occurredAt, receivedAt));

		StoredTimes times = jdbc.sql("SELECT occurred_at, received_at, processed_at FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query((rs, rowNum) -> new StoredTimes(
						rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
						rs.getObject("received_at", OffsetDateTime.class).toInstant(),
						rs.getObject("processed_at", OffsetDateTime.class).toInstant()))
				.single();
		assertThat(times.occurredAt()).isEqualTo(occurredAt);
		assertThat(times.receivedAt()).isEqualTo(receivedAt);
		assertThat(times.processedAt()).isEqualTo(NOW);
	}

	record StoredTimes(Instant occurredAt, Instant receivedAt, Instant processedAt) {
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId)
				.query(Integer.class)
				.single();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

}
