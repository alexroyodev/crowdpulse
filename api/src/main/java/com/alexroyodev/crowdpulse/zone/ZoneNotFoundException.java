package com.alexroyodev.crowdpulse.zone;

/** The requested zone resource does not exist (GET → 404). */
public class ZoneNotFoundException extends RuntimeException {

	private final String zoneId;

	public ZoneNotFoundException(String zoneId) {
		super("Zone not found: " + zoneId);
		this.zoneId = zoneId;
	}

	public String zoneId() {
		return zoneId;
	}

}
