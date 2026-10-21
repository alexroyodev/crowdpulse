package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

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
	void seedsTheZonesOfSpec001AndTheDemoZoneOfSpec002() {
		List<ZoneRow> zones = jdbc.sql("SELECT zone_id, name, capacity FROM zone ORDER BY zone_id")
				.query(ZoneRow.class)
				.list();

		assertThat(zones).containsExactly(
				new ZoneRow("ESTE", "Lateral Este", 15000),
				new ZoneRow("NORTE", "Fondo Norte", 20000),
				new ZoneRow("OESTE", "Lateral Oeste", 15000),
				new ZoneRow("PALCO", "Palco VIP", 200),
				new ZoneRow("SUR", "Fondo Sur", 20000));
	}

	@Test
	void everyZoneStartsEmptyNormalAndWithoutHistory() {
		Integer zonesWithState = jdbc.sql("""
				SELECT count(*) FROM zone_occupancy
				WHERE occupancy = 0 AND last_event_at IS NULL AND alert_level = 'NORMAL'
				""")
				.query(Integer.class)
				.single();
		Integer events = jdbc.sql("SELECT count(*) FROM sensor_event").query(Integer.class).single();
		Integer alerts = jdbc.sql("SELECT count(*) FROM alert").query(Integer.class).single();

		assertThat(zonesWithState).isEqualTo(5);
		assertThat(events).isZero();
		assertThat(alerts).isZero();
	}

	@Test
	void databaseRejectsAnUnknownAlertLevel() {
		assertThatThrownBy(() -> jdbc.sql("UPDATE zone_occupancy SET alert_level = 'PANIC' WHERE zone_id = 'NORTE'").update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("zone_occupancy_alert_level_check");
	}

	@Test
	@Transactional // rolled back at the end: the rows below never reach the other tests
	void oneEventCausesAtMostOneLevelChange() {
		UUID eventId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO sensor_event (event_id, sensor_id, zone_id, type, occurred_at, received_at)
				VALUES (:id, 'NORTE-P01', 'NORTE', 'ENTRY', now(), now())
				""").param("id", eventId).update();
		String insertAlert = """
				INSERT INTO alert (alert_id, zone_id, from_level, to_level, occupancy, capacity, event_id,
				                   occurred_at, detected_at)
				VALUES (:alertId, 'NORTE', 'NORMAL', 'WARNING', 17000, 20000, :eventId, now(), now())
				""";
		jdbc.sql(insertAlert).param("alertId", UUID.randomUUID()).param("eventId", eventId).update();

		assertThatThrownBy(() -> jdbc.sql(insertAlert).param("alertId", UUID.randomUUID()).param("eventId", eventId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("alert_event_id_key");
	}

	@Test
	void databaseRejectsNegativeOccupancy() {
		assertThatThrownBy(() -> jdbc.sql("UPDATE zone_occupancy SET occupancy = -1 WHERE zone_id = 'NORTE'").update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("zone_occupancy_occupancy_check");
	}

}
