package com.alexroyodev.crowdpulse.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ZoneQueryIT {

	private final RestTestClient client;
	private final JdbcClient jdbc;

	@Autowired
	ZoneQueryIT(RestTestClient client, JdbcClient jdbc) {
		this.client = client;
		this.jdbc = jdbc;
	}

	@Test
	void listsTheFourZonesOrderedByZoneId() {
		client.get().uri("/api/v1/zones")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(4)
				.jsonPath("$[*].zoneId").isEqualTo(List.of("ESTE", "NORTE", "OESTE", "SUR"))
				.jsonPath("$[1].name").isEqualTo("Fondo Norte")
				.jsonPath("$[1].capacity").isEqualTo(20000)
				.jsonPath("$[1].occupancy").isEqualTo(0)
				.jsonPath("$[1].lastEventAt").isEmpty();
	}

	@Test
	void serializesPercentageAsNumberAndLastEventAtAsIsoUtc() {
		jdbc.sql("""
				UPDATE zone_occupancy
				SET occupancy = 12345, last_event_at = '2026-10-19T18:30:05.123Z'
				WHERE zone_id = 'NORTE'
				""").update();

		String body = client.get().uri("/api/v1/zones/NORTE")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		// Exact JSON text: occupancyPct a number with one decimal, lastEventAt an ISO-8601 string.
		assertThat(body).isEqualTo("""
				{"zoneId":"NORTE","name":"Fondo Norte","capacity":20000,"occupancy":12345,\
				"occupancyPct":61.7,"lastEventAt":"2026-10-19T18:30:05.123Z"}""");
	}

	@Test
	void lastEventAtAlwaysHasMilliseconds() {
		// Whole seconds keep ".000"; microseconds (PostgreSQL precision) are cut to milliseconds.
		jdbc.sql("UPDATE zone_occupancy SET last_event_at = '2026-10-19T18:30:05Z' WHERE zone_id = 'ESTE'").update();
		jdbc.sql("UPDATE zone_occupancy SET last_event_at = '2026-10-19T18:30:05.988466Z' WHERE zone_id = 'OESTE'")
				.update();

		client.get().uri("/api/v1/zones")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].lastEventAt").isEqualTo("2026-10-19T18:30:05.000Z")
				.jsonPath("$[2].lastEventAt").isEqualTo("2026-10-19T18:30:05.988Z");
	}

	@Test
	void emptyZoneHasZeroPercentWithOneDecimal() {
		String body = client.get().uri("/api/v1/zones/SUR")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		assertThat(body).contains("\"occupancyPct\":0.0", "\"lastEventAt\":null");
	}

	@Test
	void unknownZoneIsNotFoundWithProblemDetail() {
		// CA9
		client.get().uri("/api/v1/zones/NOEXISTE")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(404)
				.jsonPath("$.title").isEqualTo("Zona no encontrada")
				.jsonPath("$.zoneId").isEqualTo("NOEXISTE");
	}

	@Test
	void zoneIdIsCaseSensitive() {
		client.get().uri("/api/v1/zones/norte")
				.exchange()
				.expectStatus().isNotFound();
	}

}
