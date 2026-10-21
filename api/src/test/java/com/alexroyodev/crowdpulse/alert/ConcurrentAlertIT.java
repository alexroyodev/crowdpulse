package com.alexroyodev.crowdpulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

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

import com.alexroyodev.crowdpulse.Concurrently;
import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

/**
 * Spec 002, rule 8 / CA7: concurrent requests that cross a threshold record the crossing
 * exactly once. NORTE starts at 16 990; 20 ENTRY events are released at the same moment.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ConcurrentAlertIT {

	private static final int THREADS = 20;

	private final RestTestClient client;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ConcurrentAlertIT(RestTestClient client, JdbcClient jdbc, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void concurrentEntriesCrossingTheThresholdRecordItOnce() throws Exception {
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 16990 WHERE zone_id = 'NORTE'").update();
		List<Callable<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			String body = """
					{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
					""".formatted(UUID.randomUUID(), clock.instant().minusSeconds(1));
			requests.add(() -> post(body));
		}

		List<Integer> statuses = Concurrently.run(THREADS, requests);

		assertThat(statuses).hasSize(THREADS).containsOnly(202);
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isEqualTo(17_010);
		assertThat(jdbc.sql("SELECT from_level || '->' || to_level FROM alert WHERE zone_id = 'NORTE'")
				.query(String.class).list()).containsExactly("NORMAL->WARNING");
		assertThat(jdbc.sql("SELECT occupancy FROM alert WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isEqualTo(17_000);
	}

	private int post(String body) {
		return client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.returnResult()
				.getStatus()
				.value();
	}

}
