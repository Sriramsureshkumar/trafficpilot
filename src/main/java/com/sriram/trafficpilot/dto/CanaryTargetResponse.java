package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.model.RegisteredService;

/**
 * Response DTO for a single canary target entry returned to the caller.
 */
public record CanaryTargetResponse(
		Long id,
		String serviceName,
		String serviceBaseUrl,
		Integer weight,
		Boolean enabled
) {
	public static CanaryTargetResponse from(CanaryTarget target, RegisteredService service) {
		return new CanaryTargetResponse(
				target.getId(),
				service.getName(),
				service.getBaseUrl(),
				target.getWeight(),
				target.getEnabled()
		);
	}
}
