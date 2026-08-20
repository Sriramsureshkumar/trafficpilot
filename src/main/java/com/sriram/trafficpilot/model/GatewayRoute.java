package com.sriram.trafficpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("gateway_routes")
public class GatewayRoute {

	@Id
	private Long id;
	private String routeId;
	private String path;
	private Long serviceId;
	private Integer stripPrefix;
	private Boolean authRequired;
	private Integer rateLimitPerMinute;
	private Boolean enabled;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

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

	public Long getServiceId() {
		return serviceId;
	}

	public void setServiceId(Long serviceId) {
		this.serviceId = serviceId;
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