package com.alexroyodev.crowdpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;

/** Errors produced by Spring MVC itself: ProblemDetail format and Spanish texts (messages.properties). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ApiErrorsIT {

	private final RestTestClient client;

	@Autowired
	ApiErrorsIT(RestTestClient client) {
		this.client = client;
	}

	@Test
	void unknownRouteIsNotFound() {
		client.get().uri("/api/v1/nothing-here")
				.exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Recurso no encontrado")
				.jsonPath("$.detail").value(String.class,
						detail -> assertThat(detail).startsWith("No existe el recurso").contains("nothing-here"));
	}

	@Test
	void wrongMethodIsNotAllowed() {
		client.delete().uri("/api/v1/zones")
				.exchange()
				.expectStatus().isEqualTo(405)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Método no permitido")
				.jsonPath("$.detail").isEqualTo("El método 'DELETE' no está permitido en este recurso. Métodos permitidos: [GET].");
	}

	@Test
	void unsupportedResponseFormatIsNotAcceptable() {
		client.get().uri("/api/v1/zones")
				.accept(MediaType.APPLICATION_XML)
				.exchange()
				.expectStatus().isEqualTo(406);
	}

	@Test
	void textsAreSpanishWhateverTheClientLanguage() {
		client.delete().uri("/api/v1/zones")
				.header("Accept-Language", "en-US")
				.exchange()
				.expectStatus().isEqualTo(405)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Método no permitido");
	}

}
