package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TrafficGeneratorTest {

	private static final int STEPS = 10_000;
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T18:30:00Z"), ZoneOffset.UTC);

	private static TrafficGenerator generator(int duplicatesPct) {
		return new TrafficGenerator("NORTE", duplicatesPct, new Random(42), CLOCK);
	}

	/** Runs a whole simulation of STEPS events, progress going from 0 to 1. */
	private static List<SimulatedEvent> run(TrafficGenerator generator) {
		List<SimulatedEvent> events = new ArrayList<>();
		for (int i = 0; i < STEPS; i++) {
			events.add(generator.next((double) i / STEPS));
		}
		return events;
	}

	@Test
	void simulatedOccupancyIsNeverNegative() {
		TrafficGenerator generator = generator(5);
		for (int i = 0; i < STEPS; i++) {
			generator.next((double) i / STEPS);
			assertTrue(generator.occupancy() >= 0, "negative occupancy at step " + i);
		}
	}

	@Test
	void firstEventIsAnEntry() {
		assertEquals("ENTRY", generator(0).next(0.5).type());
	}

	@Test
	void mostlyEntriesAtTheStartAndMostlyExitsAtTheEnd() {
		List<SimulatedEvent> events = run(generator(0));

		List<SimulatedEvent> first = events.subList(0, STEPS / 10);
		List<SimulatedEvent> last = events.subList(STEPS - STEPS / 10, STEPS);
		assertTrue(count(first, "ENTRY") > count(first, "EXIT"), "start should be mostly ENTRY");
		assertTrue(count(last, "EXIT") > count(last, "ENTRY"), "end should be mostly EXIT");
	}

	@Test
	void occupancyEqualsUniqueEntriesMinusUniqueExits() {
		TrafficGenerator generator = generator(10);
		List<SimulatedEvent> unique = run(generator).stream().filter(e -> !e.duplicate()).toList();

		assertEquals(count(unique, "ENTRY") - count(unique, "EXIT"), generator.occupancy());
	}

	@Test
	void duplicatesAreCloseToTheConfiguredPercentage() {
		List<SimulatedEvent> events = run(generator(10));

		long duplicates = events.stream().filter(SimulatedEvent::duplicate).count();
		double ratio = (double) duplicates / STEPS;
		assertTrue(ratio > 0.08 && ratio < 0.12, "duplicate ratio was " + ratio);
	}

	@Test
	void duplicatesRepeatAnEventAlreadySent() {
		List<SimulatedEvent> events = run(generator(10));

		Set<UUID> seen = new HashSet<>();
		for (SimulatedEvent event : events) {
			if (event.duplicate()) {
				assertTrue(seen.contains(event.eventId()), "duplicate of an event never sent");
			}
			else {
				assertTrue(seen.add(event.eventId()), "new event reused an eventId");
			}
		}
	}

	@Test
	void noDuplicatesWhenDisabled() {
		assertFalse(run(generator(0)).stream().anyMatch(SimulatedEvent::duplicate));
	}

	@Test
	void sensorIdsBelongToTheZone() {
		for (SimulatedEvent event : run(generator(0))) {
			assertTrue(event.sensorId().matches("NORTE-P0[1-5]"), event.sensorId());
			assertEquals("NORTE", event.zoneId());
		}
	}

	@Test
	void jsonFollowsTheEventContract() {
		SimulatedEvent event = new SimulatedEvent(UUID.fromString("3f2b8c1e-6a4d-4e0f-9b7a-2c5d8e1f0a93"),
				"NORTE-P03", "NORTE", "ENTRY", Instant.parse("2026-10-19T18:30:05.123Z"), false);

		assertEquals("""
				{"eventId":"3f2b8c1e-6a4d-4e0f-9b7a-2c5d8e1f0a93","sensorId":"NORTE-P03","zoneId":"NORTE",\
				"type":"ENTRY","occurredAt":"2026-10-19T18:30:05.123Z"}""", event.toJson());
	}

	private static long count(List<SimulatedEvent> events, String type) {
		return events.stream().filter(e -> e.type().equals(type)).count();
	}

}
