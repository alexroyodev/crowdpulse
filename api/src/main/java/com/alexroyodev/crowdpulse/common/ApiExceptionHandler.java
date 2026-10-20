package com.alexroyodev.crowdpulse.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.alexroyodev.crowdpulse.zone.UnknownZoneException;
import com.alexroyodev.crowdpulse.zone.ZoneNotFoundException;

/**
 * Turns exceptions into RFC 9457 ProblemDetail responses. Spring MVC's own exceptions
 * (unknown route, wrong method...) are handled by the parent class in the same format.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
