package com.alexroyodev.crowdpulse.zone;

/** An event refers to a zone that is not in the catalogue (POST → 422). */
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
