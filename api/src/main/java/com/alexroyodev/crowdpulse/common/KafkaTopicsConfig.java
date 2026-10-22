package com.alexroyodev.crowdpulse.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.alexroyodev.crowdpulse.shared.Topics;

/** Spring Kafka creates this topic at startup if it does not exist yet. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

	/**
	 * 3 partitions for 5 zones: several zones share a partition, which shows that the order per
	 * zone comes from the key, not from one partition per zone (plan 003, section 4).
	 */
	@Bean
	NewTopic sensorEventsTopic() {
		return TopicBuilder.name(Topics.SENSOR_EVENTS).partitions(3).replicas(1).build();
	}

}
