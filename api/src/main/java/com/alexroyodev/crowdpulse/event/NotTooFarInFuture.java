package com.alexroyodev.crowdpulse.event;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The instant is at most {@code crowdpulse.events.max-future-skew} ahead of the server clock.
 * Null is valid: presence is checked by {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = NotTooFarInFutureValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface NotTooFarInFuture {

	String message() default "está demasiado lejos en el futuro respecto al reloj del servidor";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
