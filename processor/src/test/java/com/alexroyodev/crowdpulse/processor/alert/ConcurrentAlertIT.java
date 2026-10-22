package com.alexroyodev.crowdpulse.processor.alert;

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
import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * Spec 002, rule 8 / CA7: concurrent events that cross a threshold record the crossing exactly
 * once. NORTE starts at 16 990; 20 ENTRY events are released at the same moment.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class ConcurrentAlertIT {

	private static final int THREADS = 20;

	private final EventProcessor processor;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	ConcurrentAlertIT(EventProcessor processor, JdbcClient jdbc, Clock clock) {
		this.processor = processor;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void concurrentEntriesCrossingTheThresholdRecordItOnce() throws Exception {
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 16990 WHERE zone_id = 'NORTE'").update();
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
			tasks.add(() -> {
				processor.process(message);
				return true;
			});
		}

		List<Boolean> results = Concurrently.run(THREADS, tasks);

		assertThat(results).hasSize(THREADS).containsOnly(true);
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isEqualTo(17_010);
		assertThat(jdbc.sql("SELECT from_level || '->' || to_level FROM alert WHERE zone_id = 'NORTE'")
				.query(String.class).list()).containsExactly("NORMAL->WARNING");
		assertThat(jdbc.sql("SELECT occupancy FROM alert WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isEqualTo(17_000);
	}

}
