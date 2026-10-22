package com.alexroyodev.crowdpulse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.TopicProbe;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 003, CA2 and D3, the API's half: with Kafka unreachable the API answers 503, never 202, and
 * what got a 202 before is in the topic once Kafka is back. The broker is paused (docker pause), not
 * stopped, so it comes back on the same port. It is this test's own broker: pausing the shared one
 * would break the other tests.
 */
@Import(KafkaOutageIT.OwnContainers.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class KafkaOutageIT {

	@TestConfiguration(proxyBeanMethods = false)
	static class OwnContainers {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);
		}

	}

	private final RestTestClient client;
	private final KafkaContainer kafka;
	private final TopicProbe topic;
	private final JsonMapper json;
	private final Clock clock;

	@Autowired
	KafkaOutageIT(RestTestClient client, KafkaContainer kafka, ConsumerFactory<String, String> consumers,
			JsonMapper json, Clock clock) {
		this.client = client;
		this.kafka = kafka;
		this.topic = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
		this.json = json;
		this.clock = clock;
	}

	@Test
	void withKafkaDownTheApiAnswers503AndLosesNothingItAccepted() throws Exception {
		topic.mark();
		UUID before = UUID.randomUUID();
		post(before).expectStatus().isAccepted();

		UUID during = UUID.randomUUID();
		pauseKafka();
		try {
			long start = System.nanoTime();
			post(during)
					.expectStatus().isEqualTo(503)
					.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
					.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
					.expectBody()
					.jsonPath("$.title").isEqualTo("Servicio no disponible")
					.jsonPath("$.eventId").isEqualTo(during.toString())
					.jsonPath("$.trace").doesNotExist();
			// The producer's timeouts bound the wait (about 3 s); the sensor is not left hanging.
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
		}
		finally {
			unpauseKafka();
		}

		// The sensor retries the rejected event until Kafka takes it again.
		retryUntilAccepted(during);

		// The 503 attempt may still have reached the broker before it was paused: a harmless duplicate.
		assertThat(topic.published())
				.extracting(record -> json.readValue(record.value(), SensorEventMessage.class).eventId())
				.contains(before, during)
				.containsOnly(before, during);
	}

	private void retryUntilAccepted(UUID eventId) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (post(eventId).returnResult().getStatus().value() != 202) {
			assertThat(System.nanoTime()).as("202 within 30 s after Kafka is back").isLessThan(deadline);
			Thread.sleep(500);
		}
	}

	private void pauseKafka() {
		DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
	}

	private void unpauseKafka() {
		DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
	}

	private ResponseSpec post(UUID eventId) {
		return client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY","occurredAt":"%s"}
						""".formatted(eventId, clock.instant().minusSeconds(1)))
				.exchange();
	}

}
