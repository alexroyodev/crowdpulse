package com.alexroyodev.crowdpulse.event;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alexroyodev.crowdpulse.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.TopicProbe;
import com.alexroyodev.crowdpulse.shared.Topics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Spec 007, the API's half: the request's span carries the event, and its trace goes on in the
 * Kafka message (traceparent), where the processor picks it up. Spans are read from an in-memory
 * exporter; next to it, the real OTLP exporter sends to a backend that never answers (CA6).
 */
@Import({ TestcontainersConfiguration.class, TracingIT.InMemorySpans.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		// Export every 100 ms instead of 5 s, so the tests do not wait.
		"management.opentelemetry.tracing.export.schedule-delay=100ms",
		// Each export to the hung backend gives up after 2 s (10 s by default) and the next batch goes.
		"management.opentelemetry.tracing.export.otlp.timeout=2s" })
@AutoConfigureRestTestClient
@AutoConfigureTracing
class TracingIT {

	private static final String EVENTS = "/api/v1/events";
	private static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");
	private static final AttributeKey<String> URL = AttributeKey.stringKey("http.url");
	private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey(TraceAttributes.EVENT_ID);
	private static final AttributeKey<String> ZONE_ID = AttributeKey.stringKey(TraceAttributes.ZONE_ID);
	private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey(TraceAttributes.EVENT_TYPE);

	/** CA6: a trace backend that accepts the connection and never answers, like a paused Jaeger. */
	private static final ServerSocket HUNG_BACKEND = hungBackend();

	@DynamicPropertySource
	static void otlpEndpoint(DynamicPropertyRegistry registry) {
		registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
				() -> "http://127.0.0.1:" + HUNG_BACKEND.getLocalPort() + "/v1/traces");
	}

	@AfterAll
	static void closeBackend() throws IOException {
		HUNG_BACKEND.close();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class InMemorySpans {

		/** Spring Boot exports to every SpanExporter bean, this one besides OTLP. */
		@Bean
		InMemorySpanExporter inMemorySpanExporter() {
			return InMemorySpanExporter.create();
		}

	}

	private final RestTestClient client;
	private final TopicProbe topic;
	private final InMemorySpanExporter spans;
	private final Clock clock;

	@Autowired
	TracingIT(RestTestClient client, ConsumerFactory<String, String> consumers, InMemorySpanExporter spans,
			Clock clock) {
		this.client = client;
		this.topic = new TopicProbe(consumers, Topics.SENSOR_EVENTS);
		this.spans = spans;
		this.clock = clock;
	}

	@BeforeEach
	void reset() {
		topic.mark();
		spans.reset();
	}

	@Test
	void theTraceOfAnAcceptedEventGoesOnInItsKafkaMessage() {
		// CA1, the API's half.
		UUID eventId = UUID.randomUUID();
		post(event(eventId, "NORTE"), 202);

		SpanData request = awaitSpan("the request span of " + eventId, span -> eventId.toString().equals(span.getAttributes().get(EVENT_ID)));
		assertThat(request.getAttributes().get(ZONE_ID)).isEqualTo("NORTE");
		assertThat(request.getAttributes().get(EVENT_TYPE)).isEqualTo("ENTRY");
		assertThat(request.getAttributes().get(STATUS)).isEqualTo("202");

		ConsumerRecord<String, String> record = single(topic.published());
		Header traceparent = record.headers().lastHeader("traceparent");
		assertThat(traceparent).as("traceparent header of the message").isNotNull();
		// W3C Trace Context: 00-<trace id>-<parent span id>-<flags>
		String[] parts = new String(traceparent.value(), UTF_8).split("-");
		assertThat(parts[1]).isEqualTo(request.getTraceId());
		// Its parent is the publish span, a child of the request: the same trace, one level down.
		SpanData publish = awaitSpan("the publish span", span -> span.getSpanId().equals(parts[2]));
		assertThat(publish.getTraceId()).isEqualTo(request.getTraceId());
		assertThat(publish.getParentSpanId()).isEqualTo(request.getSpanId());
	}

	@Test
	void anUnknownZoneIsTracedWithTheEventAndA422() {
		UUID eventId = UUID.randomUUID();
		post(event(eventId, "NOPE"), 422);

		SpanData request = awaitSpan("the request span of " + eventId, span -> eventId.toString().equals(span.getAttributes().get(EVENT_ID)));
		assertThat(request.getAttributes().get(STATUS)).isEqualTo("422");
	}

	@Test
	void anInvalidRequestIsTracedWithoutEventAttributes() {
		// Plan 007, section 0: a 400 never reaches the controller, which is where they are set.
		post("""
				{"sensorId":"NORTE-P01","zoneId":"NORTE","type":"ENTRY"}
				""", 400);

		SpanData request = awaitSpan("the request span of the 400", span -> "400".equals(span.getAttributes().get(STATUS)));
		assertThat(request.getAttributes().get(EVENT_ID)).isNull();
		assertThat(request.getAttributes().get(ZONE_ID)).isNull();
	}

	@Test
	void actuatorRequestsAreNotTraced() {
		// CA8. Prometheus' endpoint is not set up without @AutoConfigureMetrics: a 404, still a request.
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
		client.get().uri("/actuator/prometheus").exchange();
		// Spans are exported in the order they end: once this one is there, the earlier ones would be too.
		client.get().uri("/api/v1/zones").exchange().expectStatus().isOk();
		awaitSpan("the span of GET /api/v1/zones", span -> String.valueOf(span.getAttributes().get(URL)).endsWith("/api/v1/zones"));

		assertThat(spans.getFinishedSpanItems())
				.extracting(span -> String.valueOf(span.getAttributes().get(URL)))
				.noneMatch(url -> url.contains("/actuator"));
	}

	@Test
	void eventsAreAcceptedWithoutWaitingForAHungTraceBackend() {
		// CA6: every export to HUNG_BACKEND takes 2 s (the OTLP timeout). If a request waited for it,
		// it would take that long too.
		post(event(UUID.randomUUID(), "NORTE"), 202); // warm-up: the first publish opens the producer
		for (int i = 0; i < 10; i++) {
			long start = System.nanoTime();
			post(event(UUID.randomUUID(), "NORTE"), 202);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
		}
		awaitSpan("a request span, exported while the backend hangs", span -> "202".equals(span.getAttributes().get(STATUS)));
	}

	private String event(UUID eventId, String zoneId) {
		return """
				{"eventId":"%s","sensorId":"NORTE-P01","zoneId":"%s","type":"ENTRY","occurredAt":"%s"}
				""".formatted(eventId, zoneId, clock.instant().minusSeconds(1));
	}

	private void post(String body, int status) {
		client.post().uri(EVENTS)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isEqualTo(status);
	}

	/** Exports run every 100 ms: waits up to 10 s for a matching span. */
	private SpanData awaitSpan(String what, Predicate<SpanData> matches) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			for (SpanData span : spans.getFinishedSpanItems()) {
				if (matches.test(span)) {
					return span;
				}
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
		}
		throw new AssertionError("No span after 10 s: " + what + "; exported: " + spans.getFinishedSpanItems());
	}

	private static ConsumerRecord<String, String> single(List<ConsumerRecord<String, String>> records) {
		assertThat(records).hasSize(1);
		return records.get(0);
	}

	private static ServerSocket hungBackend() {
		try {
			// The OS completes the TCP handshake on its own (backlog); nobody ever reads the request.
			return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
