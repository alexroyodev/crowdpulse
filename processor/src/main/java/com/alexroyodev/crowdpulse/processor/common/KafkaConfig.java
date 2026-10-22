package com.alexroyodev.crowdpulse.processor.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.alexroyodev.crowdpulse.processor.event.UnknownZoneException;
import com.alexroyodev.crowdpulse.processor.event.UnsupportedMessageVersionException;
import com.alexroyodev.crowdpulse.shared.Topics;

import jakarta.validation.ConstraintViolationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Kafka setup of the processor. Spring Kafka creates the declared topics at startup if missing. */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

	private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

	/**
	 * Also declared by the API. Without it, a processor that starts first would subscribe to a
	 * topic that does not exist, and the broker would create it with 1 partition.
	 */
	@Bean
	NewTopic sensorEventsTopic() {
		return TopicBuilder.name(Topics.SENSOR_EVENTS).partitions(Topics.SENSOR_EVENTS_PARTITIONS).replicas(1).build();
	}

	/** Nobody consumes it in phase 2; it is inspected by hand. One partition is enough. */
	@Bean
	NewTopic deadLetterTopic() {
		return TopicBuilder.name(Topics.SENSOR_EVENTS_DLT).partitions(1).replicas(1).build();
	}

	/**
	 * Spec 003, rule 7 (D1): a message that cannot be processed is retried a few times and then
	 * moved to the dead-letter topic, so it neither gets lost nor blocks its zone forever. Spring
	 * Boot plugs this bean into the listener container. After the dead-letter publish the offset is
	 * committed and the next message of the partition goes on; if that publish fails too, the
	 * message is not committed and is tried again.
	 */
	@Bean
	DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template, JsonMapper json) {
		DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(template,
				// Negative partition: Kafka chooses it from the key.
				(record, ex) -> new TopicPartition(Topics.SENSOR_EVENTS_DLT, -1));
		ConsumerRecordRecoverer loggingRecoverer = (record, ex) -> {
			log.atError().setMessage("Event sent to dead-letter topic")
					.addKeyValue("eventId", eventIdOf(record.value(), json))
					.addKeyValue("zoneId", record.key())
					.addKeyValue("partition", record.partition())
					.addKeyValue("offset", record.offset())
					// The innermost cause says why; the outer exception only says "listener failed".
					.addKeyValue("cause", NestedExceptionUtils.getMostSpecificCause(ex).toString())
					.setCause(ex)
					.log();
			deadLetter.accept(record, ex);
		};
		// 3 attempts in total, 1 s apart, for failures that may go away (e.g. the database for a moment).
		DefaultErrorHandler handler = new DefaultErrorHandler(loggingRecoverer, new FixedBackOff(1000L, 2L));
		// Failures that retrying cannot fix go to the dead-letter topic at the first attempt.
		handler.addNotRetryableExceptions(JacksonException.class, ConstraintViolationException.class,
				UnsupportedMessageVersionException.class, UnknownZoneException.class);
		return handler;
	}

	/** Best effort: the eventId if the value is JSON with one, so the log can be searched by it. */
	private static String eventIdOf(Object value, JsonMapper json) {
		if (!(value instanceof String text)) {
			return null;
		}
		try {
			JsonNode eventId = json.readTree(text).get("eventId");
			return eventId == null ? null : eventId.asString();
		}
		catch (JacksonException ex) {
			return null;
		}
	}

}
