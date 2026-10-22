package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.TopicProbe;
import com.alexroyodev.crowdpulse.shared.Topics;

@Import({ TestcontainersConfiguration.class, EventIngestionIT.FixedClockConfig.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
/**
 * What the API still decides itself (spec 003): 400, 422 and 202, and that a rejected event never
 * reaches Kafka. The processing cases that used to live here (occupancy, duplicates, anomalies,
 * stored times) moved with the code to the processor's EventProcessingIT.
 */
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
	private final TopicProbe topic;

	@Autowired
	EventIngestionIT(RestTestClient client, ConsumerFactory<String, String> consumers) {
		this.client = client;
		this.topic = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
	}

	@BeforeEach
	void markTopic() {
		topic.mark();
	}

	@Test
	void unknownZoneIsUnprocessableAndNothingIsPublished() {
		// Spec 001 CA4; spec 003 D2 (checked before publishing) and CA3
		post(event(UUID.randomUUID(), "NOEXISTE", "ENTRY", NOW.minusSeconds(5)))
				.expectStatus().isEqualTo(422)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Zona desconocida")
				.jsonPath("$.zoneId").isEqualTo("NOEXISTE");

		assertNothingPublished();
	}

	// --- Spec 001 CA3: invalid requests -> 400 ProblemDetail naming the field; spec 003 CA3: nothing published ---

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

		assertNothingPublished();
	}

	@Test
	void unknownTypeIsRejectedListingTheAllowedValues() {
		post(event(UUID.randomUUID(), "NORTE", "JUMP", NOW))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.title").isEqualTo("Petición mal formada")
				.jsonPath("$.errors[0].field").isEqualTo("type")
				.jsonPath("$.errors[0].message").isEqualTo("valor no válido; valores permitidos: [ENTRY, EXIT]");

		assertNothingPublished();
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

		assertNothingPublished();
	}

	@Test
	void malformedOccurredAtIsRejectedNamingIt() {
		post("""
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"ayer"}
				""".formatted(UUID.randomUUID()))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[0].field").isEqualTo("occurredAt");

		assertNothingPublished();
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

		assertNothingPublished();
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

		assertNothingPublished();
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

		assertNothingPublished();
	}

	@Test
	void occurredAtFourMinutesAheadIsAccepted() {
		post(event(UUID.randomUUID(), "NORTE", "ENTRY", NOW.plusSeconds(240))).expectStatus().isAccepted();

		assertThat(topic.published()).hasSize(1);
	}

	@Test
	void unknownJsonFieldsAreIgnored() {
		// A producer that sends a newer field must not break (constitution, principle 5).
		post("""
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY",\
				"occurredAt":"%s","battery":87}
				""".formatted(UUID.randomUUID(), NOW))
				.expectStatus().isAccepted();

		assertThat(topic.published()).singleElement()
				.extracting(record -> record.value()).asString().doesNotContain("battery");
	}

	private void assertNothingPublished() {
		assertThat(topic.published()).isEmpty();
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

}
