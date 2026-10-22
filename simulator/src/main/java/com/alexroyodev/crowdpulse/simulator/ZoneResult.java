package com.alexroyodev.crowdpulse.simulator;

import java.util.Map;

/**
 * What one zone's sender did.
 *
 * @param newEvents events sent for the first time
 * @param duplicates resends of an already sent event
 * @param statuses number of responses per HTTP status ({@link ApiClient#CONNECTION_ERROR} for no response)
 * @param expectedDelta unique entries minus unique exits: how much the API occupancy should change
 * @param retries resends after a 503 (spec 005)
 * @param retriedEvents events that needed at least one resend
 */
record ZoneResult(String zoneId, int newEvents, int duplicates, Map<Integer, Integer> statuses, int expectedDelta,
		int retries, int retriedEvents) {

	ZoneResult {
		statuses = Map.copyOf(statuses);
	}

	int sent() {
		return newEvents + duplicates;
	}

}
