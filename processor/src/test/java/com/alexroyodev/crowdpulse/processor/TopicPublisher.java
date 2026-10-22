package com.alexroyodev.crowdpulse.processor;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import tools.jackson.databind.json.JsonMapper;

/** Test helper: publishes to the events topic as the API does, or something the API never would. */
public final class TopicPublisher {

	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;

	public TopicPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
		this.kafka = kafka;
		this.json = json;
	}

	/** Like the API: key zoneId, version header, JSON value. Returns once the broker has it. */
	public void publish(SensorEventMessage message) {
		publishRaw(message.zoneId(), json.writeValueAsString(message), Topics.MESSAGE_VERSION);
	}

	/** Any key, value and version (null = no version header), waiting for the broker. */
	public void publishRaw(String key, String value, String version) {
		ProducerRecord<String, String> record = new ProducerRecord<>(Topics.SENSOR_EVENTS, key, value);
		if (version != null) {
			record.headers().add(Topics.MESSAGE_VERSION_HEADER, version.getBytes(UTF_8));
		}
		try {
			kafka.send(record).get();
		}
		catch (ExecutionException ex) {
			throw new IllegalStateException("Could not publish the test message", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while publishing the test message", ex);
		}
	}

}
