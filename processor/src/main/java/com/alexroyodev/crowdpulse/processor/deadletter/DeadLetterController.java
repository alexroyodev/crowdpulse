package com.alexroyodev.crowdpulse.processor.deadletter;

import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec 004, D2: replays the dead-letter topic on demand, once the cause has been fixed. No
 * authentication for now: the processor's port is meant to be reachable only locally.
 */
@RestController
@RequestMapping("/admin/dead-letters")
class DeadLetterController {

	private static final Logger log = LoggerFactory.getLogger(DeadLetterController.class);

	record ReplayResult(int replayed) {
	}

	private final DeadLetterReplayer replayer;

	DeadLetterController(DeadLetterReplayer replayer) {
		this.replayer = replayer;
	}

	@PostMapping("/replay")
	ReplayResult replay() throws ExecutionException, InterruptedException {
		return new ReplayResult(replayer.replay());
	}

	/** Kafka did not answer: nothing was committed, so calling again replays from the same point. */
	@ExceptionHandler({ ExecutionException.class, org.apache.kafka.common.KafkaException.class,
			org.springframework.kafka.KafkaException.class })
	ProblemDetail handleKafkaUnavailable(Exception ex) {
		log.warn("Dead-letter replay failed", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"No se ha podido reprocesar el topic de mensajes fallidos; reinténtalo.");
		problem.setTitle("Servicio no disponible");
		return problem;
	}

}
