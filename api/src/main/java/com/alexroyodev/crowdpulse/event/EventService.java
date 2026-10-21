package com.alexroyodev.crowdpulse.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alexroyodev.crowdpulse.alert.Alert;
import com.alexroyodev.crowdpulse.alert.AlertLevel;
import com.alexroyodev.crowdpulse.alert.AlertPolicy;
import com.alexroyodev.crowdpulse.alert.AlertRepository;
import com.alexroyodev.crowdpulse.occupancy.LockedZone;
import com.alexroyodev.crowdpulse.occupancy.OccupancyChange;
import com.alexroyodev.crowdpulse.occupancy.OccupancyRepository;
import com.alexroyodev.crowdpulse.zone.UnknownZoneException;
import com.alexroyodev.crowdpulse.zone.ZoneView;

@Service
class EventService {

	private static final Logger log = LoggerFactory.getLogger(EventService.class);

	private final OccupancyRepository occupancies;
	private final SensorEventRepository events;
	private final AlertRepository alerts;
	private final AlertPolicy alertPolicy;
	private final Clock clock;

	EventService(OccupancyRepository occupancies, SensorEventRepository events, AlertRepository alerts,
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
	 * repeated eventId changes nothing (spec 001 rule 2), so it cannot change the level either.
	 */
	@Transactional
	public void accept(EventRequest request) {
		Instant receivedAt = clock.instant();

		// 1. Lock the zone row first, so two copies of the same event are serialised too.
		LockedZone zone = occupancies.lockByZoneId(request.zoneId())
				.orElseThrow(() -> {
					log.atWarn().setMessage("Event rejected: unknown zone")
							.addKeyValue("eventId", request.eventId())
							.addKeyValue("zoneId", request.zoneId())
							.log();
					return new UnknownZoneException(request.zoneId());
				});

		// 2. Pure domain decision: new occupancy and whether this is an anomaly.
		OccupancyChange change = zone.occupancy().apply(request.type(), request.occurredAt());

		// 3. Store the event; if the eventId already exists, stop here without touching anything.
		SensorEvent event = new SensorEvent(request.eventId(), request.sensorId(), request.zoneId(),
				request.type(), request.occurredAt(), receivedAt, change.anomaly());
		if (!events.insertIfAbsent(event)) {
			// DEBUG, not INFO: duplicates are expected with at-least-once producers and would flood the log.
			log.atDebug().setMessage("Duplicate event ignored")
					.addKeyValue("eventId", request.eventId())
					.log();
			return;
		}

		// 4. Pure domain decision: the alert level for the new occupancy (thresholds + hysteresis).
		int occupancy = change.result().value();
		AlertLevel level = alertPolicy.next(zone.alertLevel(), occupancy, zone.capacity());

		// 5. Apply the new occupancy and level.
		occupancies.update(request.zoneId(), change.result(), level);

		// 6. Record the level change, if any (spec 002, rule 6).
		if (level != zone.alertLevel()) {
			alerts.insert(new Alert(UUID.randomUUID(), request.zoneId(), zone.alertLevel(), level, occupancy,
					zone.capacity(), request.eventId(), request.occurredAt(), receivedAt));
			log.atWarn().setMessage("Alert level changed")
					.addKeyValue("zoneId", request.zoneId())
					.addKeyValue("from", zone.alertLevel())
					.addKeyValue("to", level)
					.addKeyValue("occupancyPct", ZoneView.percentage(occupancy, zone.capacity()))
					.addKeyValue("eventId", request.eventId())
					.log();
		}

		if (change.anomaly()) {
			log.atWarn().setMessage("EXIT with zone already empty: occupancy kept at 0")
					.addKeyValue("eventId", request.eventId())
					.addKeyValue("zoneId", request.zoneId())
					.addKeyValue("sensorId", request.sensorId())
					.log();
		}
	}

}
