package com.alexroyodev.crowdpulse.processor;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 006, CA1: /actuator/prometheus on the management port has every metric of the contract.
 * In tests Spring Boot only sets up the in-memory registry; @AutoConfigureMetrics brings the
 * Prometheus one, as in the real application.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class PrometheusEndpointIT {

	/** The contract of spec 006 for the processor, plus the Kafka consumer lag (question 4). */
	private static final List<String> CONTRACT = List.of(
			"crowdpulse_events_processed_total{",
			"crowdpulse_events_dead_lettered_total{",
			"crowdpulse_event_processing_latency_seconds_bucket{",
			"crowdpulse_event_sensor_delay_seconds_bucket{",
			"crowdpulse_zone_occupancy{",
			"crowdpulse_zone_capacity{",
			"crowdpulse_zone_alert_level{",
			"crowdpulse_alerts_total{",
			"crowdpulse_database_outage ",
			"kafka_consumer_fetch_manager_records_lag_max{");

	@LocalManagementPort
	int managementPort;

	private final TopicPublisher topic;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	PrometheusEndpointIT(KafkaTemplate<String, String> kafka, JsonMapper json, JdbcClient jdbc, Clock clock) {
		this.topic = new TopicPublisher(kafka, json);
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Test
	void exposesEveryMetricOfTheContract() {
		// Counters with tags only appear once used: one event that changes OESTE's alert level
		// (one below 85 % of 15 000) and one broken message for the dead-letter topic.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 12749 WHERE zone_id = 'OESTE'").update();
		topic.publish(message("OESTE", ENTRY, clock.instant()));
		topic.publishRaw("OESTE", "{\"eventId\": ", Topics.MESSAGE_VERSION);

		// The zone gauges come from the scheduled refresh (every 5 s).
		Eventually.await("every metric of the contract", Duration.ofSeconds(20),
				() -> CONTRACT.stream().allMatch(scrape()::contains));

		assertThat(scrape()).contains(CONTRACT);
	}

	private String scrape() {
		return RestClient.create("http://127.0.0.1:" + managementPort).get().uri("/actuator/prometheus")
				.retrieve()
				.body(String.class);
	}

}
