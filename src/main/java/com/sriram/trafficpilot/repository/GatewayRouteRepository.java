package com.sriram.trafficpilot.repository;

import com.sriram.trafficpilot.model.GatewayRoute;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GatewayRouteRepository extends ReactiveCrudRepository<GatewayRoute, Long> {

	Flux<GatewayRoute> findByEnabledTrue();

	Mono<Boolean> existsByRouteId(String routeId);

	Mono<GatewayRoute> findByRouteId(String routeId);
}