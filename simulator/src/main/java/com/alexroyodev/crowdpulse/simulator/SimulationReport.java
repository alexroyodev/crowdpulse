package com.alexroyodev.crowdpulse.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Summary printed at the end, with the CA10 check: every response was 202 and, for every zone,
 * the change in the API's occupancy equals unique entries minus unique exits sent. Comparing the
 * change (after - before) instead of the absolute value lets the check work on a non-empty database.
 *
 * @param waited how long the API took to show the changes after the last send (spec 003, D4), or
 *        how long the simulator waited before giving up
 */
record SimulationReport(List<ZoneResult> zones, Map<String, Integer> before, Map<String, Integer> after,
		Duration elapsed, Duration waited) {

	SimulationReport {
		zones = List.copyOf(zones);
		before = Map.copyOf(before);
		after = Map.copyOf(after);
	}

	int sent() {
		return zones.stream().mapToInt(ZoneResult::sent).sum();
	}

	int duplicates() {
		return zones.stream().mapToInt(ZoneResult::duplicates).sum();
	}

	int retries() {
		return zones.stream().mapToInt(ZoneResult::retries).sum();
	}

	int retriedEvents() {
		return zones.stream().mapToInt(ZoneResult::retriedEvents).sum();
	}

	Map<Integer, Integer> statuses() {
		Map<Integer, Integer> total = new TreeMap<>();
		zones.forEach(zone -> zone.statuses().forEach((status, n) -> total.merge(status, n, Integer::sum)));
		return total;
	}

	boolean allAccepted() {
		return statuses().keySet().stream().allMatch(status -> status == 202);
	}

	boolean matches(ZoneResult zone) {
		Integer b = before.get(zone.zoneId());
		Integer a = after.get(zone.zoneId());
		return b != null && a != null && a - b == zone.expectedDelta();
	}

	boolean changesMatch() {
		return zones.stream().allMatch(this::matches);
	}

	boolean ok() {
		return allAccepted() && changesMatch();
	}

	String format() {
		double seconds = elapsed.toMillis() / 1000.0;
		StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "Simulación terminada en %.1f s%n", seconds));
		out.append(String.format(Locale.ROOT, "  Eventos enviados: %d (tasa real %.1f ev/s)%n", sent(), sent() / seconds));
		out.append(String.format(Locale.ROOT, "  Duplicados enviados: %d%n", duplicates()));
		out.append("  Respuestas:");
		statuses().forEach((status, n) -> out.append(' ')
				.append(status == ApiClient.CONNECTION_ERROR ? "sin conexión" : status).append('=').append(n));
		out.append(System.lineSeparator());
		out.append(String.format(Locale.ROOT, "  Reintentos por 503: %d (%d eventos)%n", retries(), retriedEvents()));
		out.append(String.format(Locale.ROOT, changesMatch()
				? "  La API cuadró %.1f s después del último envío%n"
				: "  La API no cuadró tras esperar %.1f s%n", waited.toMillis() / 1000.0));
		out.append("Ocupación en la API (antes -> después; cambio / esperado):").append(System.lineSeparator());
		for (ZoneResult zone : zones) {
			Integer b = before.get(zone.zoneId());
			Integer a = after.get(zone.zoneId());
			String change = b == null || a == null ? "zona no encontrada en la API" : "%d -> %d; %+d / %+d"
					.formatted(b, a, a - b, zone.expectedDelta());
			out.append(String.format(Locale.ROOT, "  %-8s %s %s%n", zone.zoneId(), change, matches(zone) ? "OK" : "NO COINCIDE"));
		}
		out.append(ok() ? "Resultado: OK" : "Resultado: ERROR (" + (allAccepted() ? "" : "hay respuestas distintas de 202; ")
				+ "revisa la tabla)").append(System.lineSeparator());
		return out.toString();
	}

}
