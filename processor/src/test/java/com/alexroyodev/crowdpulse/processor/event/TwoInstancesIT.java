package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.ProcessorApplication;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.TopicPublisher;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 004, CA4: two processors in the same consumer group share the 3 partitions and apply the
 * first 500 events; one is then closed, and the other takes its partitions over and applies the
 * other 500 alone. Halfway by construction, not by timing. The result
 * is exact and nothing is out of order. No code of its own: one consumer per partition, offsets
 * committed after the transaction, the zone lock and the eventId (ADR 0002, ADR 0004).
 */
class TwoInstancesIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	private static final KafkaContainer kafka = new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);

	/**
	 * The real zones leave partition 1 empty (key hash), and then an instance holding only that
	 * partition would have no work. So the test adds one zone per partition to its own database.
	 */
	private static final List<String> ZONES = onePerPartition();

	private static JdbcClient jdbc;

	@BeforeAll
	static void startContainers() {
		postgres.start();
		kafka.start();
		jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
	}

	@AfterAll
	static void stopContainers() {
		postgres.stop();
		kafka.stop();
	}

	@Test
	void whenOneOfTwoProcessorsStopsTheOtherFinishesExactlyAndInOrder() {
		Map<String, Integer> expected = new HashMap<>();
		List<SensorEventMessage> messages = new ArrayList<>();
		for (int i = 0; i < 1_000; i++) {
			// One zone per partition, so that both instances have work.
			String zoneId = ZONES.get(i % ZONES.size());
			EventType type = (i / ZONES.size()) % 3 == 2 ? EXIT : ENTRY;
			messages.add(message(zoneId, type, Clock.systemUTC().instant()));
			expected.merge(zoneId, type == ENTRY ? 1 : -1, Integer::sum);
		}
		List<SensorEventMessage> firstHalf = messages.subList(0, 500);
		List<SensorEventMessage> secondHalf = messages.subList(500, 1_000);

		try (ConfigurableApplicationContext first = startProcessor()) {
			ZONES.forEach(zoneId -> {
				jdbc.sql("INSERT INTO zone (zone_id, name, capacity) VALUES (:zoneId, 'Zona de test', 100000)")
						.param("zoneId", zoneId).update();
				jdbc.sql("INSERT INTO zone_occupancy (zone_id) VALUES (:zoneId)").param("zoneId", zoneId).update();
			});
			TopicPublisher topic = new TopicPublisher(first.getBean(KafkaTemplate.class), first.getBean(JsonMapper.class));
			TopicProbe events = new TopicProbe(first.getBean(ConsumerFactory.class), Topics.SENSOR_EVENTS);
			try (ConfigurableApplicationContext second = startProcessor()) {
				// The group is formed before any event is published, so the work really is shared.
				Eventually.await("both instances have partitions", Duration.ofSeconds(30),
						() -> !partitionsOf(first).isEmpty() && !partitionsOf(second).isEmpty());
				assertThat(partitionsOf(first).size() + partitionsOf(second).size()).isEqualTo(3);

				events.mark();
				firstHalf.forEach(topic::publish);
				Eventually.await("the first half processed by both", () -> storedEvents() == 500);
				assertThat(events.published()).extracting(ConsumerRecord::partition).contains(0, 1, 2);
			}
			// The second instance is gone: the first takes its partitions over and does the rest alone.
			secondHalf.forEach(topic::publish);
			Eventually.await("the remaining instance has every partition", Duration.ofSeconds(30),
					() -> partitionsOf(first).size() == 3);
			Eventually.await("the 1 000 events processed", Duration.ofSeconds(60), () -> storedEvents() == 1_000);
		}

		expected.forEach((zoneId, occupancy) -> assertThat(occupancyOf(zoneId)).as(zoneId).isEqualTo(occupancy));
		assertThat(jdbc.sql("SELECT count(*) FROM sensor_event WHERE anomaly").query(Integer.class).single()).isZero();
	}

	/** The first test zone names that Kafka's partitioner puts on partitions 0, 1 and 2. */
	private static List<String> onePerPartition() {
		Map<Integer, String> byPartition = new TreeMap<>();
		for (int i = 1; byPartition.size() < Topics.SENSOR_EVENTS_PARTITIONS; i++) {
			String zoneId = "TEST" + i;
			// What the producer does with a key: murmur2 hash, positive, modulo the partitions.
			int partition = Utils.toPositive(Utils.murmur2(zoneId.getBytes(UTF_8))) % Topics.SENSOR_EVENTS_PARTITIONS;
			byPartition.putIfAbsent(partition, zoneId);
		}
		return List.copyOf(byPartition.values());
	}

	/** Command-line arguments, not builder properties: those are defaults and application.yml (port 8081) wins. */
	private static ConfigurableApplicationContext startProcessor() {
		return new SpringApplicationBuilder(ProcessorApplication.class).run(
				"--server.port=0",
				"--spring.docker.compose.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
	}

	private static MessageListenerContainer listenerOf(ConfigurableApplicationContext app) {
		return app.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(SensorEventListener.ID);
	}

	private static List<TopicPartition> partitionsOf(ConfigurableApplicationContext app) {
		var assigned = listenerOf(app).getAssignedPartitions();
		return assigned == null ? List.of() : List.copyOf(assigned);
	}

	private static int storedEvents() {
		return jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
	}

	private static int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
	}

}
