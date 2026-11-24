package com.sriram.trafficpilot.controller;

import com.sriram.trafficpilot.audit.AuditService;
import com.sriram.trafficpilot.dto.CanaryTargetEntry;
import com.sriram.trafficpilot.dto.CanaryTargetResponse;
import com.sriram.trafficpilot.dto.SetCanaryRequest;
import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.ResourceAccess;
import jakarta.validation.Valid;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.stream.Collectors;
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

	private final ResourceAccess access;
	private final CanaryTargetRepository canaryTargetRepository;
	private final RegisteredServiceRepository serviceRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final AuditService audit;
	private final TransactionalOperator transactions;

	public AdminCanaryController(
			ResourceAccess access,
			CanaryTargetRepository canaryTargetRepository,
			RegisteredServiceRepository serviceRepository,
			ApplicationEventPublisher eventPublisher,
			AuditService audit,
			TransactionalOperator transactions
	) {
		this.access = access;
		this.canaryTargetRepository = canaryTargetRepository;
		this.serviceRepository = serviceRepository;
		this.eventPublisher = eventPublisher;
		this.audit = audit;
		this.transactions = transactions;
	}

	// -------------------------------------------------------------------------
	// GET /admin/routes/{routeId}/canary
	// -------------------------------------------------------------------------

	@GetMapping
	public Flux<CanaryTargetResponse> listCanaryTargets(@PathVariable Long routeId, ServerWebExchange exchange) {
		return access.route(routeId, AuthPrincipal.of(exchange))
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
			@Valid @RequestBody SetCanaryRequest request,
			ServerWebExchange exchange
	) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		long totalWeight = request.getTargets().stream()
				.mapToLong(CanaryTargetEntry::getWeight)
				.sum();
		if (totalWeight != 100) {
			return Flux.error(new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Canary target weights must sum to 100 (got " + totalWeight + ")"));
		}
		if (request.getTargets().stream().map(CanaryTargetEntry::getServiceName).distinct().count()
				!= request.getTargets().size()) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate canary service"));
		}
		String details = request.getTargets().stream()
				.map(entry -> entry.getServiceName() + "=" + entry.getWeight())
				.collect(Collectors.joining(", "));
		return access.route(routeId, principal)
				.flatMap(route -> access.service(route.getServiceId(), principal).flatMap(owner ->
						Flux.fromIterable(request.getTargets())
								.concatMap(entry -> access.serviceByName(entry.getServiceName(), principal)
										.map(service -> {
											if (!Objects.equals(owner.getTeamId(), service.getTeamId())) {
												throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
														"Canary services must belong to the route's team");
											}
											CanaryTarget target = new CanaryTarget();
											target.setRouteId(routeId);
											target.setServiceId(service.getId());
											target.setWeight(entry.getWeight());
											target.setEnabled(true);
											return target;
										}))
								.collectList()
								.flatMap(newTargets ->
										canaryTargetRepository.deleteByRouteId(routeId)
												.thenMany(canaryTargetRepository.saveAll(newTargets))
												.concatMap(saved -> serviceRepository.findById(saved.getServiceId())
														.map(svc -> CanaryTargetResponse.from(saved, svc)))
												.collectList()
												.flatMap(saved -> audit.record(principal, owner.getTeamId(), route,
														"CANARY_REPLACED", details).thenReturn(saved)))))
				.as(transactions::transactional)
				.doOnSuccess(saved -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)))
				.flatMapMany(Flux::fromIterable);
	}

	// -------------------------------------------------------------------------
	// DELETE /admin/routes/{routeId}/canary
	// Removes all canary targets → route reverts to its default service.
	// -------------------------------------------------------------------------

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> removeCanaryTargets(@PathVariable Long routeId, ServerWebExchange exchange) {
		AuthPrincipal principal = AuthPrincipal.of(exchange);
		return access.route(routeId, principal)
				.flatMap(route -> access.service(route.getServiceId(), principal).flatMap(owner ->
						canaryTargetRepository.deleteByRouteId(routeId)
								.then(audit.record(principal, owner.getTeamId(), route, "CANARY_REMOVED",
										"Reverted to service=" + owner.getName()))))
				.as(transactions::transactional)
				.doOnSuccess(ignored -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
	}

}
