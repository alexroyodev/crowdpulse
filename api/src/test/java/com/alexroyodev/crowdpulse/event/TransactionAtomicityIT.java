package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.alexroyodev.crowdpulse.occupancy.OccupancyRepository;

/**
 * Rule 3: storing the event and updating the occupancy happen in one transaction. The occupancy
 * update is made to fail AFTER the event row was inserted; the insert must be rolled back too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class TransactionAtomicityIT {

	@MockitoSpyBean
	OccupancyRepository occupancies;

	private final RestTestClient client;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	TransactionAtomicityIT(RestTestClient client, JdbcClient jdbc, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void eventIsNotStoredWhenTheOccupancyUpdateFails() {
		doThrow(new IllegalStateException("simulated failure")).when(occupancies).update(eq("NORTE"), any());
		UUID eventId = UUID.randomUUID();
		String body = """
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
				""".formatted(eventId, clock.instant().minusSeconds(1));

		client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isEqualTo(500)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Error interno");

		// The INSERT ran before the failure; the rollback must have undone it.
		Integer stored = jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query(Integer.class)
				.single();
		assertThat(stored).isZero();
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isZero();
	}

}
