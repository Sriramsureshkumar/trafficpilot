package com.sriram.trafficpilot.canary;

import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Phase 8 — Canary Routing Filter.
 *
 * Runs BEFORE the Gateway rewrites the URL (order -2).
 *
 * Algorithm:
 *   1. Get the matched gateway route string-id from the exchange.
 *   2. Look up the numeric gateway_routes.id from the DB.
 *   3. Load all enabled canary_targets for that route.
 *   4. If canary targets exist, pick one using weighted random selection.
 *   5. Look up the chosen service and redirect the request URI to its base_url.
 *   6. If no canary targets exist, pass through untouched (normal routing).
 *
 * The X-Canary-Service response header is added for observability.
 */
@Component
public class CanaryRoutingFilter implements GlobalFilter, Ordered {

	private final GatewayRouteRepository routeRepository;
	private final CanaryTargetRepository canaryTargetRepository;
	private final RegisteredServiceRepository serviceRepository;

	public CanaryRoutingFilter(
			GatewayRouteRepository routeRepository,
			CanaryTargetRepository canaryTargetRepository,
			RegisteredServiceRepository serviceRepository
	) {
		this.routeRepository = routeRepository;
		this.canaryTargetRepository = canaryTargetRepository;
		this.serviceRepository = serviceRepository;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Route gatewayRoute = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		if (gatewayRoute == null) {
			return chain.filter(exchange);
		}

		String routeStringId = gatewayRoute.getId();

		return routeRepository.findByRouteId(routeStringId)
				.flatMap(dbRoute -> canaryTargetRepository
						.findByRouteIdAndEnabledTrue(dbRoute.getId())
						.collectList()
						.flatMap(targets -> {
							if (targets.isEmpty()) {
								// No canary configured — normal routing
								return chain.filter(exchange);
							}
							return applyCanary(exchange, chain, targets);
						}))
				.switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
	}

	/**
	 * Pick a target using weighted random selection, then mutate the request URI
	 * so Spring Cloud Gateway forwards to the chosen backend.
	 */
	private Mono<Void> applyCanary(
			ServerWebExchange exchange,
			GatewayFilterChain chain,
			List<CanaryTarget> targets
	) {
		CanaryTarget chosen = weightedRandom(targets);

		return serviceRepository.findById(chosen.getServiceId())
				.flatMap(service -> {
					ServerWebExchange mutated = rewriteUri(exchange, service);
					// Expose which backend was chosen for debugging / tracing
					mutated.getResponse().getHeaders().add("X-Canary-Service", service.getName());
					return chain.filter(mutated);
				});
	}

	/**
	 * Weighted random selection over a list of canary targets.
	 *
	 * Works even when weights don't sum to exactly 100 — uses a random number
	 * in [0, totalWeight) and walks the list until the running sum passes it.
	 */
	static CanaryTarget weightedRandom(List<CanaryTarget> targets) {
		int totalWeight = targets.stream()
				.mapToInt(t -> t.getWeight() == null ? 0 : t.getWeight())
				.sum();

		if (totalWeight <= 0) {
			// Fallback: pick first target
			return targets.get(0);
		}

		int rand = ThreadLocalRandom.current().nextInt(totalWeight);
		int cumulative = 0;
		for (CanaryTarget target : targets) {
			cumulative += (target.getWeight() == null ? 0 : target.getWeight());
			if (rand < cumulative) {
				return target;
			}
		}
		// Should never reach here, but guard against floating-point/rounding
		return targets.get(targets.size() - 1);
	}

	/**
	 * Replace the GATEWAY_REQUEST_URL_ATTR (the URL Spring Cloud Gateway will
	 * actually forward to) with the chosen service's base_url, preserving the
	 * original request path and query string.
	 */
	private ServerWebExchange rewriteUri(ServerWebExchange exchange, RegisteredService service) {
		URI originalUri = exchange.getRequest().getURI();
		URI serviceBase = URI.create(service.getBaseUrl());

		URI newUri = UriComponentsBuilder.fromUri(serviceBase)
				.path(originalUri.getRawPath())
				.query(originalUri.getRawQuery())
				.build(true)
				.toUri();

		exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, newUri);
		return exchange;
	}

	/**
	 * Order -2: runs before GatewayApiKeyFilter (-1) so the URL rewrite
	 * happens before auth and rate-limit checks inspect it.
	 *
	 * Actually for this use case we want auth/rate-limit to still apply, so
	 * let's run AFTER them but before the actual forwarding.  Spring Cloud
	 * Gateway's NettyRoutingFilter that does the actual HTTP call is at
	 * Integer.MIN_VALUE + 1, so order 1 is fine.
	 *
	 * Chosen order: 1  (after auth=-1, after rate-limit=0, before forwarding)
	 */
	@Override
	public int getOrder() {
		return 1;
	}
}
