package com.alexroyodev.crowdpulse.processor.event;

import java.util.List;

import jakarta.validation.ConstraintViolationException;
import tools.jackson.core.JacksonException;

/**
 * Failures in the content of a message: JSON that cannot be read, missing or invalid fields, an
 * unknown message version. No change in the environment fixes them, so they go to the dead-letter
 * topic at the first attempt (spec 003) and are not replayed by default (spec 005, rule 2). An
 * unknown zone is not one of them: creating the zone fixes it.
 */
public final class ContentFailure {

	public static final List<Class<? extends Exception>> TYPES = List.of(JacksonException.class,
			ConstraintViolationException.class, UnsupportedMessageVersionException.class);

	private ContentFailure() {
	}

	/**
	 * @param className the class of a failure, as Spring Kafka stores it in the dead-letter headers
	 * @return true if it is (or extends) one of {@link #TYPES}; false if unknown: when in doubt, replay
	 */
	public static boolean isContentFailure(String className) {
		if (className == null) {
			return false;
		}
		try {
			Class<?> type = Class.forName(className, false, ContentFailure.class.getClassLoader());
			return TYPES.stream().anyMatch(failure -> failure.isAssignableFrom(type));
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

}
