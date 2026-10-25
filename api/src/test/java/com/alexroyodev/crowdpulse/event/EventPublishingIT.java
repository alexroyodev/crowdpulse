package com.alexroyodev.crowdpulse.event;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.TopicProbe;
import com.alexroyodev.crowdpulse.shared.EventType;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/** Spec 003: what the API publishes for every accepted event, and with which key. */
@Import({ TestcontainersConfiguration.class, EventPublishingIT.FixedClockConfig.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class EventPublishingIT {

	static final Instant NOW = Instant.parse("2026-10-22T18:00:00.123Z");

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}

	private final RestTestClient client;
	private final TopicProbe topic;
	private final JsonMapper json;

	@Autowired
	EventPublishingIT(RestTestClient client, ConsumerFactory<String, String> consumers, JsonMapper json) {
		this.client = client;
		this.topic = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
		this.json = json;
	}

	@BeforeEach
	void markTopic() {
		topic.mark();
	}

	@Test
	void acceptedEventIsPublishedWithZoneKeyVersionAndReceivedAt() {
		// CA1, the API's half: 202 means the message is already in Kafka.
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = NOW.minusSeconds(3);

		post(eventId, "NORTE", "ENTRY", occurredAt);

		ConsumerRecord<String, String> record = single(topic.published());
		assertThat(record.key()).isEqualTo("SUR") // CI CHECK: broken on purpose (spec 009, CA3);
		assertThat(new String(record.headers().lastHeader(Topics.MESSAGE_VERSION_HEADER).value(), UTF_8))
				.isEqualTo("1");
		assertThat(json.readValue(record.value(), SensorEventMessage.class)).isEqualTo(new SensorEventMessage(
				eventId, "NORTE-P01", "NORTE", EventType.ENTRY, occurredAt, NOW));
	}

	@Test
	void duplicateIsPublishedAgain() {
		// Rule 5: the API cannot know it is a duplicate; the processor ignores it.
		UUID eventId = UUID.randomUUID();

		post(eventId, "SUR", "ENTRY", NOW.minusSeconds(1));
		post(eventId, "SUR", "ENTRY", NOW.minusSeconds(1));

		assertThat(topic.published()).hasSize(2)
				.extracting(record -> json.readValue(record.value(), SensorEventMessage.class).eventId())
				.containsOnly(eventId);
	}

	@Test
	void eventsOfAZoneGoToOnePartitionInTheOrderTheyWereAccepted() {
		// CA5, the API's half. Events of other zones are interleaved; only the order within a zone counts.
		List<UUID> norte = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			UUID eventId = UUID.randomUUID();
			norte.add(eventId);
			post(eventId, "NORTE", i % 2 == 0 ? "ENTRY" : "EXIT", NOW.minusSeconds(1));
			post(UUID.randomUUID(), i % 2 == 0 ? "SUR" : "ESTE", "ENTRY", NOW.minusSeconds(1));
		}

		// Selected by the zone in the message, not by the key, so that a wrong key makes the test fail below.
		List<ConsumerRecord<String, String>> norteRecords = topic.published().stream()
				.filter(record -> "NORTE".equals(json.readValue(record.value(), SensorEventMessage.class).zoneId()))
				.toList();
		assertThat(norteRecords).hasSize(20);
		assertThat(norteRecords).extracting(ConsumerRecord::partition).containsOnly(norteRecords.get(0).partition());
		assertThat(norteRecords)
				.extracting(record -> json.readValue(record.value(), SensorEventMessage.class).eventId())
				.containsExactlyElementsOf(norte);
	}

	private void post(UUID eventId, String zoneId, String type, Instant occurredAt) {
		client.post().uri("/api/v1/events")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"eventId":"%s","sensorId":"%s-P01","zoneId":"%s","type":"%s","occurredAt":"%s"}
						""".formatted(eventId, zoneId, zoneId, type, occurredAt))
				.exchange()
				.expectStatus().isAccepted();
	}

	private static ConsumerRecord<String, String> single(List<ConsumerRecord<String, String>> records) {
		assertThat(records).hasSize(1);
		return records.get(0);
	}

}
