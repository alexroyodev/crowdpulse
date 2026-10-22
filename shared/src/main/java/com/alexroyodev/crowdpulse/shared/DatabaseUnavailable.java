package com.alexroyodev.crowdpulse.shared;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Tells "the database is not available" apart from any other failure (spec 004). The processor
 * waits without limit on these (D1) and the API answers 503 (D3). The list is short on purpose: a
 * failure classified here by mistake would make the processor retry its message forever.
 */
public final class DatabaseUnavailable {

	private static final List<Class<? extends Throwable>> CAUSES = List.of(
			// @Transactional could not get a connection to begin.
			CannotCreateTransactionException.class,
			// Spring's translation of connection errors (SQL state 08...); includes CannotGetJdbcConnectionException.
			DataAccessResourceFailureException.class,
			TransientDataAccessResourceException.class,
			// Hikari gave up waiting for a connection (connection-timeout).
			SQLTransientConnectionException.class);

	private DatabaseUnavailable() {
	}

	/** SQL standard: SQL states of class 08 are connection exceptions (e.g. 08003, 08006). */
	private static final String CONNECTION_EXCEPTION_CLASS = "08";

	/** True if the exception, or any of its causes, means that the database cannot be reached. */
	public static boolean isCause(Throwable ex) {
		for (Throwable current = ex; current != null; current = current.getCause()) {
			Throwable candidate = current;
			if (CAUSES.stream().anyMatch(type -> type.isInstance(candidate))) {
				return true;
			}
			// A connection lost mid-transaction makes the rollback fail too, and Spring then throws the
			// rollback's TransactionSystemException: only the driver's SQL state says what happened.
			if (current instanceof SQLException sql && sql.getSQLState() != null
					&& sql.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS)) {
				return true;
			}
		}
		return false;
	}

}
