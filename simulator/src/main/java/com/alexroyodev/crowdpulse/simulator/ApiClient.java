package com.alexroyodev.crowdpulse.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only coupling between simulator and API: their HTTP contract. Thread-safe. */
class ApiClient {

	/** Status reported when the request did not get any HTTP response. */
	static final int CONNECTION_ERROR = -1;

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	// Reads zoneId and occupancy from GET /api/v1/zones, whose format is fixed by spec 001.
	private static final Pattern ZONE = Pattern.compile("\"zoneId\":\"([^\"]+)\"[^}]*?\"occupancy\":(\\d+)");

	private final URI baseUrl;
	private final HttpClient http;

	ApiClient(URI baseUrl) {
		this.baseUrl = baseUrl;
		this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	/** Sends one event; returns the HTTP status, or CONNECTION_ERROR. */
	int send(SimulatedEvent event) {
		HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/api/v1/events"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(event.toJson()))
				.build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
		catch (IOException ex) {
			return CONNECTION_ERROR;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return CONNECTION_ERROR;
		}
	}

	/** Current occupancy of every zone, by zoneId. */
	Map<String, Integer> occupancies() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/api/v1/zones")).timeout(TIMEOUT).GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("GET /api/v1/zones respondió " + response.statusCode());
		}
		return parseOccupancies(response.body());
	}

	static Map<String, Integer> parseOccupancies(String json) {
		Map<String, Integer> occupancies = new TreeMap<>();
		Matcher matcher = ZONE.matcher(json);
		while (matcher.find()) {
			occupancies.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
		}
		return occupancies;
	}

}
