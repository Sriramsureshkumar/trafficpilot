package com.sriram.trafficpilot.canary;

import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class CanaryRoutingFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(CanaryRoutingFilter.class);
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

		// Resolve a value before invoking the Mono<Void> chain: empty completion is not a missing route.
		return routeRepository.findByRouteId(gatewayRoute.getId())
				.flatMap(route -> canaryTargetRepository.findByRouteIdAndEnabledTrue(route.getId())
						.collectList())
				.defaultIfEmpty(List.of())
				.flatMap(targets -> targets.isEmpty() ? Mono.just(Optional.<RegisteredService>empty())
						: chosenService(targets).map(Optional::of))
				.flatMap(service -> {
					service.ifPresent(chosen -> rewriteUri(exchange, chosen));
					return chain.filter(exchange);
				});
	}

	private Mono<RegisteredService> chosenService(List<CanaryTarget> targets) {
		return Mono.fromSupplier(() -> weightedTarget(targets, ThreadLocalRandom.current().nextInt(100)))
				.flatMap(target -> serviceRepository.findById(target.getServiceId())
						.switchIfEmpty(Mono.error(new IllegalStateException("Canary service no longer exists"))))
				.onErrorMap(IllegalStateException.class, ex -> {
					log.error("Invalid canary configuration", ex);
					return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid canary configuration", ex);
				});
	}

	static CanaryTarget weightedTarget(List<CanaryTarget> targets, int draw) {
		if (targets.isEmpty() || targets.stream().anyMatch(t ->
				t.getWeight() == null || t.getWeight() < 1 || t.getWeight() > 100)
				|| targets.stream().mapToLong(CanaryTarget::getWeight).sum() != 100) {
			throw new IllegalStateException("Enabled canary weights must be positive and sum to 100");
		}
		if (draw < 0 || draw >= 100) {
			throw new IllegalArgumentException("Draw must be in [0, 100)");
		}
		int cumulative = 0;
		for (CanaryTarget target : targets) {
			cumulative += target.getWeight();
			if (draw < cumulative) {
				return target;
			}
		}
		throw new IllegalStateException("No canary target selected");
	}

	private void rewriteUri(ServerWebExchange exchange, RegisteredService service) {
		URI routed = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		try {
			URI base = URI.create(service.getBaseUrl());
			if (base.getHost() == null || !("http".equalsIgnoreCase(base.getScheme())
					|| "https".equalsIgnoreCase(base.getScheme()))) {
				throw new IllegalArgumentException("Canary backend must have an HTTP(S) host");
			}
			// Like normal Gateway routing, keep the already stripped/encoded path and query.
			URI target = UriComponentsBuilder.fromUri(routed)
					.scheme(base.getScheme()).host(base.getHost()).port(base.getPort())
					.userInfo(null).fragment(null).build(true).toUri();
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, target);
			exchange.getResponse().getHeaders().set("X-Canary-Service", service.getName());
		}
		catch (IllegalArgumentException ex) {
			log.error("Invalid canary backend URL for service '{}'", service.getName(), ex);
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid canary backend URL", ex);
		}
	}

	@Override
	public int getOrder() {
		return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
	}
}
