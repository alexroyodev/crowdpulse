package com.alexroyodev.crowdpulse.simulator;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Command-line options of the simulator, already validated.
 *
 * @param url base URL of the API
 * @param rate total events per second, spread evenly across the zones
 * @param duration how long to send events
 * @param zones zone ids to simulate, one sender thread each
 * @param duplicatesPct percentage of sends that repeat an already sent event (same eventId)
 */
record SimulatorConfig(URI url, int rate, Duration duration, List<String> zones, int duplicatesPct) {

	static final String USAGE = """
			Uso: java -jar simulator.jar [opciones]
			  --url=URL            URL base de la API (por defecto http://localhost:8080)
			  --rate=N             eventos por segundo en total (por defecto 100)
			  --duration=D         duración: 60s, 2m o segundos sin unidad (por defecto 60s)
			  --zones=A,B,...      zonas a simular (por defecto NORTE,SUR,ESTE,OESTE)
			  --duplicates=P       % de envíos que repiten un evento ya enviado, 0-99 (por defecto 0)
			""";

	private static final List<String> KEYS = List.of("url", "rate", "duration", "zones", "duplicates");

	SimulatorConfig {
		if (rate <= 0) {
			throw new IllegalArgumentException("--rate debe ser mayor que 0");
		}
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException("--duration debe ser mayor que 0");
		}
		if (zones.isEmpty()) {
			throw new IllegalArgumentException("--zones no puede estar vacío");
		}
		if (new HashSet<>(zones).size() != zones.size()) {
			throw new IllegalArgumentException("--zones no puede repetir zonas");
		}
		if (duplicatesPct < 0 || duplicatesPct > 99) {
			throw new IllegalArgumentException("--duplicates debe estar entre 0 y 99");
		}
		zones = List.copyOf(zones);
	}

	/** Parses {@code --key=value} arguments; anything missing takes its default. */
	static SimulatorConfig parse(String... args) {
		Map<String, String> options = new TreeMap<>();
		for (String arg : args) {
			if (!arg.startsWith("--") || !arg.contains("=")) {
				throw new IllegalArgumentException("Argumento no válido: '" + arg + "' (formato --clave=valor)");
			}
			String key = arg.substring(2, arg.indexOf('='));
			if (!KEYS.contains(key)) {
				throw new IllegalArgumentException("Opción desconocida: --" + key);
			}
			options.put(key, arg.substring(arg.indexOf('=') + 1));
		}
		return new SimulatorConfig(
				parseUrl(options.getOrDefault("url", "http://localhost:8080")),
				parseInt("rate", options.getOrDefault("rate", "100")),
				parseDuration(options.getOrDefault("duration", "60s")),
				parseZones(options.getOrDefault("zones", "NORTE,SUR,ESTE,OESTE")),
				parseInt("duplicates", options.getOrDefault("duplicates", "0")));
	}

	/** Events per second for each zone's sender. */
	double ratePerZone() {
		return (double) rate / zones.size();
	}

	private static URI parseUrl(String value) {
		try {
			URI url = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
			if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
				throw new IllegalArgumentException();
			}
			return url;
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("--url no es una URL http(s) válida: '" + value + "'");
		}
	}

	private static int parseInt(String key, String value) {
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("--" + key + " debe ser un número entero: '" + value + "'");
		}
	}

	private static Duration parseDuration(String value) {
		String v = value.trim();
		try {
			if (v.endsWith("m")) {
				return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
			}
			if (v.endsWith("s")) {
				return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
			}
			return Duration.ofSeconds(Long.parseLong(v));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("--duration no es válida: '" + value + "' (ejemplos: 60s, 2m, 90)");
		}
	}

	private static List<String> parseZones(String value) {
		return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(zone -> !zone.isEmpty())
				.toList();
	}

}
