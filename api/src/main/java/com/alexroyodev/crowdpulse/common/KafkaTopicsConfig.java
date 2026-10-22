package com.alexroyodev.crowdpulse.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.alexroyodev.crowdpulse.shared.Topics;

/** Spring Kafka creates this topic at startup if it does not exist yet. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

	@Bean
	NewTopic sensorEventsTopic() {
		return TopicBuilder.name(Topics.SENSOR_EVENTS).partitions(Topics.SENSOR_EVENTS_PARTITIONS).replicas(1).build();
	}

}
