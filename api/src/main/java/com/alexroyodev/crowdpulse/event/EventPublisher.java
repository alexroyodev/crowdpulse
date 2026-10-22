package com.alexroyodev.crowdpulse.event;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Clock;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;
import com.alexroyodev.crowdpulse.zone.UnknownZoneException;
import com.alexroyodev.crowdpulse.zone.ZoneQueryRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts a valid event by publishing it to Kafka (spec 003). Returns only once Kafka has
 * confirmed it (rule 3: never a 202 without the event in Kafka); the processor applies it later.
 */
@Service
class EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

	private final ZoneQueryRepository zones;
	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;
	private final Clock clock;

	EventPublisher(ZoneQueryRepository zones, KafkaTemplate<String, String> kafka, JsonMapper json, Clock clock) {
		this.zones = zones;
		this.kafka = kafka;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * @throws UnknownZoneException if the zone is not in the catalogue (422, checked before publishing: D2)
	 * @throws EventNotPublishedException if Kafka does not confirm the event in time (503: D3)
	 */
	void publish(EventRequest request) {
		if (!zones.exists(request.zoneId())) {
			log.atWarn().setMessage("Event rejected: unknown zone")
					.addKeyValue("eventId", request.eventId())
					.addKeyValue("zoneId", request.zoneId())
					.log();
			throw new UnknownZoneException(request.zoneId());
		}

		SensorEventMessage message = new SensorEventMessage(request.eventId(), request.sensorId(), request.zoneId(),
				request.type(), request.occurredAt(), clock.instant());
		// Key = zoneId: every event of a zone goes to the same partition, so the processor sees them in order.
		ProducerRecord<String, String> record = new ProducerRecord<>(Topics.SENSOR_EVENTS, request.zoneId(),
				json.writeValueAsString(message));
		record.headers().add(Topics.MESSAGE_VERSION_HEADER, Topics.MESSAGE_VERSION.getBytes(UTF_8));

		try {
			// Waits for the broker's acknowledgement; the producer's own timeouts bound the wait (application.yml).
			kafka.send(record).get();
		}
		catch (ExecutionException | org.apache.kafka.common.KafkaException | org.springframework.kafka.KafkaException ex) {
			throw new EventNotPublishedException(request.eventId(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EventNotPublishedException(request.eventId(), ex);
		}
	}

}
