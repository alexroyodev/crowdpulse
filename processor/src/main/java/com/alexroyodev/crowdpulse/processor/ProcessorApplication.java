package com.alexroyodev.crowdpulse.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// Spec 006: ZoneMetrics refreshes the zone gauges from the database periodically.
@EnableScheduling
public class ProcessorApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProcessorApplication.class, args);
	}

}
