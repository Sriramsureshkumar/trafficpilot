package com.sriram.trafficpilot;

import com.sriram.trafficpilot.audit.AuditService;
import com.sriram.trafficpilot.controller.AdminAuditController;
import com.sriram.trafficpilot.controller.AdminCanaryController;
import com.sriram.trafficpilot.controller.AdminRouteController;
import com.sriram.trafficpilot.dto.CanaryTargetEntry;
import com.sriram.trafficpilot.dto.CreateRouteRequest;
import com.sriram.trafficpilot.dto.SetCanaryRequest;
import com.sriram.trafficpilot.dto.RouteResponse;
import com.sriram.trafficpilot.health.ServiceHealthPoller;
import com.sriram.trafficpilot.model.AuditEntry;
import com.sriram.trafficpilot.model.CanaryTarget;
import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.model.Team;
import com.sriram.trafficpilot.ratelimit.RedisRateLimitFilter;
import com.sriram.trafficpilot.repository.AuditEntryRepository;
import com.sriram.trafficpilot.repository.CanaryTargetRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import com.sriram.trafficpilot.repository.TeamRepository;
import com.sriram.trafficpilot.security.AuthPrincipal;
import com.sriram.trafficpilot.security.ResourceAccess;
import com.sriram.trafficpilot.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"trafficpilot.health.enabled=true",
		"trafficpilot.health.initial-delay=1h",
		"trafficpilot.security.bootstrap-admin-username=test-bootstrap-admin",
		"trafficpilot.security.bootstrap-admin-password=test-only-password-123"
})
class GatewayPolicyIntegrationTests {

	private static final Duration WAIT = Duration.ofSeconds(15);
	@Autowired private RegisteredServiceRepository services;
	@Autowired private GatewayRouteRepository routes;
	@Autowired private CanaryTargetRepository targets;
	@Autowired private TeamRepository teams;
	@Autowired private AuditEntryRepository audit;
	@Autowired private AdminRouteController routeController;
	@Autowired private AdminCanaryController canaryController;
	@Autowired private AdminAuditController auditController;
	@Autowired private ResourceAccess access;
	@Autowired private TransactionalOperator transactions;
	@Autowired private ReactiveStringRedisTemplate redis;
	@Autowired private CachingRouteLocator locator;
	@Autowired private Environment environment;
	@Autowired private ApplicationEventPublisher publisher;
	@Autowired private ServiceHealthPoller healthPoller;

	private Team team;
	private String name;
	private final List<Long> serviceIds = new ArrayList<>();
	private final List<Long> routeIds = new ArrayList<>();
	private final AuthPrincipal admin = new AuthPrincipal(123L, "integration-actor", Role.PLATFORM_ADMIN, null, null);

	@BeforeEach
	void setup() {
		name = "policy-test-" + UUID.randomUUID();
		Team created = new Team();
		created.setName(name);
		created.setCreatedAt(Instant.now());
		team = teams.save(created).block(WAIT);
	}

	@AfterEach
	void cleanup() {
		for (Long id : routeIds) {
			targets.deleteByRouteId(id).then(routes.deleteById(id)).block(WAIT);
		}
		for (Long id : serviceIds) {
			services.deleteById(id).block(WAIT);
		}
		audit.findRecentByTeamId(team.getId(), 100, 0).concatMap(audit::delete).then().block(WAIT);
		teams.delete(team).block(WAIT);
		publisher.publishEvent(new RefreshRoutesEvent(this));
	}

	@Test
	void liveGatewayEnforcesAuthAndProxiesDefaultThenCanaryThenDefaultAgain() {
		AtomicInteger stableCalls = new AtomicInteger();
		AtomicInteger candidateCalls = new AtomicInteger();
		var stable = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			stableCalls.incrementAndGet();
			return res.sendString(Mono.just("v1:" + req.uri()));
		}).bindNow();
		var candidate = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			candidateCalls.incrementAndGet();
			return res.sendString(Mono.just("v2:" + req.uri()));
		}).bindNow();
		try {
			var v1 = service("v1", stable.port());
			var v2 = service("v2", candidate.port());
			var route = createRoute(v1);
			// Wait for an explicit cache refresh rather than sleeping on the asynchronous event.
			locator.refresh().collectList().block(WAIT);
			var client = WebClient.create("http://localhost:" + environment.getProperty("local.server.port"));
			String path = "/" + route.routeId() + "/a%20b?x=%2F";
			assertEquals(401, client.get().uri(java.net.URI.create(clientBase() + path))
					.exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())).block(WAIT));
			assertEquals(0, stableCalls.get());
			assertEquals("v1:/a%20b?x=%2F", get(client, path).block(WAIT));
			canaryController.setCanaryTargets(route.id(), canary(v2.getName(), 100), exchange(admin)).collectList().block(WAIT);
			assertEquals("v2:/a%20b?x=%2F", get(client, path).block(WAIT));
			canaryController.removeCanaryTargets(route.id(), exchange(admin)).block(WAIT);
			assertEquals("v1:/a%20b?x=%2F", get(client, path).block(WAIT));
			routeController.deleteRoute(route.id(), exchange(admin)).block(WAIT);
			locator.refresh().collectList().block(WAIT);
			assertEquals(404, client.get().uri(java.net.URI.create(clientBase() + path))
					.exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())).block(WAIT));
			assertEquals(2, stableCalls.get());
			assertEquals(1, candidateCalls.get());
		}
		finally {
			stable.disposeNow();
			candidate.disposeNow();
		}
	}

	@Test
	void disabledRouteIsNotProxiedAndHealthChecksPersistIndependently() {
		AtomicInteger proxied = new AtomicInteger();
		var backend = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			if (!req.uri().equals("/health")) {
				proxied.incrementAndGet();
			}
			return res.status(204).send();
		}).bindNow();
		try {
			var service = service("v1", backend.port());
			var request = routeRequest(service);
			request.setEnabled(false);
			var route = routeController.createRoute(request, exchange(admin)).block(WAIT);
			routeIds.add(route.id());
			locator.refresh().collectList().block(WAIT);
			assertEquals(404, WebClient.create().get().uri(clientBase() + "/" + name + "/status")
					.exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())).block(WAIT));
			assertEquals(0, proxied.get());
			healthPoller.poll().block(WAIT);
			var checked = services.findById(service.getId()).block(WAIT);
			assertEquals("UP", checked.getStatus());
			assertNotNull(checked.getLastCheckedAt());
			assertFalse(routes.findById(route.id()).block(WAIT).getEnabled());
		}
		finally {
			backend.disposeNow();
		}
	}

	@Test
	void auditIsPersistedWithActorTeamAndTimeAndSurvivesRouteDeletion() {
		var route = createRoute(service("v1", 9001));
		routeController.deleteRoute(route.id(), exchange(admin)).block(WAIT);
		List<AuditEntry> history = audit.findRecentByTeamId(team.getId(), 10, 0).collectList().block(WAIT);
		assertEquals(List.of("ROUTE_DELETED", "ROUTE_CREATED"), history.stream().map(AuditEntry::action).toList());
		assertTrue(history.stream().allMatch(entry -> entry.actorUsername().equals(admin.username())
				&& entry.teamId().equals(team.getId()) && entry.routeId().equals(route.routeId())
				&& entry.occurredAt() != null && entry.details().contains("service=" + name + "-v1")));
		assertEquals(2, auditController.list(10, 0,
				exchange(new AuthPrincipal(124L, "viewer", Role.VIEWER, team.getId(), team.getName())))
				.count().block(WAIT));
		assertEquals(0, auditController.list(10, 0,
				exchange(new AuthPrincipal(125L, "other-team", Role.VIEWER, -1L, "other")))
				.count().block(WAIT));
		assertThrows(ResponseStatusException.class, () -> auditController.list(101, 0, exchange(admin)).collectList().block(WAIT));
	}

	@Test
	void failedAuditRollsBackCanaryReplacementAndRouteCreationWithoutRefresh() {
		var v1 = service("v1", 9001);
		var v2 = service("v2", 9002);
		var route = createRoute(v1);
		canaryController.setCanaryTargets(route.id(), canary(v1.getName(), 100), exchange(admin)).collectList().block(WAIT);
		var failedAudit = mock(AuditEntryRepository.class);
		when(failedAudit.save(any(AuditEntry.class))).thenReturn(Mono.error(new DataAccessResourceFailureException("test write failure")));
		var events = mock(ApplicationEventPublisher.class);
		var failingCanary = new AdminCanaryController(access, targets, services, events, new AuditService(failedAudit), transactions);
		assertThrows(DataAccessResourceFailureException.class, () ->
				failingCanary.setCanaryTargets(route.id(), canary(v2.getName(), 100), exchange(admin)).collectList().block(WAIT));
		assertEquals(List.of(v1.getId()), targets.findByRouteId(route.id()).map(CanaryTarget::getServiceId).collectList().block(WAIT));
		var failingRoutes = new AdminRouteController(routes, services, targets, access, events, new AuditService(failedAudit), transactions);
		var request = routeRequest(v1);
		request.setRouteId(name + "-failed");
		request.setPath("/" + name + "-failed/**");
		assertThrows(DataAccessResourceFailureException.class, () -> failingRoutes.createRoute(request, exchange(admin)).block(WAIT));
		assertEquals(false, routes.existsByRouteId(request.getRouteId()).block(WAIT));
		verifyNoInteractions(events);
	}

	@Test
	void canaryRejectsDuplicateServicesAndOtherTeamsEvenForPlatformAdmin() {
		var v1 = service("v1", 9001);
		var route = createRoute(v1);
		var duplicate = canary(v1.getName(), 50);
		duplicate.setTargets(List.of(duplicate.getTargets().getFirst(), duplicate.getTargets().getFirst()));
		assertThrows(ResponseStatusException.class, () ->
				canaryController.setCanaryTargets(route.id(), duplicate, exchange(admin)).collectList().block(WAIT));
		v1.setTeamId(null);
		services.save(v1).block(WAIT);
		var v2 = service("v2", 9002);
		var error = assertThrows(ResponseStatusException.class, () ->
				canaryController.setCanaryTargets(route.id(), canary(v2.getName(), 100), exchange(admin)).collectList().block(WAIT));
		assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
		assertEquals(0, targets.findByRouteId(route.id()).count().block(WAIT));
	}

	@Test
	void realRedisCounterIsAtomicUnderConcurrencyAndExpiresAndRecoversMissingTtl() {
		GatewayRoute route = new GatewayRoute();
		route.setRouteId(name);
		route.setRateLimitPerMinute(5);
		var repository = mock(GatewayRouteRepository.class);
		when(repository.findByRouteId(name)).thenReturn(Mono.just(route));
		var limiter = new RedisRateLimitFilter(repository, redis, 1);
		String key = "trafficpilot:rate:" + name + ":client";
		try {
			List<Integer> statuses = Flux.range(0, 40).flatMap(i -> {
				var exchange = rateExchange();
				return limiter.filter(exchange, e -> Mono.empty()).then(Mono.fromSupplier(() ->
						exchange.getResponse().getStatusCode() == null ? 200 : exchange.getResponse().getStatusCode().value()));
			}, 16).collectList().block(WAIT);
			assertEquals(5, statuses.stream().filter(s -> s == 200).count());
			assertEquals(35, statuses.stream().filter(s -> s == 429).count());
			assertTrue(redis.getExpire(key).block(WAIT).toMillis() > 0);
			Mono.delay(Duration.ofMillis(1200)).block(WAIT);
			var nextWindow = rateExchange();
			limiter.filter(nextWindow, e -> Mono.empty()).block(WAIT);
			assertNull(nextWindow.getResponse().getStatusCode());
			assertEquals("4", nextWindow.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
			redis.opsForValue().set(key, "3").block(WAIT);
			limiter.filter(rateExchange(), e -> Mono.empty()).block(WAIT);
			assertTrue(redis.getExpire(key).block(WAIT).toMillis() > 0);
		}
		finally {
			redis.delete(key).block(WAIT);
		}
	}

	private MockServerWebExchange rateExchange() {
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-Api-Key", "client"));
		exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id(name).uri("http://localhost:9001").predicate(e -> true).build());
		return exchange;
	}

	private Mono<String> get(WebClient client, String path) {
		return client.get().uri(java.net.URI.create(clientBase() + path)).header("X-Api-Key", "user-secret")
				.retrieve().bodyToMono(String.class);
	}

	private String clientBase() {
		return "http://localhost:" + environment.getProperty("local.server.port");
	}

	private RegisteredService service(String suffix, int port) {
		RegisteredService service = new RegisteredService();
		service.setName(name + "-" + suffix);
		service.setBaseUrl("http://127.0.0.1:" + port);
		service.setHealthCheckPath("/health");
		service.setOwnerTeam(team.getName());
		service.setTeamId(team.getId());
		service.setStatus("UNKNOWN");
		service.setCreatedAt(Instant.now());
		var saved = services.save(service).block(WAIT);
		serviceIds.add(saved.getId());
		return saved;
	}

	private RouteResponse createRoute(RegisteredService service) {
		var response = routeController.createRoute(routeRequest(service), exchange(admin)).block(WAIT);
		routeIds.add(response.id());
		return response;
	}

	private CreateRouteRequest routeRequest(RegisteredService service) {
		var request = new CreateRouteRequest();
		request.setRouteId(name);
		request.setPath("/" + name + "/**");
		request.setServiceName(service.getName());
		request.setStripPrefix(1);
		request.setAuthRequired(true);
		return request;
	}

	private SetCanaryRequest canary(String service, int weight) {
		var entry = new CanaryTargetEntry();
		entry.setServiceName(service);
		entry.setWeight(weight);
		var request = new SetCanaryRequest();
		request.setTargets(List.of(entry));
		return request;
	}

	private MockServerWebExchange exchange(AuthPrincipal principal) {
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/routes"));
		exchange.getAttributes().put(AuthPrincipal.class.getName(), principal);
		return exchange;
	}
}
