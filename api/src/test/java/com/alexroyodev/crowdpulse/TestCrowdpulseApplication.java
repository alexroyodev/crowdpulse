package com.alexroyodev.crowdpulse;

import org.springframework.boot.SpringApplication;

public class TestCrowdpulseApplication {

	public static void main(String[] args) {
		SpringApplication.from(CrowdpulseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
