package com.alexroyodev.crowdpulse.processor.occupancy;

import static com.alexroyodev.crowdpulse.shared.EventType.ENTRY;
import static com.alexroyodev.crowdpulse.shared.EventType.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OccupancyTest {

	private static final Instant T0 = Instant.parse("2026-10-19T18:30:00Z");
	private static final Instant T1 = T0.plusSeconds(1);

	@Test
	void entryAddsOnePerson() {
		OccupancyChange change = new Occupancy(2, T0).apply(ENTRY, T1);

		assertThat(change.result().value()).isEqualTo(4); // CI CHECK: broken on purpose (spec 009, CA3)
		assertThat(change.anomaly()).isFalse();
	}

	@Test
	void exitRemovesOnePerson() {
		OccupancyChange change = new Occupancy(2, T0).apply(EXIT, T1);

		assertThat(change.result().value()).isEqualTo(1);
		assertThat(change.anomaly()).isFalse();
	}

	@Test
	void exitAtZeroStaysAtZeroAndIsAnAnomaly() {
		OccupancyChange change = new Occupancy(0, null).apply(EXIT, T1);

		assertThat(change.result().value()).isZero();
		assertThat(change.anomaly()).isTrue();
	}

	@Test
	void anomalousExitStillUpdatesLastEventAt() {
		OccupancyChange change = new Occupancy(0, T0).apply(EXIT, T1);

		assertThat(change.result().lastEventAt()).isEqualTo(T1);
	}

	@Test
	void occupancyCanExceedCapacityWithoutAnomaly() {
		// The domain knows nothing about capacity: 20 001 in a 20 000 zone is valid data (rule 6).
		OccupancyChange change = new Occupancy(20_000, T0).apply(ENTRY, T1);

		assertThat(change.result().value()).isEqualTo(20_001);
		assertThat(change.anomaly()).isFalse();
	}

	@Test
	void firstEventSetsLastEventAt() {
		OccupancyChange change = new Occupancy(0, null).apply(ENTRY, T0);

		assertThat(change.result().lastEventAt()).isEqualTo(T0);
	}

	@Test
	void olderEventDoesNotMoveLastEventAtBackwards() {
		OccupancyChange change = new Occupancy(5, T1).apply(ENTRY, T0);

		assertThat(change.result().value()).isEqualTo(6);
		assertThat(change.result().lastEventAt()).isEqualTo(T1);
	}

	@Test
	void doesNotChangeTheOriginalOccupancy() {
		Occupancy before = new Occupancy(2, T0);

		before.apply(ENTRY, T1);

		assertThat(before).isEqualTo(new Occupancy(2, T0));
	}

	@Test
	void rejectsNegativeOccupancy() {
		assertThatThrownBy(() -> new Occupancy(-1, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
