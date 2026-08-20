package com.sriram.trafficpilot.ratelimit;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class RedisRateLimitFilter implements GlobalFilter, Ordered {

	private static final String API_KEY_HEADER = "X-Api-Key";
	private static final String RATE_LIMIT_PREFIX = "trafficpilot:rate:";

	private final GatewayRouteRepository routeRepository;
	private final ReactiveStringRedisTemplate redisTemplate;
	private final Duration rateLimitWindow;

	public RedisRateLimitFilter(
			GatewayRouteRepository routeRepository,
			ReactiveStringRedisTemplate redisTemplate,
			@Value("${trafficpilot.rate-limit.window-seconds}") long windowSeconds
	) {
		this.routeRepository = routeRepository;
		this.redisTemplate = redisTemplate;
		this.rateLimitWindow = Duration.ofSeconds(windowSeconds);
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Route gatewayRoute = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		if (gatewayRoute == null) {
			return chain.filter(exchange);
		}

		return routeRepository.findByRouteId(gatewayRoute.getId())
				.flatMap(route -> applyRateLimit(exchange, chain, route))
				.switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(true)))
				.then();
	}

	private Mono<Boolean> applyRateLimit(ServerWebExchange exchange, GatewayFilterChain chain, GatewayRoute route) {
		Integer limit = route.getRateLimitPerMinute();
		if (limit == null || limit <= 0) {
			return chain.filter(exchange).thenReturn(true);
		}

		String key = rateLimitKey(exchange, route);
		return redisTemplate.opsForValue().increment(key)
				.flatMap(count -> expireNewCounter(key, count)
						.then(Mono.defer(() -> handleCount(exchange, chain, limit, count))))
				.onErrorResume(ignored -> {
					exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
					return exchange.getResponse().setComplete().thenReturn(true);
				});
	}

	private Mono<Boolean> expireNewCounter(String key, Long count) {
		if (count == 1) {
			return redisTemplate.expire(key, rateLimitWindow);
		}
		return Mono.just(true);
	}

	private Mono<Boolean> handleCount(
			ServerWebExchange exchange,
			GatewayFilterChain chain,
			Integer limit,
			Long count
	) {
		long remaining = Math.max(limit - count, 0);
		exchange.getResponse().getHeaders().add("X-RateLimit-Limit", limit.toString());
		exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", Long.toString(remaining));

		if (count > limit) {
			exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
			exchange.getResponse().getHeaders().add("Retry-After", Long.toString(rateLimitWindow.toSeconds()));
			return exchange.getResponse().setComplete().thenReturn(true);
		}

		return chain.filter(exchange).thenReturn(true);
	}

	private String rateLimitKey(ServerWebExchange exchange, GatewayRoute route) {
		String clientId = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
		if (clientId == null || clientId.isBlank()) {
			clientId = exchange.getRequest().getRemoteAddress() == null
					? "anonymous"
					: exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
		}
		return RATE_LIMIT_PREFIX + route.getRouteId() + ":" + clientId;
	}

	@Override
	public int getOrder() {
		return 0;
	}
}