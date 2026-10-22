package com.alexroyodev.crowdpulse.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class DatabaseUnavailableTest {

	static Stream<Throwable> databaseUnavailable() {
		return Stream.of(
				new CannotCreateTransactionException("no connection to begin"),
				new DataAccessResourceFailureException("connection refused"),
				new CannotGetJdbcConnectionException("pool exhausted"),
				new TransientDataAccessResourceException("resource busy"),
				new SQLTransientConnectionException("HikariPool - Connection is not available"),
				// Seen in DatabaseOutageIT: the connection dies mid-transaction and the rollback fails too.
				new TransactionSystemException("Could not roll back JDBC transaction",
						new SQLException("This connection has been closed.", "08003")),
				new SQLException("An I/O error occurred while sending to the backend.", "08006"));
	}

	static Stream<Throwable> otherFailures() {
		return Stream.of(
				new DataIntegrityViolationException("check constraint violated"),
				new SQLException("duplicate key value violates unique constraint", "23505"),
				new SQLException("no SQL state"),
				new IllegalStateException("a bug"),
				new RuntimeException("listener failed", new IllegalArgumentException("bad message")));
	}

	@ParameterizedTest
	@MethodSource("databaseUnavailable")
	void recognisesTheDatabaseFailure(Throwable ex) {
		assertThat(DatabaseUnavailable.isCause(ex)).isTrue();
	}

	@ParameterizedTest
	@MethodSource("databaseUnavailable")
	void recognisesItWrappedTwoLevelsDeep(Throwable ex) {
		// As it reaches the error handler: listener exception -> processing exception -> cause.
		Throwable wrapped = new RuntimeException("listener failed", new IllegalStateException("processing failed", ex));

		assertThat(DatabaseUnavailable.isCause(wrapped)).isTrue();
	}

	@ParameterizedTest
	@MethodSource("otherFailures")
	void anythingElseIsNotADatabaseFailure(Throwable ex) {
		assertThat(DatabaseUnavailable.isCause(ex)).isFalse();
	}

	@Test
	void nullIsNotADatabaseFailure() {
		assertThat(DatabaseUnavailable.isCause(null)).isFalse();
	}

}
