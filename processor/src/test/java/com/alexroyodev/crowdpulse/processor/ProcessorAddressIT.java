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
 * Spec 005, CA1: by default the processor only answers on 127.0.0.1, so the dead-letter replay
 * endpoint is not reachable from other machines. ProcessorAddressOpenIT checks the opposite.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProcessorAddressIT {

	@LocalServerPort
	int port;

	@Test
	void answersOnLoopbackOnlyByDefault() throws Exception {
		Optional<InetAddress> external = Addresses.nonLoopbackIpv4();
		// Depends on the machine, not on the code: without a network address there is nothing to check.
		assumeTrue(external.isPresent(), "this machine has no IPv4 address other than loopback");

		assertThat(Addresses.accepts(InetAddress.getByName("127.0.0.1"), port)).isTrue();
		assertThat(Addresses.accepts(external.get(), port)).as("reachable on " + external.get()).isFalse();
	}

}
