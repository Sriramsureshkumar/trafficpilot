package com.sriram.trafficpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class CreateServiceRequest {

	@NotBlank
	private String name;
	@NotBlank
	@Pattern(regexp = "^https?://[^\\s/:]+(:\\d{1,5})?(/\\S*)?$", message = "must be a full URL such as http://localhost:9001")
	private String baseUrl;
	@NotBlank
	@Pattern(regexp = "^/(?!/)[^\\s?#]*$", message = "must be an absolute path such as /health")
	private String healthCheckPath;
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