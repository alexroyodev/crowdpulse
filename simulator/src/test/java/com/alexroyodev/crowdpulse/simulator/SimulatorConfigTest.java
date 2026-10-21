package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class SimulatorConfigTest {

	@Test
	void usesDefaultsWhenNoArguments() {
		SimulatorConfig config = SimulatorConfig.parse();

		assertEquals(URI.create("http://localhost:8080"), config.url());
		assertEquals(100, config.rate());
		assertEquals(Duration.ofSeconds(60), config.duration());
		assertEquals(List.of("NORTE", "SUR", "ESTE", "OESTE"), config.zones());
		assertEquals(0, config.duplicatesPct());
	}

	@Test
	void parsesEveryOption() {
		SimulatorConfig config = SimulatorConfig.parse("--url=http://api:9090/", "--rate=40",
				"--duration=2m", "--zones=NORTE, SUR", "--duplicates=5");

		assertEquals(URI.create("http://api:9090"), config.url());
		assertEquals(40, config.rate());
		assertEquals(Duration.ofMinutes(2), config.duration());
		assertEquals(List.of("NORTE", "SUR"), config.zones());
		assertEquals(5, config.duplicatesPct());
		assertEquals(20.0, config.ratePerZone());
	}

	@Test
	void durationAcceptsSecondsWithOrWithoutUnit() {
		assertEquals(Duration.ofSeconds(90), SimulatorConfig.parse("--duration=90").duration());
		assertEquals(Duration.ofSeconds(30), SimulatorConfig.parse("--duration=30s").duration());
	}

	@Test
	void rejectsNonNumericRateWithAClearMessage() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> SimulatorConfig.parse("--rate=abc"));

		assertEquals("--rate debe ser un número entero: 'abc'", ex.getMessage());
	}

	@Test
	void rejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--rate=0"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--duration=0s"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--duration=ayer"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--zones=,"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--zones=NORTE,NORTE"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--duplicates=100"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--url=ftp://x"));
	}

	@Test
	void rejectsUnknownOrMalformedArguments() {
		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
				() -> SimulatorConfig.parse("--speed=10"));
		assertTrue(unknown.getMessage().contains("--speed"));

		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("rate=10"));
		assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse("--rate"));
	}

}
