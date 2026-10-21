package com.alexroyodev.crowdpulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

/**
 * Spec 002 over HTTP against PostgreSQL. The starting state (e.g. NORTE at 16 999) is set with
 * SQL instead of sending thousands of events; the event that crosses the threshold goes by HTTP.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
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

	record AlertRow(String fromLevel, String toLevel, int occupancy, int capacity, UUID eventId) {
	}

	@Test
	void reachingEightyFivePercentRaisesWarning() {
		// CA1
		givenZone("NORTE", 16_999, "NORMAL");
		UUID eventId = UUID.randomUUID();

		send(eventId, "NORTE", "ENTRY");

		assertThat(levelOf("NORTE")).isEqualTo("WARNING");
		assertThat(alertsOf("NORTE")).containsExactly(new AlertRow("NORMAL", "WARNING", 17_000, 20_000, eventId));
	}

	@Test
	void warningIsKeptDownToEightyPercentAndClearedBelowIt() {
		// CA2: 1 000 EXIT from 17 000 to 16 000 (80.0 %) change nothing; one more clears it.
		givenZone("NORTE", 17_000, "WARNING");
		for (int i = 0; i < 1_000; i++) {
			send(UUID.randomUUID(), "NORTE", "EXIT");
		}
		assertThat(levelOf("NORTE")).isEqualTo("WARNING");
		assertThat(alertsOf("NORTE")).isEmpty();

		UUID last = UUID.randomUUID();
		send(last, "NORTE", "EXIT");

		assertThat(levelOf("NORTE")).isEqualTo("NORMAL");
		assertThat(alertsOf("NORTE")).containsExactly(new AlertRow("WARNING", "NORMAL", 15_999, 20_000, last));
	}

	@Test
	void oscillatingAroundTheThresholdRecordsASingleChange() {
		// CA3: ENTRY / EXIT ten times between 16 999 and 17 000.
		givenZone("NORTE", 16_999, "NORMAL");
		for (int i = 0; i < 10; i++) {
			send(UUID.randomUUID(), "NORTE", "ENTRY");
			send(UUID.randomUUID(), "NORTE", "EXIT");
		}

		assertThat(alertsOf("NORTE")).extracting(AlertRow::toLevel).containsExactly("WARNING");
		assertThat(levelOf("NORTE")).isEqualTo("WARNING");
	}

	@Test
	void criticalFromNinetyFivePercentStaysAboveCapacityAndClearsBelowNinety() {
		// CA4
		givenZone("NORTE", 18_999, "WARNING");
		send(UUID.randomUUID(), "NORTE", "ENTRY"); // 19 000 = 95.0 %
		assertThat(levelOf("NORTE")).isEqualTo("CRITICAL");

		givenZone("NORTE", 20_499, "CRITICAL");
		send(UUID.randomUUID(), "NORTE", "ENTRY"); // 20 500 = 102.5 %
		assertThat(levelOf("NORTE")).isEqualTo("CRITICAL");

		givenZone("NORTE", 18_000, "CRITICAL");
		send(UUID.randomUUID(), "NORTE", "EXIT"); // 17 999 = 89.995 %

		assertThat(levelOf("NORTE")).isEqualTo("WARNING");
		assertThat(alertsOf("NORTE")).extracting(AlertRow::fromLevel, AlertRow::toLevel)
				.containsExactly(tuple("WARNING", "CRITICAL"),
						tuple("CRITICAL", "WARNING"));
	}

	@Test
	void resendingTheEventThatChangedTheLevelRecordsNothingMore() {
		// CA5
		givenZone("NORTE", 16_999, "NORMAL");
		UUID eventId = UUID.randomUUID();

		send(eventId, "NORTE", "ENTRY");
		send(eventId, "NORTE", "ENTRY");

		assertThat(alertsOf("NORTE")).hasSize(1);
		assertThat(occupancyOf("NORTE")).isEqualTo(17_000);
	}

	@Test
	void anomalousExitDoesNotChangeTheLevel() {
		// Rule 1: an EXIT on an empty zone leaves occupancy at 0, so the level cannot change.
		send(UUID.randomUUID(), "PALCO", "EXIT");

		assertThat(levelOf("PALCO")).isEqualTo("NORMAL");
		assertThat(alertsOf("PALCO")).isEmpty();
	}

	@Test
	void everyLevelChangeIsLoggedAsWarning(CapturedOutput output) {
		// CA9
		givenZone("PALCO", 169, "NORMAL");
		UUID eventId = UUID.randomUUID();

		send(eventId, "PALCO", "ENTRY");

		String line = output.getOut().lines()
				.filter(l -> l.contains("Alert level changed"))
				.findFirst().orElseThrow();
		assertThat(line).contains("\"level\":\"WARN\"", "\"zoneId\":\"PALCO\"", "\"from\":\"NORMAL\"",
				"\"to\":\"WARNING\"", "\"occupancyPct\":", "85.0", "\"eventId\":\"" + eventId + "\"");
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
		UUID palcoWarning = UUID.randomUUID();
		UUID norteWarning = UUID.randomUUID();
		UUID palcoCritical = UUID.randomUUID();
		givenZone("PALCO", 169, "NORMAL");
		send(palcoWarning, "PALCO", "ENTRY");
		givenZone("NORTE", 16_999, "NORMAL");
		send(norteWarning, "NORTE", "ENTRY");
		givenZone("PALCO", 189, "WARNING");
		send(palcoCritical, "PALCO", "ENTRY");

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
		givenZone("PALCO", 169, "NORMAL");
		send(UUID.randomUUID(), "PALCO", "ENTRY");
		givenZone("NORTE", 16_999, "NORMAL");
		send(UUID.randomUUID(), "NORTE", "ENTRY");
		givenZone("PALCO", 189, "WARNING");
		UUID newest = UUID.randomUUID();
		send(newest, "PALCO", "ENTRY");

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

	private void givenZone(String zoneId, int occupancy, String level) {
		jdbc.sql("UPDATE zone_occupancy SET occupancy = :occupancy, alert_level = :level WHERE zone_id = :zoneId")
				.param("occupancy", occupancy)
				.param("level", level)
				.param("zoneId", zoneId)
				.update();
	}

	private void send(UUID eventId, String zoneId, String type) {
		String body = """
				{"eventId":"%s","sensorId":"%s-P01","zoneId":"%s","type":"%s","occurredAt":"%s"}
				""".formatted(eventId, zoneId, zoneId, type, clock.instant().minusSeconds(1));
		client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isAccepted();
	}

	private String levelOf(String zoneId) {
		return jdbc.sql("SELECT alert_level FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(String.class).single();
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

	private List<AlertRow> alertsOf(String zoneId) {
		return jdbc.sql("""
				SELECT from_level, to_level, occupancy, capacity, event_id
				FROM alert WHERE zone_id = :zoneId ORDER BY seq
				""")
				.param("zoneId", zoneId)
				.query(AlertRow.class)
				.list();
	}

}
