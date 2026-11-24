package com.sriram.trafficpilot;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.sriram.trafficpilot.model.GatewayRoute;
import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.AppUserRepository;
import com.sriram.trafficpilot.repository.GatewayRouteRepository;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
		"trafficpilot.health.enabled=false",
		"trafficpilot.security.bootstrap-admin-username=test-bootstrap-admin",
		"trafficpilot.security.bootstrap-admin-password=test-only-password-123"
})
class TrafficpilotApplicationTests {

	@Autowired
	private AppUserRepository userRepository;

	@AfterAll
	void removeBootstrapAdmin() {
		userRepository.findByUsername("test-bootstrap-admin").flatMap(userRepository::delete).block(Duration.ofSeconds(10));
	}

	@Autowired
	private RegisteredServiceRepository serviceRepository;

	@Autowired
	private GatewayRouteRepository routeRepository;

	@Autowired
	private DatabaseClient databaseClient;

	@Autowired
	private ReactiveTransactionManager transactionManager;

	@Test
	void contextLoads() {
	}

	@Test
	void postgresPersistsServiceAndRouteMappings() {
		String name = "db-test-" + UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		RegisteredService service = new RegisteredService();
		service.setName(name);
		service.setBaseUrl("http://localhost:9001");
		service.setHealthCheckPath("/health");
		service.setOwnerTeam("test");
		service.setStatus("UNKNOWN");
		service.setCreatedAt(createdAt);

		String version = TransactionalOperator.create(transactionManager).execute(transaction -> {
			transaction.setRollbackOnly();
			return serviceRepository.save(service)
					.flatMap(saved -> {
						assertNotNull(saved.getId());
						GatewayRoute route = new GatewayRoute();
						route.setRouteId(name);
						route.setPath("/test/**");
						route.setServiceId(saved.getId());
						route.setStripPrefix(1);
						route.setAuthRequired(true);
						route.setRateLimitPerMinute(100);
						route.setEnabled(true);
						return routeRepository.save(route);
					})
					.flatMap(saved -> routeRepository.findByRouteId(name))
					.doOnNext(saved -> {
						assertNotNull(saved.getId());
						assertEquals(100, saved.getRateLimitPerMinute());
						assertEquals(Boolean.TRUE, saved.getAuthRequired());
					})
					.flatMap(saved -> serviceRepository.findById(saved.getServiceId()))
					.doOnNext(saved -> assertEquals(createdAt, saved.getCreatedAt()))
					.flatMap(saved -> databaseClient.sql("SELECT version()")
							.map((row, metadata) -> row.get(0, String.class)).one());
		}).blockLast(Duration.ofSeconds(15));
		assertNotNull(version);
		assertTrue(version.startsWith("PostgreSQL"));
	}
}
