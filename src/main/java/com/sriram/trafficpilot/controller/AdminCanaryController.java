package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.dto.CanaryTargetEntry;
import com.sriram.trafficpilot.dto.CanaryTargetResponse;
import com.sriram.trafficpilot.dto.SetCanaryRequest;
import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import jakarta.validation.Valid;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase 8 — Canary Routing Admin APIs
 *
 * All endpoints are under /admin/routes/{routeId}/canary
 * (routeId is the numeric gateway_routes.id PK).
 *
 * Endpoints:
 *   GET    /admin/routes/{routeId}/canary          — list current canary targets
 *   PUT    /admin/routes/{routeId}/canary          — set (replace) canary targets
 *   DELETE /admin/routes/{routeId}/canary          — remove all canary targets (revert to normal routing)
 */
@RestController
@RequestMapping("/admin/routes/{routeId}/canary")
public class AdminCanaryController {

	private final GatewayRouteRepository routeRepository;
	private final CanaryTargetRepository canaryTargetRepository;
	private final RegisteredServiceRepository serviceRepository;
	private final ApplicationEventPublisher eventPublisher;

	public AdminCanaryController(
			GatewayRouteRepository routeRepository,
			CanaryTargetRepository canaryTargetRepository,
			RegisteredServiceRepository serviceRepository,
			ApplicationEventPublisher eventPublisher
	) {
		this.routeRepository = routeRepository;
		this.canaryTargetRepository = canaryTargetRepository;
		this.serviceRepository = serviceRepository;
		this.eventPublisher = eventPublisher;
	}

	// -------------------------------------------------------------------------
	// GET /admin/routes/{routeId}/canary
	// -------------------------------------------------------------------------

	@GetMapping
	public Flux<CanaryTargetResponse> listCanaryTargets(@PathVariable Long routeId) {
		return routeRepository.findById(routeId)
				.switchIfEmpty(Mono.error(routeNotFound(routeId)))
				.thenMany(canaryTargetRepository.findByRouteId(routeId)
						.flatMap(target -> serviceRepository.findById(target.getServiceId())
								.map(service -> CanaryTargetResponse.from(target, service))));
	}

	// -------------------------------------------------------------------------
	// PUT /admin/routes/{routeId}/canary
	// Replaces ALL canary targets for the route atomically.
	// -------------------------------------------------------------------------

	@PutMapping
	@ResponseStatus(HttpStatus.OK)
	public Flux<CanaryTargetResponse> setCanaryTargets(
			@PathVariable Long routeId,
			@Valid @RequestBody SetCanaryRequest request
	) {
		// 1. Validate weight sum == 100
		int totalWeight = request.getTargets().stream()
				.mapToInt(CanaryTargetEntry::getWeight)
				.sum();
		if (totalWeight != 100) {
			return Flux.error(new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Canary target weights must sum to 100 (got " + totalWeight + ")"));
		}

		// 2. Verify route exists
		return routeRepository.findById(routeId)
				.switchIfEmpty(Mono.error(routeNotFound(routeId)))
				.flatMapMany(route ->
						// 3. Resolve each serviceName → service, fail fast if any is missing
						Flux.fromIterable(request.getTargets())
								.flatMap(entry -> serviceRepository.findByName(entry.getServiceName())
										.switchIfEmpty(Mono.error(new ResponseStatusException(
												HttpStatus.NOT_FOUND,
												"Service not found: " + entry.getServiceName())))
										.map(service -> {
											CanaryTarget target = new CanaryTarget();
											target.setRouteId(routeId);
											target.setServiceId(service.getId());
											target.setWeight(entry.getWeight());
											target.setEnabled(true);
											return target;
										}))
								.collectList()
								.flatMapMany(newTargets ->
										// 4. Delete existing targets then save new ones
										canaryTargetRepository.deleteByRouteId(routeId)
												.thenMany(canaryTargetRepository.saveAll(newTargets))
												.flatMap(saved -> serviceRepository.findById(saved.getServiceId())
														.map(svc -> CanaryTargetResponse.from(saved, svc)))
												.doOnComplete(() ->
														eventPublisher.publishEvent(new RefreshRoutesEvent(this))))
				);
	}

	// -------------------------------------------------------------------------
	// DELETE /admin/routes/{routeId}/canary
	// Removes all canary targets → route reverts to its default service.
	// -------------------------------------------------------------------------

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> removeCanaryTargets(@PathVariable Long routeId) {
		return routeRepository.findById(routeId)
				.switchIfEmpty(Mono.error(routeNotFound(routeId)))
				.then(canaryTargetRepository.deleteByRouteId(routeId))
				.doOnSuccess(ignored -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private ResponseStatusException routeNotFound(Long routeId) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + routeId);
	}
}
