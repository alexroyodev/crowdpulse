package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.Concurrently;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * Spec 001, rule 4 / CA6: concurrent events on the same zone lose no update. All threads are
 * released at the same moment to maximise contention on the row lock. Since spec 003 the events
 * reach EventProcessor from Kafka instead of HTTP; the lock still protects two consumers that
 * overlap (plan 003, section 5.2).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ConcurrentIngestionIT {

	private static final int THREADS = 20;

	private final EventProcessor processor;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ConcurrentIngestionIT(EventProcessor processor, JdbcClient jdbc, Clock clock) {
		this.processor = processor;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void thousandDistinctEntriesFromTwentyThreadsCountExactlyAThousand() throws Exception {
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
			tasks.add(() -> process(message));
		}

		List<Boolean> results = Concurrently.run(THREADS, tasks);

		assertThat(results).hasSize(1_000).containsOnly(true);
		assertThat(occupancyOfNorte()).isEqualTo(1_000);
		assertThat(storedEvents()).isEqualTo(1_000);
	}

	@Test
	void sameEventFromTwentyThreadsAtOnceCountsOnce() throws Exception {
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			tasks.add(() -> process(message));
		}

		List<Boolean> results = Concurrently.run(THREADS, tasks);

		assertThat(results).hasSize(THREADS).containsOnly(true);
		assertThat(occupancyOfNorte()).isEqualTo(1);
		assertThat(storedEvents()).isEqualTo(1);
	}

	/** True when processing ended without an exception, like the 202 of every request before spec 003. */
	private boolean process(SensorEventMessage message) {
		processor.process(message);
		return true;
	}

	private int occupancyOfNorte() {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single();
	}

	private int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

}
