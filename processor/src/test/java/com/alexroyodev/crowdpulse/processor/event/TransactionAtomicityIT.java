package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.occupancy.OccupancyRepository;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * Spec 001, rule 3: storing the event and updating the occupancy happen in one transaction. The
 * occupancy update is made to fail AFTER the event row was inserted; the insert must be rolled
 * back too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class TransactionAtomicityIT {

	@MockitoSpyBean
	OccupancyRepository occupancies;

	private final EventProcessor processor;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	TransactionAtomicityIT(EventProcessor processor, JdbcClient jdbc, Clock clock) {
		this.processor = processor;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void eventIsNotStoredWhenTheOccupancyUpdateFails() {
		doThrow(new IllegalStateException("simulated failure")).when(occupancies).update(eq("NORTE"), any(), any());
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());

		assertThatThrownBy(() -> processor.process(message))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("simulated failure");

		// The INSERT ran before the failure; the rollback must have undone it.
		Integer stored = jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", message.eventId())
				.query(Integer.class)
				.single();
		assertThat(stored).isZero();
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'NORTE'").query(Integer.class).single())
				.isZero();
	}

}
