package com.alexroyodev.crowdpulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

/**
 * Spec 002, CA8: the alert history over HTTP. Since spec 003 the processor detects and stores the
 * changes (its AlertProcessingIT has the rest of spec 002), so here they are stored with SQL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class AlertIT {

	private final RestTestClient client;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	AlertIT(RestTestClient client, JdbcClient jdbc, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// --- CA8: GET /api/v1/alerts ---

	@Test
	void historyIsEmptyWithoutChanges() {
		client.get().uri("/api/v1/alerts")
				.exchange()
				.expectStatus().isOk()
				.expectBody().json("[]");
	}

	@Test
	void historyListsTheNewestChangeFirst() {
		UUID palcoWarning = givenAlert("PALCO", "NORMAL", "WARNING", 170, 200);
		UUID norteWarning = givenAlert("NORTE", "NORMAL", "WARNING", 17_000, 20_000);
		UUID palcoCritical = givenAlert("PALCO", "WARNING", "CRITICAL", 190, 200);

		client.get().uri("/api/v1/alerts")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(3)
				.jsonPath("$[*].eventId").isEqualTo(List.of(palcoCritical.toString(), norteWarning.toString(),
						palcoWarning.toString()))
				.jsonPath("$[0].zoneId").isEqualTo("PALCO")
				.jsonPath("$[0].from").isEqualTo("WARNING")
				.jsonPath("$[0].to").isEqualTo("CRITICAL")
				.jsonPath("$[0].occupancy").isEqualTo(190)
				.jsonPath("$[0].capacity").isEqualTo(200)
				.jsonPath("$[0].occupancyPct").isEqualTo(95.0)
				.jsonPath("$[0].alertId").isNotEmpty()
				.jsonPath("$[0].detectedAt").value(String.class,
						detectedAt -> assertThat(detectedAt).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
	}

	@Test
	void historyCanBeFilteredByZoneAndLimited() {
		givenAlert("PALCO", "NORMAL", "WARNING", 170, 200);
		givenAlert("NORTE", "NORMAL", "WARNING", 17_000, 20_000);
		UUID newest = givenAlert("PALCO", "WARNING", "CRITICAL", 190, 200);

		client.get().uri("/api/v1/alerts?zoneId=PALCO")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[*].zoneId").isEqualTo(List.of("PALCO", "PALCO"));
		client.get().uri("/api/v1/alerts?limit=1")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(1)
				.jsonPath("$[0].eventId").isEqualTo(newest.toString());
	}

	@Test
	void unknownZoneFilterIsNotFound() {
		client.get().uri("/api/v1/alerts?zoneId=NOEXISTE")
				.exchange()
				.expectStatus().isNotFound()
				.expectBody()
				.jsonPath("$.title").isEqualTo("Zona no encontrada")
				.jsonPath("$.zoneId").isEqualTo("NOEXISTE");
	}

	@Test
	void limitOutOfRangeIsBadRequestNamingTheParameter() {
		for (String limit : List.of("0", "501")) {
			client.get().uri("/api/v1/alerts?limit=" + limit)
					.exchange()
					.expectStatus().isBadRequest()
					.expectBody()
					.jsonPath("$.title").isEqualTo("Parámetros no válidos")
					.jsonPath("$.errors[0].field").isEqualTo("limit");
		}
	}

	@Test
	void nonNumericLimitIsBadRequestNamingTheParameter() {
		client.get().uri("/api/v1/alerts?limit=abc")
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.title").isEqualTo("Parámetros no válidos")
				.jsonPath("$.detail").isEqualTo("El parámetro 'limit' no admite el valor 'abc'.")
				.jsonPath("$.errors[0].field").isEqualTo("limit")
				.jsonPath("$.errors[0].message").isEqualTo("formato no válido");
	}

	/**
	 * Stores a level change as the processor would: its event first (foreign key), then the change.
	 * Since spec 003 the API only reads the history.
	 */
	private UUID givenAlert(String zoneId, String from, String to, int occupancy, int capacity) {
		UUID eventId = UUID.randomUUID();
		OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
		jdbc.sql("""
				INSERT INTO sensor_event (event_id, sensor_id, zone_id, type, occurred_at, received_at, processed_at)
				VALUES (:eventId, :sensorId, :zoneId, 'ENTRY', :now, :now, :now)
				""")
				.param("eventId", eventId).param("sensorId", zoneId + "-P01").param("zoneId", zoneId).param("now", now)
				.update();
		jdbc.sql("""
				INSERT INTO alert (alert_id, zone_id, from_level, to_level, occupancy, capacity, event_id,
				                   occurred_at, detected_at)
				VALUES (:alertId, :zoneId, :from, :to, :occupancy, :capacity, :eventId, :now, :now)
				""")
				.param("alertId", UUID.randomUUID()).param("zoneId", zoneId).param("from", from).param("to", to)
				.param("occupancy", occupancy).param("capacity", capacity).param("eventId", eventId).param("now", now)
				.update();
		return eventId;
	}

}
