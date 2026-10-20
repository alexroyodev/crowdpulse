package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MigrationIT {

	private final JdbcClient jdbc;

	@Autowired
	MigrationIT(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record ZoneRow(String zoneId, String name, int capacity) {
	}

	@Test
	void seedsTheFourZonesOfTheSpec() {
		List<ZoneRow> zones = jdbc.sql("SELECT zone_id, name, capacity FROM zone ORDER BY zone_id")
				.query(ZoneRow.class)
				.list();

		assertThat(zones).containsExactly(
				new ZoneRow("ESTE", "Lateral Este", 15000),
				new ZoneRow("NORTE", "Fondo Norte", 20000),
				new ZoneRow("OESTE", "Lateral Oeste", 15000),
				new ZoneRow("SUR", "Fondo Sur", 20000));
	}

	@Test
	void everyZoneStartsEmptyAndWithoutEvents() {
		Integer zonesWithState = jdbc.sql("""
				SELECT count(*) FROM zone_occupancy
				WHERE occupancy = 0 AND last_event_at IS NULL
				""")
				.query(Integer.class)
				.single();
		Integer events = jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();

		assertThat(zonesWithState).isEqualTo(4);
		assertThat(events).isZero();
	}

	@Test
	void databaseRejectsNegativeOccupancy() {
		assertThatThrownBy(() -> jdbc.sql("UPDATE zone_occupancy SET occupancy = -1 WHERE zone_id = 'NORTE'").update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("zone_occupancy_occupancy_check");
	}

}
