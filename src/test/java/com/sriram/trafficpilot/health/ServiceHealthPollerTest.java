package com.sriram.trafficpilot.health;

import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceHealthPollerTest {

	@Test
	void pollStoresHttpStatusAndTimestampAndReenumeratesOnEachSubscription() {
		List<String> paths = new ArrayList<>();
		var server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
			paths.add(req.uri());
			return res.status(req.uri().equals("/health") ? 204 : 503).send();
		}).bindNow();
		try {
			var repository = mock(RegisteredServiceRepository.class);
			var healthy = service(1L, server.port(), "/health");
			var unhealthy = service(2L, server.port(), "/unhealthy");
			when(repository.findAll()).thenReturn(Flux.just(healthy), Flux.just(unhealthy));
			when(repository.updateHealth(anyLong(), anyString(), any())).thenReturn(Mono.just(1));
			var poller = new ServiceHealthPoller(repository, WebClient.builder(), Duration.ofSeconds(2));
			Mono<Void> scheduled = poller.poll();
			Instant before = Instant.now();
			scheduled.block(Duration.ofSeconds(5));
			scheduled.block(Duration.ofSeconds(5));
			var timestamp = ArgumentCaptor.forClass(Instant.class);
			verify(repository).updateHealth(eq(1L), eq("UP"), timestamp.capture());
			assertFalse(timestamp.getValue().isBefore(before));
			verify(repository).updateHealth(eq(2L), eq("DOWN"), any(Instant.class));
			assertEquals(List.of("/health", "/unhealthy"), paths);
			verify(repository, never()).save(any());
		}
		finally {
			server.disposeNow();
		}
	}

	@Test
	void timeoutsAndInvalidLegacyUrlsAreDownWithoutAbortingThePoll() {
		var server = HttpServer.create().host("127.0.0.1").port(0)
				.handle((req, res) -> Mono.never()).bindNow();
		try {
			var poller = new ServiceHealthPoller(mock(RegisteredServiceRepository.class),
					WebClient.builder(), Duration.ofMillis(100));
			assertEquals("DOWN", poller.check(service(1L, server.port(), "/health")).block(Duration.ofSeconds(3)));
			var invalid = service(2L, server.port(), "/health");
			invalid.setBaseUrl("http:localhost:9001");
			assertEquals("DOWN", poller.check(invalid).block(Duration.ofSeconds(3)));
			invalid.setBaseUrl("http://localhost:9001");
			invalid.setHealthCheckPath("//other-host/health");
			assertEquals("DOWN", poller.check(invalid).block(Duration.ofSeconds(3)));
		}
		finally {
			server.disposeNow();
		}
	}

	private RegisteredService service(Long id, int port, String path) {
		var service = new RegisteredService();
		service.setId(id);
		service.setName("backend-" + id);
		service.setBaseUrl("http://127.0.0.1:" + port + "/ignored-base-path");
		service.setHealthCheckPath(path);
		service.setStatus("UNKNOWN");
		return service;
	}
}
