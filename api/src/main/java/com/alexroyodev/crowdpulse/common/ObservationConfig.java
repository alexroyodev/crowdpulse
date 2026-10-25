package com.alexroyodev.crowdpulse.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/** Observations (spans and their metrics) this service does not want. */
@Configuration(proxyBeanMethods = false)
class ObservationConfig {

	/**
	 * Spec 007, rule 6: no traces for /actuator, which Prometheus reads every 5 s. It drops the whole
	 * observation, so http_server_requests for /actuator goes too; nothing uses it.
	 */
	@Bean
	ObservationPredicate noActuatorObservations() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext request
				&& request.getCarrier().getRequestURI().startsWith("/actuator"));
	}

}
