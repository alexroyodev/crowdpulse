package com.alexroyodev.crowdpulse.processor.event;

/**
 * The message names a zone that is not in the catalogue. The API checks it before publishing
 * (spec 003, D2), so this only happens if the catalogue changes in between or the message did
 * not come from the API. Retrying cannot fix it.
 */
public class UnknownZoneException extends RuntimeException {

	private final String zoneId;

	public UnknownZoneException(String zoneId) {
		super("Unknown zone: " + zoneId);
		this.zoneId = zoneId;
	}

	public String zoneId() {
		return zoneId;
	}

}
