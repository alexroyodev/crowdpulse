package com.alexroyodev.crowdpulse.processor.occupancy;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

/**
 * What EventService needs from a zone while holding its row lock: its occupancy, its capacity
 * (to evaluate the alert thresholds) and its current alert level.
 */
public record LockedZone(Occupancy occupancy, int capacity, AlertLevel alertLevel) {
}
