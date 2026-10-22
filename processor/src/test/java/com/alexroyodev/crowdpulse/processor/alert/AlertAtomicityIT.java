package com.alexroyodev.crowdpulse.processor.alert;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.alexroyodev.crowdpulse.processor.event.EventProcessor;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * Spec 002, rule 7 / CA6: the event, the occupancy and the level change are stored in one
 * transaction. The alert insert, the last write, is made to fail; the earlier writes must be
 * rolled back too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class AlertAtomicityIT {

	@MockitoSpyBean
	AlertRepository alerts;

	private final EventProcessor processor;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	AlertAtomicityIT(EventProcessor processor, JdbcClient jdbc, Clock clock) {
		this.processor = processor;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void nothingIsStoredWhenRecordingTheLevelChangeFails() {
		doThrow(new IllegalStateException("simulated failure")).when(alerts).insert(any());
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 169 WHERE zone_id = 'PALCO'").update();
		SensorEventMessage message = message("PALCO", ENTRY, clock.instant());

		assertThatThrownBy(() -> processor.process(message)).isInstanceOf(IllegalStateException.class);

		// The event INSERT and the occupancy UPDATE ran before the failure; both were rolled back.
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id").param("id", message.eventId())
				.query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = 'PALCO'")
				.query(Integer.class).single()).isEqualTo(169);
		assertThat(jdbc.sql("SELECT alert_level FROM zone_occupancy WHERE zone_id = 'PALCO'")
				.query(String.class).single()).isEqualTo("NORMAL");
	}

}
