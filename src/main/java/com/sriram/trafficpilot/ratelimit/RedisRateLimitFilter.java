package com.sriram.trafficpilot.ratelimit;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class RedisRateLimitFilter implements GlobalFilter, Ordered {

	private static final String API_KEY_HEADER = "X-Api-Key";
	private static final String RATE_LIMIT_PREFIX = "trafficpilot:rate:";
	private static final Logger log = LoggerFactory.getLogger(RedisRateLimitFilter.class);
	private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
				redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return count
			""", Long.class);

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
		if (windowSeconds <= 0) {
			throw new IllegalArgumentException("Rate limit window must be positive");
		}
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
		return redisTemplate.execute(INCREMENT, List.of(key), List.of(Long.toString(rateLimitWindow.toMillis())))
				.single()
				.onErrorMap(DataAccessException.class, ex -> {
					log.error("Redis rate limit check failed for route '{}'", route.getRouteId(), ex);
					return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable", ex);
				})
				.flatMap(count -> handleCount(exchange, chain, limit, count));
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