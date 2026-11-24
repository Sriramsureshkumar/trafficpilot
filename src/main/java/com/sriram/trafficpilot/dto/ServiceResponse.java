package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.model.RegisteredService;

import java.time.Instant;

public record ServiceResponse(
		Long id,
		String name,
		String baseUrl,
		String healthCheckPath,
		String ownerTeam,
		String status,
		Instant lastCheckedAt,
		Instant createdAt
) {

	public static ServiceResponse from(RegisteredService service) {
		return new ServiceResponse(
				service.getId(),
				service.getName(),
				service.getBaseUrl(),
				service.getHealthCheckPath(),
				service.getOwnerTeam(),
				service.getStatus(),
				service.getLastCheckedAt(),
				service.getCreatedAt()
		);
	}
}