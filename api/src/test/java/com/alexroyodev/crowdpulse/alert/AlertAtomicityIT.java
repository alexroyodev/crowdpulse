package com.alexroyodev.crowdpulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

/**
 * Spec 002, rule 7 / CA6: the event, the occupancy and the level change are stored in one
 * transaction. The alert insert, the last write, is made to fail; the earlier writes must be
 * rolled back too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class AlertAtomicityIT {

	@MockitoSpyBean
	AlertRepository alerts;

	private final RestTestClient client;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	AlertAtomicityIT(RestTestClient client, JdbcClient jdbc, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void nothingIsStoredWhenRecordingTheLevelChangeFails() {
		doThrow(new IllegalStateException("simulated failure")).when(alerts).insert(any());
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 169 WHERE zone_id = 'PALCO'").update();
		UUID eventId = UUID.randomUUID();
		String body = """
				{"eventId":"%s","sensorId":"PALCO-P01","zoneId":"PALCO","type":"ENTRY","occurredAt":"%s"}
				""".formatted(eventId, clock.instant().minusSeconds(1));

		client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isEqualTo(500);

		// The event INSERT and the occupancy UPDATE ran before the failure; both were rolled back.
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id").param("id", eventId)
				.query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'PALCO'")
				.query(Integer.class).single()).isEqualTo(169);
		assertThat(jdbc.sql("SELECT alert_level FROM zone_occupancy WHERE zone_id = 'PALCO'")
				.query(String.class).single()).isEqualTo("NORMAL");
	}

}
