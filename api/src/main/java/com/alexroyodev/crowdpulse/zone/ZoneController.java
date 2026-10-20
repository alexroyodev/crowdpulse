package com.alexroyodev.crowdpulse.zone;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/zones")
class ZoneController {

	private final ZoneQueryRepository zones;

	ZoneController(ZoneQueryRepository zones) {
		this.zones = zones;
	}

	@GetMapping
	List<ZoneView> findAll() {
		return zones.findAll();
	}

	@GetMapping("/{zoneId}")
	ZoneView findById(@PathVariable String zoneId) {
		return zones.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
	}

}
