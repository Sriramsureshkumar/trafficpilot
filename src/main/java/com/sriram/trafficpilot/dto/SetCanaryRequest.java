package com.sriram.trafficpilot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for POST/PUT /admin/routes/{id}/canary
 *
 * Example:
 * {
 *   "targets": [
 *     { "serviceName": "payment-service-v1", "weight": 90 },
 *     { "serviceName": "payment-service-v2", "weight": 10 }
 *   ]
 * }
 *
 * The weights must sum to 100 (validated in the controller/service layer).
 */
public class SetCanaryRequest {

	@NotEmpty(message = "targets list must not be empty")
	private List<@Valid @NotNull CanaryTargetEntry> targets;

	public List<CanaryTargetEntry> getTargets() {
		return targets;
	}

	public void setTargets(List<CanaryTargetEntry> targets) {
		this.targets = targets;
	}
}
