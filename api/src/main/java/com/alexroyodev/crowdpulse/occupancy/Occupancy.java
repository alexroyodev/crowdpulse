package com.alexroyodev.crowdpulse.occupancy;

import java.time.Instant;
import java.util.Objects;

import com.alexroyodev.crowdpulse.event.EventType;

/**
 * Occupancy of one zone: pure domain logic, no Spring and no database.
 *
 * @param value number of people in the zone, never negative
 * @param lastEventAt most recent {@code occurredAt} applied to the zone, or null if none
 */
public record Occupancy(int value, Instant lastEventAt) {

	public Occupancy {
		if (value < 0) {
			throw new IllegalArgumentException("Occupancy cannot be negative: " + value);
		}
	}

	/**
	 * Applies one event. There is no upper limit: occupancy may exceed the zone capacity
	 * (rule 6). An EXIT at 0 keeps occupancy at 0 and is reported as an anomaly (rule 5).
	 */
	public OccupancyChange apply(EventType type, Instant occurredAt) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(occurredAt, "occurredAt");

		// Events may arrive out of order: keep the latest event time, not the latest received.
		Instant latest = (lastEventAt == null || occurredAt.isAfter(lastEventAt)) ? occurredAt : lastEventAt;

		return switch (type) {
			case ENTRY -> new OccupancyChange(new Occupancy(value + 1, latest), false);
			case EXIT -> value > 0
					? new OccupancyChange(new Occupancy(value - 1, latest), false)
					: new OccupancyChange(new Occupancy(0, latest), true);
		};
	}

}
