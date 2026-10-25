package com.alexroyodev.crowdpulse.processor.event;

import static com.alexroyodev.crowdpulse.processor.Messages.message;
import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;

import com.alexroyodev.crowdpulse.processor.Eventually;
import com.alexroyodev.crowdpulse.processor.TestcontainersConfiguration;
import com.alexroyodev.crowdpulse.processor.TopicProbe;
import com.alexroyodev.crowdpulse.processor.alert.AlertRepository;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;
import com.alexroyodev.crowdpulse.shared.Topics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 007, the processor's half: each delivery of a message is a span of the trace in its
 * traceparent header (or of a new one), with the event and its outcome. Messages are published
 * with a plain producer, not the KafkaTemplate, whose observation would add a traceparent of its
 * own. Spans are read from an in-memory exporter; next to it, the real OTLP exporter sends to a
 * backend that never answers (CA6).
 */
@Import({ TestcontainersConfiguration.class, TracingIT.InMemorySpans.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		// Export every 100 ms instead of 5 s, so the tests do not wait.
		"management.opentelemetry.tracing.export.schedule-delay=100ms",
		// Each export to the hung backend gives up after 2 s (10 s by default) and the next batch goes.
		"management.opentelemetry.tracing.export.otlp.timeout=2s" })
@AutoConfigureTracing
@Sql(scripts = "/reset-state.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-state.sql", executionPhase = AFTER_TEST_METHOD)
class TracingIT {

	private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey(TraceAttributes.EVENT_ID);
	private static final AttributeKey<String> ZONE_ID = AttributeKey.stringKey(TraceAttributes.ZONE_ID);
	private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey(TraceAttributes.EVENT_TYPE);
	private static final AttributeKey<String> OCCURRED_AT = AttributeKey.stringKey(TraceAttributes.OCCURRED_AT);
	private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey(TraceAttributes.OUTCOME);
	private static final AttributeKey<String> REASON = AttributeKey.stringKey(TraceAttributes.DEAD_LETTER_REASON);
	private static final AttributeKey<String> URL = AttributeKey.stringKey("http.url");

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

	// The repository, not the @Transactional EventProcessor: a failure here rolls its transaction back.
	@MockitoSpyBean
	AlertRepository alerts;

	@LocalServerPort
	int port;

	private final ProducerFactory<String, String> producers;
	private final ConsumerFactory<String, String> consumers;
	private final TopicProbe deadLetters;
	private final InMemorySpanExporter spans;
	private final JsonMapper json;
	private final JdbcClient jdbc;
	private final Clock clock;

	@Autowired
	TracingIT(ProducerFactory<String, String> producers, ConsumerFactory<String, String> consumers,
			InMemorySpanExporter spans, JsonMapper json, JdbcClient jdbc, Clock clock) {
		this.producers = producers;
		this.consumers = consumers;
		this.deadLetters = new TopicProbe(consumers, Topics.SENSOR_EVENTS_DLT);
		this.spans = spans;
		this.json = json;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@BeforeEach
	void reset() {
		deadLetters.mark();
		spans.reset();
	}

	@Test
	void theDeliveryContinuesTheTraceOfItsMessage() {
		// CA1, the processor's half: the traceparent the API wrote.
		Traceparent api = Traceparent.random();
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());

		publish(Topics.SENSOR_EVENTS, message, api);

		SpanData delivery = awaitDelivery(message, "applied");
		assertThat(delivery.getTraceId()).isEqualTo(api.traceId());
		assertThat(delivery.getParentSpanId()).isEqualTo(api.spanId());
		assertThat(delivery.getAttributes().get(ZONE_ID)).isEqualTo("NORTE");
		assertThat(delivery.getAttributes().get(EVENT_TYPE)).isEqualTo("ENTRY");
		assertThat(delivery.getAttributes().get(OCCURRED_AT)).isEqualTo(message.occurredAt().toString());
	}

	@Test
	void eachOutcomeIsOnItsSpan() {
		// CA2. ESTE is empty after reset-state.sql.
		SensorEventMessage exit = message("ESTE", EXIT, clock.instant());
		SensorEventMessage entry = message("ESTE", ENTRY, clock.instant());

		publish(Topics.SENSOR_EVENTS, exit, Traceparent.random());
		publish(Topics.SENSOR_EVENTS, entry, Traceparent.random());
		publish(Topics.SENSOR_EVENTS, entry, Traceparent.random()); // the sensor sent it twice

		awaitDelivery(exit, "exit_at_zero");
		awaitDelivery(entry, "applied");
		awaitDelivery(entry, "duplicate");
	}

	@Test
	void aMessageWithoutTraceparentStartsANewTrace() {
		// CA3 (rule 2): published before spec 007, or by another producer.
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());

		publish(Topics.SENSOR_EVENTS, message, null);

		SpanData delivery = awaitDelivery(message, "applied");
		assertThat(delivery.getParentSpanId()).isEqualTo(SpanId.getInvalid());
		assertThat(delivery.getSpanContext().isValid()).isTrue();
	}

	@Test
	void eachAttemptIsASpanOfTheSameTrace() {
		// CA4 (rule 3): OESTE one person below 85 % of 15 000 (V2), so the next ENTRY inserts an alert.
		// That insert fails once, inside the transaction: it rolls back and the message is delivered again.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 12749 WHERE zone_id = 'OESTE'").update();
		AtomicBoolean failed = new AtomicBoolean();
		doAnswer(invocation -> {
			if (failed.compareAndSet(false, true)) {
				throw new IllegalStateException("simulated failure inside the transaction");
			}
			return invocation.callRealMethod();
		}).when(alerts).insert(any());
		Traceparent api = Traceparent.random();
		SensorEventMessage message = message("OESTE", ENTRY, clock.instant());

		publish(Topics.SENSOR_EVENTS, message, api);

		awaitDelivery(message, "applied");
		List<SpanData> attempts = spans.getFinishedSpanItems().stream()
				.filter(span -> message.eventId().toString().equals(span.getAttributes().get(EVENT_ID)))
				.sorted(Comparator.comparingLong(SpanData::getStartEpochNanos))
				.toList();
		assertThat(attempts).hasSize(2);
		assertThat(attempts).allSatisfy(attempt -> {
			assertThat(attempt.getTraceId()).isEqualTo(api.traceId());
			assertThat(attempt.getParentSpanId()).isEqualTo(api.spanId());
		});
		SpanData rolledBack = attempts.get(0);
		assertThat(rolledBack.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		assertThat(rolledBack.getAttributes().get(OUTCOME)).as("outcome of the rolled-back attempt").isNull();
		assertThat(attempts.get(1).getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
	}

	@Test
	void aDeadLetteredMessageShowsItInItsTrace() {
		// CA5: a zone not in the catalogue goes to the DLT at the first attempt.
		Traceparent api = Traceparent.random();
		SensorEventMessage message = message("NOPE", ENTRY, clock.instant());

		publish(Topics.SENSOR_EVENTS, message, api);

		// delivery (error) -> dead letter (outcome, reason) -> publish to the DLT, all in the API's trace.
		SpanData delivery = awaitSpan("the delivery of " + message.eventId(),
				span -> message.eventId().toString().equals(span.getAttributes().get(EVENT_ID)));
		assertThat(delivery.getTraceId()).isEqualTo(api.traceId());
		assertThat(delivery.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		SpanData deadLettered = awaitSpan("the dead-letter span",
				span -> "dead_lettered".equals(span.getAttributes().get(OUTCOME))
						&& span.getParentSpanId().equals(delivery.getSpanId()));
		assertThat(deadLettered.getAttributes().get(REASON)).isEqualTo("other");
		SpanData publishToDlt = awaitSpan("the publish to the DLT",
				span -> span.getName().contains(Topics.SENSOR_EVENTS_DLT));
		assertThat(publishToDlt.getTraceId()).isEqualTo(api.traceId());
		assertThat(publishToDlt.getParentSpanId()).isEqualTo(deadLettered.getSpanId());
	}

	@Test
	void aReplayIsTheTraceOfItsRequestNotTheOldOne() throws ExecutionException, InterruptedException {
		// Rule 7 (plan 007, section 5): the DLT message keeps the traceparent of its first attempt.
		skipPendingDeadLetters();
		Traceparent old = Traceparent.random();
		SensorEventMessage message = message("NORTE", ENTRY, clock.instant());
		publish(Topics.SENSOR_EVENTS_DLT, message, old);

		String result = RestClient.create("http://127.0.0.1:" + port).post().uri("/admin/dead-letters/replay")
				.retrieve().body(String.class);
		assertThat(result).isEqualTo("{\"replayed\":1,\"skipped\":0}");

		SpanData delivery = awaitDelivery(message, "applied");
		SpanData replay = awaitSpan("the replay request span",
				span -> String.valueOf(span.getAttributes().get(URL)).endsWith("/admin/dead-letters/replay"));
		assertThat(delivery.getTraceId()).isNotEqualTo(old.traceId()).isEqualTo(replay.getTraceId());
	}

	@Test
	void eventsAreProcessedWithAHungTraceBackend() {
		// CA6: every export to HUNG_BACKEND takes 2 s (the OTLP timeout); processing does not wait for it.
		List<SensorEventMessage> messages = IntStream.range(0, 20)
				.mapToObj(i -> message("SUR", ENTRY, clock.instant()))
				.toList();

		messages.forEach(message -> publish(Topics.SENSOR_EVENTS, message, Traceparent.random()));

		Eventually.await("the 20 events are applied", () -> occupancyOf("SUR") == 20);
		assertThat(deadLetters.published()).isEmpty();
	}

	/** W3C Trace Context of a made-up upstream span. */
	record Traceparent(String traceId, String spanId) {

		static Traceparent random() {
			return new Traceparent(hex(16), hex(8));
		}

		String header() {
			return "00-" + traceId + "-" + spanId + "-01";
		}

		private static String hex(int bytes) {
			byte[] random = new byte[bytes];
			ThreadLocalRandom.current().nextBytes(random);
			return HexFormat.of().formatHex(random);
		}

	}

	/** As the API publishes it, with the given traceparent (null: none), waiting for the broker. */
	private void publish(String topic, SensorEventMessage message, Traceparent traceparent) {
		ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.zoneId(),
				json.writeValueAsString(message));
		record.headers().add(Topics.MESSAGE_VERSION_HEADER, Topics.MESSAGE_VERSION.getBytes(UTF_8));
		if (traceparent != null) {
			record.headers().add("traceparent", traceparent.header().getBytes(UTF_8));
		}
		try (Producer<String, String> producer = producers.createProducer()) {
			producer.send(record).get();
		}
		catch (ExecutionException ex) {
			throw new IllegalStateException("Could not publish the test message", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** Moves the replay group to the end of the DLT: only what this test puts there is replayed. */
	private void skipPendingDeadLetters() {
		Properties manualCommit = new Properties();
		manualCommit.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		// DeadLetterReplayer.GROUP, package-private in deadletter.
		try (Consumer<String, String> consumer = consumers.createConsumer("crowdpulse-dlt-replay", "-test", null,
				manualCommit)) {
			List<TopicPartition> partitions = consumer.partitionsFor(Topics.SENSOR_EVENTS_DLT).stream()
					.map(info -> new TopicPartition(info.topic(), info.partition()))
					.toList();
			Map<TopicPartition, OffsetAndMetadata> end = new HashMap<>();
			consumer.endOffsets(partitions).forEach((partition, offset) -> end.put(partition, new OffsetAndMetadata(offset)));
			consumer.commitSync(end);
		}
	}

	private SpanData awaitDelivery(SensorEventMessage message, String outcome) {
		return awaitSpan("a span of " + message.eventId() + " with outcome " + outcome,
				span -> message.eventId().toString().equals(span.getAttributes().get(EVENT_ID))
						&& outcome.equals(span.getAttributes().get(OUTCOME)));
	}

	private SpanData awaitSpan(String what, Predicate<SpanData> matches) {
		try {
			Eventually.await(what, Duration.ofSeconds(15),
					() -> spans.getFinishedSpanItems().stream().anyMatch(matches));
		}
		catch (AssertionError ex) {
			throw new AssertionError(ex.getMessage() + "; exported: " + spans.getFinishedSpanItems().stream()
					.map(span -> span.getName() + " " + span.getTraceId() + "/" + span.getSpanId() + "<-"
							+ span.getParentSpanId() + " " + span.getAttributes())
					.toList(), ex);
		}
		return spans.getFinishedSpanItems().stream().filter(matches).findFirst().orElseThrow();
	}

	private int occupancyOf(String zoneId) {
		return jdbc.sql("SELECT occupancy FROM zone_occupancy WHERE zone_id = :zoneId")
				.param("zoneId", zoneId).query(Integer.class).single();
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
