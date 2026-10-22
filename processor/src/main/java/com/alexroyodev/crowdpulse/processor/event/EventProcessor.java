package com.alexroyodev.crowdpulse.processor.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alexroyodev.crowdpulse.processor.alert.Alert;
import com.alexroyodev.crowdpulse.processor.alert.AlertPolicy;
import com.alexroyodev.crowdpulse.processor.alert.AlertRepository;
import com.alexroyodev.crowdpulse.processor.occupancy.LockedZone;
import com.alexroyodev.crowdpulse.processor.occupancy.OccupancyChange;
import com.alexroyodev.crowdpulse.processor.occupancy.OccupancyRepository;
import com.alexroyodev.crowdpulse.shared.AlertLevel;
import com.alexroyodev.crowdpulse.shared.Percentages;
import com.alexroyodev.crowdpulse.shared.SensorEventMessage;

/**
 * Applies one accepted event (spec 003, rule 1: the rules of specs 001 and 002, now outside the
 * HTTP request). Until spec 003 this was the API's EventService; only the input and processedAt
 * are new.
 */
@Service
public class EventProcessor {

	private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

	private final OccupancyRepository occupancies;
	private final SensorEventRepository events;
	private final AlertRepository alerts;
	private final AlertPolicy alertPolicy;
	private final Clock clock;

	EventProcessor(OccupancyRepository occupancies, SensorEventRepository events, AlertRepository alerts,
			AlertPolicy alertPolicy, Clock clock) {
		this.occupancies = occupancies;
		this.events = events;
		this.alerts = alerts;
		this.alertPolicy = alertPolicy;
		this.clock = clock;
	}

	/**
	 * Stores the event, updates the zone's occupancy and records an alert level change, all in
	 * one transaction (spec 001 rule 3, spec 002 rule 7): either all happen or none does. A
	 * repeated eventId changes nothing (spec 001 rule 2), so a redelivered message is harmless
	 * (spec 003, rule 4).
	 *
	 * @throws UnknownZoneException if the zone is not in the catalogue
	 */
	@Transactional
	public void process(SensorEventMessage message) {
		Instant processedAt = clock.instant();

		// 1. Lock the zone row first, so two copies of the same event are serialised too.
		LockedZone zone = occupancies.lockByZoneId(message.zoneId())
				.orElseThrow(() -> new UnknownZoneException(message.zoneId()));

		// 2. Pure domain decision: new occupancy and whether this is an anomaly.
		OccupancyChange change = zone.occupancy().apply(message.type(), message.occurredAt());

		// 3. Store the event; if the eventId already exists, stop here without touching anything.
		SensorEvent event = new SensorEvent(message.eventId(), message.sensorId(), message.zoneId(), message.type(),
				message.occurredAt(), message.receivedAt(), processedAt, change.anomaly());
		if (!events.insertIfAbsent(event)) {
			// DEBUG, not INFO: duplicates are expected with at-least-once delivery and would flood the log.
			log.atDebug().setMessage("Duplicate event ignored")
					.addKeyValue("eventId", message.eventId())
					.log();
			return;
		}

		// 4. Pure domain decision: the alert level for the new occupancy (thresholds + hysteresis).
		int occupancy = change.result().value();
		AlertLevel level = alertPolicy.next(zone.alertLevel(), occupancy, zone.capacity());

		// 5. Apply the new occupancy and level.
		occupancies.update(message.zoneId(), change.result(), level);

		// 6. Record the level change, if any (spec 002, rule 6). It is detected now, when processed.
		if (level != zone.alertLevel()) {
			alerts.insert(new Alert(UUID.randomUUID(), message.zoneId(), zone.alertLevel(), level, occupancy,
					zone.capacity(), message.eventId(), message.occurredAt(), processedAt));
			log.atWarn().setMessage("Alert level changed")
					.addKeyValue("zoneId", message.zoneId())
					.addKeyValue("from", zone.alertLevel())
					.addKeyValue("to", level)
					.addKeyValue("occupancyPct", Percentages.of(occupancy, zone.capacity()))
					.addKeyValue("eventId", message.eventId())
					.log();
		}

		if (change.anomaly()) {
			log.atWarn().setMessage("EXIT with zone already empty: occupancy kept at 0")
					.addKeyValue("eventId", message.eventId())
					.addKeyValue("zoneId", message.zoneId())
					.addKeyValue("sensorId", message.sensorId())
					.log();
		}

		log.atDebug().setMessage("Event processed")
				.addKeyValue("eventId", message.eventId())
				.addKeyValue("zoneId", message.zoneId())
				.addKeyValue("latencyMs", processedAt.toEpochMilli() - message.receivedAt().toEpochMilli())
				.log();
	}

}
