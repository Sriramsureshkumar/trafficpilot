package com.sriram.trafficpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("registered_services")
public class RegisteredService {

	@Id
	private Long id;
	private String name;
	private String baseUrl;
	private String healthCheckPath;
	private String ownerTeam;
	private String status;
	private Instant createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}