package com.sriram.trafficpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthCheckController {

	@GetMapping("/health-check")
	public Map<String, String> healthCheck() {
		return Map.of("status", "TrafficPilot is running");
	}
}