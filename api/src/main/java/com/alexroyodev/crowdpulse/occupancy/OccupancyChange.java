package com.alexroyodev.crowdpulse.occupancy;

/**
 * Result of applying one event to a zone's occupancy.
 *
 * @param result the occupancy after the event
 * @param anomaly true when an EXIT arrived with occupancy already at 0 (spec 001, rule 5)
 */
public record OccupancyChange(Occupancy result, boolean anomaly) {
}
