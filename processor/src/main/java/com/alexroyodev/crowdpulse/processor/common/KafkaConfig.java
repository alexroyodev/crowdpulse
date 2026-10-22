package com.alexroyodev.crowdpulse.processor.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.alexroyodev.crowdpulse.shared.Topics;

/** Kafka setup of the processor. Spring Kafka creates the declared topics at startup if missing. */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

	/**
	 * Also declared by the API. Without it, a processor that starts first would subscribe to a
	 * topic that does not exist, and the broker would create it with 1 partition.
	 */
	@Bean
	NewTopic sensorEventsTopic() {
		return TopicBuilder.name(Topics.SENSOR_EVENTS).partitions(Topics.SENSOR_EVENTS_PARTITIONS).replicas(1).build();
	}

}
