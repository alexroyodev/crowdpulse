package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * CA8: after restarting the API the occupancy is the same, because PostgreSQL is the source of
 * truth (principle 6). The application is started twice for real, against one database that
 * outlives both runs; Spring's test context cache is not involved.
 */
class RestartIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	@BeforeAll
	static void startDatabase() {
		postgres.start();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void occupancySurvivesARestart() {
		String before;
		try (ConfigurableApplicationContext app = startApi()) {
			RestClient api = clientFor(app);
			Instant occurredAt = Clock.systemUTC().instant().minusSeconds(1);
			post(api, "NORTE", "ENTRY", occurredAt);
			post(api, "NORTE", "ENTRY", occurredAt);
			post(api, "NORTE", "ENTRY", occurredAt);
			post(api, "NORTE", "EXIT", occurredAt);
			post(api, "SUR", "ENTRY", occurredAt);
			before = zones(api);
		}

		String after;
		try (ConfigurableApplicationContext app = startApi()) {
			after = zones(clientFor(app));
		}

		assertThat(before).contains("\"zoneId\":\"NORTE\"", "\"occupancy\":2", "\"occupancy\":1");
		assertThat(after).isEqualTo(before);
	}

	private static ConfigurableApplicationContext startApi() {
		return new SpringApplicationBuilder(CrowdpulseApplication.class)
				.properties(
						"server.port=0",
						"spring.docker.compose.enabled=false",
						"spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword())
				.run();
	}

	private static RestClient clientFor(ConfigurableApplicationContext app) {
		String port = app.getEnvironment().getProperty("local.server.port");
		return RestClient.create("http://localhost:" + port);
	}

	private static void post(RestClient api, String zoneId, String type, Instant occurredAt) {
		String body = """
				{"eventId":"%s","sensorId":"%s-P01","zoneId":"%s","type":"%s","occurredAt":"%s"}
				""".formatted(UUID.randomUUID(), zoneId, zoneId, type, occurredAt);
		api.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toBodilessEntity();
	}

	private static String zones(RestClient api) {
		return api.get().uri("/api/v1/zones").retrieve().body(String.class);
	}

}
