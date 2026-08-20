package com.sriram.trafficpilot.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateServiceRequest {

	@NotBlank
	private String name;
	@NotBlank
	private String baseUrl;
	@NotBlank
	private String healthCheckPath;
	@NotBlank
	private String ownerTeam;
	private String status = "UNKNOWN";

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getHealthCheckPath() {
		return healthCheckPath;
	}

	public void setHealthCheckPath(String healthCheckPath) {
		this.healthCheckPath = healthCheckPath;
	}

	public String getOwnerTeam() {
		return ownerTeam;
	}

	public void setOwnerTeam(String ownerTeam) {
		this.ownerTeam = ownerTeam;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
}