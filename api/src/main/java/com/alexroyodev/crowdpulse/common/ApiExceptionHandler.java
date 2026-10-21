package com.alexroyodev.crowdpulse.common;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.alexroyodev.crowdpulse.event.EventRequest;
import com.alexroyodev.crowdpulse.zone.UnknownZoneException;
import com.alexroyodev.crowdpulse.zone.ZoneNotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns exceptions into RFC 9457 ProblemDetail responses. Spring MVC's own exceptions
 * (unknown route, wrong method...) are handled by the parent class in the same format.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** One entry of the {@code errors} property of a 400 response. */
	record FieldProblem(String field, String message) {
	}

	/** 400: Bean Validation failed. Title and detail come from messages.properties. */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldProblem(error.getField(), error.getDefaultMessage()))
				.sorted(comparing(FieldProblem::field))
				.toList();
		log.atWarn().setMessage("Request rejected: invalid fields")
				.addKeyValue("eventId", ex.getTarget() instanceof EventRequest event ? event.eventId() : null)
				.addKeyValue("fields", errors.stream().map(FieldProblem::field).toList())
				.log();
		return withErrors(super.handleMethodArgumentNotValid(ex, headers, status, request), errors);
	}

	/**
	 * 400: the body is not valid JSON, or a value cannot become its Java type (an unknown
	 * EventType, a malformed UUID or instant). Jackson fails before Bean Validation runs, so the
	 * field name is taken from the path of Jackson's exception. Broken JSON has no field.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = fieldProblemOf(ex);
		log.atWarn().setMessage("Request rejected: unreadable body")
				.addKeyValue("fields", errors.stream().map(FieldProblem::field).toList())
				.log();
		return withErrors(super.handleHttpMessageNotReadable(ex, headers, status, request), errors);
	}

	/** 400: a constraint on a request parameter failed (e.g. {@code limit} outside 1-500). */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new FieldProblem(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.sorted(comparing(FieldProblem::field))
				.toList();
		log.atWarn().setMessage("Request rejected: invalid parameters")
				.addKeyValue("fields", errors.stream().map(FieldProblem::field).toList())
				.log();
		return withErrors(super.handleHandlerMethodValidationException(ex, headers, status, request), errors);
	}

	/** 400: a request parameter cannot become its Java type (e.g. {@code limit=abc}). */
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String field = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : ex.getPropertyName();
		List<FieldProblem> errors = List.of(new FieldProblem(field, "formato no válido"));
		log.atWarn().setMessage("Request rejected: invalid parameters")
				.addKeyValue("fields", List.of(field))
				.log();
		return withErrors(super.handleTypeMismatch(ex, headers, status, request), errors);
	}

	private static List<FieldProblem> fieldProblemOf(HttpMessageNotReadableException ex) {
		if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
			String field = mismatch.getPath().stream()
					.map(JacksonException.Reference::getPropertyName)
					.filter(Objects::nonNull)
					.collect(joining("."));
			Class<?> target = mismatch.getTargetType();
			String message = target != null && target.isEnum()
					? "valor no válido; valores permitidos: " + Arrays.toString(target.getEnumConstants())
					: "formato no válido";
			return List.of(new FieldProblem(field, message));
		}
		return List.of();
	}

	private static ResponseEntity<Object> withErrors(ResponseEntity<Object> response, List<FieldProblem> errors) {
		if (!errors.isEmpty() && response != null && response.getBody() instanceof ProblemDetail problem) {
			problem.setProperty("errors", errors);
		}
		return response;
	}

	@ExceptionHandler(ZoneNotFoundException.class)
	ProblemDetail handleZoneNotFound(ZoneNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
				"No existe la zona '" + ex.zoneId() + "'.");
		problem.setTitle("Zona no encontrada");
		problem.setProperty("zoneId", ex.zoneId());
		return problem;
	}

	@ExceptionHandler(UnknownZoneException.class)
	ProblemDetail handleUnknownZone(UnknownZoneException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"La zona '" + ex.zoneId() + "' no existe en el catálogo.");
		problem.setTitle("Zona desconocida");
		problem.setProperty("zoneId", ex.zoneId());
		return problem;
	}

	/** Last resort: the client gets a generic message; the details and stack trace go only to the log. */
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"Se ha producido un error inesperado.");
		problem.setTitle("Error interno");
		return problem;
	}

}
