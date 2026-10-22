package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spec 003, plan section 6: when V5 is applied to a database that already has events, each one
 * gets its received_at as processed_at, because phase 1 processed it in the request that
 * received it. Migrates to V4 only, stores an event, then applies the rest.
 */
class ProcessedAtMigrationIT {

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
	void existingEventsWereProcessedWhenTheyWereReceived() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
		Flyway.configure().dataSource(dataSource).target("4").load().migrate();
		JdbcClient jdbc = JdbcClient.create(dataSource);
		UUID eventId = UUID.randomUUID();
		OffsetDateTime receivedAt = OffsetDateTime.parse("2026-10-21T18:30:05.123456Z");
		jdbc.sql("""
				INSERT INTO sensor_event (event_id, sensor_id, zone_id, type, occurred_at, received_at)
				VALUES (:id, 'NORTE-P01', 'NORTE', 'ENTRY', :receivedAt, :receivedAt)
				""").param("id", eventId).param("receivedAt", receivedAt).update();

		Flyway.configure().dataSource(dataSource).load().migrate();

		OffsetDateTime processedAt = jdbc.sql("SELECT processed_at FROM sensor_event WHERE event_id = :id")
				.param("id", eventId)
				.query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class))
				.single();
		assertThat(processedAt.toInstant()).isEqualTo(receivedAt.toInstant());
	}

}
