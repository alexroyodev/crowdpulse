package com.alexroyodev.crowdpulse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Test helper: what was published to a topic since {@link #mark()}. The API answers 202 only after
 * Kafka confirmed the message, so once the response is back the message is already in the topic:
 * reading needs no waiting, and "nothing new" really means nothing was published.
 */
public final class TopicProbe {

	private final ConsumerFactory<String, String> consumers;
	private final String topic;
	private Map<TopicPartition, Long> mark = Map.of();

	public TopicProbe(ConsumerFactory<String, String> consumers, String topic) {
		this.consumers = consumers;
		this.topic = topic;
	}

	/** Remembers the current end of every partition. */
	public void mark() {
		try (Consumer<String, String> consumer = createConsumer()) {
			mark = consumer.endOffsets(partitions(consumer));
		}
	}

	/** Records published since {@link #mark()}, partition by partition, each in its own order. */
	public List<ConsumerRecord<String, String>> published() {
		try (Consumer<String, String> consumer = createConsumer()) {
			List<TopicPartition> partitions = partitions(consumer);
			Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
			consumer.assign(partitions);
			partitions.forEach(partition -> consumer.seek(partition, mark.getOrDefault(partition, 0L)));
			List<ConsumerRecord<String, String>> records = new ArrayList<>();
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("Could not read the published records of " + topic + " in 10 s");
				}
				consumer.poll(Duration.ofMillis(100)).forEach(records::add);
			}
			return records;
		}
	}

	/** Never commits: in the processor the factory's group is the processor's own. */
	private Consumer<String, String> createConsumer() {
		Properties noCommits = new Properties();
		noCommits.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		return consumers.createConsumer(null, "-probe", null, noCommits);
	}

	private List<TopicPartition> partitions(Consumer<String, String> consumer) {
		return consumer.partitionsFor(topic).stream()
				.map(info -> new TopicPartition(topic, info.partition()))
				.toList();
	}

}
