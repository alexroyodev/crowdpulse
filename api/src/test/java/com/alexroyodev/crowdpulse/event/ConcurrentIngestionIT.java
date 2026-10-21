package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

/**
 * Rule 4 / CA6: concurrent requests on the same zone lose no update. Requests go through real
 * HTTP and all threads are released at the same moment to maximise contention on the row lock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ConcurrentIngestionIT {

	private static final int THREADS = 20;

	private final RestTestClient client;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ConcurrentIngestionIT(RestTestClient client, JdbcClient jdbc, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void thousandDistinctEntriesFromTwentyThreadsCountExactlyAThousand() throws Exception {
		Instant occurredAt = clock.instant().minusSeconds(1);
		List<Callable<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			String body = entry(UUID.randomUUID(), occurredAt);
			requests.add(() -> post(body));
		}

		List<Integer> statuses = runConcurrently(requests);

		assertThat(statuses).hasSize(1_000).containsOnly(202);
		assertThat(occupancyOfNorte()).isEqualTo(1_000);
		assertThat(storedEvents()).isEqualTo(1_000);
	}

	@Test
	void sameEventFromTwentyThreadsAtOnceCountsOnce() throws Exception {
		String body = entry(UUID.randomUUID(), clock.instant().minusSeconds(1));
		List<Callable<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			requests.add(() -> post(body));
		}

		List<Integer> statuses = runConcurrently(requests);

		assertThat(statuses).hasSize(THREADS).containsOnly(202);
		assertThat(occupancyOfNorte()).isEqualTo(1);
		assertThat(storedEvents()).isEqualTo(1);
	}

	/** Runs the requests on THREADS threads that all start together; returns the HTTP statuses. */
	private static List<Integer> runConcurrently(List<Callable<Integer>> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> request : requests) {
				futures.add(pool.submit(() -> {
					startGate.await();
					return request.call();
				}));
			}
			startGate.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
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

	private static String entry(UUID eventId, Instant occurredAt) {
		return """
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
				""".formatted(eventId, occurredAt);
	}

	private int occupancyOfNorte() {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

}
