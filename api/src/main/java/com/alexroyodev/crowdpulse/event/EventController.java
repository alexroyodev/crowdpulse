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

	private final EventService service;

	EventController(EventService service) {
		this.service = service;
	}

	/** 202 for new and duplicate events alike, so the contract holds once processing is asynchronous. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	void accept(@Valid @RequestBody EventRequest request) {
		service.accept(request);
	}

}
