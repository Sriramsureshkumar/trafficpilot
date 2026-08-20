package com.sriram.trafficpilot.dto;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;

public record RouteResponse(
		Long id,
		String routeId,
		String path,
		Long serviceId,
		String serviceName,
		String serviceBaseUrl,
		Integer stripPrefix,
		Boolean authRequired,
		Integer rateLimitPerMinute,
		Boolean enabled
) {

	public static RouteResponse from(GatewayRoute route, RegisteredService service) {
		return new RouteResponse(
				route.getId(),
				route.getRouteId(),
				route.getPath(),
				route.getServiceId(),
				service.getName(),
				service.getBaseUrl(),
				route.getStripPrefix(),
				route.getAuthRequired(),
				route.getRateLimitPerMinute(),
				route.getEnabled()
		);
	}
}