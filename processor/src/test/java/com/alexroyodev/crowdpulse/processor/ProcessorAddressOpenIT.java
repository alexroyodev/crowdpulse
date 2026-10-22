package com.alexroyodev.crowdpulse.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Spec 005, CA1: with CROWDPULSE_PROCESSOR_ADDRESS=0.0.0.0 the processor answers on every address
 * of the machine. Set here as a property: Spring resolves the placeholder in application.yml from
 * it just as it would from the environment variable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "CROWDPULSE_PROCESSOR_ADDRESS=0.0.0.0")
class ProcessorAddressOpenIT {

	@LocalServerPort
	int port;

	@Test
	void answersOnEveryAddressWhenOpened() throws Exception {
		Optional<InetAddress> external = Addresses.nonLoopbackIpv4();
		assumeTrue(external.isPresent(), "this machine has no IPv4 address other than loopback");

		assertThat(Addresses.accepts(external.get(), port)).as("reachable on " + external.get()).isTrue();
	}

}
