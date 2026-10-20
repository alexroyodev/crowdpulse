package com.alexroyodev.crowdpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CrowdpulseApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrowdpulseApplication.class, args);
	}

}
