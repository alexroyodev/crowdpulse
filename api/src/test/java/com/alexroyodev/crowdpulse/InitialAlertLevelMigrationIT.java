package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spec 002, rule 11: when V3 is applied to a database that already has occupancy, every zone
 * starts at the level of that occupancy. Migrates to V2 only, loads occupancy, then applies the rest.
 */
class InitialAlertLevelMigrationIT {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	@BeforeAll
	static void startDatabase() {
		postgres.start();
	}

	@AfterAll
	static void stopDatabase() {
		postgres.stop();
	}

	@Test
	void eachZoneStartsAtTheLevelOfItsExistingOccupancy() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		Flyway.configure().dataSource(dataSource).target("2").load().migrate();
		JdbcClient jdbc = JdbcClient.create(dataSource);
		// NORTE 85.0 %, SUR 84.995 % (exact comparison: not reached), ESTE 95.0 %, OESTE 0 %.
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 17000 WHERE zone_id = 'NORTE'").update();
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 16999 WHERE zone_id = 'SUR'").update();
		jdbc.sql("UPDATE zone_occupancy SET occupancy = 14250 WHERE zone_id = 'ESTE'").update();

		Flyway.configure().dataSource(dataSource).load().migrate();

		Map<String, String> levels = jdbc.sql("SELECT zone_id, alert_level FROM zone_occupancy")
				.query((rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2)))
				.list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		assertThat(levels).containsExactlyInAnyOrderEntriesOf(Map.of(
				"NORTE", "WARNING", "SUR", "NORMAL", "ESTE", "CRITICAL", "OESTE", "NORMAL", "PALCO", "NORMAL"));
		assertThat(jdbc.sql("SELECT count(*) FROM alert").query(Integer.class).single()).isZero();
	}

}
