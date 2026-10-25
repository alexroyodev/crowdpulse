package com.alexroyodev.crowdpulse.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Spec 005, CA1: by default the processor only answers on 127.0.0.1, so the dead-letter replay
 * endpoint is not reachable from other machines. ProcessorAddressOpenIT checks the opposite.
 * Spec 006 (D1, ADR 0006): the management server (health, prometheus) is open to every address,
 * and the replay endpoint is not on it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProcessorAddressIT {

	@LocalServerPort
	int port;

	// RANDOM_PORT also gives the management server a random port, since it differs from server.port.
	@LocalManagementPort
	int managementPort;

	@Test
	void answersOnLoopbackOnlyByDefault() throws Exception {
		InetAddress external = externalAddress();

		assertThat(Addresses.accepts(InetAddress.getByName("127.0.0.1"), port)).isTrue();
		assertThat(Addresses.accepts(external, port)).as("reachable on " + external).isFalse();
	}

	@Test
	void managementServerAnswersOnEveryAddress() throws Exception {
		InetAddress external = externalAddress();

		assertThat(Addresses.accepts(external, managementPort)).as("reachable on " + external).isTrue();
	}

	@Test
	void replayEndpointIsNotOnTheManagementServer() {
		int status = RestClient.create("http://127.0.0.1:" + managementPort).post()
				.uri("/admin/dead-letters/replay")
				.exchange((request, response) -> response.getStatusCode().value());

		assertThat(status).isEqualTo(404);
	}

	private static InetAddress externalAddress() throws Exception {
		Optional<InetAddress> external = Addresses.nonLoopbackIpv4();
		// Depends on the machine, not on the code: without a network address there is nothing to check.
		assumeTrue(external.isPresent(), "this machine has no IPv4 address other than loopback");
		return external.get();
	}

}
