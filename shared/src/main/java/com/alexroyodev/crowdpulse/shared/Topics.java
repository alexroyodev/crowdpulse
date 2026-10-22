package com.alexroyodev.crowdpulse.shared;

/** Kafka names shared by the API (producer) and the processor (consumer), spec 003. */
public final class Topics {

	/** Accepted sensor events, keyed by zoneId so that each zone keeps its order. */
	public static final String SENSOR_EVENTS = "crowdpulse.sensor-events";

	/**
	 * 3 partitions for 5 zones: several zones share a partition, which shows that the order per
	 * zone comes from the key, not from one partition per zone (plan 003, section 4). Declared by
	 * both applications, so the topic has them whichever starts first.
	 */
	public static final int SENSOR_EVENTS_PARTITIONS = 3;

	/** Messages the processor could not process (rule 7): never discarded, kept here. */
	public static final String SENSOR_EVENTS_DLT = "crowdpulse.sensor-events.dlt";

	/** Header with the version of {@link SensorEventMessage}; the processor rejects any other. */
	public static final String MESSAGE_VERSION_HEADER = "crowdpulse-message-version";

	public static final String MESSAGE_VERSION = "1";

	private Topics() {
	}

}
