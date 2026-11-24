package com.sriram.trafficpilot.canary;

import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class CanaryRoutingFilterTest {

	private final GatewayRouteRepository routes = mock(GatewayRouteRepository.class);
	private final CanaryTargetRepository targets = mock(CanaryTargetRepository.class);
	private final RegisteredServiceRepository services = mock(RegisteredServiceRepository.class);
	private final CanaryRoutingFilter filter = new CanaryRoutingFilter(routes, targets, services);

	@BeforeEach
	void setup() {
		GatewayRoute route = new GatewayRoute();
		route.setId(1L);
		when(routes.findByRouteId("payments")).thenReturn(Mono.just(route));
		when(targets.findByRouteIdAndEnabledTrue(1L)).thenReturn(Flux.empty());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void forwardsExactlyOnceToChosenBackendAfterStrippingWithEncodedPathAndQuery(boolean canary) {
		AtomicInteger stableCalls = new AtomicInteger();
		AtomicInteger canaryCalls = new AtomicInteger();
		var stable = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			stableCalls.incrementAndGet();
			return res.sendString(Mono.just("stable:" + req.uri()));
		}).bindNow();
		var candidate = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			canaryCalls.incrementAndGet();
			return res.sendString(Mono.just("canary:" + req.uri()));
		}).bindNow();
		try {
			var exchange = exchange("http://127.0.0.1:" + stable.port());
			if (canary) {
				when(targets.findByRouteIdAndEnabledTrue(1L)).thenReturn(Flux.just(target(100)));
				RegisteredService service = new RegisteredService();
				service.setName("v2");
				service.setBaseUrl("http://127.0.0.1:" + candidate.port());
				when(services.findById(2L)).thenReturn(Mono.just(service));
			}
			var stripConfig = new StripPrefixGatewayFilterFactory.Config();
			stripConfig.setParts(1);
			var toUrl = new RouteToRequestUrlFilter();
			List<GatewayFilter> pipeline = new ArrayList<>(List.of(
					new OrderedGatewayFilter(new StripPrefixGatewayFilterFactory().apply(stripConfig), 0),
					new OrderedGatewayFilter(toUrl::filter, toUrl.getOrder()),
					new OrderedGatewayFilter(filter::filter, filter.getOrder())));
			AnnotationAwareOrderComparator.sort(pipeline);
			AtomicInteger forwarded = new AtomicInteger();
			List<String> bodies = new ArrayList<>();
			GatewayFilterChain terminal = e -> {
				forwarded.incrementAndGet();
				URI uri = e.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
				return WebClient.create().get().uri(uri).retrieve().bodyToMono(String.class)
						.doOnNext(bodies::add).then();
			};
			runFilters(pipeline, 0, exchange, terminal).block(Duration.ofSeconds(5));
			assertEquals(List.of((canary ? "canary:" : "stable:") + "/a%20b?x=%2F&x=two"), bodies);
			assertEquals(1, forwarded.get());
			assertEquals(canary ? 0 : 1, stableCalls.get());
			assertEquals(canary ? 1 : 0, canaryCalls.get());
			assertEquals(canary ? "v2" : null, exchange.getResponse().getHeaders().getFirst("X-Canary-Service"));
		}
		finally {
			stable.disposeNow();
			candidate.disposeNow();
		}
	}

	@Test
	void missingDatabaseRoutePassesThroughOnce() {
		when(routes.findByRouteId("payments")).thenReturn(Mono.empty());
		AtomicInteger calls = new AtomicInteger();
		filter.filter(exchange("http://localhost:9001"), e -> Mono.fromRunnable(calls::incrementAndGet))
				.block(Duration.ofSeconds(2));
		assertEquals(1, calls.get());
	}

	@Test
	void missingSelectedServiceReturns503WithoutForwarding() {
		when(targets.findByRouteIdAndEnabledTrue(1L)).thenReturn(Flux.just(target(100)));
		when(services.findById(2L)).thenReturn(Mono.empty());
		AtomicInteger calls = new AtomicInteger();
		ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
				filter.filter(exchange("http://localhost:9001"), e -> Mono.fromRunnable(calls::incrementAndGet))
						.block(Duration.ofSeconds(2)));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
		assertEquals(0, calls.get());
	}

	@Test
	void weightedSelectionHasExactNinetyTenBoundariesAndRejectsBrokenWeights() {
		CanaryTarget stable = target(90);
		CanaryTarget candidate = target(10);
		for (int draw = 0; draw < 100; draw++) {
			assertSame(draw < 90 ? stable : candidate,
					CanaryRoutingFilter.weightedTarget(List.of(stable, candidate), draw));
		}
		assertThrows(IllegalStateException.class, () -> CanaryRoutingFilter.weightedTarget(List.of(target(0)), 0));
		assertThrows(IllegalStateException.class, () -> CanaryRoutingFilter.weightedTarget(List.of(target(90)), 0));
		assertThrows(IllegalStateException.class, () -> CanaryRoutingFilter.weightedTarget(List.of(), 0));
	}

	private CanaryTarget target(int weight) {
		CanaryTarget target = new CanaryTarget();
		target.setServiceId(2L);
		target.setWeight(weight);
		return target;
	}

	private MockServerWebExchange exchange(String base) {
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,
				URI.create("http://gateway/payments/a%20b?x=%2F&x=two")));
		exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("payments")
				.uri(base).predicate(e -> true).build());
		return exchange;
	}

	private Mono<Void> runFilters(List<GatewayFilter> filters, int index, ServerWebExchange exchange,
			GatewayFilterChain terminal) {
		return Mono.defer(() -> index == filters.size() ? terminal.filter(exchange)
				: filters.get(index).filter(exchange, e -> runFilters(filters, index + 1, e, terminal)));
	}
}
