package com.alexroyodev.crowdpulse.event;

import java.util.UUID;

/** Kafka did not confirm the event, so it was not accepted (POST → 503, spec 003 D3). */
public class EventNotPublishedException extends RuntimeException {

	private final UUID eventId;

	EventNotPublishedException(UUID eventId, Throwable cause) {
		super("Event not published: " + eventId, cause);
		this.eventId = eventId;
	}

	public UUID eventId() {
		return eventId;
	}

}
