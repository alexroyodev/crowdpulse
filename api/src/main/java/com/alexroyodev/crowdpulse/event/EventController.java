package com.alexroyodev.crowdpulse.event;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/events")
class EventController {

	private final EventPublisher publisher;

	EventController(EventPublisher publisher) {
		this.publisher = publisher;
	}

	/**
	 * 202 once Kafka has the event: it will be processed, not that it already was (spec 003). New and
	 * duplicate events alike; the processor ignores duplicates.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	void accept(@Valid @RequestBody EventRequest request) {
		publisher.publish(request);
	}

}
