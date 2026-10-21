package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

	// --- CA3: invalid requests -> 400 ProblemDetail naming the field; nothing changes ---

	@Test
	void missingFieldIsRejectedNamingIt() {
		post("""
				{"eventId":"%s","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
				""".formatted(UUID.randomUUID(), NOW))
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Datos no válidos")
				.jsonPath("$.errors[0].field").isEqualTo("sensorId")
				.jsonPath("$.errors[0].message").isEqualTo("no debe estar vacío");

		assertNothingChanged();
	}

	@Test
	void unknownTypeIsRejectedListingTheAllowedValues() {
		post(event(UUID.randomUUID(), "NORTE", "JUMP", NOW))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.title").isEqualTo("Petición mal formada")
				.jsonPath("$.errors[0].field").isEqualTo("type")
				.jsonPath("$.errors[0].message").isEqualTo("valor no válido; valores permitidos: [ENTRY, EXIT]");

		assertNothingChanged();
	}

	@Test
	void malformedEventIdIsRejectedNamingIt() {
		post("""
				{"eventId":"not-a-uuid","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
				""".formatted(NOW))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("eventId")
				.jsonPath("$.errors[0].message").isEqualTo("formato no válido");

		assertNothingChanged();
	}

	@Test
	void malformedOccurredAtIsRejectedNamingIt() {
		post("""
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"ayer"}
				""".formatted(UUID.randomUUID()))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("occurredAt");

		assertNothingChanged();
	}

	@Test
	void brokenJsonIsRejectedWithoutLeakingParserDetails() {
		post("{\"eventId\": ")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.title").isEqualTo("Petición mal formada")
				.jsonPath("$.detail").isEqualTo("El cuerpo de la petición no se puede leer.")
				.jsonPath("$.errors").doesNotExist()
				.jsonPath("$.trace").doesNotExist();

		assertNothingChanged();
	}

	@Test
	void tooLongSensorIdIsRejected() {
		String body = """
				{"eventId":"%s","sensorId":"%s","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
				""".formatted(UUID.randomUUID(), "S".repeat(51), NOW);

		post(body)
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("sensorId");

		assertNothingChanged();
	}

	@Test
	void everyInvalidFieldIsReported() {
		post("""
				{"eventId":"%s","type":"ENTRY"}
				""".formatted(UUID.randomUUID()))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[*].field").isEqualTo(List.of("occurredAt", "sensorId", "zoneId"));
	}

	// --- CA5 over HTTP: also proves Spring builds the validator with the (fixed) Clock ---

	@Test
	void occurredAtJustBeyondFiveMinutesAheadIsRejected() {
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.plusSeconds(300).plusMillis(1)))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("occurredAt")
				.jsonPath("$.errors[0].message").isEqualTo("está demasiado lejos en el futuro respecto al reloj del servidor");

		assertNothingChanged();
	}

	@Test
	void occurredAtFourMinutesAheadIsAccepted() {
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.plusSeconds(240))).expectStatus().isAccepted();

		assertThat(occupancyOf("NORTE")).isEqualTo(1);
	}

	@Test
	void unknownJsonFieldsAreIgnored() {
		// A producer that sends a newer field must not break (constitution, principle 5).
		post("""
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY",\
				"occurredAt":"%s","battery":87}
				""".formatted(UUID.randomUUID(), NOW))
				.expectStatus().isAccepted();

		assertThat(occupancyOf("NORTE")).isEqualTo(1);
	}

	private void assertNothingChanged() {
		assertThat(storedEvents()).isZero();
		assertThat(occupancyOf("NORTE")).isZero();
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
