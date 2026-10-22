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

	// Longer than the API takes to answer 503 in the worst case (about 6 s with the database down,
	// spec 005): a timeout is not retried, so with less the event would be lost instead of resent.
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	// Reads zoneId and occupancy from GET /api/v1/zones, whose format is fixed by spec 001.
	private static final Pattern ZONE = Pattern.compile("\"zoneId\":\"([^\"]+)\"[^}]*?\"occupancy\":(\\d+)");

	private final URI baseUrl;
	private final HttpClient http;

	ApiClient(URI baseUrl) {
		this.baseUrl = baseUrl;
		this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	/** When the API asks for no specific wait (spec 005: as a sensor, honour Retry-After). */
	static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

	/**
	 * @param status the HTTP status, or CONNECTION_ERROR
	 * @param retryAfter how long the API asks to wait before retrying (Retry-After, in seconds)
	 */
	record Response(int status, Duration retryAfter) {

		static Response of(int status) {
			return new Response(status, DEFAULT_RETRY_AFTER);
		}

	}

	/** Sends one event once. */
	Response send(SimulatedEvent event) {
		HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/api/v1/events"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(event.toJson()))
				.build();
		try {
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			return new Response(response.statusCode(), retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
		}
		catch (IOException ex) {
			return Response.of(CONNECTION_ERROR);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Response.of(CONNECTION_ERROR);
		}
	}

	/** Retry-After in seconds; 1 s if missing or not a non-negative number. */
	static Duration retryAfter(String header) {
		try {
			long seconds = Long.parseLong(header == null ? "" : header.trim());
			return seconds >= 0 ? Duration.ofSeconds(seconds) : DEFAULT_RETRY_AFTER;
		}
		catch (NumberFormatException ex) {
			return DEFAULT_RETRY_AFTER;
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
