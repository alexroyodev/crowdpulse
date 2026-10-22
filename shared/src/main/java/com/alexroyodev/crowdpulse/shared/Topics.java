package com.alexroyodev.crowdpulse.shared;

/** Kafka names shared by the API (producer) and the processor (consumer), spec 003. */
public final class Topics {

	/** Accepted sensor events, keyed by zoneId so that each zone keeps its order. */
	public static final String SENSOR_EVENTS = "crowdpulse.sensor-events";

	/** Messages the processor could not process (rule 7): never discarded, kept here. */
	public static final String SENSOR_EVENTS_DLT = "crowdpulse.sensor-events.dlt";

	/** Header with the version of {@link SensorEventMessage}; the processor rejects any other. */
	public static final String MESSAGE_VERSION_HEADER = "crowdpulse-message-version";

	public static final String MESSAGE_VERSION = "1";

	private Topics() {
	}

}
