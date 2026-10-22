package com.alexroyodev.crowdpulse.processor.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the pure AlertPolicy as a bean, so the domain class itself needs no Spring annotation. */
@Configuration(proxyBeanMethods = false)
class AlertConfig {

	@Bean
	AlertPolicy alertPolicy(AlertProperties properties) {
		return new AlertPolicy(properties);
	}

}
