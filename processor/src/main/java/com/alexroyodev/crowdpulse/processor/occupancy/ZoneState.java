package com.alexroyodev.crowdpulse.processor.occupancy;

import com.alexroyodev.crowdpulse.shared.AlertLevel;

/** A zone as stored right now, for the metrics (spec 006). */
public record ZoneState(String zoneId, int occupancy, int capacity, AlertLevel alertLevel) {
}
