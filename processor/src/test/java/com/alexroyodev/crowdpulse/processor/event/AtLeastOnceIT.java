package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 003, rule 4 / CA7: at-least-once. The first attempt fails as if the processor died, and
 * the listener container is stopped and started again: the new consumer starts from the committed
 * offset, as after a real restart. A retry inside the same consumer would not prove anything here,
 * because it re-reads the message whatever offset was committed.
 * <ul>
 * <li>Dies before the database commit: the offset was not committed, so the event comes back and
 * is applied (not lost).</li>
 * <li>Dies after the database commit, before the offset commit: the event comes back and the
 * stored eventId makes it count once (not doubled).</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class AtLeastOnceIT {

	@MockitoSpyBean
	EventProcessor processor;

	private final TopicPublisher topic;
	private final MessageListenerContainer listener;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	AtLeastOnceIT(KafkaTemplate<String, String> kafka, JsonMapper json, KafkaListenerEndpointRegistry registry,
			JdbcClient jdbc, Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.listener = registry.getListenerContainer(SensorEventListener.ID);
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@AfterEach
	void listenerRunning() {
		if (!listener.isRunning()) {
			listener.start();
		}
	}

	@Test
	void eventLostBeforeTheDatabaseCommitIsDeliveredAgainAndApplied() throws Exception {
		CountDownLatch died = new CountDownLatch(1);
		doAnswer(invocation -> {
			died.countDown();
			throw new IllegalStateException("simulated crash before the database commit");
		}).when(processor).process(any());
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());

		topic.publish(message);
		restartListenerAfter(died);

		Eventually.await("the event is applied after the restart", () -> isStored(message.eventId()));
		assertThat(occupancyOf("NORTE")).isEqualTo(1);
	}

	@Test
	void eventRedeliveredAfterTheDatabaseCommitCountsOnce() throws Exception {
		CountDownLatch died = new CountDownLatch(1);
		doAnswer(invocation -> {
			// The spy runs inside the transactional proxy: an exception thrown here would roll the
			// transaction back. Crashing in afterCommit happens once PostgreSQL has committed, and
			// still before the listener returns, so before the offset is committed.
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					died.countDown();
					throw new IllegalStateException("simulated crash after the database commit, before the offset commit");
				}
			});
			return invocation.callRealMethod();
		}).when(processor).process(any());
		SensorEventMessage message = message("SUR", ENTRY, clock.instant());
		SensorEventMessage marker = message("SUR", ENTRY, clock.instant());

		topic.publish(message);
		restartListenerAfter(died);
		// The redelivered copy comes first (same partition); the marker tells when it has been consumed.
		topic.publish(marker);

		Eventually.await("the marker is applied", () -> isStored(marker.eventId()));
		assertThat(isStored(message.eventId())).as("stored by the first attempt").isTrue();
		verify(processor, times(2)).process(argThat(m -> m.eventId().equals(message.eventId())));
		assertThat(occupancyOf("SUR")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single()).isEqualTo(2);
	}

	/** Stops the consumer while the first attempt has failed, then lets the next attempts through. */
	private void restartListenerAfter(CountDownLatch died) throws InterruptedException {
		assertThat(died.await(10, TimeUnit.SECONDS)).as("first attempt reached").isTrue();
		listener.stop();
		doCallRealMethod().when(processor).process(any());
		listener.start();
	}

	private boolean isStored(UUID eventId) {
		return jdbc.sql("SELECT count(*) FROM sensor_event WHERE event_id = :id")
				.param("id", eventId).query(Integer.class).single() == 1;
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
