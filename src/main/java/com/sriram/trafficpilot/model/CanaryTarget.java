package com.sriram.trafficpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Represents a single weighted backend target for a canary route.
 *
 * A route can have multiple CanaryTargets (one per service version).
 * Weights should sum to 100 across all targets for a given routeId.
 *
 * Example:
 *   route: /payments/**
 *     CanaryTarget { serviceId: 1 (payment-v1), weight: 90 }
 *     CanaryTarget { serviceId: 2 (payment-v2), weight: 10 }
 */
@Table("canary_targets")
public class CanaryTarget {

	@Id
	private Long id;

	/** FK → gateway_routes.id (the numeric PK, not the route_id string) */
	private Long routeId;

	/** FK → registered_services.id */
	private Long serviceId;

	/** Percentage of traffic to send to this service version (0–100). */
	private Integer weight;

	private Boolean enabled;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getRouteId() {
		return routeId;
	}

	public void setRouteId(Long routeId) {
		this.routeId = routeId;
	}

	public Long getServiceId() {
		return serviceId;
	}

	public void setServiceId(Long serviceId) {
		this.serviceId = serviceId;
	}

	public Integer getWeight() {
		return weight;
	}

	public void setWeight(Integer weight) {
		this.weight = weight;
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}
}
