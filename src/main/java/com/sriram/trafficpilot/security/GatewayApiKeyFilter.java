package com.sriram.trafficpilot.security;

import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class GatewayApiKeyFilter implements GlobalFilter, Ordered {

	private static final String API_KEY_HEADER = "X-Api-Key";

	private final GatewayRouteRepository routeRepository;
	private final ApiKeyProperties apiKeyProperties;

	public GatewayApiKeyFilter(GatewayRouteRepository routeRepository, ApiKeyProperties apiKeyProperties) {
		this.routeRepository = routeRepository;
		this.apiKeyProperties = apiKeyProperties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Route gatewayRoute = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		if (gatewayRoute == null) {
			return chain.filter(exchange);
		}

		return routeRepository.findByRouteId(gatewayRoute.getId())
				.flatMap(route -> {
					if (!Boolean.TRUE.equals(route.getAuthRequired())) {
						return chain.filter(exchange).thenReturn(true);
					}
					String providedKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
					if (apiKeyProperties.getGatewayApiKey().equals(providedKey)) {
						return chain.filter(exchange).thenReturn(true);
					}
					exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
					return exchange.getResponse().setComplete().thenReturn(true);
				})
				.switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(true)))
				.then();
	}

	@Override
	public int getOrder() {
		return -1;
	}
}