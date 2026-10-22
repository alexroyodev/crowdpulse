package com.alexroyodev.crowdpulse.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SimulationReportTest {

	private static final ZoneResult NORTE = new ZoneResult("NORTE", 95, 5, Map.of(202, 100), 40, 0, 0);
	private static final ZoneResult SUR = new ZoneResult("SUR", 100, 0, Map.of(202, 100), 12, 0, 0);

	@Test
	void okWhenEveryResponseIsAcceptedAndChangesMatch() {
		SimulationReport report = new SimulationReport(List.of(NORTE, SUR), Map.of("NORTE", 10, "SUR", 0),
				Map.of("NORTE", 50, "SUR", 12), Duration.ofSeconds(2), Duration.ofMillis(1_200));

		assertTrue(report.ok());
		assertEquals(200, report.sent());
		assertEquals(5, report.duplicates());
		assertTrue(report.format().contains("NORTE    10 -> 50; +40 / +40 OK"), report.format());
		assertTrue(report.format().contains("tasa real 100.0 ev/s"), report.format());
		assertTrue(report.format().contains("La API cuadró 1.2 s después del último envío"), report.format());
		assertTrue(report.format().contains("Reintentos por 503: 0 (0 eventos)"), report.format());
		assertTrue(report.format().endsWith("Resultado: OK" + System.lineSeparator()));
	}

	@Test
	void notOkWhenAChangeDoesNotMatch() {
		SimulationReport report = new SimulationReport(List.of(NORTE), Map.of("NORTE", 0), Map.of("NORTE", 39),
				Duration.ofSeconds(1), Duration.ofSeconds(30));

		assertFalse(report.ok());
		assertTrue(report.format().contains("NO COINCIDE"));
		assertTrue(report.format().contains("La API no cuadró tras esperar 30.0 s"), report.format());
	}

	@Test
	void notOkWhenAnyResponseIsNot202() {
		ZoneResult withErrors = new ZoneResult("NORTE", 100, 0, Map.of(202, 99, 500, 1), 40, 0, 0);
		SimulationReport report = new SimulationReport(List.of(withErrors), Map.of("NORTE", 0), Map.of("NORTE", 40),
				Duration.ofSeconds(1), Duration.ofSeconds(30));

		assertFalse(report.ok());
		assertTrue(report.format().contains("500=1"));
	}

	@Test
	void notOkWhenTheZoneDoesNotExistInTheApi() {
		ZoneResult unknown = new ZoneResult("FOO", 10, 0, Map.of(422, 10), 3, 0, 0);
		SimulationReport report = new SimulationReport(List.of(unknown), Map.of(), Map.of(), Duration.ofSeconds(1),
				Duration.ofSeconds(30));

		assertFalse(report.ok());
		assertTrue(report.format().contains("zona no encontrada en la API"));
	}

	@Test
	void parsesOccupanciesFromTheZonesResponse() {
		String json = """
				[{"zoneId":"ESTE","name":"Lateral Este","capacity":15000,"occupancy":0,"occupancyPct":0.0,"lastEventAt":null},\
				{"zoneId":"NORTE","name":"Fondo Norte","capacity":20000,"occupancy":12345,"occupancyPct":61.7,\
				"lastEventAt":"2026-10-19T18:30:05.123Z"}]""";

		assertEquals(Map.of("ESTE", 0, "NORTE", 12345), ApiClient.parseOccupancies(json));
	}

}
