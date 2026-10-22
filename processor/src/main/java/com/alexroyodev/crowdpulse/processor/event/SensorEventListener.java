package com.alexroyodev.crowdpulse.processor.event;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The processor's edge (spec 003): turns each Kafka message into a valid SensorEventMessage and
 * hands it to EventProcessor. When this method returns, the container commits the offset
 * (ack-mode: record), so it is committed only after the database transaction (rule 4). If it
 * throws, the offset is not committed and the message is delivered again.
 */
@Component
class SensorEventListener {

	static final String ID = "sensor-events";

	private final EventProcessor processor;
	private final JsonMapper json;
	private final Validator validator;

	SensorEventListener(EventProcessor processor, JsonMapper json, Validator validator) {
		this.processor = processor;
		this.json = json;
		this.validator = validator;
	}

	@KafkaListener(id = ID, topics = Topics.SENSOR_EVENTS)
	void onMessage(ConsumerRecord<String, String> record) {
		checkVersion(record.headers().lastHeader(Topics.MESSAGE_VERSION_HEADER));
		// Broken JSON throws a JacksonException here: the message cannot be processed, ever.
		SensorEventMessage message = json.readValue(record.value(), SensorEventMessage.class);
		Set<ConstraintViolation<SensorEventMessage>> violations = validator.validate(message);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
		processor.process(message);
	}

	private static void checkVersion(Header header) {
		String version = header == null ? null : new String(header.value(), UTF_8);
		if (!Topics.MESSAGE_VERSION.equals(version)) {
			throw new UnsupportedMessageVersionException(version);
		}
	}

}
