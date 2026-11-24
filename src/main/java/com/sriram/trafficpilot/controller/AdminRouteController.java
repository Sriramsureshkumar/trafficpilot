package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.audit.AuditService;
import com.sriram.trafficpilot.dto.CreateRouteRequest;
import com.sriram.trafficpilot.dto.RouteResponse;
import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.ResourceAccess;
import jakarta.validation.Valid;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
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
	private final CanaryTargetRepository canaryTargetRepository;
	private final ResourceAccess access;
	private final ApplicationEventPublisher eventPublisher;
	private final AuditService audit;
	private final TransactionalOperator transactions;

	public AdminRouteController(
			GatewayRouteRepository routeRepository,
			RegisteredServiceRepository serviceRepository,
			CanaryTargetRepository canaryTargetRepository,
			ResourceAccess access,
			ApplicationEventPublisher eventPublisher,
			AuditService audit,
			TransactionalOperator transactions
	) {
		this.routeRepository = routeRepository;
		this.serviceRepository = serviceRepository;
		this.canaryTargetRepository = canaryTargetRepository;
		this.access = access;
		this.eventPublisher = eventPublisher;
		this.audit = audit;
		this.transactions = transactions;
	}

	@GetMapping
	public Flux<RouteResponse> listRoutes(ServerWebExchange exchange) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return routeRepository.findAll().concatMap(route -> serviceRepository.findById(route.getServiceId())
				.filter(service -> principal.canAccessTeam(service.getTeamId()))
				.map(service -> RouteResponse.from(route, service)));
	}

	@GetMapping("/{id}")
	public Mono<RouteResponse> getRoute(@PathVariable Long id, ServerWebExchange exchange) {
		return access.route(id, AuthPrincipal.of(exchange)).flatMap(this::toRouteResponse);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<RouteResponse> createRoute(@Valid @RequestBody CreateRouteRequest request, ServerWebExchange exchange) {
		String routeId = resolveRouteId(request);
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return access.serviceByName(request.getServiceName(), principal)
				.flatMap(service -> routeRepository.existsByRouteId(routeId)
						.flatMap(exists -> {
							if (exists) {
								return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Route ID already exists"));
							}
							return routeRepository.existsByPath(request.getPath());
						})
						.flatMap(pathTaken -> {
							if (pathTaken) {
								return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Path is already used by another route"));
							}
							return routeRepository.save(toRoute(routeId, request, service))
									.flatMap(saved -> audit.record(principal, service.getTeamId(), saved, "ROUTE_CREATED",
											routeDetails(saved, service)).thenReturn(saved))
									.map(savedRoute -> RouteResponse.from(savedRoute, service));
						}))
				.as(transactions::transactional)
				.doOnSuccess(saved -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteRoute(@PathVariable Long id, ServerWebExchange exchange) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return access.route(id, principal)
				.flatMap(route -> access.service(route.getServiceId(), principal)
						.flatMap(service -> canaryTargetRepository.deleteByRouteId(route.getId())
								.then(routeRepository.delete(route))
								.then(audit.record(principal, service.getTeamId(), route, "ROUTE_DELETED",
										routeDetails(route, service)))))
				.as(transactions::transactional)
				.doOnSuccess(ignored -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
	}

	private String routeDetails(GatewayRoute route, RegisteredService service) {
		return "path=" + route.getPath() + "; service=" + service.getName()
				+ "; stripPrefix=" + route.getStripPrefix() + "; authRequired=" + route.getAuthRequired()
				+ "; rateLimit=" + route.getRateLimitPerMinute() + "; enabled=" + route.getEnabled();
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