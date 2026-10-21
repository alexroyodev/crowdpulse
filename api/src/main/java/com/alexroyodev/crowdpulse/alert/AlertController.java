package com.alexroyodev.crowdpulse.alert;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alexroyodev.crowdpulse.zone.ZoneNotFoundException;
import com.alexroyodev.crowdpulse.zone.ZoneQueryRepository;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** History of alert level changes, newest first (spec 002). */
@RestController
@RequestMapping("/api/v1/alerts")
class AlertController {

	private final AlertRepository alerts;
	private final ZoneQueryRepository zones;

	AlertController(AlertRepository alerts, ZoneQueryRepository zones) {
		this.alerts = alerts;
		this.zones = zones;
	}

	/**
	 * Spring MVC validates the {@code @Min}/{@code @Max} on {@code limit} by itself; a value out
	 * of range or not a number answers 400 with ProblemDetail (ApiExceptionHandler).
	 */
	@GetMapping
	List<AlertView> list(@RequestParam(required = false) String zoneId,
			@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
		if (zoneId == null) {
			return alerts.findLatest(limit).stream().map(AlertView::of).toList();
		}
		if (!zones.exists(zoneId)) {
			throw new ZoneNotFoundException(zoneId);
		}
		return alerts.findLatestOfZone(zoneId, limit).stream().map(AlertView::of).toList();
	}

}
