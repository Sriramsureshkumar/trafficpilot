package com.sriram.trafficpilot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A single (serviceName, weight) pair inside a SetCanaryRequest.
 */
public class CanaryTargetEntry {

	@NotBlank(message = "serviceName is required")
	private String serviceName;

	@NotNull
	@Min(value = 1, message = "weight must be at least 1")
	@Max(value = 100, message = "weight must be at most 100")
	private Integer weight;

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	public Integer getWeight() {
		return weight;
	}

	public void setWeight(Integer weight) {
		this.weight = weight;
	}
}
