package com.alexroyodev.crowdpulse.event;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alexroyodev.crowdpulse.occupancy.Occupancy;
import com.alexroyodev.crowdpulse.occupancy.OccupancyChange;
import com.alexroyodev.crowdpulse.occupancy.OccupancyRepository;
import com.alexroyodev.crowdpulse.zone.UnknownZoneException;

@Service
class EventService {

	private static final Logger log = LoggerFactory.getLogger(EventService.class);

	private final OccupancyRepository occupancies;
	private final SensorEventRepository events;
	private final Clock clock;

	EventService(OccupancyRepository occupancies, SensorEventRepository events, Clock clock) {
		this.occupancies = occupancies;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Stores the event and updates the zone's occupancy in one transaction (rule 3): either
	 * both happen or neither does. A repeated eventId changes nothing (rule 2).
	 */
	@Transactional
	public void accept(EventRequest request) {
		Instant receivedAt = clock.instant();

		// 1. Lock the zone row first, so two copies of the same event are serialised too.
		Occupancy current = occupancies.lockByZoneId(request.zoneId())
				.orElseThrow(() -> {
					log.atWarn().setMessage("Event rejected: unknown zone")
							.addKeyValue("eventId", request.eventId())
							.addKeyValue("zoneId", request.zoneId())
							.log();
					return new UnknownZoneException(request.zoneId());
				});

		// 2. Pure domain decision: new occupancy and whether this is an anomaly.
		OccupancyChange change = current.apply(request.type(), request.occurredAt());

		// 3. Store the event; if the eventId already exists, stop here without touching anything.
		SensorEvent event = new SensorEvent(request.eventId(), request.sensorId(), request.zoneId(),
				request.type(), request.occurredAt(), receivedAt, change.anomaly());
		if (!events.insertIfAbsent(event)) {
			log.atInfo().setMessage("Duplicate event ignored")
					.addKeyValue("eventId", request.eventId())
					.log();
			return;
		}

		// 4. Apply the new occupancy.
		occupancies.update(request.zoneId(), change.result());

		if (change.anomaly()) {
			log.atWarn().setMessage("EXIT with zone already empty: occupancy kept at 0")
					.addKeyValue("eventId", request.eventId())
					.addKeyValue("zoneId", request.zoneId())
					.addKeyValue("sensorId", request.sensorId())
					.log();
		}
	}

}
