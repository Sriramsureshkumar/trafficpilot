package com.sriram.trafficpilot.security;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Loads services and routes on behalf of a caller. Anything outside the caller's team
 * is reported as not found so other teams' resources are not revealed.
 */
@Component
public class ResourceAccess {

	private final RegisteredServiceRepository serviceRepository;
	private final GatewayRouteRepository routeRepository;

	public ResourceAccess(RegisteredServiceRepository serviceRepository, GatewayRouteRepository routeRepository) {
		this.serviceRepository = serviceRepository;
		this.routeRepository = routeRepository;
	}

	public Mono<RegisteredService> service(Long id, AuthPrincipal principal) {
		return serviceRepository.findById(id)
				.filter(service -> principal.canAccessTeam(service.getTeamId()))
				.switchIfEmpty(Mono.error(notFound("Service not found")));
	}

	public Mono<RegisteredService> serviceByName(String name, AuthPrincipal principal) {
		return serviceRepository.findByName(name)
				.filter(service -> principal.canAccessTeam(service.getTeamId()))
				.switchIfEmpty(Mono.error(notFound("Service not found: " + name)));
	}

	public Mono<GatewayRoute> route(Long id, AuthPrincipal principal) {
		return routeRepository.findById(id)
				.flatMap(route -> serviceRepository.findById(route.getServiceId())
						.filter(service -> principal.canAccessTeam(service.getTeamId()))
						.map(service -> route))
				.switchIfEmpty(Mono.error(notFound("Route not found: " + id)));
	}

	private ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}
}
