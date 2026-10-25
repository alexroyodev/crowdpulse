package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec 006, CA1: /actuator/prometheus answers in the API. Its contract is Spring Boot's own HTTP
 * metric for POST /api/v1/events, by status (plan 006, section 0). In tests Spring Boot only sets
 * up the in-memory registry; @AutoConfigureMetrics brings the Prometheus one.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureMetrics
class PrometheusEndpointIT {

	private final RestTestClient client;
	private final Clock clock;

	@Autowired
	PrometheusEndpointIT(RestTestClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	@Test
	void exposesTheEventRequestsByStatus() {
		client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
						""".formatted(UUID.randomUUID(), clock.instant().minusSeconds(1)))
				.exchange()
				.expectStatus().isAccepted();

		String metrics = client.get().uri("/actuator/prometheus").exchange()
				.expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		assertThat(metrics).containsPattern(
				"http_server_requests_seconds_count\\{[^}]*method=\"POST\"[^}]*status=\"202\"[^}]*uri=\"/api/v1/events\"");
	}

}
