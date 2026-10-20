package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

@Import({ TestcontainersConfiguration.class, EventIngestionIT.FixedClockConfig.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class EventIngestionIT {

	static final Instant NOW = Instant.parse("2026-10-19T18:30:00Z");

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}

	private final RestTestClient client;
	private final JdbcClient jdbc;

	@Autowired
	EventIngestionIT(RestTestClient client, JdbcClient jdbc) {
		this.client = client;
		this.jdbc = jdbc;
	}

	@Test
	void entriesAndExitsUpdateOccupancy() {
		// CA1: 3 ENTRY + 1 EXIT -> 2. The newest occurredAt is not the last one sent.
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.minusSeconds(40))).expectStatus().isAccepted();
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.minusSeconds(10))).expectStatus().isAccepted();
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.minusSeconds(30))).expectStatus().isAccepted();
		post(event(UUID.randomUUID(), "NORTE", "EXIT", NOW.minusSeconds(20))).expectStatus().isAccepted();

		client.get().uri("/api/v1/zones/NORTE")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.occupancy").isEqualTo(2)
				.jsonPath("$.lastEventAt").isEqualTo(NOW.minusSeconds(10).toString());
	}

	@Test
	void duplicateEventIsAcceptedButCountedOnce() {
		// CA2
		String body = event(UUID.randomUUID(), "SUR", "ENTRY", NOW.minusSeconds(5));

		post(body).expectStatus().isAccepted();
		post(body).expectStatus().isAccepted();

		assertThat(occupancyOf("SUR")).isEqualTo(1);
		assertThat(storedEvents()).isEqualTo(1);
	}

	@Test
	void unknownZoneIsUnprocessableAndNothingIsStored() {
		// CA4
		post(event(UUID.randomUUID(), "NOEXISTE", "ENTRY", NOW.minusSeconds(5)))
				.expectStatus().isEqualTo(422)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Zona desconocida")
				.jsonPath("$.zoneId").isEqualTo("NOEXISTE");

		assertThat(storedEvents()).isZero();
	}

	@Test
	void exitOnEmptyZoneIsAcceptedKeptAtZeroAndMarkedAsAnomaly() {
		// CA7 (rule 5)
		UUID eventId = UUID.randomUUID();

		post(event(eventId, "ESTE", "EXIT", NOW.minusSeconds(5))).expectStatus().isAccepted();

		assertThat(occupancyOf("ESTE")).isZero();
		Boolean anomaly = jdbc.sql("SELECT anomaly FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query(Boolean.class)
				.single();
		assertThat(anomaly).isTrue();
	}

	@Test
	void storesEventTimeAndProcessingTimeSeparately() {
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = NOW.minusSeconds(7);

		post(event(eventId, "OESTE", "ENTRY", occurredAt)).expectStatus().isAccepted();

		StoredTimes times = jdbc.sql("SELECT occurred_at, received_at FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query((rs, rowNum) -> new StoredTimes(
						rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
						rs.getObject("received_at", OffsetDateTime.class).toInstant()))
				.single();
		assertThat(times.occurredAt()).isEqualTo(occurredAt);
		assertThat(times.receivedAt()).isEqualTo(NOW);
	}

	record StoredTimes(Instant occurredAt, Instant receivedAt) {
	}

	private ResponseSpec post(String body) {
		return client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange();
	}

	private static String event(UUID eventId, String zoneId, String type, Instant occurredAt) {
		return """
				{"eventId":"%s","sensorId":"%s-P01","zoneId":"%s","type":"%s","occurredAt":"%s"}
				""".formatted(eventId, zoneId, zoneId, type, occurredAt);
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
