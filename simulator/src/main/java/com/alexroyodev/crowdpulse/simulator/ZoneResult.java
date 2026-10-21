package com.alexroyodev.crowdpulse.simulator;

import java.util.Map;

/**
 * What one zone's sender did.
 *
 * @param newEvents events sent for the first time
 * @param duplicates resends of an already sent event
 * @param statuses number of responses per HTTP status ({@link ApiClient#CONNECTION_ERROR} for no response)
 * @param expectedDelta unique entries minus unique exits: how much the API occupancy should change
 */
record ZoneResult(String zoneId, int newEvents, int duplicates, Map<Integer, Integer> statuses, int expectedDelta) {

	ZoneResult {
		statuses = Map.copyOf(statuses);
	}

	int sent() {
		return newEvents + duplicates;
	}

}
