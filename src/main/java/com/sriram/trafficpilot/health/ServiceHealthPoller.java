package com.sriram.trafficpilot.health;

import com.sriram.trafficpilot.model.RegisteredService;
import com.sriram.trafficpilot.repository.RegisteredServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "trafficpilot.health.enabled", matchIfMissing = true)
public class ServiceHealthPoller {

	private static final Logger log = LoggerFactory.getLogger(ServiceHealthPoller.class);
	private final RegisteredServiceRepository repository;
	private final WebClient client;
	private final Duration timeout;

	public ServiceHealthPoller(
			RegisteredServiceRepository repository,
			WebClient.Builder builder,
			@Value("${trafficpilot.health.timeout:2s}") Duration timeout
	) {
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("Health timeout must be positive");
		}
		this.repository = repository;
		this.client = builder.build();
		this.timeout = timeout;
	}

	@Scheduled(fixedDelayString = "${trafficpilot.health.interval:30s}",
			initialDelayString = "${trafficpilot.health.initial-delay:5s}")
	public Mono<Void> poll() {
		// Defer enumeration: Spring re-subscribes to this publisher for each scheduled run.
		return Mono.defer(() -> repository.findAll()
				.flatMap(service -> check(service)
						.flatMap(status -> {
							if (!status.equals(service.getStatus())) {
								log.info("Service '{}' health changed from {} to {}", service.getName(), service.getStatus(), status);
							}
							return repository.updateHealth(service.getId(), status, Instant.now());
						})
						.onErrorResume(DataAccessException.class, ex -> {
							log.error("Could not persist health for service '{}'", service.getName(), ex);
							return Mono.empty();
						}), 4)
				.then());
	}

	Mono<String> check(RegisteredService service) {
		return Mono.fromCallable(() -> healthUri(service))
				.flatMap(uri -> client.get().uri(uri).exchangeToMono(response -> {
					boolean up = response.statusCode().is2xxSuccessful();
					if (!up) {
						log.warn("Health check for service '{}' returned HTTP {}", service.getName(), response.statusCode().value());
					}
					return response.releaseBody().thenReturn(up ? "UP" : "DOWN");
				}))
				.timeout(timeout)
				.onErrorResume(ex -> ex instanceof WebClientRequestException
								|| ex instanceof TimeoutException || ex instanceof IllegalArgumentException,
						ex -> {
							log.warn("Health check failed for service '{}': {}", service.getName(), ex.toString());
							return Mono.just("DOWN");
						});
	}

	private URI healthUri(RegisteredService service) {
		URI base = URI.create(service.getBaseUrl());
		String path = service.getHealthCheckPath();
		if (base.getHost() == null || !("http".equalsIgnoreCase(base.getScheme())
				|| "https".equalsIgnoreCase(base.getScheme()))
				|| path == null || !path.startsWith("/") || path.startsWith("//")
				|| path.contains("?") || path.contains("#")) {
			throw new IllegalArgumentException("Health check requires an HTTP(S) backend and an absolute path");
		}
		return UriComponentsBuilder.fromUri(base).replacePath(path).replaceQuery(null)
				.fragment(null).userInfo(null).build(true).toUri();
	}
}
