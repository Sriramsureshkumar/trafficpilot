package com.sriram.trafficpilot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class CreateRouteRequest {

	private String routeId;
	@NotBlank
	private String path;
	@NotBlank
	private String serviceName;
	@Min(0)
	private Integer stripPrefix = 0;
	private Boolean authRequired = false;
	@Min(1)
	private Integer rateLimitPerMinute;
	private Boolean enabled = true;

	public String getRouteId() {
		return routeId;
	}

	public void setRouteId(String routeId) {
		this.routeId = routeId;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	public Integer getStripPrefix() {
		return stripPrefix;
	}

	public void setStripPrefix(Integer stripPrefix) {
		this.stripPrefix = stripPrefix;
	}

	public Boolean getAuthRequired() {
		return authRequired;
	}

	public void setAuthRequired(Boolean authRequired) {
		this.authRequired = authRequired;
	}

	public Integer getRateLimitPerMinute() {
		return rateLimitPerMinute;
	}

	public void setRateLimitPerMinute(Integer rateLimitPerMinute) {
		this.rateLimitPerMinute = rateLimitPerMinute;
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}
}