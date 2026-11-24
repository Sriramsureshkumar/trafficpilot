package com.sriram.trafficpilot.ratelimit;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class RedisRateLimitFilterTest {

	private final GatewayRouteRepository routes = mock(GatewayRouteRepository.class);
	private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
	private final RedisRateLimitFilter filter = new RedisRateLimitFilter(routes, redis, 60);
	private final GatewayRoute route = new GatewayRoute();

	@BeforeEach
	void setup() {
		route.setRouteId("payments");
		route.setRateLimitPerMinute(2);
		when(routes.findByRouteId("payments")).thenReturn(Mono.just(route));
	}

	@Test
	void acceptsLastAllowedRequestThenRejectsTheNextWithHeaders() {
		when(redis.<Long>execute(any(), anyList(), anyList())).thenReturn(Flux.just(2L), Flux.just(3L));
		AtomicInteger calls = new AtomicInteger();
		var allowed = exchange();
		var rejected = exchange();
		filter.filter(allowed, e -> Mono.fromRunnable(calls::incrementAndGet)).block(Duration.ofSeconds(2));
		filter.filter(rejected, e -> Mono.fromRunnable(calls::incrementAndGet)).block(Duration.ofSeconds(2));
		assertEquals(1, calls.get());
		assertEquals("2", allowed.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
		assertEquals("0", allowed.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
		assertEquals("60", rejected.getResponse().getHeaders().getFirst("Retry-After"));
		verify(redis, times(2)).execute(any(), eq(List.of("trafficpilot:rate:payments:client")), eq(List.of("60000")));
	}

	@Test
	void noLimitOrMissingRouteForwardsOnceWithoutRedis() {
		route.setRateLimitPerMinute(null);
		AtomicInteger calls = new AtomicInteger();
		filter.filter(exchange(), e -> Mono.fromRunnable(calls::incrementAndGet)).block(Duration.ofSeconds(2));
		when(routes.findByRouteId("payments")).thenReturn(Mono.empty());
		filter.filter(exchange(), e -> Mono.fromRunnable(calls::incrementAndGet)).block(Duration.ofSeconds(2));
		assertEquals(2, calls.get());
		verifyNoInteractions(redis);
	}

	@Test
	void redisFailureIs503ButDownstreamErrorsAreNotMaskedOrRetried() {
		when(redis.<Long>execute(any(), anyList(), anyList()))
				.thenReturn(Flux.error(new RedisConnectionFailureException("test outage")), Flux.just(1L));
		AtomicInteger calls = new AtomicInteger();
		ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
				filter.filter(exchange(), e -> Mono.fromRunnable(calls::incrementAndGet)).block(Duration.ofSeconds(2)));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
		assertEquals(0, calls.get());
		IllegalStateException downstream = new IllegalStateException("backend failed");
		assertSame(downstream, assertThrows(IllegalStateException.class, () ->
				filter.filter(exchange(), e -> {
					calls.incrementAndGet();
					return Mono.error(downstream);
				}).block(Duration.ofSeconds(2))));
		assertEquals(1, calls.get());
	}

	private MockServerWebExchange exchange() {
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/payments/status").header("X-Api-Key", "client"));
		exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("payments")
				.uri("http://localhost:9001").predicate(e -> true).build());
		return exchange;
	}
}
