package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CreateRouteRequest;
import com.sriram.trafficpilot.dto.RouteResponse;
import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import jakarta.validation.Valid;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;

@RestController
@RequestMapping("/admin/routes")
public class AdminRouteController {

	private final GatewayRouteRepository routeRepository;
	private final RegisteredServiceRepository serviceRepository;
	private final ApplicationEventPublisher eventPublisher;

	public AdminRouteController(
			GatewayRouteRepository routeRepository,
			RegisteredServiceRepository serviceRepository,
			ApplicationEventPublisher eventPublisher
	) {
		this.routeRepository = routeRepository;
		this.serviceRepository = serviceRepository;
		this.eventPublisher = eventPublisher;
	}

	@GetMapping
	public Flux<RouteResponse> listRoutes() {
		return routeRepository.findAll().flatMap(this::toRouteResponse);
	}

	@GetMapping("/{id}")
	public Mono<RouteResponse> getRoute(@PathVariable Long id) {
		return routeRepository.findById(id)
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found")))
				.flatMap(this::toRouteResponse);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<RouteResponse> createRoute(@Valid @RequestBody CreateRouteRequest request) {
		String routeId = resolveRouteId(request);
		return routeRepository.existsByRouteId(routeId)
				.flatMap(exists -> {
					if (exists) {
						return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Route ID already exists"));
					}
					return serviceRepository.findByName(request.getServiceName())
							.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found")))
							.flatMap(service -> routeRepository.save(toRoute(routeId, request, service))
									.doOnSuccess(savedRoute -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)))
									.map(savedRoute -> RouteResponse.from(savedRoute, service)));
				});
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteRoute(@PathVariable Long id) {
		return routeRepository.deleteById(id)
				.doOnSuccess(ignored -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
	}

	private String resolveRouteId(CreateRouteRequest request) {
		if (request.getRouteId() != null && !request.getRouteId().isBlank()) {
			return request.getRouteId();
		}
		return request.getPath()
				.toLowerCase(Locale.ROOT)
				.replace("/**", "")
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-|-$", "") + "-route";
	}

	private GatewayRoute toRoute(String routeId, CreateRouteRequest request, RegisteredService service) {
		GatewayRoute route = new GatewayRoute();
		route.setRouteId(routeId);
		route.setPath(request.getPath());
		route.setServiceId(service.getId());
		route.setStripPrefix(request.getStripPrefix());
		route.setAuthRequired(request.getAuthRequired());
		route.setRateLimitPerMinute(request.getRateLimitPerMinute());
		route.setEnabled(request.getEnabled());
		return route;
	}

	private Mono<RouteResponse> toRouteResponse(GatewayRoute route) {
		return serviceRepository.findById(route.getServiceId())
				.map(service -> RouteResponse.from(route, service));
	}
}