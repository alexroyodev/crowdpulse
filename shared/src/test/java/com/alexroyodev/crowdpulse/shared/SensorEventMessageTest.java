package com.alexroyodev.crowdpulse.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Contract test of message version 1 (plan 003, section 4): the JSON written here by hand is
 * what travels on the topic. If a change to the record alters it, this test fails before the
 * API and the processor stop understanding each other.
 */
class SensorEventMessageTest {

	private static final String JSON = """
			{"eventId":"0b6c3f1e-7a52-4c1d-9b8e-2f4a6d8c0e13","sensorId":"S-NORTE-01","zoneId":"NORTE","type":"ENTRY",
			 "occurredAt":"2026-10-22T18:00:00Z","receivedAt":"2026-10-22T18:00:00.123Z"}
			""";

	private static final SensorEventMessage MESSAGE = new SensorEventMessage(
			UUID.fromString("0b6c3f1e-7a52-4c1d-9b8e-2f4a6d8c0e13"), "S-NORTE-01", "NORTE", EventType.ENTRY,
			Instant.parse("2026-10-22T18:00:00Z"), Instant.parse("2026-10-22T18:00:00.123Z"));

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void readsTheJsonOfVersionOne() {
		assertThat(mapper.readValue(JSON, SensorEventMessage.class)).isEqualTo(MESSAGE);
	}

	@Test
	void writesTheJsonOfVersionOne() {
		assertThat(mapper.readTree(mapper.writeValueAsString(MESSAGE))).isEqualTo(mapper.readTree(JSON));
	}

}
