package com.alexroyodev.crowdpulse.processor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.Messages;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.EventType;

/**
 * Spec 002 against PostgreSQL, applied by the processor (moved from the API's AlertIT; the
 * history endpoint is still tested there). The starting state (e.g. NORTE at 16 999) is set with
 * SQL instead of processing thousands of events.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class AlertProcessingIT {

	private final EventProcessor processor;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	AlertProcessingIT(EventProcessor processor, JdbcClient jdbc, Clock clock) {
		this.processor = processor;
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
	void levelChangeIsDetectedWhenTheEventIsProcessed() {
		// Spec 003: detected_at is the processing time, the same instant stored as processed_at.
		givenZone("NORTE", 16_999, "NORMAL");
		UUID eventId = UUID.randomUUID();

		send(eventId, "NORTE", "ENTRY");

		Instant detectedAt = jdbc.sql("SELECT detected_at FROM alert WHERE event_id = :id").param("id", eventId)
				.query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
		Instant processedAt = jdbc.sql("SELECT processed_at FROM sensor_event WHERE event_id = :id").param("id", eventId)
				.query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
		assertThat(detectedAt).isEqualTo(processedAt);
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

	private void givenZone(String zoneId, int occupancy, String level) {
		jdbc.sql("UPDATE zone_occupancy SET occupancy = :occupancy, alert_level = :level WHERE zone_id = :zoneId")
				.param("occupancy", occupancy)
				.param("level", level)
				.param("zoneId", zoneId)
				.update();
	}

	private void send(UUID eventId, String zoneId, String type) {
		Instant now = clock.instant();
		processor.process(Messages.message(eventId, zoneId, EventType.valueOf(type), now.minusSeconds(1), now));
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
