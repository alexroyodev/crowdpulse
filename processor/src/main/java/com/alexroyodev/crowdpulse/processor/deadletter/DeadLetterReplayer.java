package com.alexroyodev.crowdpulse.processor.deadletter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import com.alexroyodev.crowdpulse.processor.event.ContentFailure;
import com.alexroyodev.crowdpulse.shared.Topics;

/**
 * Spec 004, D2: puts the messages of the dead-letter topic back on the events topic, once their
 * cause has been fixed. It reads the DLT with its own consumer group, so each message is replayed
 * once: a second call replays only what arrived since. Offsets are committed after republishing,
 * as the processor does; a message republished twice is a harmless duplicate (rule 5).
 * Spec 005: failures in the content of a message are skipped, since no fix of the environment
 * helps them, unless {@code all}, which replays the whole DLT from the beginning.
 */
@Service
public class DeadLetterReplayer {

	static final String GROUP = "crowdpulse-dlt-replay";

	/** Headers Spring Kafka adds when dead-lettering (original topic, exception...): not part of the event. */
	private static final String DEAD_LETTER_HEADERS_PREFIX = "kafka_dlt-";

	private static final Duration MAX_DURATION = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayer.class);

	private final ConsumerFactory<String, String> consumers;
	private final KafkaTemplate<String, String> kafka;

	DeadLetterReplayer(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
		this.consumers = consumers;
		this.kafka = kafka;
	}

	/** @param skipped content failures left in the DLT (always 0 when replaying all) */
	public record ReplayResult(int replayed, int skipped) {
	}

	/**
	 * Replays what is in the DLT now: from where the previous replay stopped, skipping content
	 * failures; or, with {@code all}, everything from the beginning. Skipped messages stay in the
	 * DLT, but the group moves past them (nothing is ever deleted).
	 */
	public synchronized ReplayResult replay(boolean all) throws ExecutionException, InterruptedException {
		// The Kafka client commits on its own by default (only listener containers turn that off):
		// here offsets must be committed by hand, after republishing.
		Properties manualCommit = new Properties();
		manualCommit.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "-replay", null, manualCommit)) {
			List<TopicPartition> partitions = consumer.partitionsFor(Topics.SENSOR_EVENTS_DLT).stream()
					.map(info -> new TopicPartition(info.topic(), info.partition()))
					.toList();
			// Assigned, not subscribed: no group membership. Without a committed offset it starts at
			// the beginning (auto-offset-reset: earliest).
			consumer.assign(partitions);
			if (all) {
				consumer.seekToBeginning(partitions);
			}
			Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

			Map<TopicPartition, OffsetAndMetadata> replayedUpTo = new HashMap<>();
			int replayed = 0;
			int skipped = 0;
			long deadline = System.nanoTime() + MAX_DURATION.toNanos();
			while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
				if (System.nanoTime() > deadline) {
					throw new TimeoutException("Dead-letter replay did not finish in " + MAX_DURATION.toSeconds() + " s");
				}
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
					TopicPartition partition = new TopicPartition(record.topic(), record.partition());
					if (record.offset() < end.get(partition)) {
						if (!all && ContentFailure.isContentFailure(causeOf(record))) {
							skipped++;
						}
						else {
							republish(record);
							replayed++;
						}
						replayedUpTo.put(partition, new OffsetAndMetadata(record.offset() + 1));
					}
				}
			}
			if (!replayedUpTo.isEmpty()) {
				consumer.commitSync(replayedUpTo);
			}
			log.atWarn().setMessage("Dead-letter topic replayed")
					.addKeyValue("replayed", replayed)
					.addKeyValue("skipped", skipped)
					.addKeyValue("all", all)
					.log();
			return new ReplayResult(replayed, skipped);
		}
	}

	/** The class of the failure's cause, as Spring Kafka recorded it when dead-lettering. */
	private static String causeOf(ConsumerRecord<String, String> record) {
		Header cause = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
		return cause == null ? null : new String(cause.value(), StandardCharsets.UTF_8);
	}

	/**
	 * Same key, value and headers as the original event; without the dead-letter headers. The old
	 * traceparent is copied too, but the KafkaTemplate replaces it with the replay's own (spec 007,
	 * rule 7; checked in TracingIT).
	 */
	private void republish(ConsumerRecord<String, String> record) throws ExecutionException, InterruptedException {
		ProducerRecord<String, String> event = new ProducerRecord<>(Topics.SENSOR_EVENTS, record.key(), record.value());
		for (Header header : record.headers()) {
			if (!header.key().startsWith(DEAD_LETTER_HEADERS_PREFIX)) {
				event.headers().add(header);
			}
		}
		kafka.send(event).get();
	}

}
