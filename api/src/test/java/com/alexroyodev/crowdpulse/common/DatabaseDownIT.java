package com.alexroyodev.crowdpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Spec 004, D3 / CA7: with PostgreSQL not answering (docker pause) the API cannot read the zone
 * catalogue, so it answers 503 with ProblemDetail, never 202 and never 500; queries too. Once the
 * database is back it accepts again. Its own containers, so the pause does not affect other tests.
 */
@Import(DatabaseDownIT.OwnContainers.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class DatabaseDownIT {

	@TestConfiguration(proxyBeanMethods = false)
	static class OwnContainers {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);
		}

	}

	private final RestTestClient client;
	private final PostgreSQLContainer postgres;
	private final Clock clock;
	private final MeterRegistry registry;

	@Autowired
	DatabaseDownIT(RestTestClient client, PostgreSQLContainer postgres, Clock clock, MeterRegistry registry) {
		this.client = client;
		this.postgres = postgres;
		this.clock = clock;
		this.registry = registry;
	}

	@Test
	void withoutTheDatabaseTheApiAnswers503AndAcceptsAgainWhenItIsBack() throws InterruptedException {
		post(UUID.randomUUID()).expectStatus().isAccepted();
		long unavailableBefore = eventRequests("503");

		pause();
		try {
			long start = System.nanoTime();
			post(UUID.randomUUID())
					.expectStatus().isEqualTo(503)
					.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
					.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
					.expectBody()
					.jsonPath("$.title").isEqualTo("Servicio no disponible")
					.jsonPath("$.detail").isEqualTo("La base de datos no está disponible; reinténtalo.")
					.jsonPath("$.trace").doesNotExist();
			// Bounded by the API's timeouts: 3 s for a connection, 3 s for a query on a hung socket.
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
			client.get().uri("/api/v1/zones").exchange().expectStatus().isEqualTo(503);
		}
		finally {
			unpause();
		}
		// Spec 006, CA5: Spring Boot's own HTTP metric counts the 503 (plan 006, section 0).
		assertThat(eventRequests("503") - unavailableBefore).isEqualTo(1);

		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (post(UUID.randomUUID()).returnResult().getStatus().value() != 202) {
			assertThat(System.nanoTime()).as("202 within 30 s after the database is back").isLessThan(deadline);
			Thread.sleep(500);
		}
	}

	private ResponseSpec post(UUID eventId) {
		return client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
						""".formatted(eventId, clock.instant().minusSeconds(1)))
				.exchange();
	}

	/** POST /api/v1/events requests answered with that status, from http.server.requests. */
	private long eventRequests(String status) {
		Timer requests = registry.find("http.server.requests")
				.tags("method", "POST", "uri", "/api/v1/events", "status", status)
				.timer();
		return requests == null ? 0 : requests.count();
	}

	private void pause() {
		DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
	}

	private void unpause() {
		DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
	}

}
