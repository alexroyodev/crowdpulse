package com.alexroyodev.crowdpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void unexpectedErrorsDoNotLeakDetailsToTheClient() {
		ProblemDetail problem = handler.handleUnexpected(
				new IllegalStateException("connection to db-internal:5432 refused"));

		assertThat(problem.getStatus()).isEqualTo(500);
		assertThat(problem.getTitle()).isEqualTo("Error interno");
		assertThat(problem.getDetail()).doesNotContain("db-internal", "IllegalStateException");
		assertThat(problem.getProperties()).isNullOrEmpty();
	}

}
