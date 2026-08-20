package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.CanaryTarget;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CanaryTargetRepository extends ReactiveCrudRepository<CanaryTarget, Long> {

	/** All enabled targets for a given gateway_routes PK */
	Flux<CanaryTarget> findByRouteIdAndEnabledTrue(Long routeId);

	/** All targets (enabled or not) for a route — used for admin listing */
	Flux<CanaryTarget> findByRouteId(Long routeId);

	/** Existence check before setting canary on a route */
	Mono<Boolean> existsByRouteIdAndEnabledTrue(Long routeId);

	/** Delete all targets for a route (e.g. when the route itself is deleted) */
	Mono<Void> deleteByRouteId(Long routeId);
}
